package com.smugview.app.data.offline

/** What one GET of an original came to. */
sealed interface FetchResult {
    /** The whole body is in the file: [bytes] long, [md5] lowercase hex, [contentType] as the server sent it. */
    class Ok(val bytes: Long, val md5: String, val contentType: String?) : FetchResult

    /** The server answered with a status that is not a success. [syntheticCacheMiss] marks the 504 an `only-if-cached` request gets. */
    class HttpError(val code: Int, val retryAfter: String?, val syntheticCacheMiss: Boolean) : FetchResult

    /** The transfer broke (no network, a dropped connection, a body shorter than declared, a full disk); [wroteBytes] says whether the file has data. */
    class Broken(val error: Throwable, val wroteBytes: Boolean) : FetchResult

    /** The address was not usable, or the answer had no body. */
    object NoSource : FetchResult
}

/**
 * One GET of an original (a photo or a video file) written to [part] in chunks, verified by MD5 as it goes. Android sends it through the
 * cache-less OkHttp stack; iOS through Ktor. Cancelling the calling coroutine cancels the transfer.
 */
interface OriginalFetcher {
    suspend fun fetch(url: String, part: String): FetchResult
}
