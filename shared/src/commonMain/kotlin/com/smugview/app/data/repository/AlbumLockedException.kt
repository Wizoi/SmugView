package com.smugview.app.data.repository

/**
 * A gallery answered 200 with no photos because it needs its password (design 3.4, R-32). The server
 * gives no error for this: `!images` of a locked gallery is `200`, no `AlbumImage`, `Pages.Total` 0, the
 * same shape as a gallery with no photos. Only the album's own `ResponseLevel == "Password"` tells them
 * apart, so a gallery that really is empty never throws this.
 *
 * [pendingReason]: a password is saved and the attempt to use it was inconclusive (offline, or 429/5xx).
 * Nothing says the password is wrong, so the screen must not ask for it again, nothing is deleted, and
 * the message says which of the two it was.
 */
class AlbumLockedException(val albumKey: String, val pendingReason: TransientReason? = null) :
    Exception(
        when (pendingReason) {
            null -> MESSAGE
            TransientReason.Offline -> OFFLINE_MESSAGE
            TransientReason.Busy -> BUSY_MESSAGE
        }
    ) {
    /** What a screen may say about this lock: the same text as the message, read without touching an exception's own text (design 3.1). */
    val userText: String get() = when (pendingReason) {
        null -> MESSAGE
        TransientReason.Offline -> OFFLINE_MESSAGE
        TransientReason.Busy -> BUSY_MESSAGE
    }

    /** The saved password could not be tried just now (not that it failed). */
    val unlockPending: Boolean get() = pendingReason != null

    companion object {
        /** What Cast and the downloads say (Q5 (a)); the grid prompts for the password instead. */
        const val MESSAGE = "This gallery needs its password. Open it once to unlock it."

        /** The saved password could not be tried because the device is offline: say so, ask for nothing. */
        const val OFFLINE_MESSAGE = "You're offline. This gallery will open once you're back online."

        /** The saved password could not be tried because SmugMug answered 429 or 5xx: say so, ask for nothing. */
        const val BUSY_MESSAGE = "SmugMug is busy right now. Try again in a moment."
    }
}
