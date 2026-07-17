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
    private lateinit var castManager: DefaultCastManager

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        castManager = DefaultCastManager(mockContext, dispatcher)
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
}
