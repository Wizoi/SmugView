package com.smugview.app.diag

import androidx.test.core.app.ApplicationProvider
import com.smugview.app.BuildConfig
import com.smugview.app.data.security.FakePasswordStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class DiagnosticsInitializerTest {
    private val savedHandler = Thread.getDefaultUncaughtExceptionHandler()

    @After fun tearDown() {
        Diag.log = DiagLog.NOOP
        Thread.setDefaultUncaughtExceptionHandler(savedHandler)
    }

    @Test fun start_installsLog_logsStartLine_andTracksSecrets() {
        val sink = RecordingSink()
        val redactor = Redactor()
        val log = DiagLog(sink, redactor)
        val store = FakePasswordStore()
        val init = DiagnosticsInitializer(
            ApplicationProvider.getApplicationContext(), log, redactor, store, CoroutineScope(Dispatchers.Unconfined)
        )
        init.start()

        assertSame(log, Diag.log)
        val start = " I app start v${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE})"
        assertTrue(sink.lines.toString(), sink.lines.any { it.contains(start) })

        store.savePassword("node1", "hunter22xyz")
        log.i("t", "oops hunter22xyz")
        assertFalse(sink.lines.last().contains("hunter22xyz"))

        val apiKey = BuildConfig.SMUGMUG_API_KEY
        log.i("t", "key $apiKey end")
        assertFalse(sink.lines.last().contains(apiKey)) // deliberately no message: never print the key
    }
}
