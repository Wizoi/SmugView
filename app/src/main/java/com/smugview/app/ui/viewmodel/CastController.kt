package com.smugview.app.ui.viewmodel

import com.smugview.app.data.api.isVideo
import com.smugview.app.data.cast.CastDevice
import com.smugview.app.data.cast.CastManager
import com.smugview.app.data.repository.SmugMugRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Owns all Google Cast / Web Companion interaction. Extracted verbatim from
 * [SmugViewModel] as part of the facade decomposition — the ViewModel keeps its
 * public cast surface and simply delegates to this controller, so screens and
 * tests are unaffected.
 *
 * [getUnlockedPassword] is injected as a suspend lambda so [castCollection] can
 * resolve gallery passwords without this controller depending on the
 * ViewModel's node/album cache.
 */
class CastController(
    val castManager: CastManager,
    private val repository: SmugMugRepository,
    private val apiKey: String,
    private val scope: CoroutineScope,
    private val getUnlockedPassword: suspend (String) -> String?,
    /** Says something to the user (the app shows a toast). Called on the controller's scope. */
    private val onMessage: (String) -> Unit = {}
) {
    val discoveredDevices = castManager.discoveredDevices
    val activeCastDevice = castManager.activeDevice
    val isCasting = castManager.isCasting
    val currentCastedImageUri = castManager.currentImageUri
    val castSlideshowInterval = castManager.slideshowInterval
    val isCastSlideshowPlaying = castManager.isSlideshowPlaying
    val castVolume = castManager.volume
    val isCastMuted = castManager.isMuted
    val isWebCompanionActive = castManager.isWebCompanionActive
    /** The address to type on the Echo Show once the screen link is really bound; null otherwise (design 3.12). */
    val webCompanionUrl = castManager.webCompanionUrl

    private val _castedAlbumKey = MutableStateFlow<String?>(null)
    val castedAlbumKeyFlow: StateFlow<String?> = _castedAlbumKey.asStateFlow()

    var castedAlbumKey: String?
        get() = _castedAlbumKey.value
        set(value) {
            _castedAlbumKey.value = value
        }

    fun startCastDiscovery() {
        castManager.startDiscovery()
    }

    fun stopCastDiscovery() {
        castManager.stopDiscovery()
    }

    fun connectToCastDevice(device: CastDevice) {
        castManager.connectToDevice(device)
    }

    fun disconnectCast() {
        castManager.disconnect()
        castedAlbumKey = null
    }

    fun castImage(url: String, title: String) {
        castManager.castImage(url, title)
    }

    fun castSlideshow(urls: List<String>, intervalSeconds: Int = 5) {
        castManager.castSlideshow(urls, intervalSeconds)
    }

    fun castCollection(collectionId: Long) {
        scope.launch {
            try {
                val photos = repository.getPhotosInCollection(collectionId).first()
                val urls = photos.mapNotNull { it.archivedUri ?: it.thumbnailUrl?.replace("/Th/", "/X3/") }.toMutableList()

                val bookmarks = repository.getBookmarksForCollection(collectionId).first()

                // 1. Process Album (Gallery) bookmarks
                val albumBookmarks = bookmarks.filter { it.type == "Album" }
                for (albumBookmark in albumBookmarks) {
                    val albumKey = albumBookmark.itemKey
                    val password = getUnlockedPassword(albumKey)
                    try {
                        val albumPhotos = repository.getAllAlbumImages(albumKey, apiKey, password)
                        val albumUrls = albumPhotos.mapNotNull {
                            if (it.isVideo) {
                                it.videoUrl ?: it.thumbnailUrl?.replace("/Th/", "/X3/")
                            } else {
                                it.thumbnailUrl?.replace("/Th/", "/X3/")
                            }
                        }
                        urls.addAll(albumUrls)
                    } catch (e: com.smugview.app.data.repository.AlbumLockedException) {
                        // Q5 (a): a locked gallery stops the cast with one message; a collection that casts
                        // everything but that gallery would be wrong without saying so.
                        onMessage(e.userText)
                        return@launch
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }

                // 2. Process Image bookmarks
                val imageBookmarks = bookmarks.filter { it.type == "Image" }
                for (imageBookmark in imageBookmarks) {
                    val imgUrl = imageBookmark.thumbnailUrl?.replace("/Th/", "/X3/")
                    if (imgUrl != null) {
                        urls.add(imgUrl)
                    }
                }

                if (urls.isNotEmpty()) {
                    castSlideshow(urls)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun toggleCastSlideshowPlay() {
        castManager.setSlideshowPlaying(!isCastSlideshowPlaying.value)
    }

    fun setCastSlideshowInterval(seconds: Int) {
        castManager.setSlideshowInterval(seconds)
    }

    fun castNextPhoto() {
        castManager.nextPhoto()
    }

    fun castPreviousPhoto() {
        castManager.previousPhoto()
    }

    fun setCastVolume(volume: Float) {
        castManager.setVolume(volume)
    }

    fun toggleCastMute() {
        castManager.toggleMute()
    }
}
