package com.smugview.app.data.offline

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.smugview.app.data.api.RetryingCallFactory
import com.smugview.app.data.api.forFileDownloads
import com.smugview.app.data.db.AppDatabase
import com.smugview.app.data.db.OfflineFile
import com.smugview.app.data.db.TestDb
import com.smugview.app.data.repository.FakeCdn
import com.smugview.app.data.repository.FakeOriginals
import com.smugview.app.data.repository.FakeSmugMugServer
import com.smugview.app.data.repository.LoopbackSmugMug
import com.smugview.app.data.repository.SmugMugRepository
import com.smugview.app.data.security.FakePasswordStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

/**
 * Phase 5 step 5-4: `OfflineDownloader.runPass` against a real loopback CDN ([FakeCdn]), the loopback API
 * ([LoopbackSmugMug]: the production client, so offline is the real synthetic 504), a real [SmugMugRepository], a
 * real in-memory Room and a real temp directory. Each failure of design 2.4 has a case, and each of them is the
 * opposite of what the old worker did (R-39, R-40: see [OldWorkerCharacterizationTest]).
 *
 * The images factory has `maxAttempts = 0`: the in-call 429/5xx retry (with `Retry-After`, up to minutes) has its own
 * tests in `RetryingCallFactoryTest`, and a unit test must not wait for it. What the pass does with the final
 * answer is what is tested here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class OfflineDownloaderTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val fake = FakeSmugMugServer()
    private val cacheDir: File = Files.createTempDirectory("smugview-dl-cache").toFile()
    private val base: File = Files.createTempDirectory("smugview-dl").toFile()
    private val filesDir = File(base, "files").also { it.mkdirs() }
    private lateinit var loopback: LoopbackSmugMug
    private lateinit var cdn: FakeCdn
    private lateinit var db: AppDatabase
    private lateinit var store: OfflineStore
    private lateinit var downloader: OfflineDownloader
    private val settings = InMemoryOfflineSettings()
    private var now = 1_800_000_000_000L
    private var free = 50L shl 30
    private var seq = 0

    @Before fun setUp() {
        loopback = LoopbackSmugMug(fake, cacheDir)
        cdn = FakeCdn()
        db = TestDb.inMemory()
        OfflineFixture.insert(db.openHelper.writableDatabase)
        val repository = SmugMugRepository(loopback.api(), db.collectionDao(), FakePasswordStore(), app)
        store = OfflineStore(db, filesDir, runId = "run0001", clock = { now }, freeBytes = { free })
        val images = RetryingCallFactory(cdn.clientOver(loopback.client).forFileDownloads(), maxAttempts = 0)
        downloader = OfflineDownloader(store, repository, images, apiKey = { "test-key" }, settings = settings, clock = { now })
    }

    @After fun tearDown() {
        cdn.close()
        loopback.close()
        db.close()
        cacheDir.deleteRecursively()
        base.deleteRecursively()
    }

    private val sql get() = db.openHelper.writableDatabase

    private fun cdnPath(key: String) = "/photos/i-$key/"

    /**
     * An Image bookmark makes [key] wanted (the reference count is a query over it), as saving a photo does. With [galleryOnly] the
     * only reference is a kept gallery's item, so the global network rule decides whether a mobile-data pass takes it.
     */
    private fun want(key: String, resolve: Boolean = false, galleryOnly: Boolean = false) = runBlocking {
        if (galleryOnly) {
            sql.execSQL("DELETE FROM collection_photos WHERE imageKey = '$key'")
            sql.execSQL("DELETE FROM collection_bookmarks WHERE type = 'Image' AND itemKey = '$key'")
            sql.execSQL(
                "INSERT OR IGNORE INTO offline_galleries (collectionId, albumKey, nickname, title, state, retryable) " +
                    "VALUES (1, '${OfflineFixture.GALLERY_ALBUM_KEY}', '${OfflineFixture.SITE}', 'Class photos', 'LISTED', 0)"
            )
            sql.execSQL("INSERT OR IGNORE INTO offline_gallery_items (collectionId, albumKey, imageKey, sortIndex) VALUES (1, '${OfflineFixture.GALLERY_ALBUM_KEY}', '$key', 0)")
        } else {
            val exists = sql.query("SELECT COUNT(*) FROM collection_bookmarks WHERE type = 'Image' AND itemKey = '$key'")
                .use { it.moveToFirst(); it.getInt(0) } > 0
            if (!exists) {
                sql.execSQL(
                    "INSERT INTO collection_bookmarks (collectionId, type, itemKey, title, albumKey, albumTitle, thumbnailUrl) " +
                        "VALUES (1, 'Image', '$key', 'IMG_$key', '${OfflineFixture.GALLERY_ALBUM_KEY}', 'Class photos', NULL)"
                )
            }
        }
        now += 1 // oldest first: the order of the calls is the order of the pass
        store.request(
            key, albumKey = OfflineFixture.GALLERY_ALBUM_KEY,
            sourceUrl = if (resolve) null else FakeOriginals.archivedUri(key),
            expectedBytes = if (resolve) null else FakeOriginals.size(key),
            md5 = if (resolve) null else FakeOriginals.md5(key)
        )
    }

    private fun row(key: String): OfflineFile = runBlocking { store.file(OfflineStore.fileKeyOf(key)) }
        ?: error("no row for $key")

    private fun parts(): List<String> = File(filesDir, "offline/.tmp").listFiles()?.map { it.name } ?: emptyList()

    private fun pass(network: NetworkClass = NetworkClass.UNMETERED, budgetMs: Long = OfflineDownloader.DEFAULT_BUDGET_MS) =
        runBlocking { downloader.runPass(network, budgetMs) }

    private fun assertDoneWithFile(key: String) {
        val r = row(key)
        assertEquals("$key should be DONE", "DONE", r.state)
        assertFalse("DONE must carry a path, never an empty one (R-40)", r.relPath.isNullOrEmpty())
        assertTrue(File(filesDir, r.relPath!!).exists())
        assertEquals(FakeOriginals.size(key), File(filesDir, r.relPath!!).length())
    }

    private val a = "Hk42gZp"
    private val b = "n83tQ3s"
    private val c = "XVRvVTM"

    @Test fun happyPath_downloadsEveryWantedPhoto_verifiedAndWithoutPartFiles() {
        want(a); want(b); want(c)

        val result = pass()

        assertEquals(3, result.downloaded)
        assertFalse(result.more)
        listOf(a, b, c).forEach { assertDoneWithFile(it) }
        assertEquals("a finished pass leaves no temp file", emptyList<String>(), parts())
        assertEquals(3, cdn.requests.size)
    }

    // ---- R-40: each failure is its own state, none is "done with an empty path" ------------------------------

    @Test fun busy429_isRetryableWithRetryAfter_andTheNextPhotoStillDownloads() {
        want(a); want(b)
        cdn.respondWith(cdnPath(a), 429, times = 1, headers = mapOf("Retry-After" to "120"))

        val result = pass()

        val first = row(a)
        assertEquals("FAILED", first.state)
        assertEquals("BUSY", first.failure)
        assertTrue(first.retryable)
        assertEquals(1, first.attempts)
        assertTrue("Retry-After 120 s is honoured", first.nextAttemptAt!! >= now + 120_000)
        assertNull(first.relPath)
        assertDoneWithFile(b)
        assertEquals(1, result.downloaded)
        assertEquals(1, result.failed)

        now += 121_000
        pass()
        assertDoneWithFile(a)
    }

    @Test fun offline_failsAsOfflineWithoutCountingAnAttempt_thenDownloadsWhenBack() {
        want(a); want(b)
        loopback.online = false

        val result = pass()

        for (key in listOf(a, b)) {
            val r = row(key)
            assertEquals("FAILED", r.state)
            assertEquals("OFFLINE", r.failure)
            assertTrue(r.retryable)
            assertEquals("not being connected is not the photo's fault", 0, r.attempts)
            assertTrue("an offline row is never DONE with an empty path", r.relPath.isNullOrEmpty())
        }
        assertEquals("the device is offline: the CDN saw nothing", 0, cdn.requests.size)
        assertEquals(FailureReason.OFFLINE, result.stoppedFor)

        loopback.online = true
        pass()

        assertDoneWithFile(a)
        assertDoneWithFile(b)
    }

    @Test fun truncatedBody_leavesNoPart_isOffline_andTheNextPassDownloadsIt() {
        want(a)
        cdn.truncate(cdnPath(a), times = 1)

        pass()

        val r = row(a)
        assertEquals("FAILED", r.state)
        assertEquals("OFFLINE", r.failure)
        assertEquals(0, r.attempts)
        assertEquals("the half-written file is deleted", emptyList<String>(), parts())

        pass()
        assertDoneWithFile(a)
    }

    @Test fun md5Mismatch_isDamaged_retriedOnce_thenPermanent() {
        want(a)
        cdn.corrupt(cdnPath(a), times = 2)

        pass()
        val first = row(a)
        assertEquals("DAMAGED", first.failure)
        assertTrue("the first mismatch is retried", first.retryable)
        assertEquals(emptyList<String>(), parts())

        now += DownloadFailure.backoffMs(1) + 1
        pass()
        val second = row(a)
        assertEquals("DAMAGED", second.failure)
        assertFalse("the second mismatch is permanent", second.retryable)
        assertEquals(2, second.attempts)
        assertEquals(2, cdn.requests.size)

        now += DownloadFailure.BACKOFF_CAP_MS
        pass()
        assertEquals("a permanent failure waits for the user", 2, cdn.requests.size)
    }

    @Test fun md5Mismatch_onceOnly_isDownloadedOnTheRetry() {
        want(a)
        cdn.corrupt(cdnPath(a), times = 1)

        pass()
        assertEquals("DAMAGED", row(a).failure)
        now += DownloadFailure.backoffMs(1) + 1
        pass()

        assertDoneWithFile(a)
    }

    @Test fun notFound404_isGone_andPermanent() {
        want(a); want(b)
        cdn.gone += a

        pass()

        val r = row(a)
        assertEquals("FAILED", r.state)
        assertEquals("GONE", r.failure)
        assertFalse(r.retryable)
        assertEquals(404, r.httpCode)
        assertDoneWithFile(b)
    }

    @Test fun forbidden403_looksTheSourceUpOnce_thenIsForbidden() {
        want("s00001")
        cdn.respondWith(cdnPath("s00001"), 403)

        pass()

        val r = row("s00001")
        assertEquals("FAILED", r.state)
        assertEquals("FORBIDDEN", r.failure)
        assertFalse(r.retryable)
        assertEquals(403, r.httpCode)
        assertEquals("one try, one re-resolve, one more try", 2, cdn.requests.size)
        assertEquals("exactly one image/{key}-0 lookup", 1, resolveRequests("s00001"))
    }

    @Test fun forbidden403_thenAFreshLinkWorks_isDone() {
        want("s00001")
        cdn.respondWith(cdnPath("s00001"), 403, times = 1)

        pass()

        assertDoneWithFile("s00001")
        assertEquals(1, resolveRequests("s00001"))
        val fresh = fake.requestLog.filter { it.url.encodedPath.endsWith("image/s00001-0") }
        assertEquals("the re-resolve skips the HTTP cache", "no-cache", fresh.last().header("Cache-Control"))
    }

    private fun resolveRequests(key: String) =
        fake.requestLog.count { it.url.encodedPath.endsWith("image/$key-0") }

    @Test fun missingSource_isResolvedFirst_thenDownloaded() {
        want("s00001", resolve = true)
        assertNull(row("s00001").sourceUrl)

        pass()

        assertDoneWithFile("s00001")
        assertEquals(FakeOriginals.archivedUri("s00001"), row("s00001").sourceUrl)
        assertEquals(FakeOriginals.md5("s00001"), row("s00001").md5)
        assertEquals(1, resolveRequests("s00001"))
    }

    @Test fun missingSource_whileOffline_isOfflineNotDone() {
        want("s00001", resolve = true)
        loopback.online = false

        pass()

        val r = row("s00001")
        assertEquals("FAILED", r.state)
        assertEquals("OFFLINE", r.failure)
        assertEquals(0, cdn.requests.size)
    }

    @Test fun missingSource_forbiddenByTheApi_isLockedNotRetriedForever() {
        want("s00001", resolve = true)
        fake.respondWith("image/s00001-0", 403, times = 5)

        pass()

        val r = row("s00001")
        assertEquals("FAILED", r.state)
        assertEquals("LOCKED", r.failure)
        assertEquals(0, cdn.requests.size)
    }

    // ---- storage ---------------------------------------------------------------------------------------------

    @Test fun lowFreeSpace_failsAsStorageFull_withoutAnotherRequest_andResumesWhenThereIsRoom() {
        want(a); want(b)
        free = OfflineStore.FREE_FLOOR_BYTES // the file would take it below the 1 GB floor (Q4)

        val result = pass()

        assertEquals(FailureReason.STORAGE_FULL, result.stoppedFor)
        assertEquals("FAILED", row(a).state)
        assertEquals("STORAGE_FULL", row(a).failure)
        assertTrue(row(a).retryable)
        assertEquals("PENDING", row(b).state)
        assertEquals("no request is made when there is no room", 0, cdn.requests.size)

        free = 50L shl 30
        now += DownloadFailure.STORAGE_RETRY_MS + 1
        pass()
        assertDoneWithFile(a)
        assertDoneWithFile(b)
    }

    // ---- cancellation and concurrency ------------------------------------------------------------------------

    @Test fun cancelMidBody_rethrows_putsTheRowBackToPending_andLeavesNoPart() = runBlocking<Unit> {
        want(a)
        cdn.holdMidBody(cdnPath(a))
        var cancelled = false
        val job = launch(Dispatchers.IO) {
            try {
                downloader.runPass(NetworkClass.UNMETERED)
            } catch (e: CancellationException) {
                cancelled = true
                throw e
            }
        }
        assertTrue("the download reached the middle of the body", cdn.awaitMidBody(cdnPath(a)))
        assertEquals("DOWNLOADING", row(a).state)

        job.cancel()
        job.join()
        cdn.releaseMidBody(cdnPath(a))

        assertTrue("CancellationException reaches the caller", cancelled)
        val r = row(a)
        assertEquals("PENDING", r.state)
        assertNull(r.claim)
        assertEquals("a cancel is not a failure", 0, r.attempts)
        assertEquals(emptyList<String>(), parts())
    }

    @Test fun twoPassesAtOnce_makeOneRequest() = runBlocking<Unit> {
        want(a)
        cdn.hold(cdnPath(a))

        val first = async(Dispatchers.IO) { downloader.runPass(NetworkClass.UNMETERED) }
        assertTrue(cdn.awaitEntered(cdnPath(a)))
        val second = async(Dispatchers.IO) { downloader.runPass(NetworkClass.UNMETERED) }
        cdn.release(cdnPath(a))
        val results = listOf(first, second).awaitAll()

        assertEquals("the second pass found it DONE", 1, cdn.countFor(cdnPath(a)))
        assertEquals(1, results.sumOf { it.downloaded })
        assertDoneWithFile(a)
    }

    // ---- budget and network class ----------------------------------------------------------------------------

    @Test fun zeroBudget_startsNoFile_andReportsMore() {
        want(a)

        val result = pass(budgetMs = 0)

        assertTrue(result.more)
        assertEquals(0, cdn.requests.size)
        assertEquals("PENDING", row(a).state)
    }

    @Test fun nothingWanted_isNotMore_andMakesNoRequest() {
        val result = pass()

        assertFalse(result.more)
        assertEquals(0, result.downloaded)
        assertEquals(0, cdn.requests.size)
    }

    @Test fun onMobileData_aGalleryOnlyPhotoWaitsUnderWifiOnly_andASavedPhotoDownloads() {
        want(a, galleryOnly = true); want(b)

        pass(NetworkClass.ANY)

        assertEquals("PENDING", row(a).state)
        assertDoneWithFile(b)
        assertEquals(0, cdn.countFor(cdnPath(a)))

        pass(NetworkClass.UNMETERED)
        assertDoneWithFile(a)
    }

    @Test fun onMobileData_underWifiAndMobile_aGalleryOnlyPhotoDownloads() {
        runBlocking { settings.set(OfflineNetworkRule.WIFI_AND_MOBILE) }
        want(a, galleryOnly = true); want(b, galleryOnly = true)

        pass(NetworkClass.ANY)

        assertDoneWithFile(a)
        assertDoneWithFile(b)
    }

    @Test fun aStaleWifiOnlyZeroOnAGalleryRow_doesNotLetMobileDataTakeItUnderWifiOnly() {
        want(a, galleryOnly = true)
        sql.execSQL("UPDATE offline_galleries SET wifiOnly = 0")
        sql.execSQL("UPDATE offline_files SET wifiOnly = 0")

        pass(NetworkClass.ANY)

        assertEquals("PENDING", row(a).state)
        assertEquals(0, cdn.countFor(cdnPath(a)))
    }

    @Test fun theRuleFlippedMidPass_letsTheCurrentFileFinish_andStartsNoNewGalleryFile() {
        runBlocking { settings.set(OfflineNetworkRule.WIFI_AND_MOBILE) }
        want(a, galleryOnly = true); want(b, galleryOnly = true)
        cdn.hold(cdnPath(a))

        val running = Thread { pass(NetworkClass.ANY) }
        running.start()
        assertTrue("the pass reached the first file", cdn.awaitEntered(cdnPath(a)))
        runBlocking { settings.set(OfflineNetworkRule.WIFI_ONLY) }
        cdn.release(cdnPath(a))
        running.join(30_000)

        assertDoneWithFile(a)
        assertEquals("PENDING", row(b).state)
        assertEquals(0, cdn.countFor(cdnPath(b)))
    }

    @Test fun aRowNoLongerWanted_isNotDownloadedAndItsFileIsCollected() {
        want(a)
        sql.execSQL("DELETE FROM collection_bookmarks WHERE type = 'Image' AND itemKey = '$a'")
        sql.execSQL("DELETE FROM collection_photos WHERE imageKey = '$a'")

        pass()

        assertEquals("nobody wants it: no request", 0, cdn.requests.size)
        assertNull(runBlocking { store.file(OfflineStore.fileKeyOf(a)) })
    }

    @Test fun passAfterNewProcess_resumesTheRowTheDeadProcessLeft() {
        want(a)
        sql.execSQL("UPDATE offline_files SET state = 'DOWNLOADING', claim = 'deadrun01' WHERE imageKey = '$a'")
        File(filesDir, "offline/.tmp").mkdirs()
        File(filesDir, "offline/.tmp/$a.orig.deadrun01.part").writeBytes(ByteArray(10))

        pass()

        assertDoneWithFile(a)
        assertEquals(emptyList<String>(), parts())
    }
}
