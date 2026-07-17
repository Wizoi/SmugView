package com.smugview.app.ui.detail

import java.util.Locale

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
        String.format(Locale.US, "%.2f MB", sizeBytes / (1024.0 * 1024.0))
    } else {
        String.format(Locale.US, "%.2f KB", sizeBytes / 1024.0)
    }
}
