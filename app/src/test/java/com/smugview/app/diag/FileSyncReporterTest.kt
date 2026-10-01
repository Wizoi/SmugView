package com.smugview.app.diag

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FileSyncReporterTest {
    @get:Rule val tmp = TemporaryFolder()

    private val sink = RecordingSink()
    private val log = DiagLog(sink, Redactor())

    private fun reporter(file: File, postSync: (suspend (SyncRun) -> Unit)? = null, scope: CoroutineScope? = null) =
        FileSyncReporter(file, log, postSync = postSync, scope = scope)

    @Test
    fun beginAndFinish_writeStartAndEndLines() {
        val f = File(tmp.root, "sync_reports.jsonl")
        val r = reporter(f)
        val run = r.begin(SyncKind.GallerySync, "nick", "sync#1")!!
        assertTrue(r.hasOpenRun())
        run.pagesFetched = 2
        run.stop = StopReason.NoNextPage
        r.finish(run)
        assertFalse(r.hasOpenRun())
        assertTrue(r.flush(2000))

        val lines = f.readLines()
        assertEquals(2, lines.size)
        assertTrue(lines[0].contains("\"t\":\"start\""))
        assertTrue(lines[1].contains("\"t\":\"end\""))
        assertEquals(2, r.recent(5).single().pagesFetched)
        assertNotNull(r.recent(5).single().endedAt)
    }

    @Test
    fun startWithoutEnd_readsBackAsInterrupted() {
        val f = File(tmp.root, "sync_reports.jsonl")
        val first = reporter(f)
        first.begin(SyncKind.GallerySync, "nick", "sync#7")
        first.flush(2000)

        val second = reporter(f)
        val run = second.recent(5).single()
        assertEquals("sync#7", run.runId)
        assertEquals(StopReason.Interrupted, run.stop)
        assertNull(run.endedAt)
    }

    @Test
    fun keepsOnlyTheLastTwentyRuns() {
        val f = File(tmp.root, "sync_reports.jsonl")
        val r = reporter(f)
        for (i in 1..25) {
            val run = r.begin(SyncKind.GallerySync, "nick", "sync#$i")!!
            run.stop = StopReason.NoNextPage
            r.finish(run)
        }
        r.flush(2000)
        assertEquals(20, r.recent(100).size)
        assertEquals("sync#25", r.recent(1).single().runId)
        // and after a restart
        val again = reporter(f).recent(100)
        assertEquals(20, again.size)
        assertEquals("sync#25", again.last().runId)
    }

    @Test
    fun unlock_attachesToTheOpenRunOfItsActionId_andAlwaysLogs() {
        val r = reporter(File(tmp.root, "sync_reports.jsonl"))
        val run = r.begin(SyncKind.LaunchUnlock, "nick", "unlock#1")!!
        r.recordUnlock(UnlockAttempt("K1", "node", "Success", 200, null, 12, "unlock#1"))
        r.recordUnlock(UnlockAttempt("K2", "album", "Rejected", 401, null, 8, "other#9")) // no such run
        assertEquals(listOf("K1"), run.unlocks.map { it.target })
        assertEquals(2, sink.lines.count { it.contains(" sync ") && it.contains("unlock ") })
        assertTrue(sink.lines.any { it.contains(" W ") && it.contains("K2") && it.contains("Rejected") })
    }

    @Test
    fun postSyncAmend_isAppliedAndSurvivesRestart() = runBlocking {
        val f = File(tmp.root, "sync_reports.jsonl")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val r = reporter(f, postSync = { run -> run.newInIndex30d = 5; run.litDotNodes = 2 }, scope = scope)
        val run = r.begin(SyncKind.GallerySync, "nick", "sync#1")!!
        run.stop = StopReason.NoNextPage
        r.finish(run)
        val deadline = System.currentTimeMillis() + 3000
        // the amend line is queued after postSync returns: poll the file, not the run
        while (System.currentTimeMillis() < deadline) {
            r.flush(2000)
            if (f.readText().contains("amend")) break
            Thread.sleep(10)
        }
        scope.cancel()

        assertEquals(5, run.newInIndex30d)
        val reloaded = reporter(f).recent(1).single()
        assertEquals(5, reloaded.newInIndex30d)
        assertEquals(2, reloaded.litDotNodes)
        assertTrue(f.readLines().any { it.contains("\"t\":\"amend\"") })
    }

    @Test
    fun cancelledScope_stillWritesTheEndLine() = runBlocking {
        val f = File(tmp.root, "sync_reports.jsonl")
        val r = reporter(f)
        val run = r.begin(SyncKind.GallerySync, "nick", "sync#1")!!
        run.stop = StopReason.Cancelled
        withContext(kotlinx.coroutines.NonCancellable) { r.finish(run) }
        r.flush(2000)
        assertTrue(f.readText().contains("Cancelled"))
    }

    @Test
    fun unwritableFile_neverThrows_andRunsStayInMemory() {
        val dirAsFile = tmp.newFolder("sync_reports.jsonl")
        val r = reporter(dirAsFile)
        val run = r.begin(SyncKind.GallerySync, "nick", "sync#1")!!
        r.finish(run)
        r.flush(2000)
        assertEquals(1, r.recent(5).size)
    }

    @Test
    fun garbageLinesInTheFile_areSkipped() {
        val f = File(tmp.root, "sync_reports.jsonl")
        f.writeText("not json\n{\"t\":\"end\",\"run\":{\"runId\":\"sync#2\",\"kind\":\"GallerySync\",\"stop\":\"NoNextPage\"}}\n{broken")
        val runs = reporter(f).recent(10)
        assertEquals(listOf("sync#2"), runs.map { it.runId })
    }
}
