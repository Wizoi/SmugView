package com.smugview.app.data.cast

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito

/**
 * These tests drive the cast manager with virtual time via an injected TestDispatcher, so the
 * connect "simulation" delay and slideshow timer are advanced deterministically instead of with
 * real Thread.sleep (which was slow and race-prone).
 *
 * A single [TestCoroutineScheduler] backs both the injected work-dispatcher and Dispatchers.Main
 * (the manager's init block and some connect steps hop to Main), so one advanceTimeBy drives all.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CastManagerTest {

    private val mockContext: Context = Mockito.mock(Context::class.java)
    private val scheduler = TestCoroutineScheduler()
    private val dispatcher = StandardTestDispatcher(scheduler)
    private val io = FakeCastIo()
    private lateinit var castManager: DefaultCastManager

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        castManager = DefaultCastManager(mockContext, dispatcher, io)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun testInitialState() {
        assertTrue(castManager.discoveredDevices.value.isEmpty())
        assertNull(castManager.activeDevice.value)
        assertFalse(castManager.isCasting.value)
        assertNull(castManager.currentImageUri.value)
        assertEquals(5, castManager.slideshowInterval.value)
        assertFalse(castManager.isSlideshowPlaying.value)
    }

    @Test
    fun testConnectAndDisconnect() = runTest(dispatcher) {
        val device = CastDevice("test_id", "Test Device", "192.168.1.100", CastType.ROKU)

        castManager.connectToDevice(device)
        advanceTimeBy(1500); runCurrent() // run the connect "simulation" delay (1000ms) to completion

        val active = castManager.activeDevice.value
        assertNotNull(active)
        assertEquals("test_id", active?.id)
        assertEquals(ConnectionState.CONNECTED, active?.state)
        assertTrue(castManager.isCasting.value)

        castManager.disconnect()
        assertNull(castManager.activeDevice.value)
        assertFalse(castManager.isCasting.value)
    }

    @Test
    fun testCastImage() = runTest(dispatcher) {
        val device = CastDevice("test_id", "Test Device", "192.168.1.100", CastType.ROKU)
        castManager.connectToDevice(device)
        advanceTimeBy(1500); runCurrent()

        castManager.castImage("https://example.com/photo.jpg", "Test Photo")
        runCurrent()
        assertEquals("https://example.com/photo.jpg", castManager.currentImageUri.value)
    }

    @Test
    fun testCastSlideshow() = runTest(dispatcher) {
        val device = CastDevice("test_id", "Test Device", "192.168.1.100", CastType.ROKU)
        castManager.connectToDevice(device)
        advanceTimeBy(1500); runCurrent()

        val urls = listOf("https://example.com/1.jpg", "https://example.com/2.jpg")
        castManager.castSlideshow(urls, intervalSeconds = 2)
        runCurrent()

        assertTrue(castManager.isSlideshowPlaying.value)
        assertEquals(2, castManager.slideshowInterval.value)

        // Advance past the first slideshow interval; the timer should have cast an image.
        advanceTimeBy(2500)
        runCurrent()
        val current = castManager.currentImageUri.value
        assertNotNull(current)
        assertTrue(urls.contains(current))

        castManager.setSlideshowPlaying(false)
        assertFalse(castManager.isSlideshowPlaying.value)
    }

    // ---- Step 6-8 (R-56, R-57): discovery stops, and Stop during a connect wins -------------------------------------------------

    private val roku = CastDevice("192.168.1.50", "Living room Roku", "192.168.1.50", CastType.ROKU)
    private val fireTv = CastDevice("192.168.1.60", "Fire TV", "192.168.1.60", CastType.AMAZON)
    private val chromecast = CastDevice("route-abc", "Kitchen display", "google-cast-route", CastType.GOOGLE)

    @Test
    fun `no SSDP scan starts later than 30 seconds after a device was picked`() = runTest(dispatcher) {
        io.devices = listOf(roku)
        castManager.startDiscovery()
        runCurrent()
        assertEquals("the first scan runs at once", 1, io.scans.get())

        castManager.connectToDevice(roku)
        advanceTimeBy(30_001); runCurrent()
        val atThirtySeconds = io.scans.get()
        assertTrue("it scanned while the user was still choosing: $atThirtySeconds", atThirtySeconds in 2..4)

        advanceTimeBy(60_000); runCurrent()
        assertEquals("not one more scan after the 30 s", atThirtySeconds, io.scans.get())
        assertFalse("the cancelled scan left its socket open", io.scanSocketOpen)
    }

    @Test
    fun `discovery stops itself after 60 seconds even when no device is ever picked, and closes its socket`() = runTest(dispatcher) {
        io.scanMillis = 6_000 // scans start at 0, 14, 28, 42, 56 s: one is in flight when the cap fires at 60 s
        castManager.startDiscovery()
        advanceTimeBy(59_000); runCurrent()
        assertEquals("a scan every 14 s until then", 5, io.scans.get())
        assertTrue("a scan is in flight", io.scanSocketOpen)

        advanceTimeBy(2_000); runCurrent() // 61 s
        assertFalse("the scan in flight was cancelled and its socket closed", io.scanSocketOpen)
        assertEquals("every socket that was opened was closed", io.socketsOpened.get(), io.socketsClosed.get())

        val atCap = io.scans.get()
        advanceTimeBy(120_000); runCurrent()
        assertEquals("no scan after the cap", atCap, io.scans.get())
    }

    @Test
    fun `starting discovery again after a pick keeps it running until the new 60 second cap`() = runTest(dispatcher) {
        castManager.startDiscovery()
        runCurrent()
        castManager.connectToDevice(roku)
        advanceTimeBy(20_000); runCurrent()
        castManager.startDiscovery() // the sheet is opened again before the pick's 30 s are up
        val beforeReopen = io.scans.get()
        advanceTimeBy(40_000); runCurrent() // 60 s after the pick: the stale pick-stop must not end the new sheet's discovery
        assertTrue("discovery kept scanning for the sheet that is open", io.scans.get() > beforeReopen)
    }

    @Test
    fun `Stop pressed while a Roku is still connecting leaves nothing connected`() = runTest(dispatcher) {
        io.probeMillis = 800 // the support probe is still waiting on the Roku at 500 ms
        castManager.connectToDevice(roku)
        advanceTimeBy(500); runCurrent()
        assertEquals(ConnectionState.CONNECTING, castManager.activeDevice.value?.state)

        castManager.disconnect()
        advanceTimeBy(3_000); runCurrent()

        assertNull("the attempt that Stop outran must not come back as the active device", castManager.activeDevice.value)
        assertFalse(castManager.isCasting.value)
        assertTrue(castManager.discoveredDevices.value.none { it.state == ConnectionState.CONNECTED })
    }

    @Test
    fun `Stop pressed during the connect delay of a Fire TV never binds the Web Companion server`() = runTest(dispatcher) {
        io.dialSupported = false // so a finished connect would bind the server for the Fire TV
        castManager.connectToDevice(fireTv)
        advanceTimeBy(500); runCurrent()

        castManager.disconnect()
        advanceTimeBy(2_000); runCurrent()

        assertNull(castManager.activeDevice.value)
        assertFalse(castManager.isCasting.value)
        assertFalse("Web Companion must not be active", castManager.isWebCompanionActive.value)
        assertTrue("the server must never have been bound: ${io.serverStarts}", io.serverStarts.isEmpty())
        assertFalse(io.serverBound)
    }

    @Test
    fun `a Fire TV that is not stopped still connects and binds the server for that device only`() = runTest(dispatcher) {
        io.dialSupported = false
        castManager.connectToDevice(fireTv)
        advanceTimeBy(1_500); runCurrent()

        assertEquals(ConnectionState.CONNECTED, castManager.activeDevice.value?.state)
        assertEquals(listOf(8080 to "192.168.1.60"), io.serverStarts.toList())
        assertTrue(castManager.isWebCompanionActive.value)
    }

    @Test
    fun `a Google session that starts after Stop is ended, not adopted`() = runTest(dispatcher) {
        castManager.connectToDevice(chromecast)
        runCurrent()
        assertEquals("the route was chosen", listOf("route-abc"), io.selectedRoutes.toList())
        castManager.disconnect()
        runCurrent()
        val endedByStop = io.endedSessions.get()

        // The Cast framework reports the session the user already stopped waiting for.
        castManager.onGoogleSessionConnected(chromecast.copy(state = ConnectionState.CONNECTED))
        runCurrent()

        assertEquals("the late session is ended", endedByStop + 1, io.endedSessions.get())
        assertNull("and not shown as the active device", castManager.activeDevice.value)
        assertFalse(castManager.isCasting.value)
    }

    @Test
    fun `a Google session that starts when nobody pressed Stop is adopted and not ended`() = runTest(dispatcher) {
        castManager.connectToDevice(chromecast)
        runCurrent()

        castManager.onGoogleSessionConnected(chromecast.copy(state = ConnectionState.CONNECTED))
        runCurrent()

        assertEquals(ConnectionState.CONNECTED, castManager.activeDevice.value?.state)
        assertTrue(castManager.isCasting.value)
        assertEquals(0, io.endedSessions.get())
    }
}
