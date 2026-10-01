package com.smugview.app.data.repository

import com.smugview.app.data.db.AppDatabase
import com.smugview.app.data.db.CachedAlbum
import com.smugview.app.data.db.CachedNode
import com.smugview.app.data.db.CollectionDao
import com.smugview.app.data.db.TestDb
import com.smugview.app.data.repository.FakeSmugMugServer.FakeAlbum
import com.smugview.app.data.security.FakePasswordStore
import com.smugview.app.diag.SyncKind
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Phase 2 step 2-9 (design 3.4, review R-05): every gallery in the flat index gets a parent folder
 * NodeID by matching its folder path (`Uris.Folder` in the crawl, `urlPath` minus the last segment
 * otherwise) against the `WebUri` paths of cached folders. user!albums carries no parent link.
 * Fixture F: root 4zqWw, Family 2sDN5x (password), School P4BKB, gallery NodeID LCdk7F / AlbumKey FfHCms.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class IndexParentResolverTest {
    private lateinit var db: AppDatabase
    private lateinit var dao: CollectionDao
    private val server = FakeSmugMugServer()
    private val reporter = RecordingSyncReporter()
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
        org.mockito.Mockito.mock(android.content.Context::class.java), reporter, state
    ).also { it.treeSyncDelayMs = 0; it.unlockDelayMs = 0 }

    private fun folder(id: String, parent: String, name: String, path: String) = CachedNode(
        nodeId = id, parentNodeId = parent, type = "Folder", title = name, description = null, access = "None",
        passwordHint = null, uri = "/api/v2/node/$id", childNodesUri = "/api/v2/node/$id!children", albumUri = null,
        webUri = "$site$path", nickname = nick
    )

    /** Family and School cached the way a browse leaves them (the site root has no row of its own). */
    private fun seedFamilyAndSchool() = runBlocking {
        dao.insertNodes(
            listOf(
                folder("2sDN5x", "4zqWw", "Family", "/Family"),
                folder("P4BKB", "2sDN5x", "School", "/Family/School")
            )
        )
    }

    /** Unlock, then crawl (no tree walk), with the site root known. */
    private fun crawl(r: SmugMugRepository = repo()): Set<String> = runBlocking {
        val unlock = r.unlockSavedRoots(nick, "k")
        r.buildInMemoryGalleryCache(nick, "k", unlock, rootNodeId = "4zqWw")
    }

    private fun index() = runBlocking { dao.getAlbumIndex(nick) }.associateBy { it.albumKey }

    private fun album(key: String, node: String, name: String, path: String, ageDays: Long) =
        FakeAlbum(key, node, name, path, server.daysAgo(ageDays), server.daysAgo(ageDays))

    private fun status(req: okhttp3.Request, code: Int) =
        Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(code).message("m")
            .body("""{"Code":$code}""".toResponseBody("application/json".toMediaTypeOrNull())).build()

    @Test fun crawl_resolvesTheGalleryToItsFolderFromThePath() {
        seedFamilyAndSchool()

        crawl()

        val rows = index()
        assertEquals("AlbumKey FfHCms resolves to School's NodeID", "P4BKB", rows.getValue("FfHCms").parentNodeId)
        assertNull("no Kentridge folder is cached, so nothing to match", rows.getValue("N74KSK").parentNodeId)
        val notes = reporter.runsOf(SyncKind.GallerySync).last().notes!!
        assertTrue(notes, notes.contains("parentsResolved=1 unresolved=1"))
    }

    @Test fun rootLevelGallery_getsTheSiteRoot() {
        server.albums = listOf(album("AkRoot1", "NdRoot1", "Top", "/Top", 3))

        crawl()

        assertEquals("4zqWw", index().getValue("AkRoot1").parentNodeId)
    }

    @Test fun crawlPath_correctsAStaleParent() {
        // The index says School, the crawl says the gallery lives in /Kentridge now.
        seedFamilyAndSchool()
        runBlocking { dao.insertNodes(listOf(folder("3BxbFF", "4zqWw", "Kentridge", "/Kentridge"))) }
        val moved = server.albums.first { it.albumKey == "N74KSK" }
        runBlocking {
            dao.upsertAlbums(
                listOf(
                    CachedAlbum(
                        albumKey = "N74KSK", nodeId = "sXQz4G", name = moved.name, securityType = "None", passwordHint = null,
                        uri = "/api/v2/album/N74KSK", webUri = null, urlPath = moved.urlPath, imageCount = 1,
                        dateModified = moved.lastUpdated, galleryStyle = null, highlightImageUrl = null, sortIndex = 0,
                        nickname = nick, parentNodeId = "P4BKB", imagesLastUpdated = moved.imagesLastUpdated
                    )
                )
            )
        }

        crawl()

        assertEquals("3BxbFF", index().getValue("N74KSK").parentNodeId)
    }

    @Test fun newSubfolder_relistsTheNearestCachedAncestorOnce_thenResolves() {
        // /Family/School/New was created after School was cached, and holds a gallery from yesterday.
        seedFamilyAndSchool()
        server.addFolder("P4BKB", "Nw1234", "New", "/Family/School/New")
        server.albums = server.albums + album("AkTrip1", "NdTrip1", "Trip", "/Family/School/New/Trip", 1)

        val relisted = crawl()

        val rows = index()
        assertEquals("the new gallery resolves to the new folder", "Nw1234", rows.getValue("AkTrip1").parentNodeId)
        assertEquals("P4BKB", rows.getValue("FfHCms").parentNodeId)
        assertEquals("exactly one relist of School", 1, server.requestsTo("node/P4BKB!children").size)
        assertEquals(listOf<String?>("no-cache"), server.childrenCacheControls("P4BKB"))
        assertTrue("the relisted folder is returned so a screen showing it can reload", "P4BKB" in relisted)
        assertTrue(
            "the new folder is cached with its own path",
            runBlocking { dao.getNodeById("Nw1234") }?.webUri?.endsWith("/Family/School/New") == true
        )
    }

    @Test fun oldGalleryInUnknownFolder_relistsNothing() {
        // Only RECENT galleries (ILU within 30 days) earn a relist.
        seedFamilyAndSchool()
        server.albums = listOf(album("AkOld01", "NdOld01", "Old", "/Family/School/Gone/Old", 90))

        crawl()

        assertNull(index().getValue("AkOld01").parentNodeId)
        assertTrue(server.requests.none { it.contains("!children") })
    }

    @Test fun lockedAncestor_isSkipped_andTheGalleryStaysUnresolved() {
        seedFamilyAndSchool()
        server.addFolder("P4BKB", "Nw1234", "New", "/Family/School/New")
        server.albums = listOf(album("AkTrip1", "NdTrip1", "Trip", "/Family/School/New/Trip", 1))
        server.childrenOverride = { id, req -> if (id == "P4BKB") status(req, 401) else null }

        crawl()

        assertNull(index().getValue("AkTrip1").parentNodeId)
        assertEquals("tried once, not hammered", 1, server.requestsTo("node/P4BKB!children").size)
    }

    @Test fun rowNotInThisCrawl_fillsAMissingParentFromUrlPath_butNeverOverwritesOne() {
        seedFamilyAndSchool()
        fun row(key: String, path: String, parent: String?) = CachedAlbum(
            albumKey = key, nodeId = "Nd$key", name = key, securityType = "None", passwordHint = null,
            uri = "/api/v2/album/$key", webUri = null, urlPath = path, imageCount = 1,
            dateModified = server.daysAgo(100), galleryStyle = null, highlightImageUrl = null, sortIndex = 0,
            nickname = nick, parentNodeId = parent, imagesLastUpdated = server.daysAgo(100)
        )
        runBlocking {
            dao.upsertAlbums(listOf(row("AkFill1", "/Family/School/Fill", null), row("AkKeep1", "/Family/School/Keep", "2sDN5x")))
        }

        val result = runBlocking {
            IndexParentResolver(dao, { System.currentTimeMillis() }) { false }.resolve(nick, "4zqWw")
        }

        val rows = index()
        assertEquals("P4BKB", rows.getValue("AkFill1").parentNodeId)
        assertEquals("a listing-derived parent is not replaced by a guess", "2sDN5x", rows.getValue("AkKeep1").parentNodeId)
        assertEquals(1, result.resolved)
        assertEquals(0, result.unresolved)
    }
}
