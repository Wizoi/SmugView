package com.smugview.app.diag

import android.util.Log
import com.smugview.app.BuildConfig
import com.smugview.app.util.LogSink

/**
 * The Android sink behind [com.smugview.app.util.SmugLog]: logcat in debug builds for debug and info, and every info, warning and error
 * to the redacted diagnostics log ([Diag.log], read on each call so a test or the initializer can swap it).
 */
object AppLogSink : LogSink {
    override fun debug(tag: String, message: String) {
        if (BuildConfig.DEBUG) Log.d(tag, message)
    }

    override fun info(tag: String, message: String) {
        if (BuildConfig.DEBUG) Log.i(tag, message)
        Diag.log.i(tag, message)
    }

    override fun warn(tag: String, message: String, throwable: Throwable?) {
        Diag.log.w(tag, message, throwable)
    }

    override fun error(tag: String, message: String, throwable: Throwable?) {
        Diag.log.e(tag, message, throwable)
    }
}
