package com.smugview.app.data.offline

import okhttp3.Headers
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.CancellationException

/**
 * Step 5-1 (design 2.4): what a failed download means. The old worker (R-40) called every 4xx permanent, so a
 * 429 or a 408 wrote the photo off with `localFilePath = ''`; the table below is the fix, one row per outcome.
 */
class DownloadFailureTest {
    private val now = 1_800_000_000_000L // a fixed instant: this class is pure, it needs no clock of its own

    private fun http(code: Int, vararg headers: Pair<String, String>, synthetic: Boolean = false, reResolved: Boolean = false): Failure? =
        DownloadFailure.classify(
            code, Headers.headersOf(*headers.flatMap { listOf(it.first, it.second) }.toTypedArray()),
            exception = null, wroteBytes = false, syntheticCacheMiss = synthetic, reResolved = reResolved, nowMs = now
        )

    private fun thrown(e: Throwable, wrote: Boolean = false): Failure? =
        DownloadFailure.classify(null, null, e, wroteBytes = wrote, nowMs = now)

    /** (what happened, expected reason, retryable) */
    private data class Case(val name: String, val actual: Failure?, val reason: FailureReason, val retryable: Boolean)

    @Test fun `every outcome of design 2_4 maps to its reason and retryability`() {
        val cases = listOf(
            Case("408", http(408), FailureReason.BUSY, true),
            Case("429", http(429), FailureReason.BUSY, true),
            Case("429 with Retry-After", http(429, "Retry-After" to "120"), FailureReason.BUSY, true),
            Case("500", http(500), FailureReason.BUSY, true),
            Case("503", http(503, "Retry-After" to "30"), FailureReason.BUSY, true),
            Case("a real 504", http(504), FailureReason.BUSY, true),
            Case("synthetic 504", http(504, synthetic = true), FailureReason.OFFLINE, true),
            Case("UnknownHostException", thrown(UnknownHostException("photos.smugmug.com")), FailureReason.OFFLINE, true),
            Case("SocketTimeoutException", thrown(SocketTimeoutException("timeout")), FailureReason.OFFLINE, true),
            Case("ConnectException (no route)", thrown(ConnectException("No route to host")), FailureReason.OFFLINE, true),
            Case("connection reset mid-body", thrown(SocketException("Connection reset"), wrote = true), FailureReason.OFFLINE, true),
            Case("404", http(404), FailureReason.GONE, false),
            Case("410", http(410), FailureReason.GONE, false),
            Case("first 401", http(401), FailureReason.FORBIDDEN, true),
            Case("first 403", http(403), FailureReason.FORBIDDEN, true),
            Case("403 after the re-resolve", http(403, reResolved = true), FailureReason.FORBIDDEN, false),
            Case("401 after the re-resolve", http(401, reResolved = true), FailureReason.FORBIDDEN, false),
            Case("400", http(400), FailureReason.UNEXPECTED, false),
            Case("418", http(418), FailureReason.UNEXPECTED, false),
            Case("ENOSPC while writing", thrown(IOException("write failed: ENOSPC (No space left on device)")), FailureReason.STORAGE_FULL, true),
            Case("ENOSPC as a cause", thrown(IOException("copy failed", IOException("No space left on device"))), FailureReason.STORAGE_FULL, true)
        )
        for (c in cases) {
            assertEquals("${c.name}: reason", c.reason, c.actual?.reason)
            assertEquals("${c.name}: retryable", c.retryable, c.actual?.retryable)
        }
    }

    @Test fun `a 429 is busy and retryable, never the permanent failure the old worker wrote down`() {
        val f = http(429)!!
        assertEquals(FailureReason.BUSY, f.reason)
        assertTrue(f.retryable)
        assertEquals(429, f.httpCode)
    }

    @Test fun `an unexpected 4xx keeps its code for the message`() {
        assertEquals(418, http(418)!!.httpCode)
        assertEquals(400, http(400)!!.httpCode)
    }

    @Test fun `the synthetic 504 from an empty only-if-cached lookup is told apart from a real 504`() {
        fun response(network: Response?): Response =
            Response.Builder().request(Request.Builder().url("https://photos.smugmug.com/x").build()).protocol(Protocol.HTTP_1_1)
                .code(504).message("Unsatisfiable Request (only-if-cached)").networkResponse(network).build()

        val real = Response.Builder().request(Request.Builder().url("https://photos.smugmug.com/x").build()).protocol(Protocol.HTTP_1_1)
            .code(504).message("Gateway Timeout").build()
        val synthetic = response(null)
        val realWrapped = response(real)

        fun classify(r: Response) = DownloadFailure.classify(
            r.code, r.headers, null, false, syntheticCacheMiss = r.networkResponse == null && r.cacheResponse == null
        )
        assertEquals(FailureReason.OFFLINE, classify(synthetic)!!.reason)
        assertEquals(FailureReason.BUSY, classify(realWrapped)!!.reason)
    }

    @Test fun `being offline is not the photos fault and does not count as an attempt`() {
        assertFalse(http(504, synthetic = true)!!.countsAsAttempt)
        assertFalse(thrown(UnknownHostException())!!.countsAsAttempt)
        assertTrue(http(503)!!.countsAsAttempt)
    }

    @Test fun `a body cut off after some bytes keeps the partial file, one that never started does not`() {
        assertTrue(thrown(SocketException("reset"), wrote = true)!!.keepPartial)
        assertFalse(thrown(SocketException("reset"), wrote = false)!!.keepPartial)
    }

    @Test fun `a first 401 or 403 asks for one re-resolve, the second does not`() {
        assertTrue(http(403)!!.reResolve)
        assertFalse(http(403, reResolved = true)!!.reResolve)
    }

    @Test fun `success and cancellation are not failures`() {
        assertNull(http(200))
        assertNull(http(206))
        assertNull(thrown(CancellationException("scope cancelled")))
        assertNull(thrown(kotlinx.coroutines.CancellationException("job cancelled")))
    }

    // --- Retry-After ---

    @Test fun `Retry-After in seconds and in an HTTP-date are read, and the later of it and the backoff wins`() {
        val seconds = http(429, "Retry-After" to "120")!!
        assertEquals(120_000L, seconds.retryAfterMs)
        // backoff(0) is 1 minute: the server's 2 minutes is longer
        assertEquals(120_000L, DownloadFailure.nextAttemptDelayMs(seconds, attempts = 0))

        val date = "Mon, 15 Nov 2027 08:12:31 GMT"
        val atMs = java.time.ZonedDateTime.parse(date, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
        val inTenMinutes = DownloadFailure.classify(
            429, Headers.headersOf("Retry-After", date), null, false, nowMs = atMs - 10 * 60_000L
        )!!
        assertEquals(10 * 60_000L, inTenMinutes.retryAfterMs)

        // backoff(3) is 2 hours: longer than a 30 second Retry-After
        val short = http(503, "Retry-After" to "30")!!
        assertEquals(2 * 60 * 60_000L, DownloadFailure.nextAttemptDelayMs(short, attempts = 3))
    }

    @Test fun `a missing, junk, past or absurd Retry-After never produces a negative or endless wait`() {
        assertNull(http(429)!!.retryAfterMs)
        assertNull(http(429, "Retry-After" to "soon")!!.retryAfterMs)
        assertEquals(0L, http(429, "Retry-After" to "-5")!!.retryAfterMs)
        assertEquals(0L, http(429, "Retry-After" to "Mon, 01 Jan 1990 00:00:00 GMT")!!.retryAfterMs)
        assertEquals(24 * 60 * 60_000L, http(429, "Retry-After" to "999999999")!!.retryAfterMs)
        assertEquals(60_000L, DownloadFailure.nextAttemptDelayMs(http(429, "Retry-After" to "junk")!!, 0))
    }

    // --- backoff ---

    @Test fun `backoff is 1 minute, 5, 30, 2 hours, then the 6 hour cap`() {
        val m = 60_000L
        assertEquals(listOf(1 * m, 5 * m, 30 * m, 120 * m, 360 * m, 360 * m, 360 * m), (0..6).map { DownloadFailure.backoffMs(it) })
    }

    @Test fun `a retryable failure never becomes permanent by count`() {
        val busy = http(503)!!
        assertEquals(6 * 60 * 60_000L, DownloadFailure.nextAttemptDelayMs(busy, attempts = 500))
        assertTrue(busy.retryable)
    }

    @Test fun `the next attempt of each reason`() {
        assertEquals(0L, DownloadFailure.nextAttemptDelayMs(http(504, synthetic = true)!!, 9))
        assertEquals(60 * 60_000L, DownloadFailure.nextAttemptDelayMs(DownloadFailure.storageFull(), 0))
        assertNull(DownloadFailure.nextAttemptDelayMs(http(404)!!, 0))
        assertNull(DownloadFailure.nextAttemptDelayMs(http(418)!!, 0))
        assertNull(DownloadFailure.nextAttemptDelayMs(http(403, reResolved = true)!!, 0))
    }

    // --- verification, locked, no source ---

    @Test fun `a size or MD5 mismatch is damaged, retried once and then permanent`() {
        val md5 = "9e107d9d372bb6826bd81d3542a419d6"
        assertNull(DownloadFailure.verify(100, 100, md5, md5, alreadyRetried = false))
        assertNull("no expectation, nothing to check", DownloadFailure.verify(null, 100, null, null, alreadyRetried = false))
        assertNull("MD5 compare ignores case", DownloadFailure.verify(100, 100, md5.uppercase(), md5, alreadyRetried = false))

        val short = DownloadFailure.verify(100, 99, md5, md5, alreadyRetried = false)!!
        assertEquals(FailureReason.DAMAGED, short.reason)
        assertTrue(short.retryable)
        val bad = DownloadFailure.verify(null, 100, md5, "00000000000000000000000000000000", alreadyRetried = false)!!
        assertEquals(FailureReason.DAMAGED, bad.reason)
        assertFalse("the second damaged download is permanent", DownloadFailure.verify(100, 99, md5, md5, alreadyRetried = true)!!.retryable)
    }

    @Test fun `locked is retryable only when the saved password could not be tried just now`() {
        assertTrue(DownloadFailure.locked(transient = true).retryable)
        assertFalse(DownloadFailure.locked(transient = false).retryable)
        assertEquals(FailureReason.LOCKED, DownloadFailure.locked(false).reason)
    }

    @Test fun `no source is permanent`() {
        val f = DownloadFailure.noSource()
        assertEquals(FailureReason.NO_SOURCE, f.reason)
        assertFalse(f.retryable)
    }
}
