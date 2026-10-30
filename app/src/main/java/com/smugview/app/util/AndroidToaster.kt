package com.smugview.app.util

import android.content.Context
import android.widget.Toast

/** Installs Android's way of showing a [Toaster] message: a Toast. Safe to call again. */
object AndroidToaster {
    fun install(context: Context) {
        val app = context.applicationContext
        Toaster.sink = { message, long -> Toast.makeText(app, message, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show() }
    }
}
