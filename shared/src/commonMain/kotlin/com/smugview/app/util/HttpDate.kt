package com.smugview.app.util

/**
 * An HTTP date (`Mon, 15 Nov 2027 08:12:31 GMT`, the form of a `Retry-After` header; the day of the week is optional, `GMT`, `UT`, `Z` or a
 * numeric offset ends it) as epoch milliseconds, without `java.time`. Null when the text is not such a date.
 */
object HttpDate {
    private val months = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
    private val shape = Regex("""^(?:[A-Za-z]{3},\s*)?(\d{1,2})\s+([A-Za-z]{3})\s+(\d{4})\s+(\d{2}):(\d{2})(?::(\d{2}))?\s+(GMT|UT|Z|[+-]\d{4})$""")

    fun epochMillisOrNull(text: String?): Long? {
        val m = shape.matchEntire(text?.trim() ?: return null) ?: return null
        val g = m.groupValues
        val month = months.indexOf(g[2].lowercase()) + 1
        if (month == 0) return null
        val day = g[1].toInt(); val year = g[3].toInt()
        val hour = g[4].toInt(); val minute = g[5].toInt(); val second = if (g[6].isEmpty()) 0 else g[6].toInt()
        if (day !in 1..31 || hour > 23 || minute > 59 || second > 59) return null
        val offsetMinutes = if (g[7][0] == '+' || g[7][0] == '-') {
            (if (g[7][0] == '-') -1 else 1) * (g[7].substring(1, 3).toInt() * 60 + g[7].substring(3).toInt())
        } else 0
        return Iso8601.civilToEpochSeconds(year, month, day, hour, minute, second, offsetMinutes) * 1000L
    }
}
