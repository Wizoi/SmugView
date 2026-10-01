package com.smugview.app.diag

import com.smugview.app.data.api.SmugMugApi
import com.smugview.app.data.db.AppDatabase
import com.smugview.app.data.db.CachedAlbum
import com.smugview.app.data.db.TestDb
import com.smugview.app.data.repository.SmugMugRepository
import com.smugview.app.data.security.FakePasswordStore
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
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
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.File
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * The repository's sync and unlock hooks, on real Room and real payload shapes: `user!albums` has no
 * `ParentNode`, carries `Uris.Folder`, and some albums have no `LastUpdated` (R-05, findings #1).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class SyncReportTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var db: AppDatabase
    private lateinit var diagDir: File
    private lateinit var sink: FileLogSink
    private lateinit var log: DiagLog
    private lateinit var reporter: FileSyncReporter

    @Before
    fun setUp() {
        db = TestDb.inMemory()
        diagDir = tmp.newFolder("diagnostics")
        sink = FileLogSink(diagDir)
        // No secrets registered on purpose: the code itself must never write a password.
        log = DiagLog(sink, Redactor())
        reporter = FileSyncReporter(File(diagDir, "sync_reports.jsonl"), log)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun daysAgo(days: Long): String = OffsetDateTime.now(ZoneOffset.UTC).minusDays(days)
        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssxxx"))

    private fun albumJson(key: String, node: String, updated: String?, security: String = "None") = buildString {
        append("""{"Uri":"/api/v2/album/$key","AlbumKey":"$key","NodeID":"$node","Name":"Gallery $key",""")
        append(""""UrlPath":"/Family/School/$key","WebUri":"https://nick.smugmug.com/Family/School/$key",""")
        append(""""SecurityType":"$security","ImageCount":12,""")
        if (updated != null) append(""""LastUpdated":"$updated",""")
        append(""""Uris":{"Folder":"/api/v2/folder/user/nick/Family/School","HighlightImage":"/api/v2/highlight/album/$key"}}""")
    }

    private fun albumsPage(albums: List<String>, next: String?, total: Int = 9) = buildString {
        append("""{"Response":{"Uri":"/api/v2/user/nick!albums","Album":[""")
        append(albums.joinToString(","))
        append("""],"Pages":{"Start":1,"Count":${albums.size},"Total":$total""")
        if (next != null) append(""","NextPage":"$next"""")
        append("}},\"Code\":200,\"Message\":\"Ok\"}")
    }

    private fun repo(interceptor: Interceptor): SmugMugRepository {
        val client = OkHttpClient.Builder().addInterceptor(interceptor).build()
        val api = Retrofit.Builder().baseUrl("https://api.smugmug.com/api/v2/").client(client)
            .addConverterFactory(GsonConverterFactory.create()).build().create(SmugMugApi::class.java)
        return SmugMugRepository(
            api, db.collectionDao(), FakePasswordStore(), ApplicationProvider.getApplicationContext(), reporter
        )
    }

    private fun reply(chain: Interceptor.Chain, code: Int, body: String) = Response.Builder()
        .request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("m")
        .body(body.toResponseBody("application/json".toMediaTypeOrNull())).build()

    private fun lastRun(): SyncRun = reporter.recent(1).single()

    @Test
    fun twoPages_crawlBothAndStopWithNoNextPage() = runBlocking {
        val repository = repo { chain ->
            val url = chain.request().url.toString()
            if (chain.request().url.queryParameter("start") == "101") {
                reply(chain, 200, albumsPage(listOf(albumJson("AAAAAA", "N3", daysAgo(5))), next = null, total = 104))
            } else {
                reply(
                    chain, 200,
                    albumsPage(
                        listOf(
                            albumJson("FfHCms", "LCdk7F", daysAgo(2)),
                            albumJson("NoDate", "N2", null),
                            albumJson("PwAlbm", "N4", daysAgo(9), security = "Password")
                        ),
                        next = "/api/v2/user/nick!albums?start=101&count=100",
                        total = 104
                    )
                )
            }
        }

        repository.buildInMemoryGalleryCache("nick", "FAKEKEY1234")
        reporter.flush(2000)

        val run = lastRun()
        assertEquals(SyncKind.GallerySync, run.kind)
        assertEquals("nick", run.nickname)
        assertEquals(StopReason.NoNextPage, run.stop)
        assertEquals(2, run.pagesFetched)
        assertEquals(4, run.albumsSeen)
        assertEquals(1, run.albumsNullLastUpdated)
        assertEquals(0, run.albumsWithParentNode) // user!albums carries no ParentNode (R-05)
        assertEquals(1, run.albumsPasswordSecurity)
        assertEquals(true, run.isFirstSync)
        assertEquals(0, run.persistedCount)
        assertEquals(4, run.changedCount)
        assertNotNull(run.endedAt)
        assertTrue(run.runId.startsWith("sync#"))
    }

    @Test
    fun knownAlbumsOnPageOne_doNotStopTheCrawl() = runBlocking {
        // findings #14: user!albums is not sorted by any date it returns, so a known album on page 1
        // says nothing about page 2. (This was knownMarkerOnFirstPage_stopsWithReachedKnown.)
        val known = daysAgo(10)
        db.collectionDao().upsertAlbums(
            listOf(
                CachedAlbum(
                    albumKey = "OldKey", nodeId = "OldNode", name = "Old", securityType = "None",
                    passwordHint = null, uri = "/api/v2/album/OldKey", webUri = null, urlPath = null,
                    imageCount = 1, dateModified = known, galleryStyle = null, highlightImageUrl = null,
                    nickname = "nick"
                )
            )
        )
        val repository = repo { chain ->
            if (chain.request().url.queryParameter("start") == "101") {
                reply(chain, 200, albumsPage(listOf(albumJson("Page2", "N7", daysAgo(1))), next = null, total = 101))
            } else {
                reply(
                    chain, 200,
                    albumsPage(
                        listOf(
                            albumJson("New1", "N1", daysAgo(1)),
                            albumJson("New2", "N2", daysAgo(2)),
                            albumJson("New3", "N3", daysAgo(3)),
                            albumJson("OldKey", "OldNode", known),
                            albumJson("Older", "N9", daysAgo(40))
                        ),
                        next = "/api/v2/user/nick!albums?start=101&count=100",
                        total = 101
                    )
                )
            }
        }

        repository.buildInMemoryGalleryCache("nick", "FAKEKEY1234")
        reporter.flush(2000)

        val run = lastRun()
        assertEquals(StopReason.NoNextPage, run.stop)
        assertEquals(null, run.stopDetail)
        assertEquals(false, run.isFirstSync)
        assertEquals(1, run.persistedCount)
        assertEquals(2, run.pagesFetched)
        assertEquals(6, run.albumsSeen)
        // New to the index: New1..3, Older and Page2. OldKey is known and unchanged.
        assertEquals(5, run.changedCount)
    }

    @Test
    fun http429OnPageTwo_isRecordedAsError() = runBlocking {
        val repository = repo { chain ->
            if (chain.request().url.queryParameter("start") == "101") {
                reply(chain, 429, """{"Code":429,"Message":"Too Many Requests"}""")
            } else {
                reply(
                    chain, 200,
                    albumsPage(
                        listOf(albumJson("FfHCms", "LCdk7F", daysAgo(2))),
                        next = "/api/v2/user/nick!albums?start=101&count=100",
                        total = 150
                    )
                )
            }
        }

        repository.buildInMemoryGalleryCache("nick", "FAKEKEY1234")
        reporter.flush(2000)

        val run = lastRun()
        assertEquals(StopReason.Error, run.stop)
        assertEquals("HttpException 429", run.stopDetail)
        assertEquals(1, run.pagesFetched)
        assertEquals(0, run.changedCount) // nothing was persisted: the error swallowed the first page
        assertNotNull(run.endedAt)
    }

    @Test
    fun rejectedUnlock_isRecorded_andPasswordNeverReachesDisk() = runBlocking {
        val password = "correct-horse-battery-staple"
        val repository = repo { chain -> reply(chain, 401, """{"Code":401,"Message":"Invalid password."}""") }
        val actionId = "unlock#test"
        val run = reporter.begin(SyncKind.LaunchUnlock, "nick", actionId)
        assertNotNull(run)

        val result = withContext(DiagContext.element(actionId)) {
            repository.unlockNodeResult("2sDN5x", "FAKEKEY1234", password)
        }
        reporter.finish(run!!)
        reporter.flush(2000)
        log.flush(2000)

        assertEquals(SmugMugRepository.UnlockResult.Rejected, result)
        val attempt = run.unlocks.single()
        assertEquals("2sDN5x", attempt.target)
        assertEquals("node", attempt.via)
        assertEquals("Rejected", attempt.result)
        assertEquals(401, attempt.httpCode)
        assertEquals(actionId, attempt.actionId)

        val everything = diagDir.walkTopDown().filter { it.isFile }.joinToString("\n") { it.readText() }
        assertTrue(everything.contains("2sDN5x"))
        assertFalse("password leaked to disk", everything.contains(password))
    }

    @Test
    fun albumUnlockException_isRecordedWithClassOnly() = runBlocking {
        val repository = repo { throw java.io.IOException("boom") }
        val run = reporter.begin(SyncKind.LaunchUnlock, "nick", "unlock#2")!!
        withContext(DiagContext.element("unlock#2")) {
            repository.unlockAlbumResult("FfHCms", "FAKEKEY1234", "some-password")
        }
        val attempt = run.unlocks.single()
        assertEquals("album", attempt.via)
        assertEquals("Transient", attempt.result)
        assertNull(attempt.httpCode)
        assertEquals("IOException", attempt.exception)
    }

    @Test
    fun subtreeWalk_recordsRunAndSkippedSubfolders() = runBlocking {
        val repository = repo { chain ->
            val url = chain.request().url.toString()
            if (url.contains("node/2sDN5x!children")) {
                reply(
                    chain, 200,
                    """{"Response":{"Uri":"/api/v2/node/2sDN5x!children","Node":[
                        {"Uri":"/api/v2/node/P4BKB","NodeID":"P4BKB","Type":"Folder","Name":"Sub",
                         "Uris":{"ChildNodes":"/api/v2/node/P4BKB!children"}}]},"Code":200,"Message":"Ok"}"""
                )
            } else {
                reply(chain, 403, """{"Code":403,"Message":"Forbidden"}""")
            }
        }
        repository.unlockAndIndexSubtree("idzifamily", "2sDN5x", "FAKEKEY1234", "pw-not-logged-1")
        reporter.flush(2000)

        val run = lastRun()
        assertEquals(SyncKind.SubtreeIndex, run.kind)
        assertTrue(run.runId.startsWith("subtree#"))
        assertEquals("nodes=2 skipped=1", run.notes)
        assertEquals(StopReason.Completed, run.stop)
        log.flush(2000)
        val everything = diagDir.walkTopDown().filter { it.isFile }.joinToString("\n") { it.readText() }
        assertFalse(everything.contains("pw-not-logged-1"))
    }
}
