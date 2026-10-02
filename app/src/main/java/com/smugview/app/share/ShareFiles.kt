package com.smugview.app.share

import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Request

/**
 * The file behind "Share picture" (design 3.10): the saved copy when it is a JPEG, else the X3 rendition, stripped of its
 * private metadata ([JpegStrip]) and written to `cache/shared_images/` (temp, then rename). Files older than a day there are
 * deleted at the next share.
 */
class ShareFiles(
    private val cacheDir: File,
    private val images: Call.Factory,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis
) {
    /** The stripped picture, or an exception: [IOException] (network, disk), [HttpFailure] or [NotAPictureException]. */
    suspend fun prepare(imageKey: String, saved: File?, thumbnailUrl: String?): File = withContext(io) {
        val dir = File(cacheDir, DIR).apply { mkdirs() }
        sweep(dir)
        val source = saved?.takeIf { it.isFile && it.length() > 0 }?.readBytes()?.takeIf { isJpeg(it) }
            ?: fetchRendition(thumbnailUrl)
        val stripped = JpegStrip.strip(source, orientationOf(source)) ?: throw NotAPictureException()
        val name = "shared_${imageKey.filter { it.isLetterOrDigit() || it == '-' || it == '_' }}.jpg"
        val tmp = File(dir, "$name.tmp")
        val file = File(dir, name)
        try {
            tmp.writeBytes(stripped)
            if (file.exists()) file.delete()
            if (!tmp.renameTo(file)) throw IOException("could not move the shared picture into place")
        } finally {
            tmp.delete()
        }
        file
    }

    private fun fetchRendition(thumbnailUrl: String?): ByteArray {
        val url = thumbnailUrl?.replace("/Th/", "/X3/")?.takeIf { it.startsWith("https://") } ?: throw NotAPictureException()
        images.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) throw HttpFailure(response.code)
            return response.body?.bytes() ?: throw IOException("empty body")
        }
    }

    private fun sweep(dir: File) {
        val cutoff = clock() - MAX_AGE_MS
        dir.listFiles()?.forEach { if (it.isFile && it.lastModified() < cutoff) it.delete() }
    }

    private fun isJpeg(b: ByteArray) = b.size > 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte()

    private fun orientationOf(jpeg: ByteArray): Int = try {
        ExifInterface(ByteArrayInputStream(jpeg)).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)
    } catch (e: Exception) {
        1
    }

    /** SmugMug answered, but not with the picture: kept apart from [IOException] so a 429 or 5xx is never shown as "offline". */
    class HttpFailure(val code: Int) : Exception("HTTP $code")

    class NotAPictureException : Exception("no picture to share")

    companion object {
        const val DIR = "shared_images"
        const val MAX_AGE_MS = 24L * 60 * 60 * 1000
    }
}
