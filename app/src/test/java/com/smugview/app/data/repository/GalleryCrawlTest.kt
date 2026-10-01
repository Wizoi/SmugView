package com.smugview.app.data.repository

import com.smugview.app.data.db.AppDatabase
import com.smugview.app.data.db.CachedAlbum
import com.smugview.app.data.db.CachedNode
import com.smugview.app.data.db.CollectionDao
import com.smugview.app.data.db.TestDb
import com.smugview.app.data.repository.FakeSmugMugServer.FakeAlbum
import com.smugview.app.data.security.FakePasswordStore
import com.smugview.app.diag.StopReason
import com.smugview.app.diag.SyncKind
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Phase 2 step 2-8 (design 3.3): the full crawl. user!albums is not sorted by any date it returns
 * (findings #14), so the crawl reads every page, builds start=1,101,... itself (NextPage drops
 * `_expand`/`_verbosity`, V3), writes all or nothing, and prunes only after a complete run.
 * These tests run the unlock and the crawl, not the tree walk.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class GalleryCrawlTest {
    private lateinit var db: AppDatabase
    private lateinit var dao: CollectionDao
    private val server = FakeSmugMugServer()
    private val reporter = RecordingSyncReporter()
    private val state = InMemorySyncStateStore()
    private val nick = "idzifamily"
    private var now = System.currentTimeMillis()

    @Before fun setUp() {
        db = TestDb.inMemory()
        dao = db.collectionDao()
    }

    @After fun tearDown() = db.close()

    private fun repo(store: FakePasswordStore = FakePasswordStore()) = SmugMugRepository(
        server.api(), dao, store,
        org.mockito.Mockito.mock(android.content.Context::class.java), reporter, state
    ).also { it.treeSyncDelayMs = 0; it.unlockDelayMs = 0; it.clock = { now } }

    /** Unlock, then crawl: the first two steps of the site sync, without the tree walk (which would
     *  merge the folder listings' galleries into the index and blur what the crawl wrote). */
    private fun sync(r: SmugMugRepository = repo()): Set<String> = runBlocking {
        val unlock = r.unlockSavedRoots(nick, "k")
        r.buildInMemoryGalleryCache(nick, "k", unlock)
    }

    private fun album(
        key: String, node: String, name: String = key, path: String = "/Archive/$name",
        lu: String = server.daysAgo(100), ilu: String? = lu, needsSession: Boolean = false
    ) = FakeAlbum(key, node, name, path, lu, ilu, needsSession)

    /** Real-looking filler: AlbumKey != NodeID, all old. */
    private fun fillers(n: Int, prefix: String = "Ak") =
        (0 until n).map { i -> album("$prefix%04d".format(i), "Nd$prefix%04d".format(i), "g$i", lu = server.daysAgo(100L + i)) }

    private fun row(a: FakeAlbum, parent: String? = null, ilu: String? = a.imagesLastUpdated, nickname: String = nick) = CachedAlbum(
        albumKey = a.albumKey, nodeId = a.nodeId, name = a.name, securityType = a.security, passwordHint = null,
        uri = "/api/v2/album/${a.albumKey}", webUri = null, urlPath = a.urlPath, imageCount = 12,
        dateModified = a.lastUpdated, galleryStyle = null, highlightImageUrl = null, sortIndex = 0,
        nickname = nickname, parentNodeId = parent, imagesLastUpdated = ilu
    )

    private fun crawlRun() = reporter.runsOf(SyncKind.GallerySync).last()

    private fun status(req: okhttp3.Request, code: Int) =
        Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(code).message("m")
            .body("""{"Code":$code}""".toResponseBody("application/json".toMediaTypeOrNull())).build()

    @Test fun newestGalleryOnPage3_isFetchedEvenWhenPage1IsKnown() {
        // findings #14: the old sync stopped at the first known album (ReachedKnown page=1) although
        // user!albums is not sorted by any date it returns. Page 1 and 2 are known; page 3 holds a new one.
        val known = fillers(245)
        val newest = album("AkNEWx", "NdNEWx", "Brand New", lu = server.daysAgo(1), ilu = server.daysAgo(1))
        val all = known + listOf(newest) + fillers(4, "Zk")
        server.albums = all
        runBlocking { dao.upsertAlbums(known.map { row(it) }) }

        sync()

        assertEquals(listOf("1", "101", "201"), server.albumsRequests().map { it.url.queryParameter("start") })
        assertNotNull("the page-3 gallery was written", runBlocking { dao.getAlbumIndex(nick) }.find { it.albumKey == "AkNEWx" })
        val run = crawlRun()
        assertEquals(StopReason.NoNextPage, run.stop)
        assertEquals(3, run.pagesFetched)
        assertEquals(250, run.albumsSeen)
    }

    @Test fun everyPageRequest_carriesExpandVerbosityNoCache_andNoSort() {
        server.albums = fillers(230)

        sync()

        val requests = server.albumsRequests()
        assertEquals(3, requests.size)
        for (req in requests) {
            val where = "page start=${req.url.queryParameter("start")}"
            assertEquals(where, "HighlightImage", req.url.queryParameter("_expand"))
            assertEquals(where, "1", req.url.queryParameter("_verbosity"))
            assertEquals(where, "no-cache", req.header("Cache-Control"))
            assertTrue(where, req.url.queryParameter("_filter")!!.contains("ImagesLastUpdated"))
            assertEquals(where, "HighlightImage,Folder", req.url.queryParameter("_filteruri"))
            assertNull(where, req.url.queryParameter("SortMethod"))
            assertNull(where, req.url.queryParameter("SortDirection"))
        }
    }

    @Test fun http429OnPage2_writesNothing_andDoesNotStampTheGate() {
        server.albums = fillers(230)
        val stale = album("AkOLD0", "NdOLD0", "Old", lu = server.daysAgo(90), ilu = server.daysAgo(90))
        runBlocking { dao.upsertAlbums(listOf(row(stale))) }
        server.albumsOverride = { start, req -> if (start == 101) status(req, 429) else null }

        sync()

        val index = runBlocking { dao.getAlbumIndex(nick) }
        assertEquals("page 1's rows were not written", listOf("AkOLD0"), index.map { it.albumKey })
        assertEquals(0L, state.getLong("lastFullCrawlAt.$nick"))
        val run = crawlRun()
        assertEquals(StopReason.Error, run.stop)
        assertTrue(run.stopDetail!!, run.stopDetail!!.contains("429"))
        assertTrue(run.notes!!, run.notes!!.startsWith("complete=false"))
    }

    @Test fun ioFailureMidCrawl_isAllOrNothing_andRetriesNextLaunch() {
        server.albums = fillers(150)
        server.albumsOverride = { start, _ -> if (start == 101) throw java.io.IOException("offline") else null }

        sync()

        assertEquals(0, runBlocking { dao.getAlbumIndex(nick) }.size)
        assertEquals(0L, state.getLong("lastFullCrawlAt.$nick"))

        server.albumsOverride = null
        sync()
        assertEquals(150, runBlocking { dao.getAlbumIndex(nick) }.size)
    }

    @Test fun listing119After2633_skipsThePrune_andKeepsEveryRow() {
        // An expired session lists only the anonymous 119 (findings #16); that must not read as
        // "2,514 galleries were deleted".
        val seeded = fillers(2633, "Sd")
        runBlocking { dao.upsertAlbums(seeded.map { row(it) }) }
        server.albums = fillers(119, "Lv")

        sync()

        assertEquals(2633 + 119, runBlocking { dao.getAlbumIndex(nick) }.size)
        val notes = crawlRun().notes!!
        assertTrue(notes, notes.contains("pruned=0"))
        assertTrue(notes, notes.contains("pruneSkipped=2633"))
        assertTrue(notes, notes.startsWith("complete=true"))
    }

    @Test fun completeCrawl_prunesGalleriesTheServerNoLongerLists() {
        val keep = server.albums
        val stale = fillers(3, "St")
        runBlocking { dao.upsertAlbums(keep.map { row(it) } + stale.map { row(it) }) }

        sync()

        val keys = runBlocking { dao.getAlbumIndex(nick) }.map { it.albumKey }.toSet()
        assertEquals(setOf("FfHCms", "N74KSK"), keys)
        val notes = crawlRun().notes!!
        assertTrue(notes, notes.contains("pruned=3"))
        assertTrue(notes, notes.contains("pruneSkipped=0"))
    }

    @Test fun rejectedUnlock_neverPrunes() {
        // The saved password is rejected, so the password folder's galleries are invisible and would
        // look deleted. A crawl that did not see them must not prune them.
        server.cookieGate = true
        server.unlockCode = 401
        val store = FakePasswordStore(mapOf("2sDN5x" to "fake-pw-1"))
        val f = server.albums.first { it.albumKey == "FfHCms" }
        runBlocking { dao.upsertAlbums(listOf(row(f))) }

        sync(repo(store))

        assertEquals(
            "the password-folder gallery survives",
            listOf("FfHCms", "N74KSK"), runBlocking { dao.getAlbumIndex(nick) }.map { it.albumKey }.sorted()
        )
        val notes = crawlRun().notes!!
        assertTrue(notes, notes.contains("pruned=0"))
    }

    @Test fun secondCallWithin15Minutes_makesNoRequest_thenCrawlsAgainAfterward() {
        server.albums = fillers(230)
        sync()
        assertEquals(3, server.albumsRequests().size)
        assertEquals(now, state.getLong("lastFullCrawlAt.$nick"))

        now += 14 * 60_000L
        sync()
        assertEquals("a crawl 14 minutes ago gates this one", 3, server.albumsRequests().size)
        assertTrue(crawlRun().notes!!, crawlRun().notes!!.startsWith("gated"))
        assertEquals("the persisted index is still published", 230, runBlocking { dao.getAlbumIndex(nick) }.size)

        now += 2 * 60_000L
        sync()
        assertEquals(6, server.albumsRequests().size)
    }

    @Test fun unlockWhileRunning_crawlsAgainInsideTheGate_andTheLockedGalleryAppears() {
        // The user types the Family password at runtime. The launch crawl (no session) ran a minute ago,
        // so the 15-minute gate would skip the crawl and the dots under Family would wait for a later launch.
        server.cookieGate = true
        val r = repo()
        runBlocking { r.runSiteSync(nick, "4zqWw", "k") }
        assertEquals("anonymous crawl: only the public gallery", listOf("N74KSK"), runBlocking { dao.getAlbumIndex(nick) }.map { it.albumKey })
        val before = server.albumsRequests().size

        now += 60_000L
        // Through UnlockManager, as every app path does: the session epoch it bumps is what a resync is for.
        assertEquals(SmugMugRepository.UnlockResult.Success, runBlocking { r.unlocks.reauthorize("2sDN5x", "k", "fake-pw-1") })
        runBlocking { r.resyncAfterUnlock(nick, "4zqWw", "k") }

        assertTrue("a new crawl ran inside the gate", server.albumsRequests().size > before)
        assertEquals(
            listOf("FfHCms", "N74KSK"), runBlocking { dao.getAlbumIndex(nick) }.map { it.albumKey }.sorted()
        )
        assertTrue(crawlRun().notes!!, crawlRun().notes!!.startsWith("complete=true"))
    }

    @Test fun unlockWhileRunning_neverPrunes() {
        val stale = fillers(3, "St")
        runBlocking { dao.upsertAlbums(stale.map { row(it) }) }

        runBlocking { repo().resyncAfterUnlock(nick, "4zqWw", "k") }

        assertEquals(3, runBlocking { dao.getAlbumIndex(nick) }.count { it.albumKey.startsWith("St") })
    }

    @Test fun crawl_storesImagesLastUpdated_neverMovesItBack_andKeepsLastUpdatedSeparate() {
        val f = server.albums.first { it.albumKey == "FfHCms" }
        val newer = server.daysAgo(1) // local ILU is newer than the 15-minute-old listing's
        runBlocking { dao.upsertAlbums(listOf(row(f, parent = "P4BKB", ilu = newer))) }

        sync()

        val index = runBlocking { dao.getAlbumIndex(nick) }.associateBy { it.albumKey }
        assertEquals("ILU never moves back", newer, index.getValue("FfHCms").imagesLastUpdated)
        assertEquals("LastUpdated is kept apart", f.lastUpdated, index.getValue("FfHCms").dateModified)
        assertEquals("the resolver owns parentNodeId; the crawl keeps it", "P4BKB", index.getValue("FfHCms").parentNodeId)
        val pub = server.albums.first { it.albumKey == "N74KSK" }
        assertEquals("a new row gets the crawled ILU", pub.imagesLastUpdated, index.getValue("N74KSK").imagesLastUpdated)
        assertNull(index.getValue("N74KSK").parentNodeId)
    }

    @Test fun changedGallery_relistsItsCachedParentFolder_andReturnsIt() {
        // The gallery's ILU moved forward since the index row was written, and its folder already has
        // a cached listing: relist that folder (forced) so the Folders tab is not stale.
        val f = server.albums.first { it.albumKey == "FfHCms" }
        runBlocking {
            dao.upsertAlbums(listOf(row(f, parent = "P4BKB", ilu = server.daysAgo(5))))
            dao.insertNodes(
                listOf(
                    CachedNode(
                        nodeId = "LCdk7F", parentNodeId = "P4BKB", type = "Album", title = "New School Year",
                        description = null, access = "None", passwordHint = null, uri = "/api/v2/node/LCdk7F",
                        childNodesUri = null, albumUri = "/api/v2/album/FfHCms", nickname = nick
                    )
                )
            )
        }
        server.cookieGate = true
        val store = FakePasswordStore(mapOf("2sDN5x" to "fake-pw-1"))

        val relisted = sync(repo(store))

        assertEquals(setOf("P4BKB"), relisted)
        assertEquals(1, server.requestsTo("node/P4BKB!children").size)
        assertEquals(listOf<String?>("no-cache"), server.childrenCacheControls("P4BKB"))
        assertTrue(crawlRun().notes!!, crawlRun().notes!!.contains("relisted=1"))
    }

    @Test fun unchangedGallery_relistsNothing() {
        val f = server.albums.first { it.albumKey == "FfHCms" }
        runBlocking { dao.upsertAlbums(server.albums.map { row(it, parent = "P4BKB") }) }

        val relisted = sync()

        assertTrue(relisted.isEmpty())
        assertFalse(server.requests.any { it.contains("!children") })
        assertEquals(f.imagesLastUpdated, runBlocking { dao.getAlbumIndex(nick) }.first { it.albumKey == "FfHCms" }.imagesLastUpdated)
    }
}
