package com.smugview.app.util

import kotlin.concurrent.Volatile

/**
 * A short message to the user that goes away by itself (a Toast on Android, a banner on iOS). The screens call [show]; the host installs
 * how it is drawn. Until one is installed nothing is shown.
 */
object Toaster {
    @Volatile var sink: (message: String, long: Boolean) -> Unit = { _, _ -> }

    fun show(message: String, long: Boolean = false) = sink(message, long)
}
