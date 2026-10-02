package com.smugview.app.data.offline

import java.util.Locale

/**
 * Every word the user reads about a saved photo or gallery (phase 5 design section 4), in one place so
 * the wording changes in one place. Each failure text names the cause and the way out (owner feedback on 4-8).
 * Pure: no Android resources, so it is unit-tested without a device.
 */
object OfflineMessages {
    const val SAVED = "Saved on this phone"
    const val SAVING = "Saving to this phone…"
    const val WAITING_NETWORK = "Waiting for a connection to save this photo."
    const val BUSY = "SmugMug is busy. This photo will be saved automatically in a few minutes."
    const val GONE = "This photo was removed from SmugMug, so it can't be saved. Remove it from this collection."
    const val FORBIDDEN = "SmugMug won't let this app download this photo. Open it once online, then try again."
    const val DAMAGED = "The download arrived damaged. Try again, or remove it from this collection."
    const val VIDEO_STILL = "Videos play online only. The saved copy is a still picture."

    /** The 4-8 lock text, reused unchanged (design section 4). */
    const val LOCKED = "This gallery needs its password. Open it once to unlock it."

    /** Not in design section 4: a photo with no usable source. Flagged to the owner in the 5-1 report. */
    const val NO_SOURCE = "SmugMug didn't say where to download this photo. Open it once online, then try again."

    /** The Q1 switch before the gallery is kept (its size is only known once listed, then it reads [keepOffline]). */
    const val KEEP_OFFLINE = "Keep offline"

    const val TRY_AGAIN = "Try again"
    const val REMOVE = "Remove"

    /** The link on a waiting kept gallery; opens the network setting (addendum 2.2). */
    const val CHANGE_NETWORK_SETTING = "Change network setting"
    const val WAITING_FOR_WIFI = "Waiting for Wi-Fi"

    /** 5-11: a kept gallery that cannot move because the phone has no network at all (not "Waiting for Wi-Fi"). */
    const val NO_CONNECTION = "No connection. Saving continues when you're back online."
    fun noConnectionProgress(done: Int, total: Int) = "$done of $total saved so far."

    fun saving(done: Int, total: Int) = "Saving to this phone… $done of $total"
    fun waitingWifi(done: Int, total: Int) = "Will save on Wi-Fi. $done of $total saved so far."

    fun storage(needBytes: Long, freeBytes: Long) =
        "Not enough space on this phone to save more photos (needs about ${size(needBytes)}, ${size(freeBytes)} free). " +
            "Free up space and saving continues by itself."

    fun unexpected(code: Int?) =
        "SmugMug answered this download with error ${code ?: "unknown"}. Try again later, or remove it from this collection."

    fun keepOffline(sizeBytes: Long?) = "Keep offline · ${size(sizeBytes)}"

    fun deleteConfirm(name: String, photos: Int, sizeBytes: Long) =
        "Delete “$name”? $photos photos saved on this phone (${size(sizeBytes)}) will be removed. They stay on SmugMug."

    /** Removing a gallery that SmugMug no longer has (6-11c): its photos are not "on SmugMug" any more, so only what is lost here is said. */
    fun removeGalleryConfirm(name: String, photos: Int, sizeBytes: Long) =
        "Delete “$name”? $photos photos saved on this phone (${size(sizeBytes)}) will be removed."

    /** Q10 (6-16): unsaving one photo whose only saved copy is this one. */
    fun removeImageConfirm(title: String, collection: String, sizeBytes: Long) =
        "Remove “$title” from $collection? The copy saved on this phone (${size(sizeBytes)}) will be deleted. It stays on SmugMug."

    /** Q10 (6-16): turning off Keep offline for a gallery whose saved photos nothing else uses. */
    fun stopKeepingConfirm(title: String, photos: Int, sizeBytes: Long) =
        "Stop keeping “$title” offline? ${if (photos == 1) "1 photo" else "$photos photos"} saved on this phone (${size(sizeBytes)}) will be deleted. ${if (photos == 1) "It stays" else "They stay"} on SmugMug."

    fun offlineGallery(photos: Int) =
        "You're offline. Showing the ${if (photos == 1) "1 photo" else "$photos photos"} saved on this phone."

    /**
     * A kept gallery's second line when some photos failed (design section 3: "3 photos can't be saved: removed
     * from SmugMug"). The largest group's reason; a failure that clears by itself says "will be saved later".
     */
    fun galleryFailed(count: Int, reason: FailureReason, httpCode: Int? = null): String {
        val photos = if (count == 1) "1 photo" else "$count photos"
        return when (reason) {
            FailureReason.OFFLINE -> "$photos will be saved later: no connection"
            FailureReason.BUSY -> "$photos will be saved later: SmugMug is busy"
            FailureReason.LOCKED -> "$photos will be saved later: the gallery needs its password"
            FailureReason.GONE -> "$photos can't be saved: removed from SmugMug"
            FailureReason.FORBIDDEN -> "$photos can't be saved: SmugMug refused the download"
            FailureReason.DAMAGED -> "$photos can't be saved: the download arrived damaged"
            FailureReason.NO_SOURCE -> "$photos can't be saved: SmugMug didn't say where to download them"
            FailureReason.STORAGE_FULL -> "$photos can't be saved: not enough space on this phone"
            FailureReason.UNEXPECTED -> "$photos can't be saved: SmugMug answered with error ${httpCode ?: "unknown"}"
        }
    }

    /**
     * The text for a failed row. [needBytes] and [freeBytes] fill [FailureReason.STORAGE_FULL]; [httpCode] fills
     * [FailureReason.UNEXPECTED].
     */
    fun forFailure(reason: FailureReason, httpCode: Int? = null, needBytes: Long = 0, freeBytes: Long = 0): String = when (reason) {
        FailureReason.OFFLINE -> WAITING_NETWORK
        FailureReason.BUSY -> BUSY
        FailureReason.STORAGE_FULL -> storage(needBytes, freeBytes)
        FailureReason.GONE -> GONE
        FailureReason.FORBIDDEN -> FORBIDDEN
        FailureReason.LOCKED -> LOCKED
        FailureReason.DAMAGED -> DAMAGED
        FailureReason.UNEXPECTED -> unexpected(httpCode)
        FailureReason.NO_SOURCE -> NO_SOURCE
    }

    /** "103 MB", "1.2 GB", "950 KB"; "size unknown" for null. */
    fun size(bytes: Long?): String {
        if (bytes == null) return "size unknown"
        val kb = 1024.0
        return when {
            bytes >= kb * kb * kb -> String.format(Locale.ROOT, "%.1f GB", bytes / (kb * kb * kb))
            bytes >= kb * kb -> String.format(Locale.ROOT, "%d MB", Math.round(bytes / (kb * kb)))
            else -> String.format(Locale.ROOT, "%d KB", maxOf(1L, Math.round(bytes / kb)))
        }
    }
}
