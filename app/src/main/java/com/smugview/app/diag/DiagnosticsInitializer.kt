package com.smugview.app.diag

import android.content.Context
import android.os.Build
import android.os.Process
import com.smugview.app.BuildConfig
import com.smugview.app.data.security.PasswordStore
import com.smugview.app.di.DiagScope
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Called from `SmugViewApp.onCreate`: wires the diagnostics log, crash handler and redaction secrets. */
@Singleton
class DiagnosticsInitializer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val diagLog: DiagLog,
    private val redactor: Redactor,
    private val passwordStore: PasswordStore,
    @DiagScope private val scope: CoroutineScope
) {
    fun start() {
        val dir = File(context.filesDir, "diagnostics")
        // The API key is known immediately; saved passwords arrive from the diag scope below.
        redactor.setSecrets(listOf(BuildConfig.SMUGMUG_API_KEY))
        Diag.log = diagLog
        CrashRecorder.install(diagLog, dir)
        diagLog.i(
            "app",
            "start v${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE}) ${BuildConfig.BUILD_TYPE} " +
                "sdk=${Build.VERSION.SDK_INT} pid=${Process.myPid()}"
        )
        scope.launch { CrashRecorder.recordExitReasons(context, diagLog, File(dir, "state.properties")) }
        scope.launch {
            // all() also primes the store, so the first emission already reflects saved passwords.
            passwordStore.unlockedKeys.collect { refreshSecrets() }
        }
    }

    private fun refreshSecrets() {
        runCatching {
            redactor.setSecrets(passwordStore.all().values + BuildConfig.SMUGMUG_API_KEY)
        }
    }
}
