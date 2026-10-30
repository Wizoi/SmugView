package com.smugview.app.download

import com.smugview.app.share.HttpFailure
import com.smugview.app.ui.text.Problem
import com.smugview.app.ui.text.Subject
import com.smugview.app.ui.text.UserMessages
import kotlinx.io.IOException

/** What a failed download says (design 5): its real cause, and a status from SmugMug is never "you went offline". */
fun downloadMessage(e: Throwable): String {
    val photo = Subject.Photo
    return when {
        e is PermissionNeededException -> UserMessages.DOWNLOAD_PERMISSION
        e is IOException && (e.message.orEmpty().contains("ENOSPC") || e.message.orEmpty().contains("No space left")) -> UserMessages.DOWNLOAD_NO_SPACE
        e is HttpFailure && e.code == 404 -> UserMessages.DOWNLOAD_NO_COPY
        e is HttpFailure -> UserMessages.downloadFailed(
            when (e.code) {
                429 -> Problem.RateLimited(photo)
                in 500..599 -> Problem.SmugMugTrouble(photo, e.code)
                else -> Problem.Unexpected(photo, e.code.toString())
            }
        )
        e is SaveFailedException || e is NoSourceException ->
            UserMessages.downloadFailed(Problem.Unexpected(photo, "save"))
        else -> when (val p = Problem.from(e, photo)) {
            is Problem.OfflineNothingSaved -> UserMessages.DOWNLOAD_OFFLINE
            else -> UserMessages.downloadFailed(p)
        }
    }
}
