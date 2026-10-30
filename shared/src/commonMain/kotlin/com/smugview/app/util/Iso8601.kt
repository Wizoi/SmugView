package com.smugview.app.util

/**
 * SmugMug's timestamps (`2026-10-27T12:00:00+00:00`, also `Z`, a fraction of a second, or no offset, which is read as UTC) as epoch
 * milliseconds, without `java.time`. A value that is not a timestamp is null.
 */
object Iso8601 {
    private val shape = Regex("""^(\d{4})-(\d{2})-(\d{2})[T ](\d{2}):(\d{2}):(\d{2})(\.\d+)?(Z|[+-]\d{2}:?\d{2})?$""")

    fun epochMillisOrNull(text: String?): Long? {
        val m = shape.matchEntire(text?.trim() ?: return null) ?: return null
        val g = m.groupValues
        val year = g[1].toInt(); val month = g[2].toInt(); val day = g[3].toInt()
        val hour = g[4].toInt(); val minute = g[5].toInt(); val second = g[6].toInt()
        if (month !in 1..12 || day !in 1..31 || hour > 23 || minute > 59 || second > 59) return null
        val millis = if (g[7].isEmpty()) 0L else (g[7].drop(1).padEnd(3, '0').take(3)).toLong()
        val offsetMinutes = when {
            g[8].isEmpty() || g[8] == "Z" -> 0
            else -> {
                val sign = if (g[8][0] == '-') -1 else 1
                val digits = g[8].drop(1).replace(":", "")
                sign * (digits.take(2).toInt() * 60 + digits.drop(2).toInt())
            }
        }
        // days since 1970-01-01 from a civil date (Howard Hinnant's algorithm)
        val y = if (month <= 2) year - 1 else year
        val era = (if (y >= 0) y else y - 399) / 400
        val yoe = y - era * 400
        val doy = (153 * (if (month > 2) month - 3 else month + 9) + 2) / 5 + day - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        val days = era * 146_097L + doe - 719_468L
        val seconds = days * 86_400L + hour * 3_600L + minute * 60L + second - offsetMinutes * 60L
        return seconds * 1000L + millis
    }

    /** True when [a] is strictly earlier than [b]; false when either is not a timestamp. */
    fun isBefore(a: String, b: String): Boolean {
        val x = epochMillisOrNull(a) ?: return false
        val y = epochMillisOrNull(b) ?: return false
        return x < y
    }
}
