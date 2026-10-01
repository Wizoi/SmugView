package com.smugview.app.data.cast

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Step 6-8: the network and Cast SDK seam of [DefaultCastManager] with no network. Every wait is a coroutine [delay], so it runs on
 * the test's virtual time; every call is recorded.
 *
 * [scanSocketOpen] is the stand-in for the SSDP socket: opened when a scan starts, closed in a `finally`, so a scan the manager
 * cancels must still leave it closed.
 */
internal class FakeCastIo : CastIo {
    /** What one SSDP scan returns. */
    var devices: List<CastDevice> = emptyList()
    /** How long (virtual) one SSDP scan takes. The real one takes about 3 s. */
    var scanMillis = 3_000L
    /** How long (virtual) the Roku support probe takes. */
    var probeMillis = 0L
    var rokuSupported = true
    var dialSupported = false

    val scans = AtomicInteger()
    @Volatile var scanSocketOpen = false
    val socketsOpened = AtomicInteger()
    val socketsClosed = AtomicInteger()
    val serverStarts = CopyOnWriteArrayList<Pair<Int, String>>()
    val serverStops = AtomicInteger()
    @Volatile var serverBound = false
    val selectedRoutes = CopyOnWriteArrayList<String>()
    val endedSessions = AtomicInteger()

    override suspend fun ssdpScan(): List<CastDevice> {
        scans.incrementAndGet()
        scanSocketOpen = true
        socketsOpened.incrementAndGet()
        try {
            delay(scanMillis)
            return devices
        } catch (e: CancellationException) {
            throw e
        } finally {
            scanSocketOpen = false
            socketsClosed.incrementAndGet()
        }
    }

    override suspend fun rokuSupportsCasting(ip: String): Boolean {
        if (probeMillis > 0) delay(probeMillis)
        return rokuSupported
    }

    override suspend fun amazonSupportsDial(ip: String): Boolean = dialSupported

    /** What the server start answers; null binds the asked-for port. A test sets it to [WebCompanionStart.Failed] or another port. */
    @Volatile var serverStartResult: WebCompanionStart? = null
    /** This phone's Wi-Fi address as the URL shows it; null is "no Wi-Fi". */
    @Volatile var localIp: String? = "192.168.1.20"

    override fun startServer(port: Int, allowedClientIp: String): WebCompanionStart {
        serverStarts += port to allowedClientIp
        val result = serverStartResult ?: WebCompanionStart.Bound(port)
        serverBound = result is WebCompanionStart.Bound
        return result
    }

    override fun localIpAddress(): String? = localIp

    override fun stopServer() {
        serverStops.incrementAndGet()
        serverBound = false
    }

    override fun selectGoogleRoute(routeId: String) {
        selectedRoutes += routeId
    }

    override fun endGoogleSession() {
        endedSessions.incrementAndGet()
    }
}
