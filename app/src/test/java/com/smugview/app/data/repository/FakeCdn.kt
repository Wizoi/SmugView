package com.smugview.app.data.repository

import okhttp3.OkHttpClient
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * The originals the fakes agree on (phase 5 design section 5, P1/P7): one deterministic byte string per
 * image key, so [FakeSmugMugServer]'s `ArchivedSize`/`ArchivedMD5` and [FakeCdn]'s body always match, the
 * way the live `ArchivedMD5` equals the CDN's ETag and `ArchivedSize` its Content-Length. A video's
 * `ArchivedUri` is a JPEG still (`-D.jpg`, 164,821 B on the live API), never the mp4.
 */
object FakeOriginals {
    /** The still the live API serves for a video (P7). */
    const val VIDEO_STILL_BYTES = 164_821

    fun bytes(key: String, video: Boolean = false): ByteArray {
        val size = if (video) VIDEO_STILL_BYTES else 20_000 + (key.hashCode() and 0x7fffffff) % 10_000
        val out = ByteArray(size)
        // A JPEG header, then bytes derived from the key: different keys never share a body.
        out[0] = 0xFF.toByte(); out[1] = 0xD8.toByte(); out[2] = 0xFF.toByte()
        val seed = key.hashCode()
        for (i in 3 until size) out[i] = ((i * 31) xor seed xor (i ushr 8)).toByte()
        return out
    }

    fun md5(key: String, video: Boolean = false): String =
        MessageDigest.getInstance("MD5").digest(bytes(key, video)).joinToString("") { "%02x".format(it) }

    fun size(key: String, video: Boolean = false): Long = bytes(key, video).size.toLong()

    /** The live shape: `https://photos.smugmug.com/photos/i-{key}/0/{hash}/D/i-{key}-D.jpg` (a wrong hash still answers 200, P2). */
    fun archivedUri(key: String, video: Boolean = false): String =
        "https://photos.smugmug.com/photos/i-$key/0/${md5(key, video).take(8)}/D/i-$key-D.jpg"

    /** The key out of an `ArchivedUri` / CDN path, or null for any other shape. */
    fun keyOf(path: String): String? =
        Regex("""/photos/i-([^/]+)/0/[^/]+/D/i-\1-D\.jpg""").find(path)?.groupValues?.get(1)
}

/**
 * `photos.smugmug.com` on a real loopback socket. It answers like the live CDN (design P1-P8): 200 with
 * `ETag` = the original's MD5 (quoted) and `Content-Length`, `Range` honoured (206), 404 `text/html`
 * `no-store` for a key that is gone, and the same body whatever the hash segment says. Tests can [hold] a
 * path (a download in flight), script a status ([respondWith], with `Retry-After`), and read [requests].
 *
 * The app's URLs are https; [clientOver] puts a rewriting application interceptor in front of a client (the
 * production one, so the offline rule and its synthetic 504 still run) that sends them to this socket.
 */
class FakeCdn : AutoCloseable {
    data class Req(val path: String, val range: String?)

    private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    private val closed = AtomicBoolean(false)
    val port: Int get() = server.localPort

    private val log = ConcurrentLinkedQueue<Req>()
    val requests: List<Req> get() = log.toList()

    /** Keys whose original is gone: 404 (P4). */
    val gone: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** Keys whose original is a video: the body is the 164,821 B JPEG still (P7). */
    val videos: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    private class Script(val pathContains: String, val code: Int, var times: Int, val headers: Map<String, String>)
    private val scripts = CopyOnWriteArrayList<Script>()
    private class Gate(val pathContains: String) {
        val latch = CountDownLatch(1)
        val entered = CountDownLatch(1)
    }
    private val gates = CopyOnWriteArrayList<Gate>()
    private val midGates = CopyOnWriteArrayList<Gate>()

    private class Mod(val pathContains: String, var times: Int, val kind: String)
    private val mods = CopyOnWriteArrayList<Mod>()

    /** The next [times] requests whose path contains [pathContains] get [code] (with [headers]), then it is the CDN again. */
    fun respondWith(pathContains: String, code: Int, times: Int = Int.MAX_VALUE, headers: Map<String, String> = emptyMap()) {
        scripts += Script(pathContains, code, times, headers)
    }

    /** Requests for [pathContains] block until [release]; [awaitEntered] says one has arrived. */
    fun hold(pathContains: String) { gates += Gate(pathContains) }
    fun awaitEntered(pathContains: String, timeoutMs: Long = 10_000): Boolean =
        gates.first { it.pathContains == pathContains }.entered.await(timeoutMs, TimeUnit.MILLISECONDS)
    fun release(pathContains: String) { gates.filter { it.pathContains == pathContains }.forEach { it.latch.countDown() } }

    /**
     * The next [times] successful answers for [pathContains] promise the whole `Content-Length` but send half the body
     * and close (a connection that dropped mid-body, phase 5 design 2.4).
     */
    fun truncate(pathContains: String, times: Int = 1) { mods += Mod(pathContains, times, "truncate") }

    /** The next [times] successful answers for [pathContains] have the right size, `ETag` and `Content-Length` but one byte changed (a damaged file). */
    fun corrupt(pathContains: String, times: Int = 1) { mods += Mod(pathContains, times, "corrupt") }

    /** Requests for [pathContains] send half the body and then block until [releaseMidBody]: a download in flight. */
    fun holdMidBody(pathContains: String) { midGates += Gate(pathContains) }
    fun awaitMidBody(pathContains: String, timeoutMs: Long = 10_000): Boolean =
        midGates.first { it.pathContains == pathContains }.entered.await(timeoutMs, TimeUnit.MILLISECONDS)
    fun releaseMidBody(pathContains: String) { midGates.filter { it.pathContains == pathContains }.forEach { it.latch.countDown() } }

    fun countFor(pathContains: String): Int = requests.count { it.path.contains(pathContains) }

    /** [base] with every request redirected to this socket (scheme, host and port); the rest of the stack is [base]'s. */
    fun clientOver(base: OkHttpClient): OkHttpClient = base.newBuilder().addInterceptor { chain ->
        val r = chain.request()
        val url = r.url.newBuilder().scheme("http").host("127.0.0.1").port(port).build()
        chain.proceed(r.newBuilder().url(url).build())
    }.build()

    init {
        thread(isDaemon = true, name = "fake-cdn-accept") {
            while (!closed.get()) {
                val socket = try { server.accept() } catch (e: IOException) { break }
                thread(isDaemon = true, name = "fake-cdn-conn") { serve(socket) }
            }
        }
    }

    private fun serve(socket: Socket) {
        socket.use { s ->
            try {
                val input = s.getInputStream().buffered()
                val line = readLine(input) ?: return
                val path = line.split(' ')[1]
                var range: String? = null
                while (true) {
                    val h = readLine(input) ?: return
                    if (h.isEmpty()) break
                    if (h.startsWith("Range:", ignoreCase = true)) range = h.substringAfter(':').trim()
                }
                log += Req(path, range)
                gates.filter { path.contains(it.pathContains) }.forEach { it.entered.countDown(); it.latch.await() }
                val (code, headers, body) = answer(path, range)
                val reason = when (code) {
                    200 -> "OK"
                    206 -> "Partial Content"
                    404 -> "Not Found"
                    429 -> "Too Many Requests"
                    403 -> "Forbidden"
                    408 -> "Request Timeout"
                    503 -> "Service Unavailable"
                    else -> "Status"
                }
                val head = StringBuilder("HTTP/1.1 $code $reason\r\n")
                headers.forEach { (k, v) -> head.append(k).append(": ").append(v).append("\r\n") }
                head.append("Content-Length: ${body.size}\r\nConnection: close\r\n\r\n")
                val out = s.getOutputStream()
                out.write(head.toString().toByteArray())
                val mod = if (code == 200) mods.firstOrNull { path.contains(it.pathContains) && it.times > 0 }?.also { it.times-- } else null
                val mid = if (code == 200) midGates.firstOrNull { path.contains(it.pathContains) } else null
                when {
                    mod?.kind == "truncate" -> { out.write(body, 0, body.size / 2); out.flush() }
                    mod?.kind == "corrupt" -> {
                        val bad = body.copyOf()
                        bad[bad.size / 2] = (bad[bad.size / 2].toInt() xor 0xFF).toByte()
                        out.write(bad); out.flush()
                    }
                    mid != null -> {
                        out.write(body, 0, body.size / 2); out.flush()
                        mid.entered.countDown(); mid.latch.await()
                        out.write(body, body.size / 2, body.size - body.size / 2); out.flush()
                    }
                    else -> { out.write(body); out.flush() }
                }
            } catch (e: IOException) {
                // a client that went away mid-request is not a test failure
            }
        }
    }

    private fun answer(path: String, range: String?): Triple<Int, Map<String, String>, ByteArray> {
        scripts.firstOrNull { path.contains(it.pathContains) && it.times > 0 }?.let { sc ->
            sc.times--
            return Triple(sc.code, sc.headers + mapOf("Content-Type" to "text/html", "Cache-Control" to "no-store"), ByteArray(0))
        }
        val key = FakeOriginals.keyOf(path)
        if (key == null || key in gone) {
            return Triple(404, mapOf("Content-Type" to "text/html", "Cache-Control" to "no-store"), "<html>Not Found</html>".toByteArray())
        }
        val video = key in videos
        val all = FakeOriginals.bytes(key, video)
        val etag = "\"" + FakeOriginals.md5(key, video) + "\""
        val base = mapOf("ETag" to etag, "Content-Type" to "image/jpeg", "Accept-Ranges" to "bytes", "Cache-Control" to "max-age=31536000")
        val from = range?.let { Regex("""bytes=(\d+)-""").find(it)?.groupValues?.get(1)?.toInt() }
        if (from != null && from < all.size) {
            return Triple(206, base + ("Content-Range" to "bytes $from-${all.size - 1}/${all.size}"), all.copyOfRange(from, all.size))
        }
        return Triple(200, base, all)
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
        gates.forEach { it.latch.countDown() }
        midGates.forEach { it.latch.countDown() }
        runCatching { server.close() }
    }
}
