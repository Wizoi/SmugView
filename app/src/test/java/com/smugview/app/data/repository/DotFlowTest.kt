package com.smugview.app.data.repository

import com.smugview.app.data.db.AppDatabase
import com.smugview.app.data.db.CachedNode
import com.smugview.app.data.db.CollectionDao
import com.smugview.app.data.db.TestDb
import com.smugview.app.data.repository.FakeSmugMugServer.FakeAlbum
import com.smugview.app.data.security.FakePasswordStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Phase 2 step 2-10 (design 3.5), end to end through the repository: crawl, then the one dot query,
 * "viewed" and the gallery-open ImagesLastUpdated raise. This replaces the repository-level
 * `testActiveUpdatesDetectionBubblingAndClearing`, which seeded `cached_nodes.dateModified`.
 * Fixture F: root 4zqWw, Family 2sDN5x (password), School P4BKB, gallery NodeID LCdk7F / AlbumKey FfHCms
 * (no cached_nodes row for the gallery, the way a never-browsed password folder leaves it).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class DotFlowTest {
    private lateinit var db: AppDatabase
    private lateinit var dao: CollectionDao
    private val server = FakeSmugMugServer()
    private val state = InMemorySyncStateStore()
    private val nick = "idzifamily"
    private val site = "https://gallery.idzifamily.com"

    @Before fun setUp() {
        db = TestDb.inMemory()
        dao = db.collectionDao()
        server.cookieGate = true
    }

    @After fun tearDown() = db.close()

    private var now = System.currentTimeMillis()

    private fun repo(passwords: Map<String, String> = mapOf("2sDN5x" to "fake-pw-1")) = SmugMugRepository(
        server.api(), dao, FakePasswordStore(passwords), RecordingSyncReporter(), state
    ).also { it.treeSyncDelayMs = 0; it.unlockDelayMs = 0; it.clock = { now } }

    private fun cachedFolders() = listOf(
        folder("2sDN5x", "4zqWw", "Family", "/Family"),
        folder("P4BKB", "2sDN5x", "School", "/Family/School"),
        folder("3BxbFF", "4zqWw", "Kentridge", "/Kentridge")
    )

    /** A crawl of the whole site, as a launch does: unlock what has a saved password, then list every gallery. */
    private fun crawl(r: SmugMugRepository) = runBlocking {
        now += 20 * 60_000L // past the 15-minute crawl gate
        val unlock = r.unlockSavedRoots(nick, "k")
        r.buildInMemoryGalleryCache(nick, "k", unlock, rootNodeId = "4zqWw")
    }

    private fun folder(id: String, parent: String, name: String, path: String) = CachedNode(
        nodeId = id, parentNodeId = parent, type = "Folder", title = name, description = null, access = "None",
        passwordHint = null, uri = "/api/v2/node/$id", childNodesUri = "/api/v2/node/$id!children", albumUri = null,
        webUri = "$site$path", nickname = nick
    )

    /**
     * A returning phone: the first crawl is the baseline (nothing is lit, see the first-visit tests below), then the gallery gets new photos
     * (its ImagesLastUpdated moves to 2 days ago, newer than what this phone first saw) and the next crawl lights the chain.
     */
    private fun crawled(): SmugMugRepository = runBlocking {
        dao.insertNodes(cachedFolders())
        val firstSeen = server.albums
        server.albums = server.albums.map { if (it.albumKey == "FfHCms") it.copy(imagesLastUpdated = server.daysAgo(10)) else it }
        val r = repo()
        crawl(r)
        assertEquals("the first visit is the baseline", emptySet<String>(), lit(r))
        server.albums = firstSeen
        crawl(r)
        r
    }

    private fun lit(r: SmugMugRepository) = runBlocking { r.getNodesWithActiveUpdates(nick).first().toSet() }

    @Test fun crawl_lightsTheChainByNodeId_andLongPressOnTheFolderClearsIt() = runBlocking {
        val r = crawled()
        assertEquals("no node row for the gallery", null, dao.getNodeById("LCdk7F"))

        val active = lit(r)
        assertEquals(setOf("LCdk7F", "P4BKB", "2sDN5x", "4zqWw"), active)
        assertFalse("the public gallery is 40 days old", "sXQz4G" in active)

        r.markNodeAsViewed("2sDN5x")

        assertEquals("viewed = the index ILU, written with no cached_nodes row involved", emptySet<String>(), lit(r))
    }

    @Test fun markingTheGallery_clearsItsChain() = runBlocking {
        val r = crawled()

        r.markNodeAsViewed("LCdk7F")

        assertEquals(emptySet<String>(), lit(r))
    }

    @Test fun markingAShownGallery_raisesTheIndexIlu_soTheViewedMarkMatchesTheAlbumsOwn() = runBlocking {
        val r = crawled()
        r.markNodeAsViewed("LCdk7F")
        assertEquals(emptySet<String>(), lit(r))
        val before = dao.getAlbumByKey("FfHCms")!!.imagesLastUpdated
        // Photos were added after the crawl: the album read reports a newer ILU than the index holds.
        val fresh = server.daysAgo(0, -600)
        server.albums = server.albums.map { if (it.albumKey == "FfHCms") it.copy(imagesLastUpdated = fresh) else it }

        val album = r.getAlbum("FfHCms", "k")

        assertEquals("LCdk7F", album?.nodeId)
        assertEquals("a read is not a view: the index row has not moved (6-12)", before, dao.getAlbumByKey("FfHCms")!!.imagesLastUpdated)
        assertEquals("the index is behind the album, so nothing is lit by the read", emptySet<String>(), lit(r))

        r.markGalleryViewed(album!!) // what loadAlbumPages does once page 1 is shown

        assertEquals("the index row moved forward to the album's own ILU", fresh, dao.getAlbumByKey("FfHCms")!!.imagesLastUpdated)
        assertEquals("and the mark matches it: nothing is lit", emptySet<String>(), lit(r))
        assertEquals(fresh, runBlocking { db.openHelper.readableDatabase.query("SELECT lastViewedDateModified FROM viewed_gallery_updates WHERE nodeId = 'LCdk7F'").use { it.moveToFirst(); it.getString(0) } })
    }

    @Test fun anOlderIluFromAnAlbumRead_neverLowersTheIndex_andIsNotAViewOfTheNewPhotos() = runBlocking {
        val r = crawled()
        val before = dao.getAlbumByKey("FfHCms")!!.imagesLastUpdated
        server.albums = server.albums.map { if (it.albumKey == "FfHCms") it.copy(imagesLastUpdated = server.daysAgo(20)) else it }

        val album = r.getAlbum("FfHCms", "k")!!
        r.markGalleryViewed(album) // a stale answer (a week-old cache while offline) was shown

        assertEquals(before, dao.getAlbumByKey("FfHCms")!!.imagesLastUpdated)
        assertTrue("what was shown is behind the index: the dot stays", "LCdk7F" in lit(r))
    }

    @Test fun theAlbumRequestAsksForImagesLastUpdated() = runBlocking {
        val r = crawled()

        r.getAlbum("FfHCms", "k")

        val req = server.requestLog.last { it.url.encodedPath.endsWith("album/FfHCms") }
        val filter = req.url.queryParameter("_filter").orEmpty()
        assertTrue(filter, filter.split(",").contains("ImagesLastUpdated"))
    }

    // ---- First visit: no dot for what was already there (tester feedback, 2026-11-06) ----------------------------------------------------

    @Test fun firstVisit_lightsNothing_evenThoughTheGalleryWasUpdatedTwoDaysAgo() = runBlocking {
        dao.insertNodes(cachedFolders())
        val r = repo()

        crawl(r)

        assertEquals("the gallery is in the index with its date", server.albums.first { it.albumKey == "FfHCms" }.imagesLastUpdated, dao.getAlbumByKey("FfHCms")!!.imagesLastUpdated)
        assertEquals("a first visit starts clear", emptySet<String>(), lit(r))
    }

    @Test fun afterTheFirstVisit_aGalleryUpdatedLater_lightsItsChain() = runBlocking {
        dao.insertNodes(cachedFolders())
        server.albums = server.albums.map { if (it.albumKey == "FfHCms") it.copy(imagesLastUpdated = server.daysAgo(10)) else it }
        val r = repo()
        crawl(r)
        assertEquals(emptySet<String>(), lit(r))

        server.albums = server.albums.map { if (it.albumKey == "FfHCms") it.copy(imagesLastUpdated = server.daysAgo(1)) else it }
        crawl(r)

        assertEquals(setOf("LCdk7F", "P4BKB", "2sDN5x", "4zqWw"), lit(r))
    }

    @Test fun afterTheFirstVisit_aBrandNewGallery_lightsItsChain() = runBlocking {
        dao.insertNodes(cachedFolders())
        val r = repo()
        crawl(r)
        assertEquals(emptySet<String>(), lit(r))

        server.albums = server.albums + FakeAlbum(
            "NwGal1", "NdNwGal1", "Fresh Gallery", "/Family/School/2026-11-05--Fresh-Gallery",
            lastUpdated = server.daysAgo(0), imagesLastUpdated = server.daysAgo(0), needsSession = true
        )
        crawl(r)

        assertTrue("the new gallery is lit", "NdNwGal1" in lit(r))
        assertFalse("the one that was already there is not", "LCdk7F" in lit(r))
    }

    @Test fun firstUnlockOfAPasswordFolder_lightsNothingUnderIt_butALaterUpdateDoes() = runBlocking {
        // Danielle on an iPad: she opened the site, then unlocked Family, and every gallery under it lit at once.
        dao.insertNodes(cachedFolders())
        val r = repo(passwords = emptyMap())
        crawl(r) // the anonymous visit: only the public gallery is listed
        assertEquals(listOf("N74KSK"), dao.getAlbumIndex(nick).map { it.albumKey })

        now += 60_000L
        assertEquals(SmugMugRepository.UnlockResult.Success, r.unlocks.reauthorize("2sDN5x", "k", "fake-pw-1"))
        r.resyncAfterUnlock(nick, "4zqWw", "k")

        assertTrue("the locked gallery is now in the index", dao.getAlbumByKey("FfHCms") != null)
        assertEquals("what the unlock revealed was already there: nothing is lit", emptySet<String>(), lit(r))

        server.albums = server.albums.map { if (it.albumKey == "FfHCms") it.copy(imagesLastUpdated = server.daysAgo(0)) else it }
        crawl(r) // a later launch: the session is still live in this process

        assertTrue("an update after that does light", "LCdk7F" in lit(r))
    }

    @Test fun aGalleryTheTreeWalkIndexedWithoutADate_isBaselinedWhenTheUnlockDatesIt() = runBlocking {
        dao.insertNodes(cachedFolders())
        val r = repo(passwords = emptyMap())
        crawl(r)
        // The walk of an unlocked folder writes the gallery with no ImagesLastUpdated; the crawl is what first dates it.
        val f = server.albums.first { it.albumKey == "FfHCms" }
        dao.upsertAlbums(listOf(CachedAlbumFixtures.row(f, nick, parent = "P4BKB", ilu = null)))

        now += 60_000L
        r.unlocks.reauthorize("2sDN5x", "k", "fake-pw-1")
        r.resyncAfterUnlock(nick, "4zqWw", "k")

        assertTrue(dao.getAlbumByKey("FfHCms")!!.imagesLastUpdated != null)
        assertEquals(emptySet<String>(), lit(r))
    }

    @Test fun aPhoneThatAlreadyHasData_keepsItsDots() = runBlocking {
        // An update before this change: the index already holds the gallery with its date and no viewed mark. Nothing is cleared.
        dao.insertNodes(cachedFolders())
        val f = server.albums.first { it.albumKey == "FfHCms" }
        dao.upsertAlbums(listOf(CachedAlbumFixtures.row(f, nick, parent = "P4BKB")))
        val r = repo()

        crawl(r)

        assertTrue("still lit", "LCdk7F" in lit(r))
    }

    // ---- "Mark all as seen" (owner, 2026-11-06): for phones that already hold many dots --------------------------------------------------

    @Test fun markAllAsSeen_clearsEveryDotOfTheSite_andALaterUpdateLightsAgain() = runBlocking {
        val r = crawled()
        assertEquals("a returning phone with a lit chain", setOf("LCdk7F", "P4BKB", "2sDN5x", "4zqWw"), lit(r))

        val marked = r.markSiteViewed(nick)

        assertTrue("it marked the galleries that carry a date", marked >= 1)
        assertEquals(emptySet<String>(), lit(r))

        server.albums = server.albums.map { if (it.albumKey == "FfHCms") it.copy(imagesLastUpdated = server.daysAgo(0)) else it }
        crawl(r)

        assertEquals("a newer update after that lights again", setOf("LCdk7F", "P4BKB", "2sDN5x", "4zqWw"), lit(r))
    }
}

/** Test rows for the index. */
private object CachedAlbumFixtures {
    fun row(a: FakeAlbum, nick: String, parent: String? = null, ilu: String? = a.imagesLastUpdated) = com.smugview.app.data.db.CachedAlbum(
        albumKey = a.albumKey, nodeId = a.nodeId, name = a.name, securityType = a.security, passwordHint = null,
        uri = "/api/v2/album/${a.albumKey}", webUri = null, urlPath = a.urlPath, imageCount = 12,
        dateModified = a.lastUpdated, galleryStyle = null, highlightImageUrl = null, sortIndex = 0,
        nickname = nick, parentNodeId = parent, imagesLastUpdated = ilu
    )
}
