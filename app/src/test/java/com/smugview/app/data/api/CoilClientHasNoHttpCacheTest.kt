package com.smugview.app.data.api

import com.smugview.app.di.buildImageCallFactory
import com.smugview.app.di.buildSmugMugClient
import okhttp3.Call
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.ServerSocket
import kotlin.concurrent.thread

/**
 * Phase 4 step 4-11 (design 3.7, R-37): Coil loaded images through the same client as the API, so the 50 MB
 * HTTP cache that API JSON depends on (offline browsing) also held every image the CDN serves with
 * `public, max-age=31536000`. 4-0 stopped the downloads from doing that; this pins the same for the image loader.
 * Offline thumbnails then come from Coil's own disk cache only.
 */
class CoilClientHasNoHttpCacheTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var cdn: ServerSocket
    private val bytes = 1_000_000

    /** A CDN: one cacheable 1 MB JPEG per connection, as photos.smugmug.com serves them. */
    @Before fun startCdn() {
        cdn = ServerSocket(0)
        thread(isDaemon = true) {
            while (!cdn.isClosed) {
                runCatching {
                    cdn.accept().use { s ->
                        s.getInputStream().bufferedReader().let { r -> while (r.readLine().orEmpty().isNotEmpty()) { /* headers */ } }
                        s.getOutputStream().apply {
                            write(
                                ("HTTP/1.1 200 OK\r\nCache-Control: public, max-age=31536000\r\nContent-Type: image/jpeg\r\n" +
                                    "Content-Length: $bytes\r\nConnection: close\r\n\r\n").toByteArray()
                            )
                            write(ByteArray(bytes))
                            flush()
                        }
                    }
                }
            }
        }
    }

    @After fun stopCdn() { cdn.close() }

    /** The production client: real cache, cookie jar, cache rewrite, offline fallback. */
    private fun apiClient() = buildSmugMugClient(
        cacheDir = tmp.newFolder("http_cache"), isOnline = { true }, cookieJar = SessionCookieJar(CredentialEpoch()),
        epoch = CredentialEpoch(), loggingInterceptor = Interceptor { it.proceed(it.request()) }
    )

    private fun load(factory: Call.Factory) {
        val request = Request.Builder().url("http://localhost:${cdn.localPort}/photos/i-AbCdEf/0/X3/i-AbCdEf-X3.jpg").build()
        factory.newCall(request).execute().use { assertEquals(bytes, it.body!!.bytes().size) }
    }

    @Test fun `an image loaded through the image loader's factory leaves the API cache untouched`() {
        val client = apiClient()
        val before = client.cache!!.size()

        load(buildImageCallFactory(client))

        assertEquals("the image landed in the API cache", before, client.cache!!.size())
    }

    @Test fun `control - through the API's own factory the same image does fill the cache`() {
        val client = apiClient()

        load(RetryingCallFactory(client))

        assertTrue("control failed: the shared client did not cache the image (${client.cache!!.size()})", client.cache!!.size() > bytes / 2)
    }

    @Test fun `the image factory is still a production client apart from the cache`() {
        val client = apiClient()
        val images = buildImageCallFactory(client)
        load(images)
        // Same pool and same interceptor chain: only the cache differs.
        val downloads = client.forFileDownloads()
        assertTrue(downloads.cache == null)
        assertEquals(client.interceptors.size, downloads.interceptors.size)
        assertEquals(client.networkInterceptors.size, downloads.networkInterceptors.size)
        assertTrue(downloads.connectionPool === client.connectionPool)
    }
}
