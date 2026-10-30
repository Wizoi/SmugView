package com.smugview.app.ui

import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

private val OUT: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM dd, yyyy\nhh:mm a")
private val EXIF = Regex("^\\d{4}:\\d{2}:\\d{2} \\d{2}:\\d{2}:\\d{2}$")

actual fun formatDateTimeForDisplay(trimmed: String): String? {
    if (EXIF.matches(trimmed)) {
        try {
            return LocalDateTime.parse(trimmed, DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss")).format(OUT)
        } catch (e: Exception) {
            // fall through
        }
    }
    try {
        return ZonedDateTime.parse(trimmed).withZoneSameInstant(ZoneId.systemDefault()).format(OUT)
    } catch (e: Exception) {
        // fall through
    }
    try {
        return LocalDateTime.parse(trimmed).format(OUT)
    } catch (e: Exception) {
        // fall through
    }
    return null
}
