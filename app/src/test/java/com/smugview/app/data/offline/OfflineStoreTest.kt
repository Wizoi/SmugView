package com.smugview.app.data.offline

import com.smugview.app.data.db.AppDatabase
import com.smugview.app.data.db.TestDb
import com.smugview.app.data.repository.FakeOriginals
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
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

/**
 * Phase 5 step 5-3: `OfflineStore`, the one owner of the files under `filesDir/offline/`, over a real in-memory
 * Room database (the [OfflineFixture] rows: `XVRvVTM` in two collections and an Image bookmark) and a real temp
 * directory. Each ordering rule of the class comment has a test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class OfflineStoreTest {
    private lateinit var db: AppDatabase
    private lateinit var base: File
    private lateinit var filesDir: File
    private var now = 1_800_000_000_000L
    private var free = 50L shl 30

    @Before fun setUp() {
        db = TestDb.inMemory()
        base = Files.createTempDirectory("smugview-store").toFile()
        filesDir = File(base, "files").also { it.mkdirs() }
        OfflineFixture.insert(db.openHelper.writableDatabase)
    }

    @After fun tearDown() {
        db.close()
        base.deleteRecursively()
    }

    private fun store(runId: String = "run0001") =
        OfflineStore(db, filesDir, runId = runId, clock = { now }, freeBytes = { free })

    private val sql get() = db.openHelper.writableDatabase
    private val dao get() = db.offlineDao()

    private fun count(table: String, where: String = "1=1"): Int =
        sql.query("SELECT COUNT(*) FROM $table WHERE $where").use { it.moveToFirst(); it.getInt(0) }

    /** Request, claim, write the real bytes into the `.part` and commit: a finished file for [key]. */
    private fun download(s: OfflineStore, key: String): OfflineStore.CommitResult = runBlocking {
        val bytes = FakeOriginals.bytes(key)
        s.request(key, albumKey = OfflineFixture.GALLERY_ALBUM_KEY, expectedBytes = bytes.size.toLong(), md5 = FakeOriginals.md5(key))
        val w = got(s.beginWrite(OfflineStore.fileKeyOf(key)))
        w.part.writeBytes(bytes)
        s.commit(w, "jpg", bytes.size.toLong())
    }

    private fun got(w: OfflineStore.Write?): OfflineStore.Write {
        assertNotNull("beginWrite returned null", w)
        return w!!
    }

    private fun finalFile(key: String) = File(filesDir, "offline/$key.orig.jpg")
    private fun partsLeft(): List<String> = File(filesDir, "offline/.tmp").listFiles()?.map { it.name } ?: emptyList()

    // ---- the reference count ---------------------------------------------------------------------------------

    @Test fun sharedKeyInTwoCollections_stayedUntilBothReferencesAreGone() = runBlocking<Unit> {
        val s = store()
        val key = OfflineFixture.SHARED
        assertTrue(download(s, key) is OfflineStore.CommitResult.Done)
        assertTrue(finalFile(key).exists())

        // Referenced by two collection_photos rows and an Image bookmark. Remove one of them: still wanted.
        sql.execSQL("DELETE FROM collection_photos WHERE imageKey = '$key' AND collectionId = 1")
        assertEquals(0, s.collectGarbage())
        assertTrue("file must stay while another collection still has it", finalFile(key).exists())
        assertEquals("DONE", dao.getFile(OfflineStore.fileKeyOf(key))!!.state)

        sql.execSQL("DELETE FROM collection_photos WHERE imageKey = '$key' AND collectionId = 2")
        assertEquals("the Image bookmark is a reference too", 0, s.collectGarbage())
        assertTrue(finalFile(key).exists())

        sql.execSQL("DELETE FROM collection_bookmarks WHERE type = 'Image' AND itemKey = '$key'")
        assertEquals(1, s.collectGarbage())
        assertFalse("no reference left: the file is deleted", finalFile(key).exists())
        assertNull("and so is its row", dao.getFile(OfflineStore.fileKeyOf(key)))
    }

    @Test fun galleryItem_isAReference() = runBlocking<Unit> {
        val s = store()
        sql.execSQL("INSERT INTO offline_collections (id, name, siteNickname, createdAt) VALUES (3, 'Kept', 'idzifamily', $now)")
        sql.execSQL(
            "INSERT INTO offline_galleries (collectionId, albumKey, nickname, title, state, retryable, wifiOnly) " +
                "VALUES (3, 'FfHCms', 'idzifamily', 'Class photos', 'LISTED', 0, 1)"
        )
        sql.execSQL("INSERT INTO offline_gallery_items (collectionId, albumKey, imageKey, sortIndex) VALUES (3, 'FfHCms', 'Zz9Yy8Xx', 0)")
        s.request("Zz9Yy8Xx")
        assertTrue(s.isReferenced("Zz9Yy8Xx"))
        assertEquals(0, s.collectGarbage())
        sql.execSQL("DELETE FROM offline_collections WHERE id = 3") // CASCADE removes the gallery and its items
        assertFalse(s.isReferenced("Zz9Yy8Xx"))
        assertEquals(1, s.collectGarbage())
    }

    @Test fun request_onDoneRow_keepsDone() = runBlocking<Unit> {
        val s = store()
        val key = OfflineFixture.SHARED
        download(s, key)
        val again = s.request(key, sourceUrl = "https://example.invalid/other")
        assertEquals("a second save must not reset a finished file (N4)", "DONE", again.state)
        assertEquals("offline/$key.orig.jpg", again.relPath)
        assertEquals(1, count("offline_files", "imageKey = '$key'"))
    }

    // ---- commit ----------------------------------------------------------------------------------------------

    @Test fun commit_afterTheReferenceIsGone_leavesNoFinalFileAndNoRow() = runBlocking<Unit> {
        val s = store()
        val key = OfflineFixture.PENDING
        s.request(key)
        val w = got(s.beginWrite(OfflineStore.fileKeyOf(key)))
        w.part.writeBytes(FakeOriginals.bytes(key))
        sql.execSQL("DELETE FROM collection_photos WHERE imageKey = '$key'") // the user removed it mid-download
        assertEquals(OfflineStore.CommitResult.Unreferenced, s.commit(w, "jpg", 1))
        assertFalse(finalFile(key).exists())
        assertFalse(w.part.exists())
        assertNull(dao.getFile(OfflineStore.fileKeyOf(key)))
    }

    @Test fun commit_marksDoneWithRelPathAndBytes_andLeavesNoPart() = runBlocking<Unit> {
        val s = store()
        val key = OfflineFixture.SHARED
        val r = download(s, key) as OfflineStore.CommitResult.Done
        val row = dao.getFile(OfflineStore.fileKeyOf(key))!!
        assertEquals("DONE", row.state)
        assertEquals(r.relPath, row.relPath)
        assertEquals(FakeOriginals.size(key), row.bytes)
        assertNull(row.claim)
        assertEquals(FakeOriginals.size(key), finalFile(key).length())
        assertEquals(emptyList<String>(), partsLeft())
    }

    @Test fun beginWrite_isExclusive_andOnlyForWantedRows() = runBlocking<Unit> {
        val s = store()
        val key = OfflineFixture.PENDING
        s.request(key)
        val results = (1..4).map { async { s.beginWrite(OfflineStore.fileKeyOf(key)) } }.awaitAll()
        assertEquals("exactly one writer gets the claim", 1, results.count { it != null })

        val unwanted = s.request("Qq1Ww2Ee3") // no collection refers to it
        assertNull(s.beginWrite(unwanted.fileKey))
        assertEquals("PENDING", dao.getFile(unwanted.fileKey)!!.state)

        download(s, OfflineFixture.SHARED)
        assertNull("a DONE row is not claimable", s.beginWrite(OfflineStore.fileKeyOf(OfflineFixture.SHARED)))
    }

    // ---- crashes ---------------------------------------------------------------------------------------------

    @Test fun recover_foreignClaimWithAPart_goesPending_andThePartIsDeleted() = runBlocking<Unit> {
        val dead = store("deadrun1")
        val key = OfflineFixture.PENDING
        dead.request(key)
        val w = got(dead.beginWrite(OfflineStore.fileKeyOf(key)))
        w.part.writeBytes(ByteArray(1234))
        assertTrue(w.part.exists())

        val fresh = store("freshrun")
        fresh.recover()
        val row = dao.getFile(OfflineStore.fileKeyOf(key))!!
        assertEquals("PENDING", row.state)
        assertNull(row.claim)
        assertEquals(emptyList<String>(), partsLeft())
        // and it can be claimed and written again by the new process
        got(fresh.beginWrite(OfflineStore.fileKeyOf(key)))
    }

    @Test fun recover_keepsThisRunsOwnPart_andItsDownloadingRow() = runBlocking<Unit> {
        val s = store("sameruns")
        val key = OfflineFixture.PENDING
        s.request(key)
        val w = got(s.beginWrite(OfflineStore.fileKeyOf(key)))
        w.part.writeBytes(ByteArray(10))
        s.recover()
        assertTrue(w.part.exists())
        assertEquals("DOWNLOADING", dao.getFile(OfflineStore.fileKeyOf(key))!!.state)
    }

    @Test fun recover_renamedButUncommitted_withTheRightSize_isAdoptedDone() = runBlocking<Unit> {
        val key = OfflineFixture.PENDING
        val bytes = FakeOriginals.bytes(key)
        val dead = store("deadrun2")
        dead.request(key, expectedBytes = bytes.size.toLong(), md5 = FakeOriginals.md5(key))
        val w = got(dead.beginWrite(OfflineStore.fileKeyOf(key)))
        w.part.writeBytes(bytes)
        assertTrue(w.part.renameTo(finalFile(key))) // the process died right after the rename

        store("freshrun").recover()
        val row = dao.getFile(OfflineStore.fileKeyOf(key))!!
        assertEquals("DONE", row.state)
        assertEquals("offline/$key.orig.jpg", row.relPath)
        assertEquals(bytes.size.toLong(), row.bytes)
        assertTrue(finalFile(key).exists())
    }

    @Test fun recover_renamedButWrongSizeOrMd5_isNotAdopted() = runBlocking<Unit> {
        val key = OfflineFixture.PENDING
        val bytes = FakeOriginals.bytes(key)
        val dead = store("deadrun3")
        dead.request(key, expectedBytes = bytes.size.toLong(), md5 = FakeOriginals.md5(key))
        val w = got(dead.beginWrite(OfflineStore.fileKeyOf(key)))
        // right size, wrong content: same length, different bytes
        w.part.writeBytes(ByteArray(bytes.size) { 7 })
        w.part.renameTo(finalFile(key))

        val fresh = store("freshrun")
        fresh.recover()
        assertEquals("PENDING", dao.getFile(OfflineStore.fileKeyOf(key))!!.state)
        assertEquals("the unverified file is an orphan and is swept", 1, fresh.sweepOrphans())
        assertFalse(finalFile(key).exists())
    }

    @Test fun sweepOrphans_deletesAFileWithNoDoneRow_andKeepsDoneFiles() = runBlocking<Unit> {
        val s = store()
        download(s, OfflineFixture.SHARED)
        val orphan = finalFile("Orph4nKey")
        orphan.writeBytes(ByteArray(5)) // a crash between the row delete and the file delete
        val tmpDir = File(filesDir, "offline/.tmp").also { it.mkdirs() }
        File(tmpDir, "keep.part").writeBytes(ByteArray(1)) // .tmp is not the sweep's business
        assertEquals(1, s.sweepOrphans())
        assertFalse(orphan.exists())
        assertTrue(finalFile(OfflineFixture.SHARED).exists())
        assertTrue(File(tmpDir, "keep.part").exists())
    }

    @Test fun collectGarbage_neverTouchesARowThatIsBeingWritten() = runBlocking<Unit> {
        val s = store()
        val key = OfflineFixture.PENDING
        s.request(key)
        val w = got(s.beginWrite(OfflineStore.fileKeyOf(key)))
        sql.execSQL("DELETE FROM collection_photos WHERE imageKey = '$key'")
        assertEquals(0, s.collectGarbage())
        assertNotNull(dao.getFile(OfflineStore.fileKeyOf(key)))
        s.abandon(w)
    }

    // ---- the key check ---------------------------------------------------------------------------------------

    @Test fun badImageKey_isNoSource_andNoFileIsCreatedOutsideOffline() = runBlocking<Unit> {
        val s = store()
        for (bad in listOf("../x", "..", "a/b/cdef", "abc", "x".repeat(17), "ab cd12", "")) {
            val row = s.request(bad)
            assertEquals("$bad", "FAILED", row.state)
            assertEquals("$bad", "NO_SOURCE", row.failure)
            assertFalse("$bad", row.retryable)
            assertNull("$bad: no claim", s.beginWrite(row.fileKey))
        }
        s.collectGarbage(); s.sweepOrphans(); s.recover()
        val all = base.walkTopDown().filter { it.isFile }.map { it.relativeTo(base).path.replace('\\', '/') }.toList()
        assertTrue("no file anywhere: $all", all.isEmpty())
        assertEquals(listOf("files"), base.listFiles()!!.map { it.name })
        assertTrue(OfflineStore.isValidKey("XVRvVTM"))
        assertFalse(OfflineStore.isValidKey("../x"))
    }

    // ---- failures and storage --------------------------------------------------------------------------------

    @Test fun fail_countsAnAttemptOnlyWhenItWasTheFilesFault() = runBlocking<Unit> {
        val s = store()
        val key = OfflineFixture.PENDING
        val fileKey = OfflineStore.fileKeyOf(key)
        s.request(key)
        s.fail(fileKey, DownloadFailure.classify(null, null, java.io.IOException("no route"), false, nowMs = now)!!)
        var row = dao.getFile(fileKey)!!
        assertEquals("OFFLINE", row.failure); assertEquals(0, row.attempts); assertTrue(row.retryable)
        assertEquals(now, row.nextAttemptAt)

        val busy = DownloadFailure.classify(429, okhttp3.Headers.headersOf("Retry-After", "120"), null, false, nowMs = now)!!
        s.fail(fileKey, busy)
        row = dao.getFile(fileKey)!!
        assertEquals("BUSY", row.failure); assertEquals(1, row.attempts)
        assertEquals(now + 120_000, row.nextAttemptAt)
        assertEquals(429, row.httpCode)

        // retryable and due: the picker offers it; not yet due: it does not
        assertEquals(emptyList<String>(), dao.candidates(now, true, 10).filter { it.imageKey == key }.map { it.imageKey })
        now += 120_000
        assertEquals(listOf(key), dao.candidates(now, true, 10).filter { it.imageKey == key }.map { it.imageKey })
    }

    @Test fun hasRoomFor_keepsOneGigabyteFree() {
        val s = store()
        val floor = OfflineStore.FREE_FLOOR_BYTES
        free = floor + OfflineStore.UNKNOWN_SIZE_BYTES
        assertTrue("size unknown: assumes 8 MB", s.hasRoomFor(null))
        free -= 1
        assertFalse(s.hasRoomFor(null))
        free = floor + 100_000_000
        assertTrue(s.hasRoomFor(100_000_000))
        assertFalse(s.hasRoomFor(100_000_001))
    }

    @Test fun extensionFor_prefersContentType_thenUrl_thenJpg() {
        assertEquals("jpg", OfflineStore.extensionFor("image/jpeg; charset=x", "https://h/p/i-A-D.png"))
        assertEquals("mp4", OfflineStore.extensionFor("video/mp4", null))
        assertEquals("png", OfflineStore.extensionFor(null, "https://h/p/i-A-D.png?x=1"))
        assertEquals("jpg", OfflineStore.extensionFor("text/html", "https://h/p/noext"))
        assertEquals("jpg", OfflineStore.extensionFor(null, "https://h/p/x.../..%2f"))
    }
}
