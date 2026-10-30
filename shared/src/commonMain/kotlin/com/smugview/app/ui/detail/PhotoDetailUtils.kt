package com.smugview.app.ui.detail

import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Shared helpers for the photo-detail screens (PhotoDetailScreen / SearchPhotoDetailScreen /
 * KeywordPhotoDetailScreen). These were copy-pasted across all three and had begun to drift;
 * centralizing them keeps the behavior identical.
 *
 * NOTE: `formatToLocalTime(...)` remains a top-level function in PhotoDetailScreen.kt (already
 * shared by all three screens). The larger structural duplication (the pager body, EXIF sheet,
 * and action capsule) is a bigger refactor tracked separately.
 */

/** Human-readable file size, e.g. "3.42 MB" / "812.00 KB", or "Unknown" for null. */
fun formatPhotoFileSize(sizeBytes: Long?): String {
    if (sizeBytes == null) return "Unknown"
    return if (sizeBytes > 1024 * 1024) {
        twoDecimals(sizeBytes / (1024.0 * 1024.0)) + " MB"
    } else {
        twoDecimals(sizeBytes / 1024.0) + " KB"
    }
}

/** [value] with two decimals and a point, whatever the device language (what `%.2f` with `Locale.US` gave). */
private fun twoDecimals(value: Double): String {
    val hundredths = abs(value * 100).roundToLong()
    val sign = if (value < 0 && hundredths != 0L) "-" else ""
    return sign + (hundredths / 100) + "." + (hundredths % 100).toString().padStart(2, '0')
}
