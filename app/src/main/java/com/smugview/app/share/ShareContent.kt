package com.smugview.app.share

import java.io.ByteArrayOutputStream

/** What a share may hand to another app (design 3.10). Pure: no Android classes. */
object ShareContent {
    /** `https` only, and never a CDN host: `photos.smugmug.com` URLs are the photo file itself (R-53). */
    fun isShareable(url: String?): Boolean {
        val u = url?.trim().orEmpty()
        if (!u.startsWith("https://", ignoreCase = true) || u.any { it.isWhitespace() }) return false
        val host = u.substring(8).substringBefore('/').substringBefore('?').substringBefore('#').substringAfterLast('@').substringBefore(':').lowercase()
        return host.isNotEmpty() && host != "photos.smugmug.com" && !host.endsWith(".photos.smugmug.com")
    }

    /**
     * The web page of a photo: its own `WebUri`, else the gallery's `WebUri` plus `/i-{imageKey}` (a photo that came from
     * search, tags, recent or saved has none of its own), else null. Never an `ArchivedUri`.
     */
    fun photoLink(photoWebUri: String?, galleryWebUri: String?, imageKey: String): String? {
        if (isShareable(photoWebUri)) return photoWebUri!!.trim()
        if (imageKey.isBlank() || !isShareable(galleryWebUri)) return null
        return galleryWebUri!!.trim().trimEnd('/') + "/i-" + imageKey
    }

    /** `/api/v2/album/{AlbumKey}` (a photo's `Uris.Album`) to its key, or null. */
    fun albumKeyOf(albumUri: String?): String? =
        albumUri?.substringBefore('?')?.trimEnd('/')?.substringAfterLast("/album/", "")?.takeIf { it.isNotEmpty() && '/' !in it && '!' !in it }
}

/**
 * Removes what a JPEG says about its owner before it leaves the phone (R-53): Exif (camera serials, owner, artist, GPS), XMP, IPTC
 * and the other APPn and COM segments. Kept: JFIF (APP0), the ICC profile (APP2) and Adobe (APP14) because the colours depend on
 * them, and the pixel data byte for byte. The one thing written back is `Orientation`, as a minimal Exif block of its own.
 */
object JpegStrip {
    private const val SOI = 0xD8
    private const val SOS = 0xDA
    private const val EOI = 0xD9

    /** The stripped JPEG, or null when [jpeg] is not a well-formed one (the caller then shares something else). */
    fun strip(jpeg: ByteArray, orientation: Int = 1): ByteArray? {
        if (jpeg.size < 4 || jpeg[0] != 0xFF.toByte() || jpeg[1].toInt() and 0xFF != SOI) return null
        val out = ByteArrayOutputStream(jpeg.size)
        out.write(0xFF); out.write(SOI)
        val exif = if (orientation in 2..8) exifOrientationSegment(orientation) else null
        var exifWritten = exif == null
        var i = 2
        while (true) {
            while (i < jpeg.size && jpeg[i] == 0xFF.toByte() && i + 1 < jpeg.size && jpeg[i + 1] == 0xFF.toByte()) i++
            if (i + 1 >= jpeg.size || jpeg[i] != 0xFF.toByte()) return null
            val type = jpeg[i + 1].toInt() and 0xFF
            if (type == SOS) {
                if (!exifWritten) out.write(exif!!)
                out.write(jpeg, i, jpeg.size - i)
                return out.toByteArray()
            }
            if (type == EOI || type == 0x00) return null
            if (type == 0x01 || type in 0xD0..0xD7) { out.write(jpeg, i, 2); i += 2; continue }
            if (i + 3 >= jpeg.size) return null
            val len = ((jpeg[i + 2].toInt() and 0xFF) shl 8) or (jpeg[i + 3].toInt() and 0xFF)
            if (len < 2 || i + 2 + len > jpeg.size) return null
            val end = i + 2 + len
            if (keeps(type, jpeg, i + 4, end)) {
                if (!exifWritten && type != 0xE0) { out.write(exif!!); exifWritten = true }
                out.write(jpeg, i, end - i)
                if (!exifWritten && type == 0xE0) { out.write(exif!!); exifWritten = true }
            }
            i = end
        }
    }

    private fun keeps(type: Int, b: ByteArray, from: Int, end: Int): Boolean = when (type) {
        0xE0 -> true
        0xE2 -> startsWith(b, from, end, "ICC_PROFILE\u0000")
        0xEE -> startsWith(b, from, end, "Adobe")
        in 0xE1..0xEF, 0xFE -> false
        else -> true
    }

    private fun startsWith(b: ByteArray, from: Int, end: Int, prefix: String): Boolean {
        if (end - from < prefix.length) return false
        return prefix.indices.all { b[from + it] == prefix[it].code.toByte() }
    }

    /** APP1 "Exif" with one IFD0 entry, `Orientation` (0x0112, SHORT). Big-endian TIFF. */
    private fun exifOrientationSegment(orientation: Int): ByteArray = byteArrayOf(
        0xFF.toByte(), 0xE1.toByte(), 0x00, 0x22,
        'E'.code.toByte(), 'x'.code.toByte(), 'i'.code.toByte(), 'f'.code.toByte(), 0, 0,
        'M'.code.toByte(), 'M'.code.toByte(), 0x00, 0x2A, 0, 0, 0, 8,
        0x00, 0x01,
        0x01, 0x12, 0x00, 0x03, 0, 0, 0, 1, 0x00, orientation.toByte(), 0, 0,
        0, 0, 0, 0
    )
}
