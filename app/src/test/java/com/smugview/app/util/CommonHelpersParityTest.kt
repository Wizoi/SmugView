package com.smugview.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI
import java.time.OffsetDateTime

/**
 * iOS port, Stage 1: the repository moved to common code, where `java.time`, `java.net.URI` and `ConcurrentHashMap` do not exist.
 * The replacements are checked here against the JVM originals they replace, on the shapes SmugMug really sends.
 */
class CommonHelpersParityTest {
    private val timestamps = listOf(
        "2026-10-27T12:00:00+00:00", "2026-10-27T12:00:00Z", "2026-10-27T05:00:00-07:00", "2026-03-08T23:59:59+05:30",
        "2024-02-29T00:00:00+00:00", "2100-12-31T23:59:59+00:00", "1969-12-31T23:59:59+00:00", "1970-01-01T00:00:00+00:00",
        "2026-10-27T12:00:00.123+00:00", "2026-10-27T12:00:00.5Z", "2026-01-01T00:00:00+14:00", "2026-06-30T10:10:10-12:00",
        "2000-02-29T12:00:00+00:00", "1900-03-01T00:00:00+00:00"
    )

    @Test fun `timestamps read the same as java time`() {
        for (t in timestamps) {
            assertEquals(t, OffsetDateTime.parse(t).toInstant().toEpochMilli(), Iso8601.epochMillisOrNull(t))
        }
    }

    @Test fun `isBefore agrees with java time and is false for text that is not a timestamp`() {
        for (a in timestamps) for (b in timestamps) {
            val expected = OffsetDateTime.parse(a).toInstant().isBefore(OffsetDateTime.parse(b).toInstant())
            assertEquals("$a < $b", expected, Iso8601.isBefore(a, b))
        }
        assertEquals(false, Iso8601.isBefore("yesterday", "2026-10-27T12:00:00Z"))
        assertEquals(false, Iso8601.isBefore("2026-10-27T12:00:00Z", ""))
        assertNull(Iso8601.epochMillisOrNull(null))
        assertNull(Iso8601.epochMillisOrNull("2026-13-45T00:00:00Z"))
    }

    private val addresses = listOf(
        "https://gallery.idzifamily.com/Family/School/Class-photos",
        "https://gallery.idzifamily.com/Family/School/Class-photos/",
        "https://idzifamily.smugmug.com/Track-and-Field/2026-05-08-KR-Track-at-Liberty/n-Z4kFvh/",
        "https://example.smugmug.com/",
        "https://example.smugmug.com",
        "https://example.smugmug.com/a%20b/c?x=1#frag",
        "https://example.smugmug.com/Sp%C3%A9cial/Caf%C3%A9"
    )

    @Test fun `host and path read the same as java net URI`() {
        for (a in addresses) {
            val uri = URI(a)
            assertEquals(a, uri.host, UrlParts.hostOrNull(a))
            assertEquals(a, uri.path.orEmpty().trimEnd('/'), UrlParts.pathOrNull(a)?.trimEnd('/').orEmpty())
        }
    }

    @Test fun `the concurrent map keeps what coroutines put in it`() {
        val map = ConcurrentMap<String, Int>()
        val threads = (1..8).map { n -> Thread { repeat(500) { map.getOrPut("k${it % 10}") { n } } } }
        threads.forEach { it.start() }; threads.forEach { it.join() }
        assertTrue((0..9).all { map["k$it"] != null })
        map.clear()
        assertNull(map["k0"])
    }

    @Test fun `the copy on write list can be read while it is written`() {
        val list = CopyOnWriteList<Int>()
        val writer = Thread { repeat(2000) { list.add(it) } }
        writer.start()
        var reads = 0
        while (writer.isAlive) { reads += list.sumOf { it }.let { 1 } } // iterating must never throw ConcurrentModificationException
        writer.join()
        assertEquals(2000, list.size)
        assertTrue(reads > 0)
    }
}

/** The `Retry-After` HTTP date and the size text, against the `java.time` and `String.format` they replaced. */
class OfflineCommonParityTest {
    @Test fun `http dates read the same as java time rfc 1123`() {
        val dates = listOf(
            "Mon, 15 Nov 2027 08:12:31 GMT", "Wed, 21 Oct 2015 07:28:00 GMT", "Sun, 06 Nov 1994 08:49:37 GMT",
            "Thu, 01 Jan 1970 00:00:00 GMT", "Sat, 29 Feb 2020 23:59:59 GMT", "Fri, 31 Dec 2100 12:00:00 +0200",
            "Tue, 5 Mar 2024 01:02:03 -0700"
        )
        for (d in dates) {
            val expected = java.time.ZonedDateTime.parse(d, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
            assertEquals(d, expected, HttpDate.epochMillisOrNull(d))
        }
        assertNull(HttpDate.epochMillisOrNull("soon"))
        assertNull(HttpDate.epochMillisOrNull("120"))
    }

    @Test fun `size text is the same as the old String format`() {
        fun old(bytes: Long): String {
            val kb = 1024.0
            return when {
                bytes >= kb * kb * kb -> String.format(java.util.Locale.ROOT, "%.1f GB", bytes / (kb * kb * kb))
                bytes >= kb * kb -> String.format(java.util.Locale.ROOT, "%d MB", Math.round(bytes / (kb * kb)))
                else -> String.format(java.util.Locale.ROOT, "%d KB", maxOf(1L, Math.round(bytes / kb)))
            }
        }
        val samples = listOf(0L, 1L, 511L, 512L, 1023L, 1024L, 1536L, 999_999L, 1_048_575L, 1_048_576L, 1_572_864L, 105_906_176L,
            1_073_741_823L, 1_073_741_824L, 1_288_490_189L, 1_610_612_736L, 5_000_000_000L, 123_456_789_012L)
        for (b in samples) assertEquals("$b", old(b), com.smugview.app.data.offline.OfflineMessages.size(b))
    }
}
