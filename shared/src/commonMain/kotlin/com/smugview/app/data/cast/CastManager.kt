package com.smugview.app.data.cast

import kotlinx.coroutines.flow.StateFlow

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
