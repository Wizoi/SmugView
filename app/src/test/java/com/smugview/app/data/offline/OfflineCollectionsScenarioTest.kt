package com.smugview.app.data.offline

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.smugview.app.data.api.RetryingCallFactory
import com.smugview.app.data.api.forFileDownloads
import com.smugview.app.data.db.AppDatabase
import com.smugview.app.data.db.CollectionBookmark
import com.smugview.app.data.db.CollectionPhoto
import com.smugview.app.data.db.OfflineFile
import com.smugview.app.data.db.TestDb
import com.smugview.app.data.repository.FakeCdn
import com.smugview.app.data.repository.FakeOriginals
import com.smugview.app.data.repository.FakeSmugMugServer
import com.smugview.app.data.repository.LoopbackSmugMug
import com.smugview.app.data.repository.SmugMugRepository
import com.smugview.app.data.security.FakePasswordStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
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
import java.io.File
import java.nio.file.Files
import java.time.Duration
import java.time.Instant

/**
 * Phase 5 step 5-6 (design 2.6): the collection and bookmark WRITERS ([OfflineCollections]) through [OfflineStore],
 * with a real CDN, Room and WorkManager's test implementation. This file replaces `OldWorkerCharacterizationTest`
 * (5-0), whose cases pinned the old worker's wrong behaviour; each flipped here (see findings, 5-6):
 *  - R-38 two downloads of one shared path: a shared file is fetched once and kept while anyone holds it
 *  - R-39 an unconstrained, non-unique enqueue per add: one unique chain, and concurrent adds give one request
 *  - R-40 a 429/408/offline written down as a finished download: covered by OfflineDownloaderTest and
 *    OfflineWorkerTest (5-4, 5-5); the old worker is deleted, so nothing is left to characterize.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class OfflineCollectionsScenarioTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val fake = FakeSmugMugServer()
    private val cacheDir: File = Files.createTempDirectory("smugview-oc-cache").toFile()
    private val base: File = Files.createTempDirectory("smugview-oc").toFile()
    private val filesDir = File(base, "files").also { it.mkdirs() }
    private lateinit var loopback: LoopbackSmugMug
    private lateinit var cdn: FakeCdn
    private lateinit var db: AppDatabase
    private lateinit var store: OfflineStore
    private lateinit var downloader: OfflineDownloader
    private val settings = InMemoryOfflineSettings()
    private lateinit var collections: OfflineCollections
    private var now = 1_800_000_000_000L

    private val shared = OfflineFixture.SHARED
    private val other = OfflineFixture.PENDING
    private val favorites = 1L
    private val trip = 2L

    @Before fun setUp() {
        loopback = LoopbackSmugMug(fake, cacheDir)
        cdn = FakeCdn()
        db = TestDb.inMemory()
        db.openHelper.writableDatabase.apply {
            execSQL("INSERT INTO offline_collections (id, name, siteNickname, createdAt) VALUES (1, 'Favorites', '${OfflineFixture.SITE}', $now)")
            execSQL("INSERT INTO offline_collections (id, name, siteNickname, createdAt) VALUES (2, 'Trip', '${OfflineFixture.SITE}', $now)")
        }
        WorkManagerTestInitHelper.initializeTestWorkManager(
            app, Configuration.Builder().setExecutor(SynchronousExecutor()).build()
        )
        val repository = SmugMugRepository(loopback.api(), db.collectionDao(), FakePasswordStore(), app)
        store = OfflineStore(db, filesDir, runId = "run0001", clock = { now }, freeBytes = { 50L shl 30 })
        val images = RetryingCallFactory(cdn.clientOver(loopback.client).forFileDownloads(), maxAttempts = 0)
        downloader = OfflineDownloader(store, repository, images, apiKey = { "test-key" }, settings = settings, clock = { now })
        val scheduler = OfflineScheduler(workManager = { WorkManager.getInstance(app) }, store = store, settings = settings, clock = { now })
        collections = OfflineCollections(db, store, scheduler, settings)
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
    private fun count(table: String, where: String = "1=1") =
        sql.query("SELECT COUNT(*) FROM $table WHERE $where").use { it.moveToFirst(); it.getInt(0) }

    private fun photo(key: String, collection: Long) = CollectionPhoto(
        imageKey = key, collectionId = collection, albumKey = OfflineFixture.GALLERY_ALBUM_KEY, title = "IMG_$key",
        thumbnailUrl = "https://photos.smugmug.com/photos/i-$key/0/Th/i-$key-Th.jpg",
        archivedUri = FakeOriginals.archivedUri(key), localFilePath = null,
        dateTaken = Instant.now().minus(Duration.ofDays(10)).toString(), keywords = null
    )

    private fun imageBookmark(key: String, collection: Long) = CollectionBookmark(
        collectionId = collection, type = "Image", itemKey = key, title = "IMG_$key",
        albumKey = OfflineFixture.GALLERY_ALBUM_KEY, albumTitle = "Class photos"
    )

    private fun pass() = runBlocking { downloader.runPass(NetworkClass.ANY) }
    private fun row(key: String): OfflineFile? = runBlocking { store.file(OfflineStore.fileKeyOf(key)) }
    private fun fileOnDisk(key: String): File? = row(key)?.relPath?.let { File(filesDir, it) }
    private fun filesInOffline() = File(filesDir, "offline").listFiles()?.filter { it.isFile }.orEmpty()

    private fun assertDone(key: String) {
        val r = row(key)
        assertNotNull("row for $key", r)
        assertEquals("$key should be DONE", "DONE", r!!.state)
        assertEquals(FakeOriginals.size(key), File(filesDir, r.relPath!!).length())
    }

    // ---- R-38: one photo in two collections is one file ----

    @Test fun aSharedFileIsFetchedOnceAndKeptWhileAnyCollectionHoldsIt() = runBlocking<Unit> {
        collections.savePhoto(photo(shared, favorites), OfflineFixture.SITE)
        collections.savePhoto(photo(shared, trip), OfflineFixture.SITE)

        pass()

        assertEquals("one file row for two collection rows", 1, count("offline_files"))
        assertEquals("one request", 1, cdn.countFor(cdnPath(shared)))
        assertEquals(1, filesInOffline().size)
        assertDone(shared)
    }

    // ---- N5: the reference count is a query ----

    @Test fun unbookmarkingInTripKeepsTheFileASavedPhotoStillWants() = runBlocking<Unit> {
        collections.savePhoto(photo(shared, favorites), OfflineFixture.SITE)
        collections.addBookmark(imageBookmark(shared, trip), OfflineFixture.SITE, FakeOriginals.archivedUri(shared))
        pass()
        assertDone(shared)

        collections.removeBookmark(trip, "Image", shared)
        pass()

        assertDone(shared)
        assertTrue("still on disk", fileOnDisk(shared)!!.isFile)
        assertEquals("not fetched again", 1, cdn.countFor(cdnPath(shared)))
    }

    @Test fun removingTheLastReferenceDeletesTheFileAtOnceAndOffline() = runBlocking<Unit> {
        collections.savePhoto(photo(shared, favorites), OfflineFixture.SITE)
        pass()
        val file = fileOnDisk(shared)!!
        assertTrue(file.isFile)
        loopback.online = false
        val requestsBefore = cdn.requests.size

        collections.removePhoto(shared, favorites)

        assertFalse("the file is gone without waiting for a pass", file.exists())
        assertEquals(0, count("offline_files"))
        assertEquals("no network for a removal (R-41)", requestsBefore, cdn.requests.size)
    }

    // ---- N4: saving again never resets a finished file ----

    @Test fun savingADonePhotoAgainMakesNoNewRequest() = runBlocking<Unit> {
        collections.savePhoto(photo(shared, favorites), OfflineFixture.SITE)
        pass()
        assertDone(shared)

        collections.savePhoto(photo(shared, favorites), OfflineFixture.SITE)
        collections.savePhoto(photo(shared, trip), OfflineFixture.SITE)
        pass()

        assertDone(shared)
        assertEquals(1, cdn.countFor(cdnPath(shared)))
    }

    @Test fun reAddingAKeyWhileItIsBeingDownloadedKeepsTheClaim() = runBlocking<Unit> {
        collections.savePhoto(photo(shared, favorites), OfflineFixture.SITE)
        cdn.hold(cdnPath(shared))
        val running = async(Dispatchers.IO) { downloader.runPass(NetworkClass.ANY) }
        assertTrue(cdn.awaitEntered(cdnPath(shared)))
        assertEquals("DOWNLOADING", row(shared)!!.state)

        collections.savePhoto(photo(shared, trip), OfflineFixture.SITE)

        assertEquals("the second add did not reset the row", "DOWNLOADING", row(shared)!!.state)
        cdn.release(cdnPath(shared))
        running.await()
        assertDone(shared)
        assertEquals(1, cdn.countFor(cdnPath(shared)))
    }

    // ---- R-41: deleting a collection ----

    @Test fun deletingACollectionRemovesItsUnsharedFilesAndKeepsTheSharedOnes() = runBlocking<Unit> {
        collections.savePhoto(photo(shared, favorites), OfflineFixture.SITE)
        collections.savePhoto(photo(shared, trip), OfflineFixture.SITE)
        collections.savePhoto(photo(other, trip), OfflineFixture.SITE)
        pass()
        assertDone(shared)
        assertDone(other)
        loopback.online = false

        collections.deleteCollection(trip)

        assertEquals("the collection's rows are gone", 0, count("collection_photos", "collectionId = 2"))
        assertDone(shared)
        assertNull("Trip's unshared file is deleted", row(other))
        assertEquals(1, filesInOffline().size)
    }

    // ---- R-39: concurrent adds ----

    @Test fun concurrentAddsOfOneKeyGiveOneRowAndOneRequest() = runBlocking<Unit> {
        withContext(Dispatchers.IO) {
            (1L..2L).flatMap { c -> List(4) { async { collections.savePhoto(photo(shared, c), OfflineFixture.SITE) } } }.awaitAll()
        }

        pass()

        assertEquals(1, count("offline_files"))
        assertEquals(2, count("collection_photos"))
        assertEquals(1, cdn.countFor(cdnPath(shared)))
        assertDone(shared)
    }

    @Test fun anAddKicksUniqueWorkOnceChained() = runBlocking<Unit> {
        collections.savePhoto(photo(shared, favorites), OfflineFixture.SITE)
        collections.savePhoto(photo(other, favorites), OfflineFixture.SITE)

        val all = WorkManager.getInstance(app).getWorkInfosForUniqueWork(OfflineScheduler.NAME).get()

        assertEquals("both adds are in the one unique chain", 2, all.size)
        assertTrue(all.none { it.state.isFinished })
        assertEquals(setOf(WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED), all.map { it.state }.toSet())
    }

    @Test fun aGalleryBookmarkIsAShortcutAndDownloadsNothing() = runBlocking<Unit> {
        collections.addBookmark(
            CollectionBookmark(collectionId = trip, type = "Album", itemKey = OfflineFixture.GALLERY_ALBUM_KEY, title = "Class photos"),
            OfflineFixture.SITE, null
        )

        assertEquals(0, count("offline_files"))
        assertTrue(WorkManager.getInstance(app).getWorkInfosForUniqueWork(OfflineScheduler.NAME).get().isEmpty())
    }
}
