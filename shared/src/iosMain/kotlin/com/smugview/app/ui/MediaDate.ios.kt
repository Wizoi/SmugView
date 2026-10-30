package com.smugview.app.ui

import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSISO8601DateFormatter
import platform.Foundation.NSLocale
import platform.Foundation.currentLocale

private val EXIF = Regex("^\\d{4}:\\d{2}:\\d{2} \\d{2}:\\d{2}:\\d{2}$")

private fun parser(pattern: String) = NSDateFormatter().apply {
    dateFormat = pattern
    locale = NSLocale("en_US_POSIX")
}

actual fun formatDateTimeForDisplay(trimmed: String): String? {
    val date: NSDate? =
        (if (EXIF.matches(trimmed)) parser("yyyy:MM:dd HH:mm:ss").dateFromString(trimmed) else null)
            ?: NSISO8601DateFormatter().dateFromString(trimmed)
            ?: parser("yyyy-MM-dd'T'HH:mm:ss").dateFromString(trimmed)
    if (date == null) return null
    val out = NSDateFormatter().apply {
        dateFormat = "MMM dd, yyyy\nhh:mm a"
        locale = NSLocale.currentLocale
    }
    return out.stringFromDate(date)
}
