package com.smugview.shared

import com.smugview.app.data.cast.CastBridge
import com.smugview.app.data.cast.CastBridgeListener
import com.smugview.app.data.cast.CastDevice
import com.smugview.app.data.cast.googleCastDeviceOf

/**
 * What Swift implements (GoogleCastBridge.swift). The Xcode app sees only this framework and it does not export :shared, so these
 * types use only Int, Float, Boolean and String. See docs/design/ios-casting.md.
 * Connection state codes: 0 disconnected, 1 connecting, 2 connected, 3 failed.
 */
interface SwiftCastBridge {
    fun setListener(listener: SwiftCastListener?)
    fun startDiscovery()
    fun stopDiscovery()
    fun connect(deviceId: String)
    fun disconnect()
    fun loadImage(url: String, title: String)
    fun setVolume(volume: Float)
    fun setMuted(muted: Boolean)
}

/** One device as Swift reports it. */
data class SwiftCastDevice(val id: String, val name: String, val ip: String, val state: Int)

/** What Swift calls back. The adapter forwards to the shared listener; any thread is fine. */
interface SwiftCastListener {
    fun onDevices(devices: List<SwiftCastDevice>)

    /** [id] null means no session. */
    fun onSession(id: String?, name: String?, ip: String?, state: Int)
    fun onVolume(volume: Float, muted: Boolean)
    fun onError(message: String)
}

/** Presents the Swift bridge as the shared [CastBridge]. */
class SwiftCastBridgeAdapter(private val swift: SwiftCastBridge) : CastBridge {
    override fun setListener(listener: CastBridgeListener?) {
        swift.setListener(listener?.let { SharedToSwiftListener(it) })
    }

    override fun startDiscovery() = swift.startDiscovery()
    override fun stopDiscovery() = swift.stopDiscovery()
    override fun connect(deviceId: String) = swift.connect(deviceId)
    override fun disconnect() = swift.disconnect()
    override fun loadImage(url: String, title: String) = swift.loadImage(url, title)
    override fun setVolume(volume: Float) = swift.setVolume(volume)
    override fun setMuted(muted: Boolean) = swift.setMuted(muted)
}

private class SharedToSwiftListener(private val target: CastBridgeListener) : SwiftCastListener {
    override fun onDevices(devices: List<SwiftCastDevice>) =
        target.onDevices(devices.map { googleCastDeviceOf(it.id, it.name, it.ip, it.state) })

    override fun onSession(id: String?, name: String?, ip: String?, state: Int) {
        val device: CastDevice? = id?.let { googleCastDeviceOf(it, name.orEmpty(), ip.orEmpty(), state) }
        target.onSession(device)
    }

    override fun onVolume(volume: Float, muted: Boolean) = target.onVolume(volume, muted)
    override fun onError(message: String) = target.onError(message)
}
