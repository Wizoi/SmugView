package com.smugview.app.data.api

import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.ServerSocket
import kotlin.concurrent.thread

class HttpClientsTest {
    @get:Rule val tmp = TemporaryFolder()

    /** Serves `count` identical cacheable 200 responses of `bodyBytes` bytes on a loopback port. */
    private fun serve(count: Int, bodyBytes: Int): Pair<ServerSocket, Int> {
        val server = ServerSocket(0)
        thread(isDaemon = true) {
            repeat(count) {
                runCatching {
                    server.accept().use { s ->
                        s.getInputStream().bufferedReader().let { r ->
                            while (r.readLine().orEmpty().isNotEmpty()) { /* skip request headers */ }
                        }
                        val out = s.getOutputStream()
                        out.write(
                            ("HTTP/1.1 200 OK\r\nCache-Control: max-age=31536000\r\n" +
                                "Content-Type: image/jpeg\r\nContent-Length: $bodyBytes\r\n" +
                                "Connection: close\r\n\r\n").toByteArray()
                        )
                        out.write(ByteArray(bodyBytes))
                        out.flush()
                    }
                }
            }
        }
        return server to server.localPort
    }

    // R-37: SmugMug serves originals with max-age=31536000. Downloading them through the shared 50 MB
    // API cache evicted every cached API response that offline browsing depends on.
    @Test
    fun fileDownloads_doNotWriteIntoTheSharedApiCache() {
        val cache = Cache(tmp.newFolder("http_cache"), 50L * 1024 * 1024)
        val shared = OkHttpClient.Builder().cache(cache).build()
        val (server, port) = serve(count = 1, bodyBytes = 200_000)
        val request = Request.Builder().url("http://localhost:$port/original.jpg").build()

        val downloads = shared.forFileDownloads()
        assertNull(downloads.cache)
        downloads.newCall(request).execute().use { assertEquals(200_000, it.body!!.bytes().size) }

        assertEquals("original landed in the shared API cache", 0L, cache.size())
        server.close()
    }

    @Test
    fun sharedClient_doesCacheSuchResponses_soTheAboveAssertionIsMeaningful() {
        val cache = Cache(tmp.newFolder("http_cache2"), 50L * 1024 * 1024)
        val shared = OkHttpClient.Builder().cache(cache).build()
        val (server, port) = serve(count = 1, bodyBytes = 200_000)
        val request = Request.Builder().url("http://localhost:$port/original.jpg").build()

        shared.newCall(request).execute().use { it.body!!.bytes() }

        assertTrue("control failed: shared client did not cache", cache.size() > 100_000)
        server.close()
    }
}
