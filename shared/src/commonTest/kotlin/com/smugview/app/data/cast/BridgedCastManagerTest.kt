package com.smugview.app.data.cast

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Records what the manager asks of the platform, and lets a test play the platform's side (devices, sessions, volume). */
private class FakeCastBridge : CastBridge {
    var heard: CastBridgeListener? = null
    val calls = mutableListOf<String>()

    override fun setListener(listener: CastBridgeListener?) { heard = listener }
    override fun startDiscovery() { calls += "startDiscovery" }
    override fun stopDiscovery() { calls += "stopDiscovery" }
    override fun connect(deviceId: String) { calls += "connect:$deviceId" }
    override fun disconnect() { calls += "disconnect" }
    override fun loadImage(url: String, title: String) { calls += "load:$url|$title" }
    override fun setVolume(volume: Float) { calls += "volume:$volume" }
    override fun setMuted(muted: Boolean) { calls += "muted:$muted" }

    val loads: List<String> get() = calls.filter { it.startsWith("load:") }.map { it.removePrefix("load:") }
}

@OptIn(ExperimentalCoroutinesApi::class)
class BridgedCastManagerTest {
    private val tv = CastDevice(id = "tv-1", name = "Living room", ipAddress = "192.168.1.20", type = CastType.GOOGLE)
    private fun connected() = tv.copy(state = ConnectionState.CONNECTED)

    private fun TestScope.manager(bridge: FakeCastBridge) = BridgedCastManager(bridge, backgroundScope)

    private fun TestScope.connect(bridge: FakeCastBridge) {
        runCurrent()
        bridge.heard!!.onSession(connected())
        runCurrent()
    }

    @Test fun theManagerRegistersItselfAsTheBridgesListener() = runTest {
        val bridge = FakeCastBridge()
        val manager = manager(bridge)
        assertTrue(bridge.heard === manager)
    }

    @Test fun devicesFoundByThePlatformAreShown() = runTest {
        val bridge = FakeCastBridge()
        val manager = manager(bridge)
        manager.startDiscovery()
        bridge.heard!!.onDevices(listOf(tv))
        runCurrent()
        assertEquals(listOf("startDiscovery"), bridge.calls)
        assertEquals(listOf(tv), manager.discoveredDevices.value)
    }

    @Test fun connectingMarksTheDeviceConnectingAndAsksThePlatform() = runTest {
        val bridge = FakeCastBridge()
        val manager = manager(bridge)
        bridge.heard!!.onDevices(listOf(tv))
        runCurrent()
        manager.connectToDevice(tv)
        runCurrent()
        assertEquals(ConnectionState.CONNECTING, manager.discoveredDevices.value.single().state)
        assertTrue("connect:tv-1" in bridge.calls)
    }

    @Test fun aSessionMakesTheManagerCastingAndItsDeviceActive() = runTest {
        val bridge = FakeCastBridge()
        val manager = manager(bridge)
        connect(bridge)
        assertEquals(connected(), manager.activeDevice.value)
        assertTrue(manager.isCasting.value)
    }

    @Test fun aConnectingSessionIsNotYetCasting() = runTest {
        val bridge = FakeCastBridge()
        val manager = manager(bridge)
        runCurrent()
        bridge.heard!!.onSession(tv.copy(state = ConnectionState.CONNECTING))
        runCurrent()
        assertFalse(manager.isCasting.value)
    }

    @Test fun aPictureAskedForBeforeConnectingIsHeldAndShownOnConnect() = runTest {
        val bridge = FakeCastBridge()
        val manager = manager(bridge)
        manager.castImage("https://x/1.jpg", "One")
        runCurrent()
        assertEquals("https://x/1.jpg", manager.currentImageUri.value)
        assertTrue(bridge.loads.isEmpty(), "nothing is sent while there is no session")
        bridge.heard!!.onSession(connected())
        runCurrent()
        assertEquals(listOf("https://x/1.jpg|One"), bridge.loads)
    }

    @Test fun aPictureWhileConnectedIsSentAtOnce() = runTest {
        val bridge = FakeCastBridge()
        val manager = manager(bridge)
        connect(bridge)
        manager.castImage("https://x/2.jpg", "Two")
        runCurrent()
        assertEquals(listOf("https://x/2.jpg|Two"), bridge.loads)
    }

    @Test fun aSlideshowAdvancesEveryIntervalAndWrapsAround() = runTest {
        val bridge = FakeCastBridge()
        val manager = manager(bridge)
        connect(bridge)
        manager.castSlideshow(listOf("a", "b", "c"), 3)
        runCurrent()
        assertTrue(manager.isSlideshowPlaying.value)
        assertEquals(listOf("a|Slideshow Media 1"), bridge.loads)
        advanceTimeBy(3_001); runCurrent()
        advanceTimeBy(3_000); runCurrent()
        advanceTimeBy(3_000); runCurrent()
        assertEquals(listOf("a|Slideshow Media 1", "b|Slideshow Media 2", "c|Slideshow Media 3", "a|Slideshow Media 1"), bridge.loads)
    }

    @Test fun theIntervalIsClampedToTwoToThirtySeconds() = runTest {
        val bridge = FakeCastBridge()
        val manager = manager(bridge)
        connect(bridge)
        manager.castSlideshow(listOf("a", "b"), 0)
        runCurrent()
        assertEquals(2, manager.slideshowInterval.value)
        manager.setSlideshowInterval(500)
        assertEquals(30, manager.slideshowInterval.value)
        manager.setSlideshowInterval(-4)
        assertEquals(2, manager.slideshowInterval.value)
    }

    @Test fun aSlideshowAskedForBeforeConnectingStartsOnConnect() = runTest {
        val bridge = FakeCastBridge()
        val manager = manager(bridge)
        manager.castSlideshow(listOf("a", "b"), 4)
        runCurrent()
        assertFalse(manager.isSlideshowPlaying.value)
        assertTrue(bridge.loads.isEmpty())
        bridge.heard!!.onSession(connected())
        runCurrent()
        assertTrue(manager.isSlideshowPlaying.value)
        assertEquals(listOf("a|Slideshow Media 1"), bridge.loads)
    }

    @Test fun anEmptySlideshowDoesNothing() = runTest {
        val bridge = FakeCastBridge()
        val manager = manager(bridge)
        connect(bridge)
        manager.castSlideshow(emptyList(), 5)
        runCurrent()
        assertFalse(manager.isSlideshowPlaying.value)
        assertTrue(bridge.loads.isEmpty())
    }

    @Test fun pauseStopsTheTimerAndPlayResumesFromTheSamePicture() = runTest {
        val bridge = FakeCastBridge()
        val manager = manager(bridge)
        connect(bridge)
        manager.castSlideshow(listOf("a", "b", "c"), 3)
        runCurrent()
        manager.setSlideshowPlaying(false)
        runCurrent()
        advanceTimeBy(20_000); runCurrent()
        assertEquals(listOf("a|Slideshow Media 1"), bridge.loads, "paused: no more pictures")
        manager.setSlideshowPlaying(true)
        runCurrent()
        assertEquals(listOf("a|Slideshow Media 1", "a|Slideshow Media 1"), bridge.loads, "resumes at the current picture")
    }

    @Test fun nextAndPreviousStepAndWrapEvenWhilePaused() = runTest {
        val bridge = FakeCastBridge()
        val manager = manager(bridge)
        connect(bridge)
        manager.castSlideshow(listOf("a", "b", "c"), 3)
        runCurrent()
        manager.setSlideshowPlaying(false)
        runCurrent()
        manager.previousPhoto()
        runCurrent()
        manager.nextPhoto()
        runCurrent()
        manager.nextPhoto()
        runCurrent()
        assertEquals(
            listOf("a|Slideshow Media 1", "c|Slideshow Media 3", "a|Slideshow Media 1", "b|Slideshow Media 2"),
            bridge.loads
        )
    }

    @Test fun steppingWithNoSlideshowDoesNothing() = runTest {
        val bridge = FakeCastBridge()
        val manager = manager(bridge)
        connect(bridge)
        manager.nextPhoto()
        manager.previousPhoto()
        runCurrent()
        assertTrue(bridge.loads.isEmpty())
    }

    @Test fun disconnectingClearsTheSessionTheSlideshowAndThePicture() = runTest {
        val bridge = FakeCastBridge()
        val manager = manager(bridge)
        bridge.heard!!.onDevices(listOf(tv))
        connect(bridge)
        manager.castSlideshow(listOf("a", "b"), 3)
        runCurrent()
        manager.disconnect()
        runCurrent()
        assertNull(manager.activeDevice.value)
        assertFalse(manager.isCasting.value)
        assertFalse(manager.isSlideshowPlaying.value)
        assertNull(manager.currentImageUri.value)
        assertTrue("disconnect" in bridge.calls)
        val before = bridge.loads.size
        advanceTimeBy(30_000); runCurrent()
        assertEquals(before, bridge.loads.size, "the timer is gone")
    }

    @Test fun aSessionEndedByTheDeviceClearsWhatWasShowing() = runTest {
        val bridge = FakeCastBridge()
        val manager = manager(bridge)
        connect(bridge)
        manager.castSlideshow(listOf("a", "b"), 3)
        runCurrent()
        bridge.heard!!.onSession(null)
        runCurrent()
        assertNull(manager.activeDevice.value)
        assertFalse(manager.isSlideshowPlaying.value)
        assertNull(manager.currentImageUri.value)
    }

    @Test fun volumeIsClampedAndSentOnlyWithASession() = runTest {
        val bridge = FakeCastBridge()
        val manager = manager(bridge)
        runCurrent()
        manager.setVolume(1.7f)
        runCurrent()
        assertEquals(1f, manager.volume.value)
        assertTrue(bridge.calls.none { it.startsWith("volume:") }, "no session, nothing to send")
        bridge.heard!!.onSession(connected())
        runCurrent()
        manager.setVolume(-2f)
        runCurrent()
        assertEquals(0f, manager.volume.value)
        assertEquals("volume:0.0", bridge.calls.last())
    }

    @Test fun muteTogglesAndIsSentOnlyWithASession() = runTest {
        val bridge = FakeCastBridge()
        val manager = manager(bridge)
        runCurrent()
        manager.toggleMute()
        runCurrent()
        assertTrue(manager.isMuted.value)
        assertTrue(bridge.calls.none { it.startsWith("muted:") })
        bridge.heard!!.onSession(connected())
        runCurrent()
        manager.toggleMute()
        runCurrent()
        assertFalse(manager.isMuted.value)
        assertEquals("muted:false", bridge.calls.last())
    }

    @Test fun aVolumeChangeOnTheDeviceIsShown() = runTest {
        val bridge = FakeCastBridge()
        val manager = manager(bridge)
        runCurrent()
        bridge.heard!!.onVolume(0.25f, true)
        runCurrent()
        assertEquals(0.25f, manager.volume.value)
        assertTrue(manager.isMuted.value)
    }

    @Test fun androidOnlyThingsAreOffAndAnErrorIsHarmless() = runTest {
        val bridge = FakeCastBridge()
        val manager = manager(bridge)
        runCurrent()
        bridge.heard!!.onError("boom")
        runCurrent()
        assertFalse(manager.isWebCompanionActive.value)
        assertNull(manager.webCompanionUrl.value)
        assertNull(manager.webCompanionFailure.value)
        assertNull(manager.getLocalIpAddress())
    }
}
