package com.smugview.app.data.offline

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
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
import com.smugview.app.data.worker.OfflineFilesWorker
import com.smugview.app.diag.DiagLog
import com.smugview.app.diag.LogSink
import com.smugview.app.diag.Redactor
import com.smugview.app.diag.SinkStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
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
 * Phase 5 step 5-5: [OfflineScheduler] and [OfflineFilesWorker] over WorkManager's test implementation
 * (`work-testing`: unique work, constraints and the chain are the real thing; the worker is built by hand with a
 * `WorkerFactory`, no Hilt) and the same real CDN, API and Room as [OfflineDownloaderTest].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class OfflineWorkerTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val fake = FakeSmugMugServer()
    private val cacheDir: File = Files.createTempDirectory("smugview-wk-cache").toFile()
    private val base: File = Files.createTempDirectory("smugview-wk").toFile()
    private val filesDir = File(base, "files").also { it.mkdirs() }
    private lateinit var loopback: LoopbackSmugMug
    private lateinit var cdn: FakeCdn
    private lateinit var db: AppDatabase
    private lateinit var store: OfflineStore
    private lateinit var downloader: OfflineDownloader
    private lateinit var scheduler: OfflineScheduler
    private lateinit var factory: WorkerFactory
    private lateinit var wm: WorkManager
    private lateinit var log: DiagLog
    private var now = 1_800_000_000_000L
    private var free = 50L shl 30

    private val a = "Hk42gZp"
    private val b = "n83tQ3s"
    private val shared = OfflineFixture.SHARED

    @Before fun setUp() {
        loopback = LoopbackSmugMug(fake, cacheDir)
        cdn = FakeCdn()
        db = TestDb.inMemory()
        OfflineFixture.insert(db.openHelper.writableDatabase)
        log = DiagLog(object : LogSink {
            override fun append(line: String) {}
            override fun flush(timeoutMs: Long) = true
            override fun read(maxBytes: Int) = ""
            override fun clear() {}
            override val status = SinkStatus(0, 0, null)
        }, Redactor())
        wireProcess(runId = "run0001")
        factory = object : WorkerFactory() {
            override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker? =
                if (workerClassName == OfflineFilesWorker::class.java.name)
                    OfflineFilesWorker(appContext, workerParameters, downloader, scheduler, log)
                else null
        }
        WorkManagerTestInitHelper.initializeTestWorkManager(
            app, Configuration.Builder().setWorkerFactory(factory).setExecutor(SynchronousExecutor()).build()
        )
        wm = WorkManager.getInstance(app)
    }

    /** What one process owns: its store (run id), downloader and scheduler. A new call is "the process started again". */
    private fun wireProcess(runId: String) {
        val repository = SmugMugRepository(loopback.api(), db.collectionDao(), FakePasswordStore(), app)
        store = OfflineStore(db, filesDir, runId = runId, clock = { now }, freeBytes = { free })
        val images = RetryingCallFactory(cdn.clientOver(loopback.client).forFileDownloads(), maxAttempts = 0)
        downloader = OfflineDownloader(store, repository, images, apiKey = { "test-key" }, clock = { now })
        scheduler = OfflineScheduler(workManager = { WorkManager.getInstance(app) }, store = store, clock = { now })
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

    private fun want(key: String, wifiOnly: Boolean = false) = runBlocking {
        val exists = sql.query("SELECT COUNT(*) FROM collection_bookmarks WHERE type = 'Image' AND itemKey = '$key'")
            .use { it.moveToFirst(); it.getInt(0) } > 0
        if (!exists) {
            sql.execSQL(
                "INSERT INTO collection_bookmarks (collectionId, type, itemKey, title, albumKey, albumTitle, thumbnailUrl) " +
                    "VALUES (1, 'Image', '$key', 'IMG_$key', '${OfflineFixture.GALLERY_ALBUM_KEY}', 'Class photos', NULL)"
            )
        }
        now += 1
        store.request(
            key, albumKey = OfflineFixture.GALLERY_ALBUM_KEY, sourceUrl = FakeOriginals.archivedUri(key),
            expectedBytes = FakeOriginals.size(key), md5 = FakeOriginals.md5(key), wifiOnly = wifiOnly
        )
    }

    private fun row(key: String): OfflineFile = runBlocking { store.file(OfflineStore.fileKeyOf(key)) } ?: error("no row for $key")
    private fun parts(): List<String> = File(filesDir, "offline/.tmp").listFiles()?.map { it.name } ?: emptyList()
    private fun infos(name: String): List<WorkInfo> = wm.getWorkInfosForUniqueWork(name).get()

    private fun assertDone(key: String) {
        val r = row(key)
        assertEquals("$key should be DONE", "DONE", r.state)
        assertFalse(r.relPath.isNullOrEmpty())
        assertEquals(FakeOriginals.size(key), File(filesDir, r.relPath!!).length())
    }

    private fun worker(network: NetworkClass? = null, budgetMs: Long? = null): OfflineFilesWorker {
        val data = Data.Builder().apply {
            if (network != null) putString(OfflineFilesWorker.KEY_NETWORK, network.name)
            if (budgetMs != null) putLong(OfflineFilesWorker.KEY_BUDGET_MS, budgetMs)
        }.build()
        return TestListenableWorkerBuilder<OfflineFilesWorker>(app).setInputData(data).setWorkerFactory(factory).build()
    }

    private fun run(network: NetworkClass? = null, budgetMs: Long? = null): ListenableWorker.Result =
        runBlocking { worker(network, budgetMs).doWork() }

    /** Lets the test WorkManager run a chain: marks each enqueued request's constraints met until all have finished. */
    private fun drive(name: String, timeoutMs: Long = 30_000) {
        val driver = WorkManagerTestInitHelper.getTestDriver(app)!!
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val all = infos(name)
            if (all.isNotEmpty() && all.all { it.state.isFinished }) return
            all.filter { it.state == WorkInfo.State.ENQUEUED }.forEach { driver.setAllConstraintsMet(it.id) }
            Thread.sleep(25)
        }
        error("work $name did not finish: ${infos(name).map { it.state }}")
    }

    // ---- the work spec -----------------------------------------------------------------------------------------

    @Test fun kick_enqueuesUniqueWork_withConnectedAndStorageNotLow() = runBlocking<Unit> {
        want(a)

        scheduler.kick()

        val all = infos(OfflineScheduler.NAME)
        assertEquals(1, all.size)
        assertEquals(NetworkType.CONNECTED, all[0].constraints.requiredNetworkType)
        assertTrue("a full phone waits instead of failing", all[0].constraints.requiresStorageNotLow())
        assertTrue("no wifi-only row: no unmetered work", infos(OfflineScheduler.NAME_WIFI).isEmpty())
    }

    @Test fun twoKicks_areChained_neverTwoPassesAtOnce_andBothAddsDownload() = runBlocking<Unit> {
        want(a)
        scheduler.kick()
        want(b) // added after the first run was queued
        scheduler.kick()

        val states = infos(OfflineScheduler.NAME).map { it.state }
        assertEquals("the second run waits behind the first", listOf(WorkInfo.State.BLOCKED, WorkInfo.State.ENQUEUED), states.sortedBy { it.name })

        drive(OfflineScheduler.NAME)

        assertEquals(listOf(WorkInfo.State.SUCCEEDED, WorkInfo.State.SUCCEEDED), infos(OfflineScheduler.NAME).map { it.state })
        assertDone(a)
        assertDone(b)
        assertEquals("each file once", 1, cdn.countFor(cdnPath(a)))
        assertEquals(1, cdn.countFor(cdnPath(b)))
    }

    @Test fun kick_withAWifiOnlyRow_alsoEnqueuesTheUnmeteredWork() = runBlocking<Unit> {
        want(a, wifiOnly = true)

        scheduler.kick()

        val wifi = infos(OfflineScheduler.NAME_WIFI)
        assertEquals(1, wifi.size)
        assertEquals(NetworkType.UNMETERED, wifi[0].constraints.requiredNetworkType)
        assertTrue(wifi[0].constraints.requiresStorageNotLow())
    }

    @Test fun kickIfWanted_doesNothingWhenEverythingIsDone_andKicksWhenSomethingIsNot() = runBlocking<Unit> {
        scheduler.kickIfWanted()
        assertTrue("nothing wanted: no work", infos(OfflineScheduler.NAME).isEmpty())

        want(a)
        scheduler.kickIfWanted()
        assertEquals(1, infos(OfflineScheduler.NAME).size)
    }

    // ---- what the pass returns and schedules -------------------------------------------------------------------

    @Test fun nothingPending_succeeds_withNoRequestAndNoWork() {
        assertEquals(ListenableWorker.Result.success(), run())

        assertEquals(0, cdn.requests.size)
        assertTrue(infos(OfflineScheduler.NAME).isEmpty())
        assertTrue(infos(OfflineScheduler.NAME_RETRY).isEmpty())
    }

    @Test fun busy429_succeeds_andSchedulesOneRetryWithRetryAfterAsTheDelay() = runBlocking<Unit> {
        want(a); want(b)
        cdn.respondWith(cdnPath(a), 429, times = 1, headers = mapOf("Retry-After" to "120"))

        val result = run()

        assertEquals("never retry(): a stuck file must not hold back the adds behind it", ListenableWorker.Result.success(), result)
        assertDone(b)
        val retry = infos(OfflineScheduler.NAME_RETRY)
        assertEquals(1, retry.size)
        assertEquals(WorkInfo.State.ENQUEUED, retry[0].state)
        assertEquals(120_000L, retry[0].initialDelayMillis)
        assertEquals(NetworkType.CONNECTED, retry[0].constraints.requiredNetworkType)
        assertTrue(retry[0].constraints.requiresStorageNotLow())
    }

    @Test fun offline_succeeds_andSchedulesARetryNoSoonerThanTheMinimum() = runBlocking<Unit> {
        want(a)
        loopback.online = false

        assertEquals(ListenableWorker.Result.success(), run())

        assertEquals("FAILED", row(a).state)
        assertEquals(OfflineScheduler.MIN_RETRY_DELAY_MS, infos(OfflineScheduler.NAME_RETRY).single().initialDelayMillis)

        loopback.online = true
        now += 60_000
        assertEquals(ListenableWorker.Result.success(), run())
        assertDone(a)
        assertTrue("nothing left: no retry is scheduled again", infos(OfflineScheduler.NAME_RETRY).none { !it.state.isFinished && it.initialDelayMillis != OfflineScheduler.MIN_RETRY_DELAY_MS })
    }

    @Test fun storageLow_succeeds_andWaitsAnHour() = runBlocking<Unit> {
        want(a)
        free = OfflineStore.FREE_FLOOR_BYTES

        assertEquals(ListenableWorker.Result.success(), run())

        assertEquals("STORAGE_FULL", row(a).failure)
        assertEquals(0, cdn.requests.size)
        assertEquals(DownloadFailure.STORAGE_RETRY_MS, infos(OfflineScheduler.NAME_RETRY).single().initialDelayMillis)
    }

    @Test fun budgetUsedUp_appendsAnotherRun() = runBlocking<Unit> {
        want(a)

        assertEquals(ListenableWorker.Result.success(), run(budgetMs = 0))

        assertEquals("PENDING", row(a).state)
        assertEquals("the pass stopped with a file due: the next run is appended", 1, infos(OfflineScheduler.NAME).size)
        assertTrue(infos(OfflineScheduler.NAME_RETRY).isEmpty())
    }

    @Test fun cancelledWorker_leavesTheRowPending_noPart_andNoRetry() = runBlocking<Unit> {
        want(a)
        cdn.holdMidBody(cdnPath(a))
        var cancelled = false
        val job = launch(Dispatchers.IO) {
            try {
                worker().doWork()
            } catch (e: CancellationException) {
                cancelled = true
                throw e
            }
        }
        assertTrue(cdn.awaitMidBody(cdnPath(a)))

        job.cancel()
        job.join()
        cdn.releaseMidBody(cdnPath(a))

        assertTrue("the worker does not swallow its cancellation (R-39)", cancelled)
        assertEquals("PENDING", row(a).state)
        assertEquals(0, row(a).attempts)
        assertEquals(emptyList<String>(), parts())
        assertTrue("a stopped worker schedules nothing", infos(OfflineScheduler.NAME_RETRY).isEmpty())
    }

    @Test fun passLogsOneLine_withCountsAndNoKeysOrUrls() {
        want(a)

        run()

        val line = log.recent(10).single { it.contains("offline pass#") }
        assertTrue(line, line.contains("downloaded=1"))
        assertFalse("no image key in the log", line.contains(a))
        assertFalse("no URL in the log", line.contains("http") || line.contains("photos.smugmug.com"))
    }

    // ---- shared files, network class, process death ------------------------------------------------------------

    @Test fun aPhotoInTwoCollections_isDownloadedOnce() {
        want(shared) // referenced by two collections and an Image bookmark in the fixture

        run()

        assertDone(shared)
        assertEquals(1, cdn.countFor(cdnPath(shared)))
        assertEquals(1, runBlocking { db.offlineDao().allFiles() }.count { it.imageKey == shared })
    }

    @Test fun passAfterNewProcess_resumesTheRowTheDeadProcessLeft() {
        want(a)
        sql.execSQL("UPDATE offline_files SET state = 'DOWNLOADING', claim = 'run0001' WHERE imageKey = '$a'")
        File(filesDir, "offline/.tmp").mkdirs()
        File(filesDir, "offline/.tmp/$a.orig.run0001.part").writeBytes(ByteArray(10))
        wireProcess(runId = "run0002") // the process died and started again

        assertEquals(ListenableWorker.Result.success(), run())

        assertDone(a)
        assertEquals(emptyList<String>(), parts())
    }

    @Test fun onMobileData_aWifiOnlyRowWaits_withoutARetryLoop_andAPhotoThatMayUseMobileDataDownloads() = runBlocking<Unit> {
        want(a, wifiOnly = true); want(b, wifiOnly = false)

        assertEquals(ListenableWorker.Result.success(), run(NetworkClass.ANY))

        assertEquals("PENDING", row(a).state)
        assertDone(b)
        assertEquals(0, cdn.countFor(cdnPath(a)))
        assertTrue("a row this pass can never take is not a reason to come back", infos(OfflineScheduler.NAME_RETRY).isEmpty())
        assertTrue(infos(OfflineScheduler.NAME_WIFI_RETRY).isEmpty())

        assertEquals(ListenableWorker.Result.success(), run(NetworkClass.UNMETERED))
        assertDone(a)
    }

    @Test fun aWifiOnlyRetry_isScheduledOnTheUnmeteredChain_notTheMobileOne() = runBlocking<Unit> {
        want(a, wifiOnly = true)
        cdn.respondWith(cdnPath(a), 429, times = 1)

        run(NetworkClass.UNMETERED)

        assertEquals(1, infos(OfflineScheduler.NAME_WIFI_RETRY).size)
        assertEquals(NetworkType.UNMETERED, infos(OfflineScheduler.NAME_WIFI_RETRY).single().constraints.requiredNetworkType)
        assertTrue(infos(OfflineScheduler.NAME_RETRY).isEmpty())
        assertNull(row(a).relPath)
    }
}
