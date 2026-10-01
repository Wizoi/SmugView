package com.smugview.app.data.repository

/**
 * A gallery answered 200 with no photos because it needs its password (design 3.4, R-32). The server
 * gives no error for this: `!images` of a locked gallery is `200`, no `AlbumImage`, `Pages.Total` 0, the
 * same shape as a gallery with no photos. Only the album's own `ResponseLevel == "Password"` tells them
 * apart, so a gallery that really is empty never throws this.
 *
 * [unlockPending]: a password is saved and the attempt to use it was inconclusive (offline, 429, 5xx).
 * Nothing says the password is wrong, so the screen must not ask for it again, and nothing is deleted.
 */
class AlbumLockedException(val albumKey: String, val unlockPending: Boolean = false) :
    Exception(if (unlockPending) PENDING_MESSAGE else MESSAGE) {
    companion object {
        /** What Cast and the downloads say (Q5 (a)); the grid prompts for the password instead. */
        const val MESSAGE = "This gallery needs its password. Open it once to unlock it."

        /** The saved password could not be tried just now (not that it failed): say so, ask for nothing. */
        const val PENDING_MESSAGE = "Couldn't unlock this gallery right now. Try again in a moment."
    }
}
