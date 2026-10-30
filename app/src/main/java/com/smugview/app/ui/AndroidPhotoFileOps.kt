package com.smugview.app.ui

import android.content.Context
import com.smugview.app.SmugViewApp
import com.smugview.app.download.PhotoDownloader
import com.smugview.app.share.ShareFiles
import java.io.File

/** Android's [PhotoFileOps]: the share cache and `MediaStore` over the app's image-fetching client. */
class AndroidPhotoFileOps(private val context: Context) : PhotoFileOps {
    private val factory get() = (context.applicationContext as SmugViewApp).callFactory

    override suspend fun prepareShareFile(imageKey: String, savedPath: String?, thumbnailUrl: String?): String =
        ShareFiles(context.cacheDir, factory).prepare(imageKey, savedPath?.let { File(it) }, thumbnailUrl).absolutePath

    override suspend fun saveToPhotoLibrary(
        imageKey: String,
        fileName: String?,
        savedPath: String?,
        sourceUrl: String?,
        smaller: List<String>
    ): Boolean = PhotoDownloader(context, factory).download(imageKey, fileName, savedPath?.let { File(it) }, sourceUrl, smaller).original
}
