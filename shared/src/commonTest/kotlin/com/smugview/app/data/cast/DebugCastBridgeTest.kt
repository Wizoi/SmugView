package com.smugview.app.data.cast

import com.smugview.app.data.offline.FakeKeyValueStore
import com.smugview.app.ui.component.CastStartGate
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DebugCastBridgeTest {
    @Test fun discoveryFindsTheOneDebugTv() = runTest {
        val bridge = DebugCastBridge(backgroundScope)
        val manager = BridgedCastManager(bridge, backgroundScope)
        manager.startDiscovery()
        runCurrent()
        assertEquals(listOf("Debug TV (simulator)"), manager.discoveredDevices.value.map { it.name })
    }

    @Test fun nothingIsFoundBeforeDiscoveryStarts() = runTest {
        val manager = BridgedCastManager(DebugCastBridge(backgroundScope), backgroundScope)
        runCurrent()
        assertTrue(manager.discoveredDevices.value.isEmpty())
    }

    @Test fun connectingTakesTheDelayThenCastsAndRecordsTheImage() = runTest {
        val bridge = DebugCastBridge(backgroundScope, connectDelayMs = 300)
        val manager = BridgedCastManager(bridge, backgroundScope)
        manager.startDiscovery()
        runCurrent()
        manager.connectToDevice(manager.discoveredDevices.value.single())
        runCurrent()
        assertFalse(manager.isCasting.value)
        advanceTimeBy(301)
        runCurrent()
        assertTrue(manager.isCasting.value)
        manager.castImage("https://photos.example/a.jpg", "A")
        runCurrent()
        assertEquals(ShownImage("https://photos.example/a.jpg", "A"), bridge.shown.value)
    }

    @Test fun anImageIsNotShownWithoutASession() = runTest {
        val bridge = DebugCastBridge(backgroundScope)
        bridge.loadImage("u", "t")
        assertNull(bridge.shown.value)
    }

    @Test fun volumeAndMuteComeBackThroughTheManager() = runTest {
        val bridge = DebugCastBridge(backgroundScope)
        val manager = BridgedCastManager(bridge, backgroundScope)
        manager.setVolume(0.8f)
        runCurrent()
        assertEquals(0.8f, manager.volume.value)
        bridge.setMuted(true)
        runCurrent()
        assertTrue(manager.isMuted.value)
    }

    @Test fun disconnectEndsTheSessionAndClearsTheImage() = runTest {
        val bridge = DebugCastBridge(backgroundScope, connectDelayMs = 10)
        val manager = BridgedCastManager(bridge, backgroundScope)
        manager.startDiscovery()
        runCurrent()
        manager.connectToDevice(manager.discoveredDevices.value.single())
        advanceTimeBy(11)
        runCurrent()
        manager.castImage("u", "t")
        runCurrent()
        manager.disconnect()
        runCurrent()
        assertFalse(manager.isCasting.value)
        assertNull(manager.activeDevice.value)
        assertNull(bridge.shown.value)
    }

    @Test fun createCastManagerFallsBackToTheDebugBridgeWhenNoneWasHandedIn() = runTest {
        assertNull(CastBridgeHost.bridge)
        val manager = createCastManager(CastBridgeHost.bridge, backgroundScope)
        manager.startDiscovery()
        runCurrent()
        assertEquals("Debug TV (simulator)", manager.discoveredDevices.value.single().name)
    }

    @Test fun createCastManagerUsesTheRealBridgeWhenGiven() = runTest {
        val calls = mutableListOf<String>()
        val real = object : CastBridge {
            override fun setListener(listener: CastBridgeListener?) { calls += "listener" }
            override fun startDiscovery() { calls += "start" }
            override fun stopDiscovery() {}
            override fun connect(deviceId: String) {}
            override fun disconnect() {}
            override fun loadImage(url: String, title: String) {}
            override fun setVolume(volume: Float) {}
            override fun setMuted(muted: Boolean) {}
        }
        createCastManager(real, backgroundScope).startDiscovery()
        assertEquals(listOf("listener", "start"), calls)
    }

    // The explanation (decision 4).

    @Test fun theExplainerIsOwedUntilContinueIsChosen() {
        val store = FakeKeyValueStore()
        val explainer = KeyValueCastExplainer(store)
        assertTrue(explainer.shouldExplain())
        explainer.markExplained()
        assertFalse(explainer.shouldExplain())
        assertFalse(KeyValueCastExplainer(store).shouldExplain())
    }

    @Test fun withoutAnExplainerTheGateStartsAtOnce() {
        var started = 0
        val gate = CastStartGate(null)
        gate.request { started++ }
        assertEquals(1, started)
        assertFalse(gate.showing)
    }

    @Test fun continueMarksExplainedThenStarts() {
        val store = FakeKeyValueStore()
        val gate = CastStartGate(KeyValueCastExplainer(store))
        var started = 0
        gate.request { started++ }
        assertTrue(gate.showing)
        assertEquals(0, started)
        gate.onContinue()
        assertFalse(gate.showing)
        assertEquals(1, started)
        gate.request { started++ }
        assertEquals(2, started)
    }

    @Test fun notNowScansNothingAndAsksAgainNextTime() {
        val gate = CastStartGate(KeyValueCastExplainer(FakeKeyValueStore()))
        var started = 0
        gate.request { started++ }
        gate.onNotNow()
        assertFalse(gate.showing)
        assertEquals(0, started)
        gate.request { started++ }
        assertTrue(gate.showing)
    }
}
