package com.smugview.app.diag

import com.smugview.app.data.db.TestDb
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Fake literals only: nothing here is a real key or password. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class DiagnosticsFileWriterTest {
    @get:Rule val tmp = TemporaryFolder()

    private val apiKey = "FAKEAPIKEY0123456789abcdef"
    private val password = "hunter22xyz"

    private lateinit var out: File
    private lateinit var sink: RecordingSink
    private lateinit var redactor: Redactor
    private lateinit var log: DiagLog
    private var db: com.smugview.app.data.db.AppDatabase? = null

    @Before
    fun setUp() {
        out = tmp.newFolder("external", "diagnostics")
        sink = RecordingSink()
        redactor = Redactor()
        redactor.setSecrets(listOf(apiKey, password))
        log = DiagLog(sink, redactor)
    }

    @After
    fun tearDown() {
        db?.close()
    }

    private fun writer(
        reporter: SyncReporter = SyncReporter.NOOP,
        doctor: CacheDoctor? = null,
        maxBytes: Int = DiagnosticsFileWriter.MAX_BYTES,
        finalPass: ((String) -> String)? = null,
        dir: File? = out
    ) = if (finalPass == null) {
        DiagnosticsFileWriter(
            { dir }, log, redactor, reporter, { listOf(apiKey, password) }, doctor, maxBytes = maxBytes
        )
    } else {
        DiagnosticsFileWriter(
            { dir }, log, redactor, reporter, { listOf(apiKey, password) }, doctor,
            maxBytes = maxBytes, finalPass = finalPass
        )
    }

    private fun report() = File(out, "report.txt").readText()

    @Test
    fun emptyLog_stillWritesAReport() {
        val result = writer().write()
        assertTrue(result.toString(), result is ReportResult.Written)
        val text = report()
        assertTrue(text.contains("SmugView diagnostics report"))
        assertTrue(text.contains("not run yet in this process"))
        assertTrue(text.contains("== last sync runs"))
        assertTrue(text.contains("none"))
        assertTrue(text.contains("<empty log>"))
    }

    @Test
    fun plantedSecrets_areRedactedFromTheReport() {
        log.i("t", "calling with APIKey=$apiKey and again $apiKey, saved $password")
        val result = writer().write()
        assertTrue(result.toString(), result is ReportResult.Written)
        val text = report()
        assertFalse(text.contains(apiKey))
        assertFalse(text.contains(password))
        assertTrue(text.contains("APIKey=<r>"))
    }

    @Test
    fun aBypassingSink_isCleanedByTheSecondPass() {
        // A line that skipped DiagLog's redaction entirely (written straight into the sink).
        sink.lines.add("2026-09-30T10:00:00.000 I http GET /api/v2/x?APIKey=$apiKey took 5ms")
        val result = writer().write()
        assertTrue(result.toString(), result is ReportResult.Written)
        assertFalse(report().contains(apiKey))
    }

    @Test
    fun aBypassingSinkAndABrokenSecondPass_tripTheLeakScan() {
        sink.lines.add("2026-09-30T10:00:00.000 I http GET /api/v2/x?APIKey=$apiKey took 5ms")
        val result = writer(finalPass = { it }).write()
        assertEquals(ReportResult.Blocked("a secret literal survived"), result)

        val text = report()
        assertTrue(text.contains("REPORT WITHHELD"))
        assertFalse(text.contains(apiKey))
        // an E line was logged, and it carries no secret
        val e = sink.lines.last { it.contains(" E diag ") }
        assertTrue(e, e.contains("report blocked"))
        assertFalse(e.contains(apiKey))
    }

    @Test
    fun aCredentialPatternWithAnUnknownValue_tripsTheScan() {
        // Not a registered secret, but exactly the shape the redactor must have replaced.
        sink.lines.add("2026-09-30T10:00:00.000 I http GET /x?Password=zzzzNotRegistered1 ok")
        val result = writer(finalPass = { it }).write()
        assertEquals(ReportResult.Blocked("pattern Password="), result)
        assertFalse(report().contains("zzzzNotRegistered1"))
    }

    @Test
    fun aMarkerThatContainsAShortSecret_isNotALeak() {
        val short = "path" // a saved password that happens to be a substring of our own <path> marker
        val w = DiagnosticsFileWriter({ out }, log, redactor, SyncReporter.NOOP, { listOf(short) })
        sink.lines.add("2026-09-30T10:00:00.000 I http GET https://nick.smugmug.com/<path> ok")
        assertTrue(w.write() is ReportResult.Written)
    }

    @Test
    fun hugeLog_isCappedAndKeepsTheNewestLines() {
        val big = StringBuilder()
        for (i in 0 until 30_000) big.append("2026-09-30T10:00:00.000 I http line $i ").append("x".repeat(60)).append('\n')
        assertTrue(big.length > 3_000_000)
        val huge = object : LogSink {
            override fun append(line: String) {}
            override fun flush(timeoutMs: Long) = true
            override fun read(maxBytes: Int) = big.toString() // ignores maxBytes on purpose
            override fun clear() {}
            override val status = SinkStatus(0, 0, null)
        }
        val w = DiagnosticsFileWriter(
            { out }, DiagLog(huge, redactor), redactor, SyncReporter.NOOP, { listOf(apiKey) }
        )
        val result = w.write()
        assertTrue(result.toString(), result is ReportResult.Written)
        val file = File(out, "report.txt")
        assertTrue("size ${file.length()}", file.length() <= 1_500_000)
        assertTrue("size ${file.length()}", file.length() > 1_000_000)
        val text = file.readText()
        assertTrue(text.contains("line 29999 "))
        assertFalse(text.contains("line 0 "))
        assertTrue(text.endsWith("\n"))
    }

    @Test
    fun syncRuns_areIncluded() {
        val reporter = FileSyncReporter(File(tmp.root, "sync_reports.jsonl"), log)
        val run = reporter.begin(SyncKind.GallerySync, "nick", "sync#1")!!
        run.pagesFetched = 2
        run.stop = StopReason.NoNextPage
        reporter.finish(run)
        writer(reporter = reporter).write()
        val text = report()
        assertTrue(text, text.contains("sync#1 GallerySync stop=NoNextPage"))
    }

    @Test
    fun doctorRunsOnceAndItsCountsAppear() = runBlocking {
        val database = TestDb.inMemory()
        db = database
        val w = writer(doctor = CacheDoctor(database.doctorDao()))
        w.onSyncFinished()
        w.onSyncFinished()
        val text = report()
        assertTrue(text, text.contains("rows_cached_nodes = 0"))
        assertTrue(text.contains("self_parent = 0"))
        assertEquals(1, sink.lines.count { it.contains(" doctor ran:") })
    }

    @Test
    fun noExternalDirectory_failsWithoutThrowing() {
        val result = writer(dir = null).write()
        assertEquals(ReportResult.Failed("no external files directory"), result)
        assertNull(File(out, "report.txt").takeIf { it.exists() })
    }

    @Test
    fun rewriting_replacesThePreviousReport() {
        val w = writer()
        log.i("t", "first-marker")
        w.write()
        log.i("t", "second-marker")
        w.write()
        val text = report()
        assertTrue(text.contains("first-marker") && text.contains("second-marker"))
        assertFalse(File(out, "report.txt.tmp").exists())
    }

    /**
     * Phase 5 (5-10): the offline counts get their own report section, and an offline row cannot leak what the
     * report must never hold: no image key, no title, no URL, no file name (only counts and bytes).
     */
    @Test
    fun offlineCounts_haveTheirOwnSection_andCarryNoKeyUrlOrFileName() = runBlocking {
        val database = TestDb.inMemory()
        db = database
        val now = System.currentTimeMillis()
        val keys = listOf("XVRvVTM", "Hk42gZp")
        keys.forEachIndexed { i, key ->
            database.offlineDao().insertFile(
                com.smugview.app.data.db.OfflineFile(
                    fileKey = "$key/orig", imageKey = key, albumKey = "FfHCms", nickname = "idzifamily",
                    sourceUrl = "https://photos.smugmug.com/photos/i-$key/0/hash/D/i-$key-D.jpg",
                    title = "Holiday party $key", state = if (i == 0) "DONE" else "FAILED",
                    relPath = if (i == 0) "offline/$key.orig.jpg" else null, bytes = if (i == 0) 4_000L else null,
                    createdAt = now, updatedAt = now
                )
            )
        }
        val w = writer(doctor = CacheDoctor(database.doctorDao()))

        w.onSyncFinished()
        val text = report()

        assertTrue(text, text.contains("== offline files (counts only) =="))
        assertTrue(text, text.contains("offline_by_state_done = 1"))
        assertTrue(text, text.contains("offline_by_state_failed = 1"))
        assertTrue(text, text.contains("offline_unreferenced = 2"))
        assertTrue(text, text.contains("offline_done_bytes = 4000"))
        val section = text.substringAfter("== offline files (counts only) ==").substringBefore("\n== ")
        keys.forEach { assertFalse("$it in the offline section", section.contains(it)) }
        assertFalse(section.contains("Holiday"))
        assertFalse(section.contains("photos.smugmug.com"))
        assertFalse(section.contains(".orig."))
        assertFalse("offline lines are not mixed into the invariant list", text.substringAfter("== doctor ==").substringBefore("== offline files").contains("offline_by_state"))
    }
}
