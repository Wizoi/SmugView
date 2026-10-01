package com.smugview.app.diag

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import java.io.File
import java.util.Properties

data class ExitRecord(
    val timestamp: Long,
    val reason: String,
    val status: Int,
    val importance: Int,
    val pssKb: Long,
    val rssKb: Long,
    val description: String?
)

object CrashRecorder {
    private val BENIGN = setOf("EXIT_SELF", "USER_REQUESTED", "USER_STOPPED", "PERMISSION_CHANGE")

    /** Logs uncaught exceptions, then hands over to [previous] so the system crash flow still runs. */
    fun install(
        log: DiagLog,
        crashDir: File,
        previous: Thread.UncaughtExceptionHandler? = Thread.getDefaultUncaughtExceptionHandler()
    ) {
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            try {
                log.e("crash", "uncaught exception on thread ${thread.name}", e)
                if (!log.flush(1000)) writeCrashFile(log, crashDir)
            } catch (_: Throwable) {
            }
            previous?.uncaughtException(thread, e)
        }
    }

    // The writer thread is dead or stuck: keep the redacted line in a plain file instead.
    private fun writeCrashFile(log: DiagLog, dir: File) {
        val line = log.recent(1, Level.E).lastOrNull() ?: return
        dir.mkdirs()
        File(dir, "crash-${System.currentTimeMillis()}.txt").writeText(line)
    }

    /** Logs exit records newer than [lastSeenMs]; returns the newest timestamp seen. */
    fun recordExits(records: List<ExitRecord>, lastSeenMs: Long, log: DiagLog): Long {
        var newest = lastSeenMs
        for (r in records.sortedBy { it.timestamp }) {
            if (r.timestamp <= lastSeenMs) continue
            val msg = "reason=${r.reason} status=${r.status} importance=${r.importance} " +
                "pss=${r.pssKb} rss=${r.rssKb} at=${r.timestamp}" + (r.description?.let { " desc=$it" } ?: "")
            if (r.reason in BENIGN) log.i("exit", msg) else log.w("exit", msg)
            if (r.timestamp > newest) newest = r.timestamp
        }
        return newest
    }

    /** API 30+: records why the previous process(es) died, once per exit. */
    fun recordExitReasons(context: Context, log: DiagLog, stateFile: File) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        runCatching { recordExitReasonsR(context, log, stateFile) }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun recordExitReasonsR(context: Context, log: DiagLog, stateFile: File) {
        val props = Properties()
        if (stateFile.isFile) stateFile.inputStream().use { props.load(it) }
        val lastSeen = props.getProperty("lastExitSeen")?.toLongOrNull() ?: 0L
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val records = am.getHistoricalProcessExitReasons(null, 0, 5).map {
            ExitRecord(it.timestamp, reasonName(it.reason), it.status, it.importance, it.pss, it.rss, it.description)
        }
        val newest = recordExits(records, lastSeen, log)
        if (newest != lastSeen) {
            props.setProperty("lastExitSeen", newest.toString())
            stateFile.parentFile?.mkdirs()
            stateFile.outputStream().use { props.store(it, null) }
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun reasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_CRASH -> "CRASH"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
        ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
        ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
        else -> "OTHER($reason)"
    }
}
