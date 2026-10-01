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

    private fun repo() = SmugMugRepository(
        server.api(), dao, FakePasswordStore(mapOf("2sDN5x" to "fake-pw-1")),
        org.mockito.Mockito.mock(android.content.Context::class.java), RecordingSyncReporter(), state
    ).also { it.treeSyncDelayMs = 0; it.unlockDelayMs = 0 }

    private fun folder(id: String, parent: String, name: String, path: String) = CachedNode(
        nodeId = id, parentNodeId = parent, type = "Folder", title = name, description = null, access = "None",
        passwordHint = null, uri = "/api/v2/node/$id", childNodesUri = "/api/v2/node/$id!children", albumUri = null,
        webUri = "$site$path", nickname = nick
    )

    /** Family, School and Kentridge cached; the crawl (with unlock) resolves each gallery's parent. */
    private fun crawled(): SmugMugRepository = runBlocking {
        dao.insertNodes(
            listOf(
                folder("2sDN5x", "4zqWw", "Family", "/Family"),
                folder("P4BKB", "2sDN5x", "School", "/Family/School"),
                folder("3BxbFF", "4zqWw", "Kentridge", "/Kentridge")
            )
        )
        val r = repo()
        val unlock = r.unlockSavedRoots(nick, "k")
        r.buildInMemoryGalleryCache(nick, "k", unlock, rootNodeId = "4zqWw")
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

    @Test fun openingAGalleryWithFreshPhotos_raisesTheIndexIlu_soTheViewedMarkMatchesIt() = runBlocking {
        val r = crawled()
        r.markNodeAsViewed("LCdk7F")
        assertEquals(emptySet<String>(), lit(r))
        // Photos were added after the crawl: the album read reports a newer ILU than the index holds.
        val fresh = server.daysAgo(0, -600)
        server.albums = server.albums.map { if (it.albumKey == "FfHCms") it.copy(imagesLastUpdated = fresh) else it }

        val album = r.getAlbum("FfHCms", "k")

        assertEquals("LCdk7F", album?.nodeId)
        assertEquals("the index row moved forward to the album's own ILU", fresh, dao.getAlbumByKey("FfHCms")!!.imagesLastUpdated)
        assertTrue("newer than the old viewed mark: lit again until the open marks it", "LCdk7F" in lit(r))

        r.markNodeAsViewed("LCdk7F") // what selectAlbum does after getAlbum

        assertEquals(emptySet<String>(), lit(r))
    }

    @Test fun anOlderIluFromAnAlbumRead_neverLowersTheIndex() = runBlocking {
        val r = crawled()
        val before = dao.getAlbumByKey("FfHCms")!!.imagesLastUpdated
        server.albums = server.albums.map { if (it.albumKey == "FfHCms") it.copy(imagesLastUpdated = server.daysAgo(20)) else it }

        r.getAlbum("FfHCms", "k")

        assertEquals(before, dao.getAlbumByKey("FfHCms")!!.imagesLastUpdated)
    }

    @Test fun theAlbumRequestAsksForImagesLastUpdated() = runBlocking {
        val r = crawled()

        r.getAlbum("FfHCms", "k")

        val req = server.requestLog.last { it.url.encodedPath.endsWith("album/FfHCms") }
        val filter = req.url.queryParameter("_filter").orEmpty()
        assertTrue(filter, filter.split(",").contains("ImagesLastUpdated"))
    }
}
