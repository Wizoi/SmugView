package com.smugview.app.data.offline

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.smugview.app.data.api.AlbumImageData
import com.smugview.app.data.db.AppDatabase
import com.smugview.app.data.db.OfflineCollection
import com.smugview.app.data.db.TestDb
import com.smugview.app.data.repository.FakeCdn
import com.smugview.app.data.repository.FakeOriginals
import com.smugview.app.data.repository.FakeSmugMugServer
import com.smugview.app.data.repository.LoopbackSmugMug
import com.smugview.app.data.repository.SmugMugRepository
import com.smugview.app.data.security.FakePasswordStore
import com.smugview.app.data.worker.OfflineDownloadWorker
import com.smugview.app.ui.viewmodel.CollectionsController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowStatFs
import java.io.File
import java.nio.file.Files
import java.time.Instant
import kotlin.concurrent.thread

/**
 * Phase 5 step 5-0 (design section 5, E2-E4): the OLD `OfflineDownloadWorker` and `addPhotoToCollection`,
 * run for real against a fake CDN, over the [OfflineFixture] rows.
 *
 * These are characterization tests: each PINS today's wrong behaviour so the suite stays green and the step
 * that fixes it has a test to flip. They are the red-first evidence for R-38, R-39 and R-40, and a later step
 * changes the assertions, it does not add new ones next to them:
 *  - R-40 (5-5 deletes the old worker, so these cases move to the new worker's `DownloadFailure` mapping)
 *  - R-39 (5-6: unique work and one worker; the "two in flight" case flips to "one request")
 *  - R-38 (5-6: a shared file is written once and never truncated by a second writer)
 * Deviation from the design text ("characterization runs, not committed"): recorded in design section 13.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class OldWorkerCharacterizationTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val fake = FakeSmugMugServer()
    private val cacheDir: File = Files.createTempDirectory("smugview-oldworker-cache").toFile()
    private lateinit var loopback: LoopbackSmugMug
    private lateinit var cdn: FakeCdn
    private lateinit var db: AppDatabase
    private lateinit var repository: SmugMugRepository
    private lateinit var client: OkHttpClient

    @Before fun setUp() {
        // Robolectric's StatFs has no free space by default, and the old worker refuses to start under 50 MB.
        ShadowStatFs.registerStats(app.filesDir.path, 1_000_000, 900_000, 900_000)
        loopback = LoopbackSmugMug(fake, cacheDir)
        cdn = FakeCdn()
        db = TestDb.inMemory()
        repository = SmugMugRepository(fake.api(), db.collectionDao(), FakePasswordStore(), app)
        client = cdn.clientOver(loopback.client)
        OfflineFixture.insert(db.openHelper.writableDatabase)
        File(app.filesDir, "offline_photos").deleteRecursively()
    }

    @After fun tearDown() {
        cdn.close()
        loopback.close()
        db.close()
        cacheDir.deleteRecursively()
        File(app.filesDir, "offline_photos").deleteRecursively()
    }

    private fun worker(attempt: Int = 0, httpClient: OkHttpClient = client): OfflineDownloadWorker =
        TestListenableWorkerBuilder<OfflineDownloadWorker>(app)
            .setRunAttemptCount(attempt)
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker =
                    OfflineDownloadWorker(appContext, workerParameters, repository, httpClient)
            })
            .build()

    private fun run(attempt: Int = 0): ListenableWorker.Result = runBlocking { worker(attempt).doWork() }

    private data class Row(val key: String, val collection: Long, val downloaded: Boolean, val path: String?)

    private fun rows(): List<Row> =
        db.openHelper.readableDatabase.query("SELECT imageKey, collectionId, isDownloaded, localFilePath FROM collection_photos ORDER BY imageKey, collectionId").use { c ->
            buildList { while (c.moveToNext()) add(Row(c.getString(0), c.getLong(1), c.getInt(2) == 1, c.getString(3))) }
        }

    private fun rowsOf(key: String) = rows().filter { it.key == key }

    // --- R-40: a failure is recorded as an empty path, and nothing tells it from a success ---

    /** 429 is "slow down", not "this photo is gone": the old worker treats every 4xx as permanent. */
    @Test fun `R-40 a 429 from the CDN is written down as a finished download with no file`() {
        cdn.respondWith("/photos/", 429, headers = mapOf("Retry-After" to "120"))

        val result = run()

        // The worker reports success: no retry is scheduled, and Retry-After is not read.
        assertEquals(ListenableWorker.Result.success(), result)
        // Every pending row is now "downloaded" with an empty path: it will never be tried again.
        assertEquals(listOf(true, true, true, true), rows().map { it.downloaded })
        assertEquals(listOf(OfflineFixture.PENDING, OfflineFixture.SHARED, OfflineFixture.SHARED), rows().filter { it.path == "" && it.key != OfflineFixture.BROKEN }.map { it.key })
        assertFalse(File(app.filesDir, "offline_photos").listFiles().orEmpty().any())
        assertEquals("one request per pending row, none retried", 3, cdn.requests.size)
    }

    @Test fun `R-40 a 408 from the CDN is also a permanent failure`() {
        cdn.respondWith("/photos/", 408)

        assertEquals(ListenableWorker.Result.success(), run())

        assertTrue(rows().all { it.downloaded && it.path == "" })
    }

    @Test fun `R-40 offline gives the synthetic 504, a retry, and after the 4th run an empty path for good`() {
        loopback.online = false

        for (attempt in 0..2) {
            assertEquals("attempt $attempt", ListenableWorker.Result.retry(), run(attempt))
            assertEquals(
                "attempt $attempt leaves the pending rows pending",
                listOf(false, false, false, true), rows().map { it.downloaded }
            )
        }
        // The only-if-cached rule answers a synthetic 504 locally: the CDN never saw a request.
        assertEquals(0, cdn.requests.size)

        // Offline for a quarter of an hour of backoff, and the photo is written off.
        assertEquals(ListenableWorker.Result.success(), run(3))
        assertEquals(listOf(true, true, true, true), rows().map { it.downloaded })
        assertEquals(setOf(""), rows().map { it.path }.toSet())
    }

    /** A pure 404 is right to be permanent; pinned so 5-5 keeps it (GONE). */
    @Test fun `R-40 a 404 is permanent and writes the same empty path as a 429`() {
        cdn.gone += OfflineFixture.PENDING

        run()

        assertEquals(listOf(Row(OfflineFixture.PENDING, 2, true, "")), rowsOf(OfflineFixture.PENDING))
        assertTrue(rowsOf(OfflineFixture.SHARED).all { it.downloaded && File(it.path!!).name == "${OfflineFixture.SHARED}.jpg" })
    }

    // --- R-38: every writer shares offline_photos/{imageKey}.jpg ---

    @Test fun `R-38 two collections that hold one photo are downloaded twice into the one path`() {
        run()

        val shared = rowsOf(OfflineFixture.SHARED)
        assertEquals(2, shared.size)
        assertEquals("both rows point at the same file", 1, shared.map { it.path }.toSet().size)
        assertEquals("the one file was fetched once per row", 2, cdn.countFor("i-${OfflineFixture.SHARED}"))
        val file = File(app.filesDir, "offline_photos/${OfflineFixture.SHARED}.jpg")
        assertEquals(FakeOriginals.size(OfflineFixture.SHARED), file.length())
        // And the file carries no sign of which row owns it: deleting either row's file deletes the other's.
    }

    // --- R-39: every add enqueues another unconstrained, non-unique worker ---

    private fun controller(workManager: WorkManager): CollectionsController =
        CollectionsController(
            app, repository, workManager,
            app.getSharedPreferences("old_worker_characterization", Context.MODE_PRIVATE), "test-key",
            CoroutineScope(SupervisorJob() + Dispatchers.Default),
            getActiveNickname = { OfflineFixture.SITE }, getCurrentAlbumKey = { OfflineFixture.GALLERY_ALBUM_KEY },
            getUnlockedPassword = { null }, onMessage = { }
        )

    @Test fun `R-39 adding one photo twice enqueues two workers, with no unique name and no constraints`() {
        val key = "FfHCmsi007"
        runBlocking { db.collectionDao().createCollection(OfflineCollection(id = 9, name = "Extra", siteNickname = OfflineFixture.SITE)) }
        val workManager = Mockito.mock(WorkManager::class.java)
        val photo = AlbumImageData(
            imageKey = key, thumbnailUrl = "https://photos.smugmug.com/photos/i-$key/0/Th/i-$key-Th.jpg",
            archivedUri = FakeOriginals.archivedUri(key), date = Instant.now().toString()
        )
        val controller = controller(workManager)

        controller.addPhotoToCollection(photo, 9)
        controller.addPhotoToCollection(photo, 9)
        val enqueues = awaitInvocations(workManager, "enqueue", 2)

        assertEquals(2, enqueues.size)
        assertTrue("no enqueueUniqueWork", Mockito.mockingDetails(workManager).invocations.none { it.method.name == "enqueueUniqueWork" })
        enqueues.forEach {
            val request = it.arguments[0] as androidx.work.WorkRequest
            assertEquals(androidx.work.Constraints.NONE, request.workSpec.constraints)
            assertEquals(OfflineDownloadWorker::class.java.name, request.workSpec.workerClassName)
        }
        assertEquals("one row: REPLACE", 1, rows().count { it.key == key })
    }

    @Test fun `R-39 two workers run at once and both fetch the same key into the same file`() {
        cdn.hold("i-${OfflineFixture.PENDING}")
        val results = java.util.concurrent.CopyOnWriteArrayList<ListenableWorker.Result>()
        val first = thread { results += runBlocking { worker().doWork() } }
        assertTrue(cdn.awaitEntered("i-${OfflineFixture.PENDING}"))
        val second = thread { results += runBlocking { worker().doWork() } }
        val deadline = System.currentTimeMillis() + 10_000
        while (cdn.countFor("i-${OfflineFixture.PENDING}") < 2 && System.currentTimeMillis() < deadline) Thread.sleep(20)

        assertEquals("both workers are on the wire for one photo", 2, cdn.countFor("i-${OfflineFixture.PENDING}"))

        cdn.release("i-${OfflineFixture.PENDING}")
        first.join(20_000); second.join(20_000)
        assertEquals(2, results.size)
        assertTrue(File(app.filesDir, "offline_photos/${OfflineFixture.PENDING}.jpg").isFile)
    }

    private fun awaitInvocations(mock: Any, method: String, n: Int): List<org.mockito.invocation.Invocation> {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            val found = Mockito.mockingDetails(mock).invocations.filter { it.method.name == method }
            if (found.size >= n) return found
            Thread.sleep(20)
        }
        return Mockito.mockingDetails(mock).invocations.filter { it.method.name == method }
    }
}
