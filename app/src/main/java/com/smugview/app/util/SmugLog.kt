package com.smugview.app.util

import android.util.Log
import com.smugview.app.BuildConfig
import com.smugview.app.diag.Diag

/**
 * Centralized logging.
 *
 * [d] is debug-only chatter: in release the lambda is never invoked and nothing is written. [i],
 * [w] and [e] are persisted (redacted) to the diagnostics log in every build, and W/E are also
 * mirrored to logcat, redacted. R8 is enabled for release, but `Log.d` calls are not stripped by
 * our rules, so route trace logging through [d] to keep the string-building out of release.
 */
object SmugLog {
    inline fun d(tag: String, message: () -> String) {
        if (BuildConfig.DEBUG) Log.d(tag, message())
    }

    inline fun i(tag: String, message: () -> String) {
        val text = message()
        if (BuildConfig.DEBUG) Log.i(tag, text)
        Diag.log.i(tag, text)
    }

    fun w(tag: String, message: String, throwable: Throwable? = null) {
        Diag.log.w(tag, message, throwable)
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        Diag.log.e(tag, message, throwable)
    }
}
