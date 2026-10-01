package com.smugview.app.diag

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CrashRecorderTest {
    @get:Rule val tmp = TemporaryFolder()
    private val savedHandler = Thread.getDefaultUncaughtExceptionHandler()

    @After fun tearDown() { Thread.setDefaultUncaughtExceptionHandler(savedHandler) }

    @Test fun uncaughtException_isRecordedAndPreviousHandlerStillRuns() {
        val sink = RecordingSink()
        val log = DiagLog(sink, Redactor())
        var previousSaw: Throwable? = null
        Thread.setDefaultUncaughtExceptionHandler { _, e -> previousSaw = e }
        CrashRecorder.install(log, tmp.newFolder())

        val t = Thread({ throw IllegalStateException("kaboom") }, "crasher")
        t.start(); t.join()

        assertTrue(sink.lines.toString(), sink.lines.any { it.contains(" E crash ") && it.contains("kaboom") && it.contains("crasher") })
        assertEquals("kaboom", previousSaw?.message)
    }

    @Test fun uncaughtException_whenWriterIsDead_writesARedactedCrashFile() {
        val sink = RecordingSink().also { it.flushResult = false }
        val dir = tmp.newFolder()
        val redactor = Redactor().also { it.setSecrets(listOf("hunter22xyz")) }
        Thread.setDefaultUncaughtExceptionHandler { _, _ -> }
        CrashRecorder.install(DiagLog(sink, redactor), dir)

        val t = Thread({ throw IllegalStateException("kaboom hunter22xyz") }, "crasher")
        t.start(); t.join()

        val files = dir.listFiles { f -> f.name.startsWith("crash-") }!!
        assertEquals(1, files.size)
        val text = files[0].readText()
        assertTrue(text.contains("kaboom"))
        assertFalse(text.contains("hunter22xyz"))
    }

    @Test fun exitRecords_newerThanLastSeenAreLogged_andNewestIsReturned() {
        val sink = RecordingSink()
        val log = DiagLog(sink, Redactor())
        val records = listOf(
            ExitRecord(1_000, "OLD", 0, 100, 1, 1, null),
            ExitRecord(2_000, "ANR", 0, 100, 2048, 4096, "user request after error: Input dispatching timed out"),
            ExitRecord(3_000, "CRASH", 1, 400, 0, 0, null)
        )
        val newest = CrashRecorder.recordExits(records, lastSeenMs = 1_500, log = log)
        assertEquals(3_000L, newest)
        assertEquals(2, sink.lines.size)
        assertTrue(sink.lines[0], sink.lines[0].contains(" exit ") && sink.lines[0].contains("ANR") && sink.lines[0].contains("pss=2048"))
        assertTrue(sink.lines.none { it.contains("OLD") })
        assertEquals(3_000L, CrashRecorder.recordExits(records, lastSeenMs = 3_000, log = log))
        assertEquals(2, sink.lines.size)
    }
}
