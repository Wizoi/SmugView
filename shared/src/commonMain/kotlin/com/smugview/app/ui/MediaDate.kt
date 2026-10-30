package com.smugview.app.ui

/**
 * A photo's date and time as shown in the info sheet: "MMM dd, yyyy" and "hh:mm a" on two lines, in the device's zone and language.
 * [trimmed] is an EXIF time ("2026:07:06 00:18:44", no zone), an ISO time with a zone ("2026-07-06T00:18:44Z") or one without
 * ("2026-07-06T00:18:44"). Null when it is none of those.
 */
expect fun formatDateTimeForDisplay(trimmed: String): String?
