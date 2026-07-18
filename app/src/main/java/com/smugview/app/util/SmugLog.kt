package com.smugview.app.util

import android.util.Log
import com.smugview.app.BuildConfig

/**
 * Centralized debug logging.
 *
 * In release builds (`BuildConfig.DEBUG == false`) the message lambda is never
 * invoked, so no string is built and nothing is written to logcat. This matters
 * because R8 minification is disabled for this app (to protect reflection-based
 * Gson/Retrofit models), so `Log.d(...)` calls are NOT stripped by the shrinker —
 * routing them through here is what keeps chatty logs and their string-building
 * out of release builds.
 *
 * Use this for diagnostic/trace logging. Genuine errors worth keeping in release
 * can call [e] or `Log.e` directly.
 */
object SmugLog {
    inline fun d(tag: String, message: () -> String) {
        if (BuildConfig.DEBUG) Log.d(tag, message())
    }

    inline fun i(tag: String, message: () -> String) {
        if (BuildConfig.DEBUG) Log.i(tag, message())
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        if (BuildConfig.DEBUG) Log.e(tag, message, throwable)
    }
}
