package com.smugview.app.data.cast
import com.smugview.app.util.SmugLog

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton
import androidx.mediarouter.media.MediaRouter
import androidx.mediarouter.media.MediaRouteSelector
import com.google.android.gms.cast.CastMediaControlIntent

interface CastManager {
    val discoveredDevices: StateFlow<List<CastDevice>>
    val activeDevice: StateFlow<CastDevice?>
    val isCasting: StateFlow<Boolean>
    val currentImageUri: StateFlow<String?>
    val slideshowInterval: StateFlow<Int>
    val isSlideshowPlaying: StateFlow<Boolean>
    val volume: StateFlow<Float>
    val isMuted: StateFlow<Boolean>
    val isWebCompanionActive: StateFlow<Boolean>
    /**
     * The address to type on the screen: `http://{phone ip}:{port}` once the Web Companion server is really bound, else null.
     * While [isWebCompanionActive] with a null URL, the server could not be started (R-58). Never show a URL that is not this.
     */
    val webCompanionUrl: StateFlow<String?>

    /** Why [webCompanionUrl] is null while [isWebCompanionActive]: ports in use, or no Wi-Fi address. Null when there is a URL or no link. */
    val webCompanionFailure: StateFlow<WebCompanionFailure?>

    fun startDiscovery()
    fun stopDiscovery()
    fun connectToDevice(device: CastDevice)
    fun disconnect()
    fun castImage(url: String, title: String)
    fun castSlideshow(urls: List<String>, intervalSeconds: Int)
    fun setSlideshowPlaying(playing: Boolean)
    fun setSlideshowInterval(seconds: Int)
    fun nextPhoto()
    fun previousPhoto()
    fun setVolume(volume: Float)
    fun toggleMute()
    fun getLocalIpAddress(): String?
}

@Singleton
class DefaultCastManager @Inject constructor(
    @ApplicationContext private val context: Context
) : CastManager {

    /**
     * Dispatcher backing the internal [scope] (discovery, connect simulation, slideshow timer).
     * Overridable via the test-only secondary constructor so unit tests can drive the timing with
     * a [kotlinx.coroutines.test.TestDispatcher] and virtual time instead of real Thread.sleep.
     */
    private var workDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.Default

    /**
     * Dispatcher for blocking network I/O (Roku ECP, DIAL, SSDP). Separate from [workDispatcher]
     * only so production uses Dispatchers.IO; the test constructor points both at one
     * TestDispatcher so the connect/discovery flow runs entirely under virtual time.
     */
    private var ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO

    /** The network and Cast SDK work (design 6-8). Production: [RealCastIo]; a test passes a fake through the constructor below. */
    private var io: CastIo = RealCastIo()

    @androidx.annotation.VisibleForTesting
    internal constructor(
        context: Context,
        dispatcher: kotlinx.coroutines.CoroutineDispatcher,
        castIo: CastIo? = null
    ) : this(context) {
        workDispatcher = dispatcher
        ioDispatcher = dispatcher
        scope = CoroutineScope(SupervisorJob() + dispatcher)
        slideshowDispatcher = dispatcher.limitedParallelism(1)
        if (castIo != null) io = castIo
    }

    private val _discoveredDevices = MutableStateFlow<List<CastDevice>>(emptyList())
    override val discoveredDevices: StateFlow<List<CastDevice>> = _discoveredDevices.asStateFlow()

    private val _activeDevice = MutableStateFlow<CastDevice?>(null)
    override val activeDevice: StateFlow<CastDevice?> = _activeDevice.asStateFlow()

    private val _isCasting = MutableStateFlow(false)
    override val isCasting: StateFlow<Boolean> = _isCasting.asStateFlow()

    private val _currentImageUri = MutableStateFlow<String?>(null)
    override val currentImageUri: StateFlow<String?> = _currentImageUri.asStateFlow()

    private val _slideshowInterval = MutableStateFlow(5)
    override val slideshowInterval: StateFlow<Int> = _slideshowInterval.asStateFlow()

    private val _isSlideshowPlaying = MutableStateFlow(false)
    override val isSlideshowPlaying: StateFlow<Boolean> = _isSlideshowPlaying.asStateFlow()

    private val _volume = MutableStateFlow(0.5f)
    override val volume: StateFlow<Float> = _volume.asStateFlow()

    private val _isMuted = MutableStateFlow(false)
    override val isMuted: StateFlow<Boolean> = _isMuted.asStateFlow()

    private val _isWebCompanionActive = MutableStateFlow(false)
    override val isWebCompanionActive: StateFlow<Boolean> = _isWebCompanionActive.asStateFlow()
    private val _webCompanionUrl = MutableStateFlow<String?>(null)
    override val webCompanionUrl: StateFlow<String?> = _webCompanionUrl.asStateFlow()
    private val _webCompanionFailure = MutableStateFlow<WebCompanionFailure?>(null)
    override val webCompanionFailure: StateFlow<WebCompanionFailure?> = _webCompanionFailure.asStateFlow()
    private var useWebCompanion = false

    private var discoveryJob: Job? = null
    /** Ends discovery [DISCOVERY_CAP_MS] after it started, whatever else happens (a sheet that never reported it was gone). */
    private var discoveryCapJob: Job? = null
    /** Ends discovery [DISCOVERY_AFTER_PICK_MS] after a device was picked: the list is no longer being read. */
    private var pickStopJob: Job? = null
    private val discoveryLock = Any()

    /**
     * Which connect attempt is the wanted one. [connectToDevice] takes a new number and [disconnect] takes one too, so an
     * attempt that is still waiting on the network when the user presses Stop (or picks another device) finds its number is no
     * longer current and gives up, instead of finishing as CONNECTED, binding the server and casting what the user stopped.
     * Everything an attempt commits happens under [connectLock], and so does [disconnect], so one of the two wins whole.
     */
    private val connectGeneration = java.util.concurrent.atomic.AtomicLong(0)
    private val connectLock = Any()
    /** The generation of the last Google attempt, to tell a session that the user has since stopped from the one wanted. */
    @Volatile private var googleAttemptGeneration = 0L
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    /**
     * The slideshow's list, index and job are read and written only on this one-at-a-time dispatcher (R-59): the timer loop, Stop
     * and Next/Previous used to touch them from different threads, and `% 0` or an index past the end of a list that Stop had just
     * emptied threw. It is a view of [workDispatcher], so a test's virtual time still drives it.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private var slideshowDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1)
    private var slideshowJob: Job? = null
    private var slideshowUrls: List<String> = emptyList()
    private var slideshowIndex = 0
    private var pendingCastImage: Pair<String, String>? = null
    private var pendingCastSlideshow: Pair<List<String>, Int>? = null
    private val webCompanionServer = WebCompanionServer(
        getActiveMediaUrl = { _currentImageUri.value },
        isVideoCheck = { url ->
            url.contains(".mp4") || url.contains("t=v") || url.contains("videoFormat=")
        }
    )

    override fun getLocalIpAddress(): String? {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
        val wifiNetwork = connectivityManager?.activeNetwork?.takeIf { network ->
            val caps = connectivityManager.getNetworkCapabilities(network)
            caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true
        } ?: connectivityManager?.allNetworks?.find { network ->
            val caps = connectivityManager.getNetworkCapabilities(network)
            caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true
        }

        var localAddress: java.net.InetAddress? = null
        if (wifiNetwork != null && connectivityManager != null) {
            try {
                val linkProps = connectivityManager.getLinkProperties(wifiNetwork)
                localAddress = linkProps?.linkAddresses?.find {
                    it.address is java.net.Inet4Address
                }?.address
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return localAddress?.hostAddress
    }

    private var castContext: com.google.android.gms.cast.framework.CastContext? = null

    private val mediaRouter: MediaRouter by lazy {
        MediaRouter.getInstance(context)
    }
    private val mediaRouteSelector: MediaRouteSelector by lazy {
        MediaRouteSelector.Builder()
            .addControlCategory(CastMediaControlIntent.categoryForCast(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID))
            .build()
    }
    private val ssdpDevices = java.util.concurrent.CopyOnWriteArrayList<CastDevice>()
    private val googleCastDevices = java.util.concurrent.CopyOnWriteArrayList<CastDevice>()

    private val mediaRouterCallback = object : MediaRouter.Callback() {
        override fun onRouteAdded(router: MediaRouter, route: MediaRouter.RouteInfo) {
            updateDiscoveredDevices()
        }
        override fun onRouteRemoved(router: MediaRouter, route: MediaRouter.RouteInfo) {
            updateDiscoveredDevices()
        }
        override fun onRouteChanged(router: MediaRouter, route: MediaRouter.RouteInfo) {
            updateDiscoveredDevices()
        }
    }

    /**
     * The id the list uses for the Google device a session reports as [sessionDeviceId]: its route's id (R-59), so the list can show
     * it as connected and Stop can find it. Falls back to the session's own id when no route carries it. Main thread.
     */
    private fun googleDeviceId(sessionDeviceId: String): String = try {
        val refs = mediaRouter.routes.map { CastRouteRef(it.id, com.google.android.gms.cast.CastDevice.getFromBundle(it.extras)?.deviceId) }
        routeIdFor(sessionDeviceId, refs) ?: sessionDeviceId
    } catch (e: Exception) {
        sessionDeviceId
    }

    private val sessionListener = object : com.google.android.gms.cast.framework.SessionManagerListener<com.google.android.gms.cast.framework.CastSession> {
        override fun onSessionStarted(session: com.google.android.gms.cast.framework.CastSession, sessionId: String) {
            val device = session.castDevice ?: return
            onGoogleSessionConnected(
                CastDevice(
                    id = googleDeviceId(device.deviceId),
                    name = device.friendlyName ?: "Google Cast Target",
                    ipAddress = device.ipAddress?.hostAddress ?: "",
                    type = CastType.GOOGLE,
                    state = ConnectionState.CONNECTED
                )
            )
        }

        override fun onSessionEnded(session: com.google.android.gms.cast.framework.CastSession, error: Int) {
            disconnect()
        }

        override fun onSessionStarting(session: com.google.android.gms.cast.framework.CastSession) {
            // An attempt the user has since stopped is not shown as connecting; its session is ended when it starts.
            if (googleAttemptGeneration != connectGeneration.get()) return
            val device = session.castDevice ?: return
            val castDevice = CastDevice(
                id = googleDeviceId(device.deviceId),
                name = device.friendlyName ?: "Google Cast Target",
                ipAddress = device.ipAddress?.hostAddress ?: "",
                type = CastType.GOOGLE,
                state = ConnectionState.CONNECTING
            )
            _activeDevice.value = castDevice
            _isCasting.value = false
        }

        override fun onSessionStartFailed(session: com.google.android.gms.cast.framework.CastSession, error: Int) {
            disconnect()
        }

        override fun onSessionSuspended(session: com.google.android.gms.cast.framework.CastSession, reason: Int) {}
        override fun onSessionResuming(session: com.google.android.gms.cast.framework.CastSession, sessionId: String) {}
        override fun onSessionResumed(session: com.google.android.gms.cast.framework.CastSession, wasSuspended: Boolean) {
            val device = session.castDevice ?: return
            onGoogleSessionConnected(
                CastDevice(
                    id = googleDeviceId(device.deviceId),
                    name = device.friendlyName ?: "Google Cast Target",
                    ipAddress = device.ipAddress?.hostAddress ?: "",
                    type = CastType.GOOGLE,
                    state = ConnectionState.CONNECTED
                )
            )
        }

        override fun onSessionResumeFailed(session: com.google.android.gms.cast.framework.CastSession, error: Int) {
            disconnect()
        }
        
        override fun onSessionEnding(session: com.google.android.gms.cast.framework.CastSession) {}
    }

    /**
     * The Google Cast framework says a session is up (started or resumed). If the user pressed Stop (or chose another device)
     * after that attempt began, nobody wants this session: it is ended, and the manager is not shown as casting to it.
     */
    @androidx.annotation.VisibleForTesting
    internal fun onGoogleSessionConnected(castDevice: CastDevice) {
        synchronized(connectLock) {
            if (googleAttemptGeneration != connectGeneration.get()) {
                SmugLog.d("CastManager") { "Google session arrived after Stop; ending it" }
                scope.launch(Dispatchers.Main) {
                    try { io.endGoogleSession() } catch (e: Exception) { e.printStackTrace() }
                }
                return
            }
            _activeDevice.value = castDevice
            _isCasting.value = true
            triggerPendingCasts()
        }
    }

    private fun updateDiscoveredDevices(newSsdp: List<CastDevice>? = null) {
        if (newSsdp != null) {
            ssdpDevices.clear()
            ssdpDevices.addAll(newSsdp)
        }

        scope.launch(Dispatchers.Main) {
            try {
                val routes = mediaRouter.routes.filter { route ->
                    route.matchesSelector(mediaRouteSelector) && !route.isDefault
                }
                val newGoogle = routes.map { route ->
                    CastDevice(
                        id = route.id,
                        name = route.name,
                        ipAddress = "google-cast-route",
                        type = CastType.GOOGLE,
                        state = if (_activeDevice.value?.id == route.id) {
                            _activeDevice.value?.state ?: ConnectionState.DISCONNECTED
                        } else {
                            ConnectionState.DISCONNECTED
                        }
                    )
                }
                googleCastDevices.clear()
                googleCastDevices.addAll(newGoogle)

                val combined = (ssdpDevices + googleCastDevices).distinctBy { it.id }
                _discoveredDevices.value = combined
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    init {
        scope.launch(Dispatchers.Main) {
            try {
                castContext = com.google.android.gms.cast.framework.CastContext.getSharedInstance(context)
                castContext?.sessionManager?.addSessionManagerListener(sessionListener, com.google.android.gms.cast.framework.CastSession::class.java)
                updateDiscoveredDevices()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    override fun startDiscovery() {
        synchronized(discoveryLock) {
            // A sheet opening again while an earlier pick's stop is pending: the list is being read again, so that stop is off,
            // and the 60 s cap starts over.
            pickStopJob?.cancel()
            pickStopJob = null
            discoveryCapJob?.cancel()
            discoveryCapJob = scope.launch {
                delay(DISCOVERY_CAP_MS)
                SmugLog.d("CastManager") { "Discovery: the ${DISCOVERY_CAP_MS / 1000} s cap reached; stopping" }
                stopDiscovery()
            }
            if (discoveryJob != null) return
        }

        scope.launch(Dispatchers.Main) {
            try {
                mediaRouter.addCallback(
                    mediaRouteSelector,
                    mediaRouterCallback,
                    MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY
                )
                updateDiscoveredDevices()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        discoveryJob = scope.launch {
            _discoveredDevices.value = emptyList()

            while (isActive) {
                val localDevices = io.ssdpScan()

                SmugLog.d("CastManager") { "SSDP: Scan successfully resolved ${localDevices.size} local devices." }
                updateDiscoveredDevices(localDevices)

                delay(8000)
            }
        }
    }

    override fun stopDiscovery() {
        synchronized(discoveryLock) {
            discoveryJob?.cancel()
            discoveryJob = null
            discoveryCapJob?.cancel()
            discoveryCapJob = null
            pickStopJob?.cancel()
            pickStopJob = null
        }
        scope.launch(Dispatchers.Main) {
            try {
                mediaRouter.removeCallback(mediaRouterCallback)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    override fun connectToDevice(device: CastDevice) {
        // The user picked a device: nothing reads the list any more, so discovery stops [DISCOVERY_AFTER_PICK_MS] later (not at
        // once: the sheet and the Cast route are still settling). Before this, a scan every 8 s ran until the process died.
        synchronized(discoveryLock) {
            pickStopJob?.cancel()
            pickStopJob = if (discoveryJob != null) scope.launch {
                delay(DISCOVERY_AFTER_PICK_MS)
                SmugLog.d("CastManager") { "Discovery: ${DISCOVERY_AFTER_PICK_MS / 1000} s after a device was picked; stopping" }
                stopDiscovery()
            } else null
        }

        val generation = connectGeneration.incrementAndGet()
        if (device.type == CastType.GOOGLE) googleAttemptGeneration = generation
        scope.launch {
            if (generation != connectGeneration.get()) return@launch
            val connectingDevice = device.copy(state = ConnectionState.CONNECTING)
            _activeDevice.value = connectingDevice
            _isCasting.value = false

            _discoveredDevices.value = _discoveredDevices.value.map {
                if (it.id == device.id) connectingDevice else it
            }

            if (device.type == CastType.GOOGLE) {
                scope.launch(Dispatchers.Main) {
                    try {
                        if (generation != connectGeneration.get()) return@launch
                        io.selectGoogleRoute(device.id)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            } else {
                val isSupported = when (device.type) {
                    CastType.ROKU -> io.rokuSupportsCasting(device.ipAddress)
                    CastType.AMAZON -> checkAmazonCastingSupport(device.ipAddress)
                    else -> true
                }
                // Every wait below can outlive the attempt: Stop, or another device, takes a new generation, and this one then
                // leaves everything alone (it would otherwise finish as CONNECTED and bind the server after the user stopped).
                if (generation != connectGeneration.get()) return@launch

                if (!isSupported) {
                    withContext(Dispatchers.Main) {
                        val message = when (device.type) {
                            CastType.ROKU -> "The Roku device does not support casting."
                            CastType.AMAZON -> "The Amazon device does not support casting."
                            else -> "This device does not support casting."
                        }
                        android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_LONG).show()
                    }
                    synchronized(connectLock) {
                        if (generation != connectGeneration.get()) return@launch
                        _activeDevice.value = null
                        _isCasting.value = false
                        _discoveredDevices.value = _discoveredDevices.value.map {
                            if (it.id == device.id) device.copy(state = ConnectionState.DISCONNECTED) else it
                        }
                    }
                    return@launch
                }

                delay(1000) // Connection simulator for Roku/Amazon targets
                if (generation != connectGeneration.get()) return@launch
                val dialSupported = device.type == CastType.AMAZON && io.amazonSupportsDial(device.ipAddress)

                // The commit. Stop (disconnect) takes the same lock, so either this runs whole before it and Stop then undoes it,
                // or it finds the new generation here and does nothing: no CONNECTED, no server bind, no pending casts.
                synchronized(connectLock) {
                    if (generation != connectGeneration.get()) return@launch
                    if (device.type == CastType.AMAZON && !dialSupported) {
                        useWebCompanion = true
                        _isWebCompanionActive.value = true
                        // Only the cast target device may fetch the (potentially private) media. The URL is published only
                        // when the server really is bound, on the port it really got (R-58); otherwise the UI says it failed.
                        val bound = bindWebCompanion(device.ipAddress)
                        _webCompanionUrl.value = bound.first
                        _webCompanionFailure.value = bound.second
                    } else {
                        useWebCompanion = false
                        _isWebCompanionActive.value = false
                        _webCompanionUrl.value = null
                        _webCompanionFailure.value = null
                        io.stopServer()
                    }
                    val connectedDevice = device.copy(state = ConnectionState.CONNECTED)
                    _activeDevice.value = connectedDevice
                    _isCasting.value = true

                    _discoveredDevices.value = _discoveredDevices.value.map {
                        if (it.id == device.id) connectedDevice else it
                    }

                    triggerPendingCasts()
                }
            }
        }
    }

    /** Binds the Web Companion server for [clientIp]: the URL to show, or null with the reason (ports in use, or no Wi-Fi address). */
    private fun bindWebCompanion(clientIp: String): Pair<String?, WebCompanionFailure?> =
        when (val started = io.startServer(port = WebCompanionServer.DEFAULT_FIRST_PORT, allowedClientIp = clientIp)) {
            is WebCompanionStart.Bound -> {
                val ip = io.localIpAddress()
                if (ip == null) {
                    io.stopServer() // nothing could be shown to type, so nothing is left listening
                    null to WebCompanionFailure.NoWifi
                } else {
                    webCompanionUrlFor(ip, started.port) to null
                }
            }
            is WebCompanionStart.Failed -> {
                SmugLog.d("CastManager") { "Web Companion could not start: ${started.reason}" }
                null to WebCompanionFailure.PortsInUse
            }
        }

    override fun disconnect() {
        synchronized(connectLock) {
            // Any connect attempt still in flight is now out of date (see connectGeneration).
            connectGeneration.incrementAndGet()
            stopSlideshow()
            useWebCompanion = false
            _isWebCompanionActive.value = false
            _webCompanionUrl.value = null
            _webCompanionFailure.value = null
            // Closing the server waits for its workers (up to a second): never on the caller's thread, which is Main.
            val stopGeneration = connectGeneration.get()
            scope.launch(ioDispatcher) {
                synchronized(connectLock) {
                    // A connect that began after this Stop owns the server now (it binds or stops it itself).
                    if (stopGeneration == connectGeneration.get()) io.stopServer()
                }
            }
            pendingCastImage = null
            pendingCastSlideshow = null
            val currentActive = _activeDevice.value
            if (currentActive != null) {
                _discoveredDevices.value = _discoveredDevices.value.map {
                    if (it.id == currentActive.id) it.copy(state = ConnectionState.DISCONNECTED) else it
                }
            }

            if (currentActive?.type == CastType.GOOGLE) {
                try {
                    scope.launch(Dispatchers.Main) {
                        try { io.endGoogleSession() } catch (e: Exception) { e.printStackTrace() }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            _activeDevice.value = null
            _isCasting.value = false
            _currentImageUri.value = null
        }
    }

    override fun castImage(url: String, title: String) {
        _currentImageUri.value = url
        val active = _activeDevice.value
        if (active == null || active.state != ConnectionState.CONNECTED) {
            pendingCastImage = Pair(url, title)
            pendingCastSlideshow = null
            return
        }
        pendingCastImage = null

        scope.launch {
            when (active.type) {
                CastType.GOOGLE -> {
                    withContext(Dispatchers.Main) {
                        castToGoogle(url, title)
                    }
                }
                CastType.ROKU -> {
                    castToRoku(active.ipAddress, url)
                }
                CastType.AMAZON -> {
                    castToAmazon(active.ipAddress, url)
                }
            }
        }
    }

    override fun castSlideshow(urls: List<String>, intervalSeconds: Int) {
        if (urls.isEmpty()) return
        val active = _activeDevice.value
        if (active == null || active.state != ConnectionState.CONNECTED) {
            pendingCastSlideshow = Pair(urls, intervalSeconds)
            pendingCastImage = null
            return
        }
        pendingCastSlideshow = null

        // Clamp to the same bounds as setSlideshowInterval so a bad caller can't create a
        // zero/negative delay tight-loop in startSlideshowLoop().
        _slideshowInterval.value = intervalSeconds.coerceIn(2, 30)
        _isSlideshowPlaying.value = true
        val list = urls.toList()
        scope.launch(slideshowDispatcher) {
            resetSlideshow()
            slideshowUrls = list
            slideshowIndex = 0
            startSlideshowLoop()
        }
    }

    private fun triggerPendingCasts() {
        val pendingImg = pendingCastImage
        if (pendingImg != null) {
            pendingCastImage = null
            castImage(pendingImg.first, pendingImg.second)
        }
        val pendingSlide = pendingCastSlideshow
        if (pendingSlide != null) {
            pendingCastSlideshow = null
            castSlideshow(pendingSlide.first, pendingSlide.second)
        }
    }

    override fun setSlideshowPlaying(playing: Boolean) {
        if (_isSlideshowPlaying.value == playing) return
        _isSlideshowPlaying.value = playing
        scope.launch(slideshowDispatcher) {
            if (playing) startSlideshowLoop() else slideshowJob?.cancel()
        }
    }

    override fun setSlideshowInterval(seconds: Int) {
        _slideshowInterval.value = seconds.coerceIn(2, 30)
    }

    override fun nextPhoto() {
        scope.launch(slideshowDispatcher) { stepSlideshow(+1) }
    }

    override fun previousPhoto() {
        scope.launch(slideshowDispatcher) { stepSlideshow(-1) }
    }

    /** On [slideshowDispatcher]. An emptied list (Stop got there first) does nothing. */
    private fun stepSlideshow(delta: Int) {
        val urls = slideshowUrls
        if (urls.isEmpty()) return
        slideshowIndex = Math.floorMod(slideshowIndex + delta, urls.size)
        castImage(urls[slideshowIndex], "Slideshow Media ${slideshowIndex + 1}")
        if (_isSlideshowPlaying.value) {
            startSlideshowLoop()
        }
    }

    override fun setVolume(volume: Float) {
        val clamped = volume.coerceIn(0f, 1f)
        val oldVol = _volume.value
        _volume.value = clamped
        val active = _activeDevice.value ?: return
        scope.launch {
            if (active.type == CastType.GOOGLE) {
                withContext(Dispatchers.Main) {
                    try {
                        castContext?.sessionManager?.currentCastSession?.volume = clamped.toDouble()
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            } else if (active.type == CastType.ROKU) {
                val diff = clamped - oldVol
                if (Math.abs(diff) > 0.05f) {
                    val steps = (Math.abs(diff) / 0.05f).toInt().coerceIn(1, 5)
                    val key = if (diff > 0) "VolumeUp" else "VolumeDown"
                    for (i in 0 until steps) {
                        sendRokuKeypress(active.ipAddress, key)
                    }
                }
            }
        }
    }

    override fun toggleMute() {
        val nextMute = !_isMuted.value
        _isMuted.value = nextMute
        val active = _activeDevice.value ?: return
        scope.launch {
            if (active.type == CastType.GOOGLE) {
                withContext(Dispatchers.Main) {
                    try {
                        castContext?.sessionManager?.currentCastSession?.isMute = nextMute
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            } else if (active.type == CastType.ROKU) {
                sendRokuKeypress(active.ipAddress, "VolumeMute")
            }
        }
    }

    private suspend fun sendRokuKeypress(ip: String, key: String) = withContext(ioDispatcher) {
        try {
            val urlString = "http://$ip:8060/keypress/$key"
            val url = java.net.URL(urlString)
            val connection = url.openConnection() as java.net.HttpURLConnection
            connection.requestMethod = "POST"
            connection.connectTimeout = 1500
            connection.responseCode
            connection.disconnect()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /** On [slideshowDispatcher]. */
    private fun startSlideshowLoop() {
        slideshowJob?.cancel()
        if (slideshowUrls.isEmpty()) return

        slideshowJob = scope.launch(slideshowDispatcher) {
            while (isActive && _isSlideshowPlaying.value) {
                val urls = slideshowUrls
                if (urls.isEmpty()) break
                val index = slideshowIndex.coerceIn(0, urls.size - 1)
                castImage(urls[index], "Slideshow Media ${index + 1}")

                delay(_slideshowInterval.value * 1000L)
                // Stop or a new list may have run while this waited; an empty list ends the loop.
                val after = slideshowUrls
                if (after.isEmpty()) break
                slideshowIndex = (index + 1) % after.size
            }
        }
    }

    /** Called from any thread: playing goes false at once; the list and job are cleared on [slideshowDispatcher], in call order. */
    private fun stopSlideshow() {
        _isSlideshowPlaying.value = false
        scope.launch(slideshowDispatcher) { resetSlideshow() }
    }

    /** On [slideshowDispatcher]. */
    private fun resetSlideshow() {
        slideshowJob?.cancel()
        slideshowJob = null
        slideshowUrls = emptyList()
        slideshowIndex = 0
    }

    private suspend fun performSsdpDiscovery(): List<CastDevice> = withContext(ioDispatcher) {
        val discovered = mutableListOf<CastDevice>()
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
        val lock = wifiManager?.createMulticastLock("SmugViewCastLock")
        // Opened below, closed in the finally whatever ends the scan (a cancelled scan used to leak the socket and its multicast group).
        var openSocket: java.net.MulticastSocket? = null
        try {
            lock?.acquire()
            val address = java.net.InetAddress.getByName("239.255.255.250")
            val port = 1900

            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
            val wifiNetwork = connectivityManager?.activeNetwork?.takeIf { network ->
                val caps = connectivityManager.getNetworkCapabilities(network)
                caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true
            } ?: connectivityManager?.allNetworks?.find { network ->
                val caps = connectivityManager.getNetworkCapabilities(network)
                caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true
            }

            var localAddress: java.net.InetAddress? = null
            if (wifiNetwork != null && connectivityManager != null) {
                try {
                    val linkProps = connectivityManager.getLinkProperties(wifiNetwork)
                    localAddress = linkProps?.linkAddresses?.find {
                        it.address is java.net.Inet4Address
                    }?.address
                } catch (e: Exception) {
                    android.util.Log.e("CastManager", "SSDP: Failed to get link properties", e)
                }
            }

            val socket = if (localAddress != null) {
                try {
                    SmugLog.d("CastManager") { "SSDP: Binding MulticastSocket to local Wi-Fi IP: $localAddress" }
                    java.net.MulticastSocket(java.net.InetSocketAddress(localAddress, 0))
                } catch (e: Exception) {
                    android.util.Log.e("CastManager", "SSDP: Failed to bind MulticastSocket to local IP, fallback to wildcard", e)
                    java.net.MulticastSocket()
                }
            } else {
                android.util.Log.w("CastManager", "SSDP: Local Wi-Fi IP not found, using wildcard binding")
                java.net.MulticastSocket()
            }
            openSocket = socket
            socket.soTimeout = 2000

            if (wifiNetwork != null) {
                try {
                    wifiNetwork.bindSocket(socket)
                    SmugLog.d("CastManager") { "SSDP: Bound MulticastSocket to Wi-Fi interface: $wifiNetwork" }
                } catch (e: Exception) {
                    android.util.Log.e("CastManager", "SSDP: Failed to bind MulticastSocket to Wi-Fi interface", e)
                }
            }

            try {
                socket.joinGroup(address)
                SmugLog.d("CastManager") { "SSDP: Joined multicast group $address" }
            } catch (e: Exception) {
                android.util.Log.e("CastManager", "SSDP: Failed to join multicast group", e)
            }

            val searchTargets = listOf(
                "urn:dial-multiscreen-org:device:dial:1",
                "roku:ecp"
            )
            for (st in searchTargets) {
                val mSearch = "M-SEARCH * HTTP/1.1\r\n" +
                        "HOST: 239.255.255.250:1900\r\n" +
                        "MAN: \"ssdp:discover\"\r\n" +
                        "MX: 3\r\n" +
                        "ST: $st\r\n\r\n"
                val buffer = mSearch.toByteArray()
                val packet = java.net.DatagramPacket(buffer, buffer.size, address, port)
                socket.send(packet)
            }

            val receiveBuffer = ByteArray(2048)
            val startTime = System.currentTimeMillis()
            while (System.currentTimeMillis() - startTime < 3000) {
                ensureActive() // a stopped discovery ends the scan here, not up to 3 s later
                try {
                    val receivePacket = java.net.DatagramPacket(receiveBuffer, receiveBuffer.size)
                    socket.receive(receivePacket)
                    val text = String(receivePacket.data, 0, receivePacket.length)
                    val ip = receivePacket.address.hostAddress

                    val locationLine = text.lines().find { it.startsWith("LOCATION:", ignoreCase = true) }
                    val locationUrl = locationLine?.let { it.substring(it.indexOf(':') + 1).trim() }

                    if (locationUrl != null && ip != null) {
                        if (discovered.none { it.ipAddress == ip }) {
                            val details = fetchDeviceFriendlyName(locationUrl)

                            val isRoku = locationUrl.contains("8060") ||
                                    text.contains("Roku", ignoreCase = true) ||
                                    details.modelName?.contains("Roku", ignoreCase = true) == true ||
                                    details.friendlyName?.contains("Roku", ignoreCase = true) == true

                            val isGoogle = locationUrl.contains("8008") ||
                                    locationUrl.contains("8009") ||
                                    text.contains("Chromecast", ignoreCase = true) ||
                                    text.contains("Google", ignoreCase = true) ||
                                    text.contains("Nest", ignoreCase = true) ||
                                    details.modelName?.contains("Chromecast", ignoreCase = true) == true ||
                                    details.modelName?.contains("Google", ignoreCase = true) == true ||
                                    details.modelName?.contains("Nest", ignoreCase = true) == true ||
                                    details.friendlyName?.contains("Google", ignoreCase = true) == true ||
                                    details.friendlyName?.contains("Nest", ignoreCase = true) == true

                            val type = when {
                                isRoku -> CastType.ROKU
                                isGoogle -> CastType.GOOGLE
                                else -> CastType.AMAZON
                            }

                            val name = details.friendlyName 
                                ?: details.modelName
                                ?: when (type) {
                                    CastType.ROKU -> "Roku TV"
                                    CastType.GOOGLE -> "Google Cast"
                                    CastType.AMAZON -> unnamedDialDeviceLabel(details.manufacturer)
                                }

                            SmugLog.d("CastManager") { "SSDP Discovered: name=$name, ip=$ip, type=$type" }
                            discovered.add(CastDevice(ip, name, ip, type))
                        }
                    }
                } catch (e: java.io.IOException) {
                    android.util.Log.e("CastManager", "SSDP: IOException during receive", e)
                    break
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.e("CastManager", "SSDP: General exception during scan", e)
        } finally {
            try {
                openSocket?.leaveGroup(java.net.InetAddress.getByName("239.255.255.250"))
            } catch (ex: Exception) {}
            try {
                openSocket?.close()
            } catch (ex: Exception) {}
            if (lock?.isHeld == true) {
                lock.release()
            }
        }
        discovered
    }

    /** The real network and Cast SDK work, as the manager did it before the [CastIo] seam existed. */
    private inner class RealCastIo : CastIo {
        override suspend fun ssdpScan(): List<CastDevice> = performSsdpDiscovery()
        override suspend fun rokuSupportsCasting(ip: String): Boolean = checkRokuCastingSupport(ip)
        override suspend fun amazonSupportsDial(ip: String): Boolean = checkAmazonDialSupport(ip)
        override fun startServer(port: Int, allowedClientIp: String) = webCompanionServer.start(port, allowedClientIp)
        override fun localIpAddress(): String? = getLocalIpAddress()
        override fun stopServer() = webCompanionServer.stop()
        override fun selectGoogleRoute(routeId: String) {
            val route = mediaRouter.routes.find { it.id == routeId }
            if (route != null) mediaRouter.selectRoute(route)
        }
        override fun endGoogleSession() {
            castContext?.sessionManager?.endCurrentSession(true)
        }
    }

    private companion object {
        /** Discovery never runs longer than this, whatever else happens (R-56: a scan every 8 s ran until the process died). */
        const val DISCOVERY_CAP_MS = 60_000L
        /** Discovery stops this long after the user picked a device. */
        const val DISCOVERY_AFTER_PICK_MS = 30_000L
    }

    private val isUnitTest: Boolean by lazy {
        try {
            Class.forName("org.junit.Assert")
            true
        } catch (e: Exception) {
            false
        }
    }

    private suspend fun checkRokuCastingSupport(ip: String): Boolean = withContext(ioDispatcher) {
        if (isUnitTest) return@withContext true
        try {
            val url = java.net.URL("http://$ip:8060/query/device-info")
            val connection = url.openConnection() as java.net.HttpURLConnection
            connection.connectTimeout = 2000
            connection.readTimeout = 2000
            val xmlText = connection.inputStream.bufferedReader().use { it.readText() }
            connection.disconnect()

            val playOnRoku = xmlText.substringAfter("<has-play-on-roku>", "").substringBefore("</has-play-on-roku>")
            if (playOnRoku.isNotBlank()) {
                playOnRoku.trim().lowercase() == "true"
            } else {
                true // Default to true if the tag isn't present (older versions)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            true // Default to true on connection failure so we still attempt casting
        }
    }

    private suspend fun checkAmazonCastingSupport(ip: String): Boolean = true

    private suspend fun checkAmazonDialSupport(ip: String): Boolean = withContext(ioDispatcher) {
        if (isUnitTest) return@withContext true
        try {
            val url = java.net.URL("http://$ip:8008/apps/Fireweb")
            val connection = url.openConnection() as java.net.HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 2000
            connection.readTimeout = 2000
            val code = connection.responseCode
            connection.disconnect()
            code == 200 || code == 201
        } catch (e: Exception) {
            false
        }
    }

    private suspend fun fetchDeviceFriendlyName(locationUrl: String): DeviceDetails = withContext(ioDispatcher) {
        try {
            val targetUrl = if (locationUrl.contains("8060")) {
                val base = locationUrl.substringBeforeLast(":8060")
                "$base:8060/query/device-info"
            } else {
                locationUrl
            }

            val url = java.net.URL(targetUrl)
            val connection = url.openConnection() as java.net.HttpURLConnection
            connection.connectTimeout = 1200
            connection.readTimeout = 1200
            val xmlText = connection.inputStream.bufferedReader().use { it.readText() }
            connection.disconnect()

            val friendlyName = when {
                targetUrl.contains("device-info") -> {
                    val userDevName = xmlText.substringAfter("<user-device-name>", "").substringBefore("</user-device-name>")
                    val userDevLoc = xmlText.substringAfter("<user-device-location>", "").substringBefore("</user-device-location>")
                    when {
                        userDevName.isNotBlank() -> userDevName
                        userDevLoc.isNotBlank() -> userDevLoc
                        else -> ""
                    }
                }
                else -> {
                    xmlText.substringAfter("<friendlyName>", "").substringBefore("</friendlyName>")
                }
            }

            val modelName = xmlText.substringAfter("<modelName>", "").substringBefore("</modelName>")
                .ifBlank { xmlText.substringAfter("<model-name>", "").substringBefore("</model-name>") }

            val manufacturer = xmlText.substringAfter("<manufacturer>", "").substringBefore("</manufacturer>")
                .ifBlank { xmlText.substringAfter("<vendor-name>", "").substringBefore("</vendor-name>") }

            DeviceDetails(
                friendlyName = friendlyName.ifBlank { null },
                modelName = modelName.ifBlank { null },
                manufacturer = manufacturer.ifBlank { null }
            )
        } catch (e: Exception) {
            DeviceDetails(null, null, null)
        }
    }

    private data class DeviceDetails(
        val friendlyName: String?,
        val modelName: String?,
        val manufacturer: String?
    )

    private suspend fun castToRoku(ip: String, imageUrl: String) = withContext(ioDispatcher) {
        try {
            val encodedUrl = java.net.URLEncoder.encode(imageUrl, "UTF-8")
            val mimeType = getMimeType(imageUrl)
            val isVideo = mimeType.startsWith("video/")
            val urlString = if (isVideo) {
                "http://$ip:8060/input/15985?t=v&u=$encodedUrl&videoFormat=mp4"
            } else {
                "http://$ip:8060/input/15985?t=p&u=$encodedUrl&tr=crossfade"
            }
            val url = java.net.URL(urlString)
            val connection = url.openConnection() as java.net.HttpURLConnection
            connection.requestMethod = "POST"
            connection.connectTimeout = 3000
            connection.readTimeout = 3000
            connection.setFixedLengthStreamingMode(0)
            val code = connection.responseCode
            SmugLog.d("CastManager") { "Roku Cast request: $urlString, Response code: $code" }
            connection.disconnect()
        } catch (e: Exception) {
            android.util.Log.e("CastManager", "Roku Cast failed for IP: $ip", e)
        }
    }

    private suspend fun castToAmazon(ip: String, imageUrl: String) = withContext(ioDispatcher) {
        if (useWebCompanion) return@withContext
        try {
            val urlString = "http://$ip:8008/apps/Fireweb"
            val url = java.net.URL(urlString)
            val connection = url.openConnection() as java.net.HttpURLConnection
            connection.requestMethod = "POST"
            connection.connectTimeout = 3000
            connection.readTimeout = 3000
            connection.doOutput = true
            
            connection.outputStream.use { os ->
                os.write(imageUrl.toByteArray(charset("UTF-8")))
            }
            
            val code = connection.responseCode
            SmugLog.d("CastManager") { "Amazon Cast request body: $imageUrl, Response code: $code" }
            connection.disconnect()
        } catch (e: Exception) {
            android.util.Log.e("CastManager", "Amazon Cast failed for IP: $ip", e)
        }
    }

    private fun getMimeType(url: String): String {
        val lower = url.lowercase()
        return when {
            lower.contains(".mp4") || lower.contains("/video") || lower.contains(".mov") || lower.contains(".m4v") -> "video/mp4"
            lower.contains(".png") -> "image/png"
            lower.contains(".gif") -> "image/gif"
            else -> "image/jpeg"
        }
    }

    private fun castToGoogle(imageUrl: String, title: String) {
        try {
            val session = castContext?.sessionManager?.currentCastSession
            val client = session?.remoteMediaClient
            if (client != null) {
                val mimeType = getMimeType(imageUrl)
                val isVideo = mimeType.startsWith("video/")
                val mediaType = if (isVideo) {
                    com.google.android.gms.cast.MediaMetadata.MEDIA_TYPE_MOVIE
                } else {
                    com.google.android.gms.cast.MediaMetadata.MEDIA_TYPE_PHOTO
                }

                val metadata = com.google.android.gms.cast.MediaMetadata(mediaType)
                metadata.putString(com.google.android.gms.cast.MediaMetadata.KEY_TITLE, title)

                val mediaInfo = com.google.android.gms.cast.MediaInfo.Builder(imageUrl)
                    .setContentType(mimeType)
                    .setStreamType(if (isVideo) com.google.android.gms.cast.MediaInfo.STREAM_TYPE_BUFFERED else com.google.android.gms.cast.MediaInfo.STREAM_TYPE_NONE)
                    .setMetadata(metadata)
                    .build()

                client.load(mediaInfo)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
