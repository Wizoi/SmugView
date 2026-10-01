package com.smugview.app.data.offline

import okhttp3.Headers
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.concurrent.CancellationException

/** Why a file did not arrive (phase 5 design 2.4). The names are stored in `offline_files.failure`. */
enum class FailureReason { OFFLINE, BUSY, STORAGE_FULL, GONE, FORBIDDEN, LOCKED, DAMAGED, UNEXPECTED, NO_SOURCE }

/**
 * One classified failure.
 *
 * @param retryable the file is tried again by itself; a non-retryable failure waits for the user ("Try again" or "Remove").
 * @param countsAsAttempt false for [FailureReason.OFFLINE]: not being connected is not the photo's fault.
 * @param retryAfterMs the server's `Retry-After` in milliseconds, when it sent a usable one.
 * @param httpCode the HTTP status, kept for [FailureReason.UNEXPECTED] ("error 418").
 * @param reResolve true for a first 401/403: look the source up again once before giving up.
 * @param keepPartial a body was cut off after some bytes arrived: the `.part` may be kept.
 */
data class Failure(
    val reason: FailureReason,
    val retryable: Boolean,
    val countsAsAttempt: Boolean = true,
    val retryAfterMs: Long? = null,
    val httpCode: Int? = null,
    val reResolve: Boolean = false,
    val keepPartial: Boolean = false
)

/**
 * The one place that decides what a failed download means (R-40: the old worker called every 4xx permanent,
 * so a 429 or 408 wrote the photo off). Pure: no clock except `nowMs`, no I/O, no Android.
 */
object DownloadFailure {
    private const val MINUTE = 60_000L
    private const val HOUR = 60 * MINUTE

    /** Delays after the 1st, 2nd, 3rd and 4th failed try; the 5th and later wait the cap. */
    private val BACKOFF_MS = longArrayOf(1 * MINUTE, 5 * MINUTE, 30 * MINUTE, 2 * HOUR)
    const val BACKOFF_CAP_MS = 6 * HOUR

    /** A bogus `Retry-After` ("999999999") must not park a photo for years. */
    private const val RETRY_AFTER_MAX_MS = 24 * HOUR

    /** Wait after a full disk before looking again (the storage constraint also gates it). */
    const val STORAGE_RETRY_MS = 1 * HOUR

    /** Delay before try number `attempts + 1`, where [attempts] counts failed tries so far (0 = none yet). */
    fun backoffMs(attempts: Int): Long =
        if (attempts < 0) BACKOFF_MS[0] else if (attempts < BACKOFF_MS.size) BACKOFF_MS[attempts] else BACKOFF_CAP_MS

    /**
     * Classifies one attempt. [code] is the HTTP status when a response arrived (else null) and [exception] what
     * was thrown (else null). Returns null when it was not a failure: a 2xx, or a [CancellationException] (the
     * caller resets the row to PENDING and rethrows).
     *
     * @param syntheticCacheMiss OkHttp's own 504 for `only-if-cached` with nothing cached (findings #6): the
     *   device is offline, the server never answered. A real 504 is [FailureReason.BUSY].
     * @param wroteBytes some of the body had been written when it failed.
     * @param reResolved the source was already looked up again after a 401/403.
     */
    fun classify(
        code: Int?,
        headers: Headers?,
        exception: Throwable?,
        wroteBytes: Boolean,
        syntheticCacheMiss: Boolean = false,
        reResolved: Boolean = false,
        nowMs: Long = System.currentTimeMillis()
    ): Failure? {
        if (exception is CancellationException) return null
        if (exception != null) {
            if (isNoSpace(exception)) return storageFull()
            return Failure(FailureReason.OFFLINE, retryable = true, countsAsAttempt = false, keepPartial = wroteBytes)
        }
        val c = code ?: return null
        return when {
            c in 200..299 -> null
            c == 504 && syntheticCacheMiss -> Failure(FailureReason.OFFLINE, retryable = true, countsAsAttempt = false)
            c == 408 || c == 429 || c in 500..599 ->
                Failure(FailureReason.BUSY, retryable = true, retryAfterMs = retryAfterMs(headers, nowMs), httpCode = c)
            c == 404 || c == 410 -> Failure(FailureReason.GONE, retryable = false, httpCode = c)
            c == 401 || c == 403 ->
                if (reResolved) Failure(FailureReason.FORBIDDEN, retryable = false, httpCode = c)
                else Failure(FailureReason.FORBIDDEN, retryable = true, httpCode = c, reResolve = true)
            else -> Failure(FailureReason.UNEXPECTED, retryable = false, httpCode = c)
        }
    }

    /** The disk filled up: waits for the storage constraint, and for [STORAGE_RETRY_MS]. */
    fun storageFull() = Failure(FailureReason.STORAGE_FULL, retryable = true)

    /** The listing or re-resolve found the gallery locked; [transient] when the saved password just could not be tried. */
    fun locked(transient: Boolean) = Failure(FailureReason.LOCKED, retryable = transient)

    /** An image key that fails the pattern, or no source after a resolve. */
    fun noSource() = Failure(FailureReason.NO_SOURCE, retryable = false)

    /**
     * Checks a finished download. Null when it matches what the listing promised (a null expectation is not
     * checked). A mismatch is [FailureReason.DAMAGED]: retried once ([alreadyRetried] false), then permanent.
     */
    fun verify(
        expectedBytes: Long?,
        actualBytes: Long,
        expectedMd5: String?,
        actualMd5: String?,
        alreadyRetried: Boolean
    ): Failure? {
        val sizeBad = expectedBytes != null && expectedBytes != actualBytes
        val md5Bad = expectedMd5 != null && actualMd5 != null && !expectedMd5.equals(actualMd5, ignoreCase = true)
        if (!sizeBad && !md5Bad) return null
        return Failure(FailureReason.DAMAGED, retryable = !alreadyRetried)
    }

    /**
     * When to try a retryable [failure] again, as a delay from now. [attempts] counts the failed tries before this
     * one. Null for a failure that is not retryable. [FailureReason.OFFLINE] is 0: the network constraint is the wait.
     */
    fun nextAttemptDelayMs(failure: Failure, attempts: Int): Long? {
        if (!failure.retryable) return null
        return when (failure.reason) {
            FailureReason.OFFLINE -> 0L
            FailureReason.STORAGE_FULL -> STORAGE_RETRY_MS
            FailureReason.BUSY -> maxOf(failure.retryAfterMs ?: 0L, backoffMs(attempts))
            FailureReason.FORBIDDEN -> 0L
            else -> backoffMs(attempts)
        }
    }

    /** `Retry-After` as milliseconds: delta-seconds or an HTTP-date; null when absent or unparseable; never negative. */
    internal fun retryAfterMs(headers: Headers?, nowMs: Long): Long? {
        val raw = headers?.get("Retry-After")?.trim().orEmpty()
        if (raw.isEmpty()) return null
        raw.toLongOrNull()?.let { return (it * 1000L).coerceIn(0L, RETRY_AFTER_MAX_MS) }
        return try {
            val at = ZonedDateTime.parse(raw, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
            (at.toEpochMilli() - Instant.ofEpochMilli(nowMs).toEpochMilli()).coerceIn(0L, RETRY_AFTER_MAX_MS)
        } catch (e: DateTimeParseException) {
            null
        }
    }

    private fun isNoSpace(e: Throwable): Boolean {
        var t: Throwable? = e
        var depth = 0
        while (t != null && depth < 8) {
            val m = t.message.orEmpty()
            if (m.contains("ENOSPC") || m.contains("No space left on device", ignoreCase = true)) return true
            t = t.cause
            depth++
        }
        return false
    }
}
