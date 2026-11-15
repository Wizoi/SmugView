package com.smugview.app.data.api

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tester feedback (2026-11-14): in the tag search and the Search tab an earlier picture showed up after a later one. Premise check against
 * the live API: the `Date` field is the UPLOAD time (it equals `DateTimeUploaded` in every gallery checked), while the info sheet shows the
 * camera's own time (`DateTimeOriginal`). The sort must follow when the photo was taken, falling back to the upload time when it has none.
 */
class PhotoSortTest {
    private fun photo(key: String, taken: String? = null, uploaded: String? = null) =
        AlbumImageData(imageKey = key, date = uploaded, dateTimeOriginal = taken)

    // The real shape of gallery nMmnCR: all uploaded on one day in July, shot between March 21 and 25.
    private val upload = "2014-07-15T14:35:34+00:00"
    private val a = photo("shot-first", taken = "2014-03-21T08:45:31+00:00", uploaded = "2014-07-15T16:07:10+00:00")
    private val b = photo("shot-second", taken = "2014-03-22T09:00:00+00:00", uploaded = upload)
    private val c = photo("shot-third", taken = "2014-03-25T21:54:06+00:00", uploaded = "2014-07-15T14:35:35+00:00")

    @Test fun `ascending follows when the photos were taken, not when they were uploaded`() {
        assertEquals(listOf("shot-first", "shot-second", "shot-third"), listOf(c, a, b).sortedByCapture(ascending = true).map { it.imageKey })
    }

    @Test fun `descending is the reverse`() {
        assertEquals(listOf("shot-third", "shot-second", "shot-first"), listOf(a, c, b).sortedByCapture(ascending = false).map { it.imageKey })
    }

    @Test fun `a photo with no capture time falls back to its upload time`() {
        val noCamera = photo("no-exif", taken = null, uploaded = "2014-03-23T00:00:00+00:00")
        assertEquals(listOf("shot-first", "shot-second", "no-exif", "shot-third"), listOf(c, noCamera, a, b).sortedByCapture(true).map { it.imageKey })
    }

    @Test fun `times with different offsets are compared as instants, not as text`() {
        val early = photo("early", taken = "2026-01-01T10:00:00-08:00")  // 18:00 UTC
        val late = photo("late", taken = "2026-01-01T17:30:00+00:00")     // 17:30 UTC, earlier
        assertEquals(listOf("late", "early"), listOf(early, late).sortedByCapture(true).map { it.imageKey })
    }

    @Test fun `a photo with no date at all sorts first ascending and last descending`() {
        val none = photo("none")
        assertEquals("none", listOf(a, none, b).sortedByCapture(true).first().imageKey)
        assertEquals("none", listOf(a, none, b).sortedByCapture(false).last().imageKey)
    }

    @Test fun `the same instant keeps the order the photos came in`() {
        val x = photo("x", taken = "2026-05-01T12:00:00+00:00")
        val y = photo("y", taken = "2026-05-01T12:00:00+00:00")
        assertEquals(listOf("x", "y"), listOf(x, y).sortedByCapture(true).map { it.imageKey })
    }

    @Test fun `a plain date counts as midnight UTC, so it still sorts next to full timestamps`() {
        val plain = photo("plain", uploaded = "2026-07-05")
        val full = photo("full", taken = "2026-07-05T12:00:00+00:00")
        val earlier = photo("earlier", uploaded = "2026-07-01")
        assertEquals(listOf("earlier", "plain", "full"), listOf(full, plain, earlier).sortedByCapture(true).map { it.imageKey })
    }
}
