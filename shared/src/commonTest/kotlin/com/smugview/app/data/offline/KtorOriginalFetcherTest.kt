package com.smugview.app.data.offline

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KtorOriginalFetcherTest {
    private val fs = createPlatformFileOps()
    private val dir = newTempDir()
    private val part = "$dir/a.part"

    private fun fetcher(handler: suspend io.ktor.client.engine.mock.MockRequestHandleScope.(io.ktor.client.request.HttpRequestData) -> io.ktor.client.request.HttpResponseData) =
        KtorOriginalFetcher(HttpClient(MockEngine(handler)), fs)

    private fun md5Of(bytes: ByteArray): String {
        val m = com.smugview.app.util.Md5()
        m.update(bytes, 0, bytes.size)
        return m.hex()
    }

    @Test
    fun ok_writes_the_file_and_reports_its_md5() = runTest {
        val body = ByteArray(1000) { (it % 251).toByte() }
        val r = fetcher {
            respond(body, HttpStatusCode.OK, io.ktor.http.Headers.build { append(HttpHeaders.ContentType, "image/jpeg"); append(HttpHeaders.ContentLength, "1000") })
        }.fetch("https://photos.smugmug.com/x/o.jpg", part)
        assertIs<FetchResult.Ok>(r)
        assertEquals(1000L, r.bytes)
        assertEquals(md5Of(body), r.md5)
        assertEquals("image/jpeg", r.contentType)
        assertEquals(1000L, fs.length(part))
        assertEquals(r.md5, fs.md5Hex(part))
    }

    @Test
    fun a_large_body_is_streamed_in_chunks_and_matches() = runTest {
        val body = ByteArray(3 * 1024 * 1024 + 17) { (it * 31).toByte() }
        val r = fetcher { respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength, body.size.toString())) }
            .fetch("https://photos.smugmug.com/x/big.mp4", part)
        assertIs<FetchResult.Ok>(r)
        assertEquals(body.size.toLong(), r.bytes)
        assertEquals(md5Of(body), r.md5)
        assertEquals(body.size.toLong(), fs.length(part))
    }

    @Test
    fun a_404_is_an_http_error_not_a_gone_photo() = runTest {
        val r = fetcher { respondError(HttpStatusCode.NotFound) }.fetch("https://photos.smugmug.com/x/o.jpg", part)
        assertIs<FetchResult.HttpError>(r)
        assertEquals(404, r.code)
        assertNull(r.retryAfter)
        assertFalse(r.syntheticCacheMiss)
        assertFalse(fs.isFile(part))
    }

    @Test
    fun a_429_carries_the_retry_after_text() = runTest {
        val r = fetcher { respondError(HttpStatusCode.TooManyRequests, headers = headersOf(HttpHeaders.RetryAfter, "120")) }
            .fetch("https://photos.smugmug.com/x/o.jpg", part)
        assertIs<FetchResult.HttpError>(r)
        assertEquals(429, r.code)
        assertEquals("120", r.retryAfter)
    }

    @Test
    fun a_503_is_an_http_error() = runTest {
        val r = fetcher { respondError(HttpStatusCode.ServiceUnavailable) }.fetch("https://photos.smugmug.com/x/o.jpg", part)
        assertIs<FetchResult.HttpError>(r)
        assertEquals(503, r.code)
    }

    @Test
    fun a_body_shorter_than_declared_is_broken_with_bytes_written() = runTest {
        val channel = ByteChannel(autoFlush = true)
        val r = fetcher {
            // Declares 1000 bytes, sends 400, then the connection closes.
            respond(channel, HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength, "1000"))
        }.let { f ->
            channel.writeFully(ByteArray(400) { 7 })
            channel.close()
            f.fetch("https://photos.smugmug.com/x/o.jpg", part)
        }
        assertIs<FetchResult.Broken>(r)
        assertTrue(r.wroteBytes)
    }

    @Test
    fun a_failure_before_any_body_is_broken_without_bytes() = runTest {
        val r = fetcher { throw kotlinx.io.IOException("no route to host") }.fetch("https://photos.smugmug.com/x/o.jpg", part)
        assertIs<FetchResult.Broken>(r)
        assertFalse(r.wroteBytes)
        assertTrue(r.error is kotlinx.io.IOException)
    }

    @Test
    fun a_redirect_is_followed() = runTest {
        val body = "hello".encodeToByteArray()
        val r = fetcher { request ->
            if (request.url.encodedPath == "/old") {
                respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://photos.smugmug.com/new"))
            } else {
                respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength, "5"))
            }
        }.fetch("https://photos.smugmug.com/old", part)
        assertIs<FetchResult.Ok>(r)
        assertEquals(5L, r.bytes)
    }

    @Test
    fun an_unusable_address_is_no_source() = runTest {
        assertIs<FetchResult.NoSource>(fetcher { respond("x") }.fetch("not a url", part))
        assertIs<FetchResult.NoSource>(fetcher { respond("x") }.fetch("", part))
    }

    @Test
    fun nothing_is_added_to_the_url() = runTest {
        var seen = ""
        fetcher { request -> seen = request.url.toString(); respond("x", HttpStatusCode.OK) }
            .fetch("https://photos.smugmug.com/x/o.jpg", part)
        assertEquals("https://photos.smugmug.com/x/o.jpg", seen)
    }
}
