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
     * The ORIGINAL as a file to share (owner: "Original size"): the saved copy, else [sourceUrl], else each of [smaller] when the one before
     * answered 404 (findings #24). A JPEG is stripped of camera data and location; any other type is written as it is, and
     * [PreparedShare.stripped] says which, so the caller can say it. Throws what [prepareShareFile] throws.
     */
    suspend fun prepareOriginalShareFile(imageKey: String, fileName: String?, savedPath: String?, sourceUrl: String?, smaller: List<String>): PreparedShare

    /**
     * Saves the original (from [savedPath] when there is one, else [sourceUrl], else the [smaller] renditions when the original
     * answers 404) into the device's photo library. Returns whether it is the original. Throws what `downloadMessage` describes.
     */
    suspend fun saveToPhotoLibrary(imageKey: String, fileName: String?, savedPath: String?, sourceUrl: String?, smaller: List<String>): Boolean
}

/** A file ready for the share sheet: where it is, whether it is the original, whether camera data and location were removed, and its type. */
class PreparedShare(val path: String, val original: Boolean, val stripped: Boolean, val mimeType: String)

/** Provided by the host at the root. */
val LocalPhotoFileOps = staticCompositionLocalOf<PhotoFileOps?> { null }
