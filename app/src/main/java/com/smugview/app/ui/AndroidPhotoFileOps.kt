package com.smugview.app.ui

import android.content.Context
import com.smugview.app.SmugViewApp
import com.smugview.app.download.PhotoDownloader
import com.smugview.app.share.ShareFiles
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request

/** Android's [PhotoFileOps]: the share cache and `MediaStore` over the app's image-fetching client. */
class AndroidPhotoFileOps(private val context: Context) : PhotoFileOps {
    private val factory get() = (context.applicationContext as SmugViewApp).callFactory

    override suspend fun prepareShareFile(imageKey: String, savedPath: String?, thumbnailUrl: String?): String =
        ShareFiles(context.cacheDir, factory).prepare(imageKey, savedPath?.let { File(it) }, thumbnailUrl).absolutePath

    private val logic by lazy {
        PhotoFileLogic(
            fetch = { url ->
                withContext(Dispatchers.IO) {
                    factory.newCall(Request.Builder().url(url).build()).execute().use { r ->
                        FetchedImage(r.code, if (r.isSuccessful) r.body?.bytes() else null)
                    }
                }
            },
            readSaved = { path -> File(path).takeIf { it.isFile && it.length() > 0 }?.readBytes() }
        )
    }

    override suspend fun prepareOriginalShareFile(
        imageKey: String,
        fileName: String?,
        savedPath: String?,
        sourceUrl: String?,
        smaller: List<String>
    ): PreparedShare {
        val shared = logic.originalShare(savedPath, sourceUrl, smaller)
        val dir = File(context.cacheDir, ShareFiles.DIR).apply { mkdirs() }
        // The file keeps the photo's own name, so the person who receives it sees a real file name.
        val file = File(dir, "${PhotoFileLogic.photoName(imageKey, fileName)}.${shared.extension}")
        withContext(Dispatchers.IO) {
            val tmp = File(dir, file.name + ".tmp")
            try {
                tmp.writeBytes(shared.bytes)
                if (file.exists()) file.delete()
                if (!tmp.renameTo(file)) throw java.io.IOException("could not move the shared picture into place")
            } finally {
                tmp.delete()
            }
        }
        return PreparedShare(file.absolutePath, shared.original, shared.stripped, shared.mimeType)
    }

    override suspend fun saveToPhotoLibrary(
        imageKey: String,
        fileName: String?,
        savedPath: String?,
        sourceUrl: String?,
        smaller: List<String>
    ): Boolean = PhotoDownloader(context, factory).download(imageKey, fileName, savedPath?.let { File(it) }, sourceUrl, smaller).original
}
