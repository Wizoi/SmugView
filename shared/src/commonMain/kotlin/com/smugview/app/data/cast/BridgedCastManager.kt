package com.smugview.app.data.cast

import com.smugview.app.util.SmugLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * [CastManager] over a platform [CastBridge] (iOS: the Google Cast sender SDK). It keeps what the screens read as flows and owns the
 * slideshow, with the rules of Android's `DefaultCastManager`: a picture or slideshow asked for before a device is connected is held and
 * shown when it connects, the interval is clamped to 2..30 s, stepping wraps around, and Stop clears everything.
 *
 * [scope] must run its work one coroutine at a time (a single-threaded dispatcher, or a test dispatcher): every command and every report
 * from the bridge is handled there, so the state below needs no lock. Roku, Fire TV and the Web Companion are not part of this manager.
 */
class BridgedCastManager(
    private val bridge: CastBridge,
    private val scope: CoroutineScope
) : CastManager, CastBridgeListener {
    private val _discoveredDevices = MutableStateFlow<List<CastDevice>>(emptyList())
    override val discoveredDevices: StateFlow<List<CastDevice>> = _discoveredDevices

    private val _activeDevice = MutableStateFlow<CastDevice?>(null)
    override val activeDevice: StateFlow<CastDevice?> = _activeDevice

    private val _isCasting = MutableStateFlow(false)
    override val isCasting: StateFlow<Boolean> = _isCasting

    private val _currentImageUri = MutableStateFlow<String?>(null)
    override val currentImageUri: StateFlow<String?> = _currentImageUri

    private val _slideshowInterval = MutableStateFlow(DEFAULT_INTERVAL_SECONDS)
    override val slideshowInterval: StateFlow<Int> = _slideshowInterval

    private val _isSlideshowPlaying = MutableStateFlow(false)
    override val isSlideshowPlaying: StateFlow<Boolean> = _isSlideshowPlaying

    private val _volume = MutableStateFlow(0.5f)
    override val volume: StateFlow<Float> = _volume

    private val _isMuted = MutableStateFlow(false)
    override val isMuted: StateFlow<Boolean> = _isMuted

    // No Web Companion on this manager (see the class comment).
    override val isWebCompanionActive: StateFlow<Boolean> = MutableStateFlow(false)
    override val webCompanionUrl: StateFlow<String?> = MutableStateFlow(null)
    override val webCompanionFailure: StateFlow<WebCompanionFailure?> = MutableStateFlow(null)

    private var pendingImage: Pair<String, String>? = null
    private var pendingSlideshow: Pair<List<String>, Int>? = null
    private var slideshowUrls: List<String> = emptyList()
    private var slideshowIndex = 0
    private var slideshowJob: Job? = null

    init {
        bridge.setListener(this)
    }

    private val connected: Boolean get() = _activeDevice.value?.state == ConnectionState.CONNECTED

    override fun startDiscovery() = bridge.startDiscovery()

    override fun stopDiscovery() = bridge.stopDiscovery()

    override fun connectToDevice(device: CastDevice) {
        scope.launch {
            _discoveredDevices.value = _discoveredDevices.value.map {
                if (it.id == device.id) it.copy(state = ConnectionState.CONNECTING) else it
            }
            bridge.connect(device.id)
        }
    }

    override fun disconnect() {
        scope.launch {
            stopSlideshow()
            pendingImage = null
            pendingSlideshow = null
            val current = _activeDevice.value
            if (current != null) {
                _discoveredDevices.value = _discoveredDevices.value.map {
                    if (it.id == current.id) it.copy(state = ConnectionState.DISCONNECTED) else it
                }
            }
            _activeDevice.value = null
            _isCasting.value = false
            _currentImageUri.value = null
            bridge.disconnect()
        }
    }

    override fun castImage(url: String, title: String) {
        scope.launch { showImage(url, title) }
    }

    override fun castSlideshow(urls: List<String>, intervalSeconds: Int) {
        if (urls.isEmpty()) return
        val list = urls.toList()
        scope.launch {
            if (!connected) {
                pendingSlideshow = list to intervalSeconds
                pendingImage = null
                return@launch
            }
            pendingSlideshow = null
            // Clamped like setSlideshowInterval, so a bad caller cannot make a zero-delay loop.
            _slideshowInterval.value = intervalSeconds.coerceIn(MIN_INTERVAL_SECONDS, MAX_INTERVAL_SECONDS)
            _isSlideshowPlaying.value = true
            slideshowUrls = list
            slideshowIndex = 0
            startSlideshowLoop()
        }
    }

    override fun setSlideshowPlaying(playing: Boolean) {
        scope.launch {
            if (_isSlideshowPlaying.value == playing) return@launch
            _isSlideshowPlaying.value = playing
            if (playing) startSlideshowLoop() else slideshowJob?.cancel()
        }
    }

    override fun setSlideshowInterval(seconds: Int) {
        _slideshowInterval.value = seconds.coerceIn(MIN_INTERVAL_SECONDS, MAX_INTERVAL_SECONDS)
    }

    override fun nextPhoto() {
        scope.launch { step(+1) }
    }

    override fun previousPhoto() {
        scope.launch { step(-1) }
    }

    override fun setVolume(volume: Float) {
        val clamped = volume.coerceIn(0f, 1f)
        scope.launch {
            _volume.value = clamped
            if (_activeDevice.value != null) bridge.setVolume(clamped)
        }
    }

    override fun toggleMute() {
        scope.launch {
            val next = !_isMuted.value
            _isMuted.value = next
            if (_activeDevice.value != null) bridge.setMuted(next)
        }
    }

    override fun getLocalIpAddress(): String? = null

    // CastBridgeListener: reports from the platform, moved onto [scope].

    override fun onDevices(devices: List<CastDevice>) {
        scope.launch { _discoveredDevices.value = devices }
    }

    override fun onSession(device: CastDevice?) {
        scope.launch {
            _activeDevice.value = device
            val nowConnected = device?.state == ConnectionState.CONNECTED
            _isCasting.value = nowConnected
            if (nowConnected) {
                triggerPending()
            } else if (device == null) {
                // The session ended on the device's side (or never started): nothing is showing any more.
                stopSlideshow()
                _currentImageUri.value = null
            }
        }
    }

    override fun onVolume(volume: Float, muted: Boolean) {
        scope.launch {
            _volume.value = volume.coerceIn(0f, 1f)
            _isMuted.value = muted
        }
    }

    override fun onError(message: String) {
        SmugLog.d("BridgedCastManager") { "cast error: $message" }
    }

    // Everything below runs on [scope].

    private fun showImage(url: String, title: String) {
        _currentImageUri.value = url
        if (!connected) {
            pendingImage = url to title
            pendingSlideshow = null
            return
        }
        pendingImage = null
        bridge.loadImage(url, title)
    }

    private fun triggerPending() {
        pendingImage?.let { (url, title) ->
            pendingImage = null
            showImage(url, title)
        }
        pendingSlideshow?.let { (urls, interval) ->
            pendingSlideshow = null
            castSlideshow(urls, interval)
        }
    }

    private fun step(delta: Int) {
        val urls = slideshowUrls
        if (urls.isEmpty()) return
        slideshowIndex = ((slideshowIndex + delta) % urls.size + urls.size) % urls.size
        showImage(urls[slideshowIndex], slideshowTitle(slideshowIndex))
        if (_isSlideshowPlaying.value) startSlideshowLoop()
    }

    private fun startSlideshowLoop() {
        slideshowJob?.cancel()
        if (slideshowUrls.isEmpty()) return
        slideshowJob = scope.launch {
            while (isActive && _isSlideshowPlaying.value) {
                val urls = slideshowUrls
                if (urls.isEmpty()) break
                val index = slideshowIndex.coerceIn(0, urls.size - 1)
                showImage(urls[index], slideshowTitle(index))
                delay(_slideshowInterval.value * 1000L)
                // Stop or a new list may have run while this waited; an empty list ends the loop.
                val after = slideshowUrls
                if (after.isEmpty()) break
                slideshowIndex = (index + 1) % after.size
            }
        }
    }

    private fun stopSlideshow() {
        _isSlideshowPlaying.value = false
        slideshowJob?.cancel()
        slideshowJob = null
        slideshowUrls = emptyList()
        slideshowIndex = 0
    }

    private fun slideshowTitle(index: Int) = "Slideshow Media ${index + 1}"

    companion object {
        const val MIN_INTERVAL_SECONDS = 2
        const val MAX_INTERVAL_SECONDS = 30
        const val DEFAULT_INTERVAL_SECONDS = 5
    }
}
