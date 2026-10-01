package com.smugview.app.data.cast

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * Step 6-9 (R-58): the Web Companion server on real loopback sockets. Robolectric only supplies `android.util.Log`.
 *
 * "Port 8080 is taken" is played on a port the OS hands out (a fixed 8080 would fail on a machine that really uses it): the test
 * holds port P and starts the server at P with a range of ten, which is what 8080 to 8089 is.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class WebCompanionServerTest {
    private val held = mutableListOf<ServerSocket>()
    private val clients = mutableListOf<Socket>()
    private val server = WebCompanionServer(getActiveMediaUrl = { "https://photos.example/a.jpg" }, isVideoCheck = { false })

    @Before fun setUp() { held.clear(); clients.clear() }

    @After fun tearDown() {
        clients.forEach { runCatching { it.close() } }
        server.stop()
        held.forEach { runCatching { it.close() } }
    }

    /** A port P where P and P+1 are both free, with P now held open by this test (so "P is taken"). */
    private fun holdPortWithFreeNeighbour(): Int {
        repeat(200) {
            val s = ServerSocket(0)
            val p = s.localPort
            val neighbour = runCatching { ServerSocket(p + 1) }.getOrNull()
            if (neighbour != null) {
                neighbour.close()
                held += s
                return p
            }
            s.close()
        }
        error("no two adjacent free ports on this machine")
    }

    private fun get(port: Int, path: String): String = Socket().use { sock ->
        sock.connect(InetSocketAddress("127.0.0.1", port), 2_000)
        sock.soTimeout = 3_000
        sock.getOutputStream().write("GET $path HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray())
        sock.getOutputStream().flush()
        sock.getInputStream().bufferedReader().readText()
    }

    @Test fun `when the first port is taken the next free one is bound, reported, and serves the feed`() {
        val taken = holdPortWithFreeNeighbour()

        val result = server.start(port = taken, allowedClientIp = "127.0.0.1", lastPort = taken + 9)

        assertEquals("the port the server really got, not the one that was asked for", WebCompanionStart.Bound(taken + 1), result)
        val reply = get(taken + 1, "/feed")
        assertTrue(reply, reply.startsWith("HTTP/1.1 200 OK"))
        assertTrue(reply, reply.contains("photos.example/a.jpg"))
    }

    @Test fun `when every port in the range is taken the start fails and nothing is left listening`() {
        val a = holdPortWithFreeNeighbour()
        held += ServerSocket(a + 1)

        val result = server.start(port = a, allowedClientIp = "127.0.0.1", lastPort = a + 1)

        assertTrue("expected Failed but was $result", result is WebCompanionStart.Failed)
        assertEquals("nothing may be running after a failed start", 0, server.workerThreadCount())
    }

    @Test fun `a second start while running keeps the port and takes the new client address`() {
        val p = holdPortWithFreeNeighbour()
        held.removeAt(0).close()

        val first = server.start(port = p, allowedClientIp = "10.9.9.9", lastPort = p)
        assertEquals(WebCompanionStart.Bound(p), first)
        assertTrue("a loopback client is not 10.9.9.9", get(p, "/feed").startsWith("HTTP/1.1 403"))

        val second = server.start(port = p, allowedClientIp = "127.0.0.1", lastPort = p)
        assertEquals(first, second)
        assertTrue(get(p, "/feed").startsWith("HTTP/1.1 200"))
    }

    @Test fun `thirty slow clients are bounded at 4 workers and 16 waiting, and the rest are dropped at once`() {
        val p = holdPortWithFreeNeighbour()
        held.removeAt(0).close()
        assertEquals(WebCompanionStart.Bound(p), server.start(port = p, allowedClientIp = "127.0.0.1", lastPort = p))

        // Clients that connect and say nothing: each holds a worker (or a place in the queue) until the 5 s read timeout.
        repeat(30) {
            clients += Socket().apply { connect(InetSocketAddress("127.0.0.1", p), 2_000); soTimeout = 50 }
        }

        // A dropped connection is closed by the server at once, so its client reads end-of-stream rather than timing out.
        var dropped = 0
        val deadline = System.currentTimeMillis() + 4_000
        while (System.currentTimeMillis() < deadline) {
            dropped = clients.count { c ->
                try { c.getInputStream().read() == -1 } catch (e: SocketTimeoutException) { false } catch (e: Exception) { true }
            }
            if (dropped >= 10) break
            Thread.sleep(100)
        }

        assertTrue("never more than 16 waiting: ${server.queuedConnections()}", server.queuedConnections() <= 16)
        assertTrue("no thread growth: ${server.workerThreadCount()}", server.workerThreadCount() <= 4)
        assertEquals("4 busy + 16 waiting = 20 kept, so 10 are dropped", 10, dropped)
    }

    @Test fun `stop frees the port so a new start can bind it again`() {
        val p = holdPortWithFreeNeighbour()
        held.removeAt(0).close()
        assertEquals(WebCompanionStart.Bound(p), server.start(port = p, lastPort = p))

        server.stop()

        assertEquals(WebCompanionStart.Bound(p), server.start(port = p, lastPort = p))
    }
}
