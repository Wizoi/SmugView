package com.smugview.app.data.cast

import android.app.Application
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog
import org.robolectric.annotation.Config

/** The scan ends when no more devices answer; that is its normal end, not an error with a stack trace (V3). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class SsdpScanLoggingTest {

    @Test fun `a scan nobody answers ends without logging an error`() {
        ShadowLog.clear()
        val app = ApplicationProvider.getApplicationContext<Application>()
        val manager = DefaultCastManager(app, Dispatchers.IO)
        val sent = mutableListOf<java.net.DatagramPacket>()
        manager.ssdpSocketFactory = { QuietSocket(sent) }

        runBlocking { manager.scanForTest() }

        assertEquals("both searches were sent through the fake socket, none through the network", 2, sent.size)

        val scan = ShadowLog.getLogs().filter { it.tag == "CastManager" && it.msg.contains("IOException during receive") }
        val errors = scan.filter { it.type >= Log.ERROR }
        assertEquals("a quiet network is not an error: ${errors.map { it.msg }}", emptyList<String>(), errors.map { it.msg })
    }

    /** A multicast socket that never opens a real one: sends are recorded and every receive times out, as on a quiet network. */
    private class QuietSocket(private val sent: MutableList<java.net.DatagramPacket>) : java.net.MulticastSocket(null as java.net.SocketAddress?) {
        override fun send(p: java.net.DatagramPacket) { sent.add(p) }
        override fun joinGroup(group: java.net.InetAddress) {}
        override fun receive(p: java.net.DatagramPacket) { Thread.sleep(50); throw java.net.SocketTimeoutException("quiet") }
        override fun close() {}
    }
}
