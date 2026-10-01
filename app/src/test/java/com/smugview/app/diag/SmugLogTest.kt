package com.smugview.app.diag

import com.smugview.app.util.SmugLog
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SmugLogTest {
    @After fun tearDown() { Diag.log = DiagLog.NOOP }

    @Test fun errorAndWarn_landInTheDiagLog_withRedaction() {
        val sink = RecordingSink()
        val redactor = Redactor().also { it.setSecrets(listOf("hunter22xyz")) }
        Diag.log = DiagLog(sink, redactor)
        SmugLog.e("tag", "bad thing hunter22xyz", RuntimeException("boom"))
        SmugLog.w("tag", "careful")
        assertEquals(2, sink.lines.size)
        assertTrue(sink.lines[0], sink.lines[0].contains(" E tag bad thing <secret>"))
        assertTrue(sink.lines[0].contains("RuntimeException: boom"))
        assertTrue(sink.lines[1], sink.lines[1].contains(" W tag careful"))
    }

    @Test fun info_isPersisted_debugIsNot() {
        val sink = RecordingSink()
        Diag.log = DiagLog(sink, Redactor())
        SmugLog.i("tag") { "hello info" }
        SmugLog.d("tag") { "chatty debug" }
        assertEquals(1, sink.lines.size)
        assertTrue(sink.lines[0].contains(" I tag hello info"))
    }

    @Test fun diagLog_mirrorsOnlyWarningsAndErrorsToLogcat_redacted() {
        val mirrored = mutableListOf<Triple<Level, String, String>>()
        val redactor = Redactor().also { it.setSecrets(listOf("hunter22xyz")) }
        val log = DiagLog(RecordingSink(), redactor, logcat = { l, c, m -> mirrored.add(Triple(l, c, m)) })
        log.i("c", "info")
        log.w("c", "warn hunter22xyz")
        log.e("c", "err")
        assertEquals(listOf(Level.W, Level.E), mirrored.map { it.first })
        assertEquals("warn <secret>", mirrored[0].third)
    }
}
