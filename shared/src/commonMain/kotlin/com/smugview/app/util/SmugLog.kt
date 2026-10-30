package com.smugview.app.util

import kotlin.concurrent.Volatile

/** Where [SmugLog] writes. The app installs one (Android: logcat in debug builds plus the redacted diagnostics log; iOS: its own). */
interface LogSink {
    fun debug(tag: String, message: String)
    fun info(tag: String, message: String)
    fun warn(tag: String, message: String, throwable: Throwable?)
    fun error(tag: String, message: String, throwable: Throwable?)
}

/**
 * Centralized logging.
 *
 * [d] is debug-only chatter: when [debugEnabled] is off the lambda is never invoked and nothing is written. [i], [w] and [e] always reach
 * the [sink], which persists them (redacted) to the diagnostics log; W/E are also mirrored to the system log, redacted. R8 is enabled
 * for release, but `Log.d` calls are not stripped by our rules, so route trace logging through [d] to keep the string-building out of release.
 * Until a sink is installed nothing is written (the same as the diagnostics log's no-op default).
 */
object SmugLog {
    @Volatile var sink: LogSink = Silent
    @Volatile var debugEnabled: Boolean = false

    inline fun d(tag: String, message: () -> String) {
        if (debugEnabled) sink.debug(tag, message())
    }

    inline fun i(tag: String, message: () -> String) {
        sink.info(tag, message())
    }

    fun w(tag: String, message: String, throwable: Throwable? = null) {
        sink.warn(tag, message, throwable)
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        sink.error(tag, message, throwable)
    }

    private object Silent : LogSink {
        override fun debug(tag: String, message: String) {}
        override fun info(tag: String, message: String) {}
        override fun warn(tag: String, message: String, throwable: Throwable?) {}
        override fun error(tag: String, message: String, throwable: Throwable?) {}
    }
}
