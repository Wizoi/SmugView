package com.smugview.app.share

/**
 * The size prompt of "Share picture" (owner, 2026-11-06): Mobile size or Original size, asked every time. Pure, so both platforms agree.
 *
 * Original is offered whenever the original can be fetched, with a warning where it matters: a file that is not a JPEG keeps its camera
 * details and location (the app can only strip a JPEG), and a very large file is flagged. A video never gets an Original: its
 * `ArchivedUri` is a JPEG still, not the video (CLAUDE.md).
 */
object ShareSizes {
    /** From this size up an original is flagged as large. */
    const val LARGE_BYTES = 25_000_000L

    enum class OriginalNote { None, KeepsCameraData, Large }

    class Choices(val originalOffered: Boolean, val originalBytes: Long?, val note: OriginalNote)

    fun choices(isVideo: Boolean, format: String?, archivedSize: Long?, hasOriginalSource: Boolean): Choices {
        if (isVideo || !hasOriginalSource) return Choices(false, null, OriginalNote.None)
        val jpeg = format?.trim()?.lowercase().let { it == "jpg" || it == "jpeg" }
        val note = when {
            !jpeg -> OriginalNote.KeepsCameraData
            archivedSize != null && archivedSize >= LARGE_BYTES -> OriginalNote.Large
            else -> OriginalNote.None
        }
        return Choices(true, archivedSize, note)
    }

    /** "4.2 MB", "428 KB", or "" when the size is not known. */
    fun sizeText(bytes: Long?): String {
        if (bytes == null || bytes < 0) return ""
        if (bytes >= 1_000_000L) {
            val tenths = (bytes + 50_000L) / 100_000L
            return "${tenths / 10}.${tenths % 10} MB"
        }
        return "${(bytes + 500L) / 1000L} KB"
    }
}
