package com.smugview.app.data.repository

import com.smugview.app.data.api.RetryingCallFactory
import com.smugview.app.data.api.SmugMugApi
import com.smugview.app.di.HostCookieJar
import com.smugview.app.di.buildSmugMugClient
import okhttp3.Dns
import okhttp3.Headers.Companion.toHeaders
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * [FakeSmugMugServer] on a real loopback socket (phase 4 design section 5), with a `Dns` that sends
 * `api.smugmug.com` to 127.0.0.1 (the `HttpTelemetryTest` pattern). The client in front of it is the
 * PRODUCTION one, [buildSmugMugClient]: the real OkHttp [okhttp3.Cache], the real cache rewrite, the
 * real cookie jar and the offline fallback, so cache hits, `Set-Cookie` and `Cookie` headers, 301
 * redirects and the synthetic offline 504 are exercised for real. Every request goes through the
 * fake's [FakeSmugMugServer.handle] (gates, recording, throttles, routing).
 *
 * One request per connection (`Connection: close`). A fake that throws an [IOException] closes the
 * socket without an answer, which is what a dropped connection looks like to OkHttp.
 */
class LoopbackSmugMug(val fake: FakeSmugMugServer, cacheDir: File) : AutoCloseable {
    private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    private val closed = AtomicBoolean(false)

    val port: Int get() = server.localPort

    /** Retrofit's base URL: plain http on the loopback port, host `api.smugmug.com` like production. */
    val baseUrl: String get() = "http://api.smugmug.com:$port/api/v2/"

    /** Flip to false for the offline rule (`only-if-cached, max-stale=7d`) of the production client. */
    @Volatile var online = true

    /** The cookie jar in front of the fake. Replace it, with a new client, to simulate a new process. */
    val cookieJar: okhttp3.CookieJar = HostCookieJar()

    val client: OkHttpClient = newClient(cacheDir, cookieJar)

    /** A production client over the same loopback, e.g. a new process over the same [cacheDir]. */
    fun newClient(cacheDir: File, jar: okhttp3.CookieJar = HostCookieJar()): OkHttpClient = buildSmugMugClient(
        cacheDir = cacheDir,
        isOnline = { online },
        cookieJar = jar,
        loggingInterceptor = Interceptor { it.proceed(it.request()) },
        dns = object : Dns {
            override fun lookup(hostname: String): List<InetAddress> =
                if (hostname == "api.smugmug.com") listOf(InetAddress.getLoopbackAddress()) else Dns.SYSTEM.lookup(hostname)
        }
    )

    init {
        fake.sessionFromCookieHeader = true
        thread(isDaemon = true, name = "loopback-smugmug-accept") {
            while (!closed.get()) {
                val socket = try { server.accept() } catch (e: IOException) { break }
                thread(isDaemon = true, name = "loopback-smugmug-conn") { serve(socket) }
            }
        }
    }

    /** The app's API over [client]; with [retrying] it goes through the app's real [RetryingCallFactory]. */
    fun api(client: OkHttpClient = this.client, retrying: Boolean = false): SmugMugApi {
        val builder = Retrofit.Builder().baseUrl(baseUrl).addConverterFactory(GsonConverterFactory.create())
        if (retrying) builder.callFactory(RetryingCallFactory(client, initialDelayMs = 1)) else builder.client(client)
        return builder.build().create(SmugMugApi::class.java)
    }

    private fun serve(socket: Socket) {
        socket.use { s ->
            try {
                val input = s.getInputStream().buffered()
                val requestLine = readLine(input) ?: return
                val (method, target) = requestLine.split(' ').let { it[0] to it[1] }
                val headers = linkedMapOf<String, String>()
                while (true) {
                    val line = readLine(input) ?: return
                    if (line.isEmpty()) break
                    headers[line.substringBefore(':').trim()] = line.substringAfter(':').trim()
                }
                val length = headers.entries.firstOrNull { it.key.equals("Content-Length", true) }?.value?.toIntOrNull() ?: 0
                val body = ByteArray(length).also { var n = 0; while (n < length) { val r = input.read(it, n, length - n); if (r < 0) break; n += r } }
                val url = "https://api.smugmug.com$target".toHttpUrl()
                val forwarded = headers.filterKeys {
                    !it.equals("Host", true) && !it.equals("Connection", true) && !it.equals("Content-Length", true) &&
                        !it.equals("Accept-Encoding", true)
                }
                val request = Request.Builder().url(url).headers(forwarded.toHeaders())
                    .method(method, if (method == "GET" || method == "HEAD") null else body.toRequestBody())
                    .build()
                val response = try { fake.handle(request) } catch (e: IOException) { return }
                val bytes = response.body?.bytes() ?: ByteArray(0)
                val head = StringBuilder("HTTP/1.1 ${response.code} ${response.message}\r\n")
                for ((name, value) in response.headers) {
                    if (name.equals("Content-Length", true) || name.equals("Transfer-Encoding", true)) continue
                    head.append(name).append(": ").append(value).append("\r\n")
                }
                response.body?.contentType()?.let { if (response.header("Content-Type") == null) head.append("Content-Type: $it\r\n") }
                head.append("Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n")
                s.getOutputStream().apply { write(head.toString().toByteArray()); write(bytes); flush() }
            } catch (e: IOException) {
                // a client that went away mid-request is not a test failure
            }
        }
    }

    private fun readLine(input: java.io.InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar())
        }
    }

    override fun close() {
        closed.set(true)
        runCatching { server.close() }
        runCatching { client.dispatcher.executorService.shutdown() }
        runCatching { client.connectionPool.evictAll() }
        runCatching { client.cache?.close() }
    }
}
