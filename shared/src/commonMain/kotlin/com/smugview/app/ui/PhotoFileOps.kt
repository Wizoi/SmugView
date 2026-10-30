package com.smugview.app.ui

import androidx.compose.runtime.staticCompositionLocalOf

/** What the device's file system and photo library do for "Share picture" and "Download photo". */
interface PhotoFileOps {
    /**
     * The saved copy, else the X3 rendition, stripped of camera data and location, as a JPEG on disk: its path. Throws
     * [com.smugview.app.share.HttpFailure], [com.smugview.app.share.NotAPictureException] or an `IOException`.
     */
    suspend fun prepareShareFile(imageKey: String, savedPath: String?, thumbnailUrl: String?): String

    /**
     * Saves the original (from [savedPath] when there is one, else [sourceUrl], else the [smaller] renditions when the original
     * answers 404) into the device's photo library. Returns whether it is the original. Throws what `downloadMessage` describes.
     */
    suspend fun saveToPhotoLibrary(imageKey: String, fileName: String?, savedPath: String?, sourceUrl: String?, smaller: List<String>): Boolean
}

/** Provided by the host at the root. */
val LocalPhotoFileOps = staticCompositionLocalOf<PhotoFileOps?> { null }
