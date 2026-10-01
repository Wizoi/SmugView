package com.smugview.app.diag

import com.smugview.app.data.api.CallOutcome
import com.smugview.app.data.api.RetryingCallFactory
import okhttp3.Cache
import okhttp3.CacheControl
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import kotlin.concurrent.thread

class HttpTelemetryTest {
    @get:Rule val tmp = TemporaryFolder()

    private val sink = RecordingSink()
    private val redactor = Redactor().also { it.setSecrets(listOf("hunter22xyz")) }
    private val log = DiagLog(sink, redactor)
    private val telemetry = HttpTelemetry(log)

    private fun httpLines() = sink.lines.filter { it.contains(" http [") }

    /** Serves the given raw HTTP responses, one per connection, on a loopback port. */
    private fun serve(vararg responses: String): Pair<ServerSocket, Int> {
        val server = ServerSocket(0)
        thread(isDaemon = true) {
            for (raw in responses) {
                runCatching {
                    server.accept().use { s ->
                        val r = s.getInputStream().bufferedReader()
                        while (r.readLine().orEmpty().isNotEmpty()) { /* skip request headers */ }
                        s.getOutputStream().apply { write(raw.toByteArray()); flush() }
                    }
                }
            }
        }
        return server to server.localPort
    }

    private fun ok(body: String = "hello") =
        "HTTP/1.1 200 OK\r\nCache-Control: max-age=31536000\r\n" +
            "Content-Length: ${body.length}\r\nConnection: close\r\n\r\n$body"

    private val revalidatable =
        "HTTP/1.1 200 OK\r\nCache-Control: max-age=0\r\nETag: \"v1\"\r\nContent-Length: 2\r\nConnection: close\r\n\r\nhi"
    private val notModified = "HTTP/1.1 304 Not Modified\r\nETag: \"v1\"\r\nConnection: close\r\n\r\n"

    /** A client whose cache is real; every host (api.smugmug.com included) resolves to loopback. */
    private fun factory(maxAttempts: Int = 0, actionId: () -> String? = { null }): RetryingCallFactory {
        val client = OkHttpClient.Builder()
            .cache(Cache(tmp.newFolder(), 10L * 1024 * 1024))
            .dns(object : Dns {
                override fun lookup(hostname: String) = listOf(InetAddress.getLoopbackAddress())
            })
            .build()
        return RetryingCallFactory(
            delegate = client, maxAttempts = maxAttempts, initialDelayMs = 5,
            actionIdProvider = actionId, onComplete = telemetry::record
        )
    }

    private fun get(f: RetryingCallFactory, url: String, cc: CacheControl? = null): Int {
        val req = Request.Builder().url(url).apply { if (cc != null) cacheControl(cc) }.build()
        return try {
            f.newCall(req).execute().use { it.body!!.string(); it.code }
        } catch (e: IOException) {
            -1
        }
    }

    private fun fakeResponse(url: String, code: Int): Response =
        Response.Builder().request(Request.Builder().url(url).build()).protocol(Protocol.HTTP_1_1)
            .code(code).message("m").build()

    private fun outcome(
        url: String, code: Int? = 200, error: IOException? = null, canceled: Boolean = false,
        ms: Long = 20, attempts: Int = 1, codes: List<Int> = listOfNotNull(code), actionId: String? = null
    ) = CallOutcome(
        Request.Builder().url(url).build(), code?.let { fakeResponse(url, it) }, error, canceled,
        attempts, codes, ms, actionId
    )

    // ---- classification and line format, against a real Cache ----

    @Test fun firstCallIsNetwork_secondIsCache_withActionIdAndNoQueryValuesLeaked() {
        val (server, port) = serve(ok())
        val f = factory(actionId = { "folder#4" })
        val url = "http://api.smugmug.com:$port/api/v2/node/2sDN5x!children?APIKey=SECRETKEY99&start=1&count=100"

        assertEquals(200, get(f, url))
        assertEquals(200, get(f, url))
        server.close()

        val lines = httpLines()
        assertEquals(lines.toString(), 2, lines.size)
        val expected = Regex(""" I http \[folder#4] GET /node/2sDN5x!children\?start=1&count=100&\+APIKey -> 200 NETWORK \d+ms tries=1$""")
        assertTrue(lines[0], expected.containsMatchIn(lines[0]))
        assertTrue(lines[1], lines[1].contains("-> 200 CACHE "))
        assertFalse(lines.joinToString("\n").contains("SECRETKEY99"))
    }

    @Test fun revalidatedResponse_isConditional() {
        val (server, port) = serve(revalidatable, notModified)
        val f = factory()
        val url = "http://api.smugmug.com:$port/api/v2/album/Ab12Cd"

        get(f, url)
        get(f, url)
        server.close()

        val lines = httpLines()
        assertTrue(lines[0], lines[0].contains("-> 200 NETWORK "))
        assertTrue(lines[1], lines[1].contains("-> 200 CONDITIONAL "))
    }

    @Test fun onlyIfCachedMiss_isSynthetic504_offline_andIsNotRetried() {
        val (server, port) = serve() // never contacted
        val f = factory(maxAttempts = 2)

        val code = get(
            f, "http://api.smugmug.com:$port/api/v2/node/zz9!children",
            CacheControl.Builder().onlyIfCached().build()
        )
        server.close()

        assertEquals(504, code)
        val line = httpLines().single()
        assertTrue(line, line.contains(" W http [-] GET /node/zz9!children -> 504 SYNTHETIC_504 "))
        assertTrue(line, line.endsWith(" tries=1 offline=1"))
    }

    @Test fun ioFailure_isLoggedAsErrWithTheClassName() {
        telemetry.record(
            outcome(
                "https://api.smugmug.com/api/v2/user/kev!albums", code = null,
                error = SocketTimeoutException("timeout"), codes = emptyList()
            )
        )
        val line = httpLines().single()
        assertTrue(line, line.contains(" W http [-] GET /user/kev!albums -> ERR:SocketTimeoutException NONE "))
    }

    @Test fun canceledCall_isLoggedAsCanceled_atInfo() {
        telemetry.record(outcome("https://api.smugmug.com/api/v2/node/a!children", code = null, canceled = true, codes = emptyList()))
        val line = httpLines().single()
        assertTrue(line, line.contains(" I http ") && line.contains("-> CANCELED NONE "))
    }

    @Test fun apiCallsAreAlwaysLogged_nonSuccessAtWarn() {
        telemetry.record(outcome("https://api.smugmug.com/api/v2/node/a", code = 401))
        assertTrue(httpLines().single().contains(" W http "))
    }

    // ---- volume policy for other hosts ----

    @Test fun cdnSuccess_isNotLogged_butCounted() {
        telemetry.record(outcome("https://photos.smugmug.com/a/b/i-AbCd/0/Kxyz/X3/IMG-X3.jpg", ms = 40))
        assertEquals(emptyList<String>(), httpLines())
        assertEquals(1, telemetry.stats().img.calls)
        assertEquals(1, telemetry.stats().img.status2xx)
        assertEquals(0, telemetry.stats().api.calls)
    }

    @Test fun cdnFailures_slowCalls_andSynthetic504_areLogged_withoutTheUrl() {
        val url = "https://photos.smugmug.com/Family/School/i-AbCd/0/Kxyz/X3/IMG_1-X3.jpg"
        telemetry.record(outcome(url, code = 404))
        telemetry.record(outcome(url, code = null, error = IOException("x"), codes = emptyList()))
        telemetry.record(outcome(url, code = 200, ms = 3500))
        telemetry.record(outcome(url, code = 504))
        val lines = httpLines()
        assertEquals(lines.toString(), 4, lines.size)
        val text = lines.joinToString("\n")
        for (leak in listOf("Family", "School", "i-AbCd", "Kxyz", "IMG_1")) assertFalse(leak, text.contains(leak))
        assertTrue(text, text.contains("photos.smugmug.com <path:7 segs>/X3"))
    }

    @Test fun canceledImageLoads_areCountedNotLogged() {
        telemetry.record(outcome("https://photos.smugmug.com/a/b/c.jpg", code = null, canceled = true, codes = emptyList()))
        assertEquals(emptyList<String>(), httpLines())
        assertEquals(1, telemetry.stats().img.canceled)
    }

    // ---- stats ----

    @Test fun stats_countStatusClassesRetriesAndTimings() {
        val api = "https://api.smugmug.com/api/v2/node/a"
        telemetry.record(outcome(api, code = 200, ms = 10))
        telemetry.record(outcome(api, code = 200, ms = 30, attempts = 3, codes = listOf(503, 503, 200)))
        telemetry.record(outcome(api, code = 404, ms = 20))
        telemetry.record(outcome(api, code = null, error = IOException("x"), ms = 40, codes = emptyList()))
        val s = telemetry.stats().api
        assertEquals(4, s.calls)
        assertEquals(2, s.status2xx)
        assertEquals(1, s.status4xx)
        assertEquals(1, s.ioErrors)
        assertEquals(1, s.retriedCalls)
        assertEquals(2, s.totalRetries)
        assertEquals(40L, s.maxMs)
        assertEquals(25L, s.meanMs)
    }

    @Test fun stats_countConditionalAndSyntheticFromRealResponses() {
        val (server, port) = serve(revalidatable, notModified)
        val f = factory()
        val url = "http://api.smugmug.com:$port/api/v2/album/Ab12Cd"
        get(f, url)
        get(f, url)
        get(f, "http://api.smugmug.com:$port/api/v2/album/never", CacheControl.Builder().onlyIfCached().build())
        server.close()
        val s = telemetry.stats().api
        assertEquals(1, s.conditional)
        assertEquals(1, s.synthetic504)
    }

    @Test fun record_neverThrows_evenWhenTheSinkDoes() {
        val brokenSink = object : LogSink {
            override fun append(line: String) = error("sink down")
            override fun flush(timeoutMs: Long) = false
            override fun read(maxBytes: Int) = ""
            override fun clear() {}
            override val status get() = SinkStatus(0, 0, "down")
        }
        val broken = HttpTelemetry(DiagLog(brokenSink, Redactor()))
        broken.record(outcome("https://api.smugmug.com/api/v2/x"))
        assertEquals(1, broken.stats().api.calls)
    }

    // ---- describeUrl ----

    private fun d(url: String) = describeUrl(url.toHttpUrl())

    @Test fun describeUrl_apiPath_dropsPrefix_keepsAllowListedValues_listsOthersByName() {
        assertEquals(
            "/user/kev!albums?start=1&count=100&Order=x&+APIKey,+Password",
            d("https://api.smugmug.com/api/v2/user/kev!albums?APIKey=K&start=1&Password=P&count=100&Order=x")
        )
        assertEquals("/node/abc!children", d("https://api.smugmug.com/api/v2/node/abc!children"))
        assertEquals(
            "/node/abc!search?+Text,+APIKey",
            d("https://api.smugmug.com/api/v2/node/abc!search?Text=grandma&APIKey=K")
        )
    }

    @Test fun describeUrl_folderByPath_hidesTheTitles() {
        assertEquals("/folder/user/nick/<path>", d("https://api.smugmug.com/api/v2/folder/user/nick/Family/School!children"))
        assertEquals("/folder/user/nick", d("https://api.smugmug.com/api/v2/folder/user/nick"))
    }

    @Test fun describeUrl_otherHosts_showHost_andReducePathToSegmentCount() {
        assertEquals(
            "photos.smugmug.com <path:7 segs>/X3",
            d("https://photos.smugmug.com/Family/School/i-AbCd/0/Kxyz/X3/IMG_1-X3.jpg")
        )
        assertEquals("example.com <path:1 segs>?+token", d("https://example.com/a.jpg?token=zzz"))
        assertEquals("example.com", d("https://example.com/"))
    }

    @Test fun describeUrl_longAllowListedValuesAreCut() {
        val long = "x".repeat(500)
        assertTrue(d("https://api.smugmug.com/api/v2/a?_expand=$long").length < 120)
    }
}
