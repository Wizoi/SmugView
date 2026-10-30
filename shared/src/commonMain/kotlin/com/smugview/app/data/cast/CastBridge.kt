package com.smugview.app.data.cast

/**
 * What the shared cast logic needs from a platform's cast SDK (on iOS: a Swift class around the Google Cast sender SDK). Plain types and
 * one listener, so Swift implements it without coroutine helpers. Every method may be called from any thread; the implementation reports
 * back through the [CastBridgeListener] it was given, from any thread too.
 *
 * Only single pictures cross the bridge. The slideshow (its timer, order and wrap-around) is [BridgedCastManager]'s, the same model as
 * Android, so it is tested without a device. Avoid overloads: they become Objective-C selectors.
 * See docs/design/ios-casting.md.
 */
interface CastBridge {
    /** The one listener to report to, or null to stop reporting. */
    fun setListener(listener: CastBridgeListener?)

    fun startDiscovery()
    fun stopDiscovery()

    /** Starts a session with the device found earlier under [deviceId]. The outcome arrives through [CastBridgeListener.onSession]. */
    fun connect(deviceId: String)

    /** Ends the session and stops casting. */
    fun disconnect()

    /** Shows the picture at [url] on the connected device. A no-op without a session. */
    fun loadImage(url: String, title: String)

    /** [volume] is 0..1. */
    fun setVolume(volume: Float)

    fun setMuted(muted: Boolean)
}

interface CastBridgeListener {
    /** The devices in range now, each with its connection state. */
    fun onDevices(devices: List<CastDevice>)

    /** The session changed: the connected device (state CONNECTED), a connecting one, or null when none. */
    fun onSession(device: CastDevice?)

    /** The device's own volume changed (a button on the TV, or this app's request being applied). */
    fun onVolume(volume: Float, muted: Boolean)

    fun onError(message: String)
}
