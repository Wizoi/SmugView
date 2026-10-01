package com.smugview.app.data.offline

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.room.testing.MigrationTestHelper
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.smugview.app.data.api.RetryingCallFactory
import com.smugview.app.data.api.forFileDownloads
import com.smugview.app.data.db.AppDatabase
import com.smugview.app.data.repository.FakeCdn
import com.smugview.app.data.repository.FakeOriginals
import com.smugview.app.data.repository.FakeSmugMugServer
import com.smugview.app.data.repository.InMemorySyncStateStore
import com.smugview.app.data.repository.LoopbackSmugMug
import com.smugview.app.data.repository.SmugMugRepository
import com.smugview.app.data.security.FakePasswordStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

/**
 * Phase 5 step 5-8 (design 6.2, Q6 a): an install that upgraded from the pre-17 offline code. The database is a real
 * version 16 file with the [OfflineFixture] rows, migrated to 17 by Room (the 5-2 backfill: every photo PENDING with a
 * `legacyPath`, every Album bookmark a `LEGACY` gallery); the files are real, in a temp `filesDir/offline_photos/`.
 * The CDN and API are the real fakes over loopback sockets.
 *
 * Shapes follow the owner's data: `XVRvVTM` in two collections (one file, R-38), `n83tQ3s` a broken row with no file
 * (R-40), gallery `FfHCms` (NodeID `LCdk7F`) with 150 photos. Test passwords, where any, are placeholders.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LegacyRepairTest {
    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java)

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val fake = FakeSmugMugServer()
    private val cacheDir: File = Files.createTempDirectory("smugview-lr-cache").toFile()
    private val base: File = Files.createTempDirectory("smugview-lr").toFile()
    private val filesDir = File(base, "files").also { it.mkdirs() }
    private val legacyDir = File(filesDir, "offline_photos")
    private lateinit var loopback: LoopbackSmugMug
    private lateinit var cdn: FakeCdn
    private lateinit var db: AppDatabase
    private lateinit var store: OfflineStore
    private lateinit var downloader: OfflineDownloader
    private lateinit var scheduler: OfflineScheduler
    private val syncState = InMemorySyncStateStore()
    private val prefs = app.getSharedPreferences("smugview_prefs", Context.MODE_PRIVATE)
    private var now = 1_800_000_000_000L

    private val shared = OfflineFixture.SHARED      // XVRvVTM: in Favorites and Trip, a valid legacy file
    private val broken = OfflineFixture.BROKEN      // n83tQ3s: isDownloaded=1, path "", no file
    private val pending = OfflineFixture.PENDING    // Hk42gZp: a truncated legacy file (the old code was killed in it)
    private val zero = "Zq7Lm2p"                    // a zero-byte legacy file
    private val thumb = "FfHCmsi003"                // saved with no ArchivedUri: the old code downloaded its thumbnail
    private val album = OfflineFixture.GALLERY_ALBUM_KEY

    @Before fun setUp() {
        loopback = LoopbackSmugMug(fake, cacheDir)
        cdn = FakeCdn()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            app, Configuration.Builder().setExecutor(SynchronousExecutor()).build()
        )
    }

    @After fun tearDown() {
        cdn.close()
        loopback.close()
        if (::db.isInitialized) db.close()
        cacheDir.deleteRecursively()
        base.deleteRecursively()
        prefs.edit().clear().commit()
    }

    /** A v16 file with the fixture plus the extra shapes above, migrated to v17 by Room. */
    private fun upgrade(withGalleryFlag: Boolean) {
        helper.createDatabase("legacy-repair", 16).apply {
            OfflineFixture.insert(this, java.time.Instant.ofEpochMilli(now))
            fun extra(key: String, archived: String?) = execSQL(
                "INSERT INTO collection_photos (imageKey, collectionId, albumKey, title, thumbnailUrl, archivedUri, localFilePath, " +
                    "dateTaken, keywords, isDownloaded) VALUES ('$key', 1, '$album', 'IMG_$key', " +
                    "'https://photos.smugmug.com/photos/i-$key/0/Th/i-$key-Th.jpg', ${archived?.let { "'$it'" } ?: "NULL"}, " +
                    "'/data/user/0/com.smugview.app/files/offline_photos/$key.jpg', '2026-09-20T10:00:00+00:00', NULL, 1)"
            )
            extra(zero, FakeOriginals.archivedUri(zero))
            extra(thumb, null)
            // A row whose key could never be a file name (the migration copies keys unchecked).
            extra("a/b", FakeOriginals.archivedUri("a-b"))
            // A second gallery bookmark with no finished flag.
            execSQL(
                "INSERT INTO collection_bookmarks (collectionId, type, itemKey, title, albumKey, albumTitle, thumbnailUrl) " +
                    "VALUES (1, 'Album', 'N74KSK', 'Public gallery', 'N74KSK', 'Public gallery', NULL)"
            )
            close()
        }
        db = Room.databaseBuilder(app, AppDatabase::class.java, "legacy-repair")
            .addMigrations(
                AppDatabase.MIGRATION_12_13, AppDatabase.MIGRATION_13_14, AppDatabase.MIGRATION_14_15,
                AppDatabase.MIGRATION_15_16, AppDatabase.MIGRATION_16_17
            )
            .allowMainThreadQueries()
            .build()
        prefs.edit().apply {
            if (withGalleryFlag) putBoolean("offline_album_$album", true)
            putBoolean("smugview_unrelated", true)
        }.commit()
        wireProcess("run0001")
    }

    private fun wireProcess(runId: String) {
        val repository = SmugMugRepository(loopback.api(), db.collectionDao(), FakePasswordStore(), app)
        store = OfflineStore(db, filesDir, runId = runId, clock = { now }, freeBytes = { 50L shl 30 })
        val images = RetryingCallFactory(cdn.clientOver(loopback.client).forFileDownloads(), maxAttempts = 0)
        downloader = OfflineDownloader(store, repository, images, apiKey = { "test-key" }, clock = { now })
        scheduler = OfflineScheduler(workManager = { WorkManager.getInstance(app) }, store = store, clock = { now })
    }

    // ---- the old files ---------------------------------------------------------------------------------------

    private fun wholeJpeg(key: String): ByteArray = FakeOriginals.bytes(key) + byteArrayOf(0xFF.toByte(), 0xD9.toByte())

    private fun legacyFile(key: String, bytes: ByteArray) {
        legacyDir.mkdirs()
        File(legacyDir, "$key.jpg").writeBytes(bytes)
    }

    /** What the old code left on a phone that saved these photos and then never cleaned up. */
    private fun writeLegacyFiles() {
        legacyFile(shared, wholeJpeg(shared))
        legacyFile(pending, wholeJpeg(pending).copyOf(5_000))   // cut off: no end marker
        legacyFile(zero, ByteArray(0))
        legacyFile(thumb, wholeJpeg(thumb))                      // a thumbnail looks whole, but nothing says it is the original
        legacyFile("Zzzz9999", wholeJpeg("Zzzz9999"))            // a photo nothing refers to any more
    }

    private val sql get() = db.openHelper.writableDatabase
    private fun cdnPath(key: String) = "/photos/i-$key/"
    private fun row(key: String) = runBlocking { store.file(OfflineStore.fileKeyOf(key)) }
    private fun repair(finished: Set<String> = emptySet()) = runBlocking { store.repairLegacy(finished) }
    private fun gallery(c: Long, key: String) = runBlocking { store.gallery(c, key) }
    private fun count(table: String, where: String = "1=1") =
        sql.query("SELECT COUNT(*) FROM $table WHERE $where").use { it.moveToFirst(); it.getInt(0) }
    private fun offlineFiles() = File(filesDir, "offline").listFiles()?.filter { it.isFile }?.map { it.name }?.sorted().orEmpty()

    // ---- the repair --------------------------------------------------------------------------------------------

    @Test fun theBackfillIsWhatTheRepairStartsFrom() {
        upgrade(withGalleryFlag = true)

        assertEquals("every photo starts PENDING with a legacy path", 6, count("offline_files", "state = 'PENDING' AND legacyPath IS NOT NULL"))
        assertEquals(2, count("offline_galleries", "state = 'LEGACY'"))
        assertEquals("the migration wrote no file", null, filesDir.listFiles()?.firstOrNull { it.name == "offline" })
    }

    @Test fun aWholeLegacyFileIsAdoptedWithNoRequest_theOthersAreDownloaded_andTheOldDirectoryGoes() {
        upgrade(withGalleryFlag = false)
        writeLegacyFiles()
        val before = File(legacyDir, "$shared.jpg").readBytes()

        val result = repair()

        val adopted = row(shared)!!
        assertEquals("DONE", adopted.state)
        assertEquals("offline/$shared.orig.jpg", adopted.relPath)
        assertNull(adopted.legacyPath)
        assertEquals(before.size.toLong(), adopted.bytes)
        assertTrue("the file moved, byte for byte", File(filesDir, adopted.relPath!!).readBytes().contentEquals(before))
        assertEquals(1, result.adopted)
        assertEquals("a truncated file, a zero-byte file, a thumbnail and a missing file are not adopted", 4, result.redownload)
        for (k in listOf(broken, pending, zero, thumb)) {
            assertEquals("$k waits to be downloaded again", "PENDING", row(k)!!.state)
            assertNull(row(k)!!.legacyPath)
            assertNull(row(k)!!.relPath)
        }
        assertFalse("nothing under offline/ for the unadopted", offlineFiles().any { it.startsWith(pending) || it.startsWith(zero) })
        assertEquals(0, cdn.requests.size)
        assertEquals("the two gallery rows were settled: none is waiting", 0, count("offline_galleries", "state = 'LEGACY'"))
        assertFalse("the old directory, and the file nothing refers to, are deleted", legacyDir.exists())
        assertEquals("the four files left (shared one was moved)", 4, result.filesDeleted)

        // The next pass fetches exactly what the repair could not keep.
        runBlocking { downloader.runPass(NetworkClass.ANY) }
        for (k in listOf(broken, pending, zero, thumb)) assertEquals("$k downloaded once", 1, cdn.countFor(cdnPath(k)))
        assertEquals("the adopted photo is never fetched", 0, cdn.countFor(cdnPath(shared)))
        assertEquals(5, count("offline_files", "state = 'DONE'"))
        assertEquals(
            "the downloaded copy of a truncated legacy file is the whole original",
            FakeOriginals.size(pending), File(filesDir, row(pending)!!.relPath!!).length()
        )
    }

    @Test fun aKeyThatCouldNotBeAFileNameIsNeverJoinedIntoAPath() {
        upgrade(withGalleryFlag = false)
        // A decoy where a path built from "a/b" would land.
        File(legacyDir, "a").mkdirs()
        File(legacyDir, "a/b.jpg").writeBytes(wholeJpeg("a-b"))

        repair()

        val r = row("a/b")!!
        assertEquals("FAILED", r.state)
        assertEquals("NO_SOURCE", r.failure)
        assertFalse(r.retryable)
        assertNull(r.legacyPath)
        assertEquals("nothing was adopted from a decoy", emptyList<String>(), offlineFiles())
        assertEquals(0, cdn.requests.size)
    }

    @Test fun aLegacyGalleryIsKeptWhenItsFlagWasTrue_andDroppedWhenItWasNot_theBookmarkStays() {
        upgrade(withGalleryFlag = true)

        val result = repair(finished = setOf(album))

        assertEquals("LIST_PENDING", gallery(2, album)!!.state)
        assertTrue("Wi-Fi only by default (Q3)", gallery(2, album)!!.wifiOnly)
        assertNull("a gallery with no flag is not kept offline", gallery(1, "N74KSK"))
        assertEquals("but its bookmark is still there as a shortcut", 1, count("collection_bookmarks", "itemKey = 'N74KSK' AND type = 'Album'"))
        assertEquals(1, result.galleriesKept)
        assertEquals(1, result.galleriesDropped)
    }

    @Test fun aKeptLegacyGalleryAdoptsItsOldFilesByKeyWhenListed_thenTheOldDirectoryGoes() {
        upgrade(withGalleryFlag = true)
        writeLegacyFiles()
        val whole = "FfHCmsi010"
        val short = "FfHCmsi011"
        legacyFile(whole, FakeOriginals.bytes(whole))                   // exactly the listed size
        legacyFile(short, FakeOriginals.bytes(short).copyOf(10_000))    // not the listed size

        repair(finished = setOf(album))

        assertTrue("a gallery is still to be listed: the old files stay for it", legacyDir.exists())
        assertTrue(File(legacyDir, "$whole.jpg").exists())

        runBlocking { downloader.runPass(NetworkClass.UNMETERED) }

        assertEquals("LISTED", gallery(2, album)!!.state)
        assertEquals("adopted by key: no request", 0, cdn.countFor(cdnPath(whole)))
        assertEquals("DONE", row(whole)!!.state)
        assertEquals("offline/$whole.orig.jpg", row(whole)!!.relPath)
        assertEquals("a file of the wrong size is downloaded again", 1, cdn.countFor(cdnPath(short)))
        assertEquals(FakeOriginals.size(short), File(filesDir, row(short)!!.relPath!!).length())
        assertEquals(1, cdn.countFor(cdnPath("FfHCmsi020")))
        assertFalse("every row is settled: the old directory is gone", legacyDir.exists())
        assertEquals(0, cdn.countFor(cdnPath(shared)))
    }

    @Test fun aDeadGalleryDoesNotKeepTheOldDirectoryForever() {
        upgrade(withGalleryFlag = true)
        writeLegacyFiles()
        repair(finished = setOf(album))
        assertTrue(legacyDir.exists())

        // The gallery cannot be listed and will not be retried (a permanent lock): nothing is waiting any more.
        sql.execSQL("UPDATE offline_galleries SET state = 'FAILED', failure = 'LOCKED', retryable = 0 WHERE albumKey = '$album'")
        runBlocking { downloader.runPass(NetworkClass.UNMETERED) }

        assertFalse(legacyDir.exists())
    }

    @Test fun repairingTwiceChangesNothingTheSecondTime() {
        upgrade(withGalleryFlag = true)
        writeLegacyFiles()
        repair(finished = setOf(album))
        val files = runBlocking { db.offlineDao().allFiles() }
        val galleries = runBlocking { db.offlineDao().allGalleries() }

        val again = repair(finished = setOf(album))

        assertEquals(OfflineStore.LegacyRepair(0, 0, 0, 0, 0), again)
        assertEquals(files, runBlocking { db.offlineDao().allFiles() })
        assertEquals(galleries, runBlocking { db.offlineDao().allGalleries() })
    }

    // ---- the one-time driver ---------------------------------------------------------------------------------

    private fun repairDriver() = OfflineLegacyRepair(store, syncState, prefs)

    @Test fun theDriverReadsTheOldFlagsOnce_removesThem_andRunsOnlyOnce() {
        upgrade(withGalleryFlag = true)
        writeLegacyFiles()

        val first = runBlocking { repairDriver().runOnce() }

        assertNotNull(first)
        assertEquals("LIST_PENDING", gallery(2, album)!!.state)
        assertFalse("the old flag is gone", prefs.contains("offline_album_$album"))
        assertTrue("an unrelated preference is untouched", prefs.getBoolean("smugview_unrelated", false))
        assertTrue(syncState.getBoolean(OfflineLegacyRepair.FLAG))

        legacyFile("Yyyy8888", wholeJpeg("Yyyy8888"))
        assertNull("it ran once", runBlocking { repairDriver().runOnce() })
        assertTrue("a later file is not touched by a run that does not happen", File(legacyDir, "Yyyy8888.jpg").exists())
    }

    @Test fun aProcessKilledBetweenTwoRowsIsFinishedByTheNextStart_withNothingFetchedOrAdoptedTwice() {
        upgrade(withGalleryFlag = true)
        writeLegacyFiles()
        var steps = 0
        store.afterLegacyStep = { if (++steps == 2) throw IllegalStateException("process killed") }

        try {
            runBlocking { repairDriver().runOnce() }
            fail("the first run was killed")
        } catch (e: IllegalStateException) {
            assertEquals("process killed", e.message)
        }
        assertFalse("not marked repaired", syncState.getBoolean(OfflineLegacyRepair.FLAG))
        assertTrue("the old flag is still there for the next start", prefs.getBoolean("offline_album_$album", false))
        assertEquals("two rows were settled, the rest are not", 4, count("offline_files", "legacyPath IS NOT NULL"))
        assertEquals("the galleries have not been looked at yet", 2, count("offline_galleries", "state = 'LEGACY'"))

        wireProcess("run0002") // the process started again
        val second = runBlocking { repairDriver().runOnce() }

        assertNotNull(second)
        assertEquals(0, count("offline_files", "legacyPath IS NOT NULL"))
        assertEquals("DONE", row(shared)!!.state)
        assertEquals(1, count("offline_files", "state = 'DONE'"))
        assertEquals("LIST_PENDING", gallery(2, album)!!.state)
        assertNull(gallery(1, "N74KSK"))
        assertTrue(syncState.getBoolean(OfflineLegacyRepair.FLAG))
        assertFalse(prefs.contains("offline_album_$album"))
        assertEquals("no file was fetched to repair", 0, cdn.requests.size)
    }

    @Test fun anInterruptedRepairNeverMovesAFileWithoutItsRow() {
        upgrade(withGalleryFlag = false)
        writeLegacyFiles()
        store.afterLegacyStep = { throw IllegalStateException("process killed") }

        try { repair(); fail("killed") } catch (e: IllegalStateException) { }

        for (name in offlineFiles()) {
            val key = name.substringBefore(".orig.")
            assertEquals("a file under offline/ always has its DONE row ($name)", "DONE", row(key)?.state)
            assertEquals("offline/$name", row(key)!!.relPath)
        }
    }

    // ---- the start-up order (the 5-5 hazard) -------------------------------------------------------------------

    @Test fun atStartTheRepairRunsBeforeTheSchedulerIsWoken_soAdoptedPhotosAreNeverQueued() {
        upgrade(withGalleryFlag = false)
        // Every legacy photo has a whole file: after the repair nothing is wanted.
        for (k in listOf(shared, pending, zero, thumb, broken)) legacyFile(k, wholeJpeg(k))
        sql.execSQL("UPDATE offline_files SET sourceUrl = '${FakeOriginals.archivedUri(thumb)}' WHERE imageKey = '$thumb'")
        sql.execSQL("DELETE FROM offline_files WHERE imageKey = 'a/b'")
        val wm = WorkManager.getInstance(app)

        runBlocking { OfflineStartup(repairDriver(), scheduler).run() }

        assertEquals("every photo adopted", 5, count("offline_files", "state = 'DONE'"))
        assertTrue(
            "no pass was queued: the repair had already settled the rows when the scheduler looked",
            wm.getWorkInfosForUniqueWork(OfflineScheduler.NAME).get().isEmpty()
        )
        assertEquals(0, cdn.requests.size)
    }

    @Test fun aRepairThatFailsDoesNotWakeTheScheduler_andDoesNotCrashStartUp() {
        upgrade(withGalleryFlag = false)
        writeLegacyFiles()
        store.afterLegacyStep = { throw IllegalStateException("disk error") }
        val wm = WorkManager.getInstance(app)

        runBlocking { OfflineStartup(repairDriver(), scheduler).run() } // must not throw

        assertTrue(wm.getWorkInfosForUniqueWork(OfflineScheduler.NAME).get().isEmpty())
        assertFalse(syncState.getBoolean(OfflineLegacyRepair.FLAG))
    }

    @Test fun afterARepairedStart_leftoverWorkIsQueuedAsBefore() {
        upgrade(withGalleryFlag = false)
        writeLegacyFiles()

        runBlocking { OfflineStartup(repairDriver(), scheduler).run() }

        assertEquals(
            "four photos still need downloading: the scheduler is woken for them",
            1, WorkManager.getInstance(app).getWorkInfosForUniqueWork(OfflineScheduler.NAME).get().size
        )
    }
}
