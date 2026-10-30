package com.smugview.app.ui

import com.smugview.app.download.NoSourceException
import com.smugview.app.share.HttpFailure
import com.smugview.app.share.JpegStrip
import com.smugview.app.share.NotAPictureException
import kotlinx.io.IOException

/** What a fetch of one picture address answered: the status, and the body when there was one. */
class FetchedImage(val code: Int, val bytes: ByteArray?)

/** The bytes to save into the photo library, and whether they are the original (false: a smaller rendition). */
class SaveSource(val bytes: ByteArray, val original: Boolean)

/**
 * The decisions behind "Share picture" and "Download photo" that do not depend on the device (mirrors Android's `ShareFiles`
 * and `PhotoDownloader`): which bytes to use, when a 404 moves on to a smaller size, what a 429 or 5xx means, what the
 * file is called. The device parts (HTTP, disk, photo library) arrive as functions so this runs in plain unit tests.
 */
class PhotoFileLogic(
    private val fetch: suspend (url: String) -> FetchedImage,
    private val readSaved: (path: String) -> ByteArray?
) {
    /** The picture to share, stripped of camera data and location: the saved copy when it is a JPEG, else the X3 rendition. */
    suspend fun shareBytes(savedPath: String?, thumbnailUrl: String?): ByteArray {
        val source = savedPath?.let { readSaved(it) }?.takeIf { it.isNotEmpty() && isJpeg(it) }
            ?: fetchRendition(thumbnailUrl)
        return JpegStrip.strip(source, ExifOrientation.read(source)) ?: throw NotAPictureException()
    }

    private suspend fun fetchRendition(thumbnailUrl: String?): ByteArray {
        val url = thumbnailUrl?.replace("/Th/", "/X3/")?.takeIf { it.startsWith("https://") } ?: throw NotAPictureException()
        val r = fetch(url)
        if (r.code !in 200..299) throw HttpFailure(r.code)
        return r.bytes ?: throw IOException("empty body")
    }

    /**
     * The original: the saved copy, else [sourceUrl], else each of [smaller] in order, moving on only when the one before
     * answered 404 (findings #24: a 404 on an original is not a gone photo). Any other status is [HttpFailure].
     */
    suspend fun saveSource(savedPath: String?, sourceUrl: String?, smaller: List<String>): SaveSource {
        val saved = savedPath?.let { readSaved(it) }?.takeIf { it.isNotEmpty() }
        if (saved != null) return SaveSource(saved, original = true)
        val first = sourceUrl?.takeIf { it.startsWith("https://") } ?: throw NoSourceException()
        val candidates = listOf(first) + smaller.filter { it.startsWith("https://") && it != first }
        for ((index, url) in candidates.withIndex()) {
            val r = fetch(url)
            if (r.code == 404 && index < candidates.lastIndex) continue
            if (r.code !in 200..299) throw HttpFailure(r.code)
            val bytes = r.bytes?.takeIf { it.isNotEmpty() } ?: throw IOException("empty body")
            return SaveSource(bytes, original = index == 0)
        }
        throw HttpFailure(404)
    }

    companion object {
        const val SHARE_DIR = "shared_images"
        const val MAX_AGE_MS = 24L * 60 * 60 * 1000

        fun isJpeg(b: ByteArray) = b.size > 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte()

        /** True for a shared file last written more than a day ago. */
        fun isStale(modifiedMs: Long, nowMs: Long) = modifiedMs < nowMs - MAX_AGE_MS

        fun shareFileName(imageKey: String): String =
            "shared_${imageKey.filter { it.isLetterOrDigit() || it == '-' || it == '_' }}.jpg"

        /** The API `FileName` without its extension (the extension comes from the bytes), or the photo's key. */
        fun photoName(imageKey: String, fileName: String?): String {
            val base = fileName?.trim()?.takeIf { it.isNotEmpty() }?.substringBeforeLast('.')?.takeIf { it.isNotEmpty() } ?: imageKey
            val bad = "/\\:*?\"<>|"
            return base.map { if (it in bad || it.code < 32) '_' else it }.joinToString("").trim().take(120).ifEmpty { imageKey }
        }

        /** The file extension for these bytes, from their magic numbers (JPEG when unsure). */
        fun extensionOf(b: ByteArray): String {
            fun at(i: Int) = if (i < b.size) b[i].toInt() and 0xFF else -1
            return when {
                at(0) == 0x89 && at(1) == 0x50 && at(2) == 0x4E && at(3) == 0x47 -> "png"
                at(0) == 0x47 && at(1) == 0x49 && at(2) == 0x46 -> "gif"
                at(0) == 0x52 && at(1) == 0x49 && at(2) == 0x46 && at(3) == 0x46 && at(8) == 0x57 && at(9) == 0x45 -> "webp"
                at(4) == 0x66 && at(5) == 0x74 && at(6) == 0x79 && at(7) == 0x70 && (at(8) == 0x68 || at(8) == 0x6D) -> "heic"
                else -> "jpg"
            }
        }
    }
}

/** Reads the EXIF `Orientation` (1..8) of a JPEG, 1 when absent or unreadable (Android uses ExifInterface for this). */
object ExifOrientation {
    fun read(jpeg: ByteArray): Int {
        try {
            if (jpeg.size < 4 || jpeg[0] != 0xFF.toByte() || jpeg[1] != 0xD8.toByte()) return 1
            var i = 2
            while (i + 4 <= jpeg.size) {
                if (jpeg[i] != 0xFF.toByte()) return 1
                val type = jpeg[i + 1].toInt() and 0xFF
                if (type == 0xFF) { i++; continue }
                if (type == 0xDA || type == 0xD9) return 1
                if (type == 0x01 || type in 0xD0..0xD7) { i += 2; continue }
                val len = u16(jpeg, i + 2, true)
                if (len < 2) return 1
                if (type == 0xE1 && len >= 16 && text(jpeg, i + 4, 4) == "Exif") return tiffOrientation(jpeg, i + 10, i + 2 + len)
                i += 2 + len
            }
        } catch (e: Exception) {
            return 1
        }
        return 1
    }

    private fun text(b: ByteArray, from: Int, n: Int) = b.copyOfRange(from, from + n).decodeToString()

    private fun u16(b: ByteArray, at: Int, big: Boolean): Int {
        val x = b[at].toInt() and 0xFF
        val y = b[at + 1].toInt() and 0xFF
        return if (big) (x shl 8) or y else (y shl 8) or x
    }

    private fun u32(b: ByteArray, at: Int, big: Boolean): Int =
        if (big) (u16(b, at, true) shl 16) or u16(b, at + 2, true) else (u16(b, at + 2, false) shl 16) or u16(b, at, false)

    private fun tiffOrientation(b: ByteArray, tiff: Int, end: Int): Int {
        if (tiff + 8 > end || tiff + 8 > b.size) return 1
        val big = when {
            b[tiff] == 'M'.code.toByte() && b[tiff + 1] == 'M'.code.toByte() -> true
            b[tiff] == 'I'.code.toByte() && b[tiff + 1] == 'I'.code.toByte() -> false
            else -> return 1
        }
        val ifd = tiff + u32(b, tiff + 4, big)
        if (ifd < tiff || ifd + 2 > end) return 1
        val count = u16(b, ifd, big)
        for (n in 0 until count) {
            val e = ifd + 2 + n * 12
            if (e + 12 > end || e + 12 > b.size) return 1
            if (u16(b, e, big) == 0x0112) return u16(b, e + 8, big).takeIf { it in 1..8 } ?: 1
        }
        return 1
    }
}
