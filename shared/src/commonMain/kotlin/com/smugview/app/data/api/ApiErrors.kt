package com.smugview.app.data.api

import com.smugview.app.data.repository.AlbumLockedException
import com.smugview.app.data.repository.TransientReason
import kotlinx.io.IOException

/** Judgements about a failure that do not depend on the screen (the screen texts stay in the app's `SmugMugErrorMapper`). */
object ApiErrors {
    /**
     * True when [error] means "this phone cannot reach SmugMug right now": no network at all, or OkHttp's synthetic
     * 504 for an `only-if-cached` miss, or a locked gallery whose saved password could not be tried for that reason.
     * A 429, a 5xx and a 404 are NOT offline: SmugMug answered.
     */
    fun isOffline(error: Throwable?): Boolean = when (error) {
        is IOException -> true
        is ApiException -> error.syntheticCacheMiss
        is AlbumLockedException -> error.pendingReason == TransientReason.Offline
        else -> false
    }

    /** True only when SmugMug explicitly refused the credentials (401/403). Offline, 429, 5xx and the synthetic 504 say nothing about a saved password. */
    fun isPasswordRejection(error: Throwable?): Boolean = error is ApiException && (error.code == 401 || error.code == 403)
}
