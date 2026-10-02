package com.smugview.app.download

import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import com.smugview.app.share.ShareFiles
import com.smugview.app.ui.text.Problem
import com.smugview.app.ui.text.Subject
import com.smugview.app.ui.text.UserMessages
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Request

/**
 * "Download photo" (design 3.11, R-55): the original, camera data kept (it is the user's own copy), saved to
 * `Pictures/SmugView` through the system photo store, from the saved copy when there is one and else from the CDN.
 *
 * Android 10 and later: a `MediaStore` row written pending and published when complete, deleted if the copy fails, so a
 * cut-off download leaves no row and no file. Android 8 and 9: needs the storage permission; the file is written
 * to a temp name beside its destination and renamed, then handed to the media scanner. The name is never overwritten.
 */
class PhotoDownloader(
    private val context: Context,
    private val images: Call.Factory,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val sdkInt: Int = Build.VERSION.SDK_INT,
    private val legacyPicturesDir: () -> File = { Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES) },
    private val hasLegacyPermission: () -> Boolean = {
        ContextCompat.checkSelfPermission(context, android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    },
    private val scan: (path: String, mime: String) -> Unit = { path, mime ->
        MediaScannerConnection.scanFile(context, arrayOf(path), arrayOf(mime), null)
    }
) {
    /** The name the photo was saved under. Throws [PermissionNeededException], [ShareFiles.HttpFailure], [IOException] or [NoSourceException]. */
    suspend fun download(imageKey: String, fileName: String?, saved: File?, sourceUrl: String?): String = withContext(io) {
        if (sdkInt < 29 && !hasLegacyPermission()) throw PermissionNeededException()
        val savedFile = saved?.takeIf { it.isFile && it.length() > 0 }
        if (savedFile != null) {
            savedFile.inputStream().use { write(it, savedFile.length(), mimeOfFile(savedFile), nameFor(imageKey, fileName)) }
        } else {
            val url = sourceUrl?.takeIf { it.startsWith("https://") } ?: throw NoSourceException()
            images.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) throw ShareFiles.HttpFailure(response.code)
                val body = response.body ?: throw IOException("empty body")
                val mime = body.contentType()?.let { "${it.type}/${it.subtype}" }?.takeIf { it.startsWith("image/") } ?: "image/jpeg"
                write(body.byteStream(), body.contentLength(), mime, nameFor(imageKey, fileName))
            }
        }
    }

    private fun write(source: InputStream, expected: Long, mime: String, baseName: String): String {
        val name = "$baseName.${extensionOf(mime)}"
        return if (sdkInt >= 29) writeToMediaStore(source, expected, mime, name) else writeLegacy(source, expected, mime, name)
    }

    private fun writeToMediaStore(source: InputStream, expected: Long, mime: String, name: String): String {
        val resolver = context.contentResolver
        val pending = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, mime)
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$FOLDER")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, pending) ?: throw SaveFailedException("the photo store refused the new picture")
        try {
            val out = resolver.openOutputStream(uri) ?: throw SaveFailedException("could not open the new picture")
            out.use { copy(source, it, expected) }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        } catch (e: Throwable) {
            try { resolver.delete(uri, null, null) } catch (_: Exception) { }
            throw e
        }
        return name
    }

    private fun writeLegacy(source: InputStream, expected: Long, mime: String, name: String): String {
        val dir = File(legacyPicturesDir(), FOLDER)
        if (!dir.isDirectory && !dir.mkdirs()) throw SaveFailedException("could not create ${dir.name}")
        val target = freeName(dir, name)
        val temp = File(dir, "${target.name}.part")
        try {
            temp.outputStream().use { copy(source, it, expected) }
            if (!temp.renameTo(target)) throw SaveFailedException("could not move the picture into place")
        } finally {
            temp.delete()
        }
        scan(target.absolutePath, mime)
        return target.name
    }

    private fun copy(source: InputStream, out: OutputStream, expected: Long) {
        val copied = source.copyTo(out)
        out.flush()
        if (expected >= 0 && copied != expected) throw IOException("the picture ended early ($copied of $expected bytes)")
    }

    /** `name`, or `base (1).ext`, `base (2).ext`: a download never replaces a file. */
    private fun freeName(dir: File, name: String): File {
        var file = File(dir, name)
        var n = 1
        while (file.exists()) {
            file = File(dir, "${name.substringBeforeLast('.')} ($n).${name.substringAfterLast('.')}")
            n++
        }
        return file
    }

    class PermissionNeededException : Exception("storage permission needed")

    /** The phone would not take the file (not a network or disk-full problem). */
    class SaveFailedException(message: String) : Exception(message)

    /** Nothing saved and no address to fetch from. */
    class NoSourceException : Exception("no source for the picture")

    companion object {
        const val FOLDER = "SmugView"

        /** The API `FileName` without its extension (the extension comes from the bytes' type), or the photo's key. */
        internal fun nameFor(imageKey: String, fileName: String?): String {
            val base = fileName?.trim()?.takeIf { it.isNotEmpty() }?.substringBeforeLast('.')?.takeIf { it.isNotEmpty() } ?: imageKey
            return base.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_").trim().take(120).ifEmpty { imageKey }
        }

        internal fun extensionOf(mime: String): String = when (mime.lowercase()) {
            "image/png" -> "png"
            "image/gif" -> "gif"
            "image/webp" -> "webp"
            "image/heic" -> "heic"
            "image/tiff" -> "tif"
            else -> "jpg"
        }

        internal fun mimeOfFile(file: File): String {
            val head = ByteArray(12)
            val n = try { file.inputStream().use { it.read(head) } } catch (e: IOException) { 0 }
            fun at(i: Int) = if (i < n) head[i].toInt() and 0xFF else -1
            return when {
                at(0) == 0x89 && at(1) == 0x50 && at(2) == 0x4E && at(3) == 0x47 -> "image/png"
                at(0) == 0x47 && at(1) == 0x49 && at(2) == 0x46 -> "image/gif"
                at(0) == 0x52 && at(1) == 0x49 && at(2) == 0x46 && at(3) == 0x46 && at(8) == 0x57 && at(9) == 0x45 -> "image/webp"
                else -> "image/jpeg"
            }
        }
    }
}

/** What a failed download says (design 5): its real cause, and a status from SmugMug is never "you went offline". */
fun downloadMessage(e: Throwable): String {
    val photo = Subject.Photo
    return when {
        e is PhotoDownloader.PermissionNeededException -> UserMessages.DOWNLOAD_PERMISSION
        e is IOException && (e.message.orEmpty().contains("ENOSPC") || e.message.orEmpty().contains("No space left")) -> UserMessages.DOWNLOAD_NO_SPACE
        e is ShareFiles.HttpFailure -> UserMessages.downloadFailed(
            when (e.code) {
                429 -> Problem.RateLimited(photo)
                in 500..599 -> Problem.SmugMugTrouble(photo, e.code)
                404 -> Problem.Gone(photo)
                else -> Problem.Unexpected(photo, e.code.toString())
            }
        )
        e is PhotoDownloader.SaveFailedException || e is PhotoDownloader.NoSourceException ->
            UserMessages.downloadFailed(Problem.Unexpected(photo, "save"))
        else -> when (val p = Problem.from(e, photo)) {
            is Problem.OfflineNothingSaved -> UserMessages.DOWNLOAD_OFFLINE
            else -> UserMessages.downloadFailed(p)
        }
    }
}
