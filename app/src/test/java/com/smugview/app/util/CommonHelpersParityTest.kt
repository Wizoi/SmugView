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
