package com.smugview.app.data.cast

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** What [DebugCastBridge] was last asked to show. */
data class ShownImage(val url: String, val title: String)

/**
 * A pretend cast target for the simulator and tests: one device, "Debug TV (simulator)", found on [startDiscovery], connecting after
 * [connectDelayMs]. It records the last picture in [shown]. Not a real network device; it lets the sheet, slideshow and controller screen
 * be driven without a Chromecast. [scope] must be single-threaded like the manager's.
 */
class DebugCastBridge(
    private val scope: CoroutineScope,
    private val connectDelayMs: Long = 300L
) : CastBridge {
    private var listener: CastBridgeListener? = null
    private var connectJob: Job? = null
    private var discovering = false
    private var connected = false
    private var volume = 0.5f
    private var muted = false

    private val _shown = MutableStateFlow<ShownImage?>(null)
    val shown: StateFlow<ShownImage?> = _shown

    private fun device(state: ConnectionState) =
        CastDevice(id = DEVICE_ID, name = DEVICE_NAME, ipAddress = "127.0.0.1", type = CastType.GOOGLE, state = state)

    override fun setListener(listener: CastBridgeListener?) {
        this.listener = listener
    }

    override fun startDiscovery() {
        discovering = true
        scope.launch {
            if (discovering) listener?.onDevices(listOf(device(if (connected) ConnectionState.CONNECTED else ConnectionState.DISCONNECTED)))
        }
    }

    override fun stopDiscovery() {
        discovering = false
    }

    override fun connect(deviceId: String) {
        if (deviceId != DEVICE_ID) {
            listener?.onError("Unknown device")
            return
        }
        connectJob?.cancel()
        listener?.onSession(device(ConnectionState.CONNECTING))
        connectJob = scope.launch {
            delay(connectDelayMs)
            connected = true
            listener?.onSession(device(ConnectionState.CONNECTED))
            listener?.onVolume(volume, muted)
        }
    }

    override fun disconnect() {
        connectJob?.cancel()
        connected = false
        _shown.value = null
        listener?.onSession(null)
    }

    override fun loadImage(url: String, title: String) {
        if (connected) _shown.value = ShownImage(url, title)
    }

    override fun setVolume(volume: Float) {
        this.volume = volume.coerceIn(0f, 1f)
        listener?.onVolume(this.volume, muted)
    }

    override fun setMuted(muted: Boolean) {
        this.muted = muted
        listener?.onVolume(volume, muted)
    }

    companion object {
        const val DEVICE_ID = "debug-tv"
        const val DEVICE_NAME = "Debug TV (simulator)"
    }
}
