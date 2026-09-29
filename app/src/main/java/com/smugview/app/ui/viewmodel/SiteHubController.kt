package com.smugview.app.ui.viewmodel

import com.smugview.app.BuildConfig
import com.smugview.app.data.api.AlbumImageData
import com.smugview.app.data.api.AlbumPreview
import com.smugview.app.data.api.AlbumDetails
import com.smugview.app.data.api.UserData
import com.smugview.app.data.repository.SmugMugRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Owns the "site" surfaces that are separable from session lifecycle: public-site
 * discovery, the live site preview, and the hub dashboard data
 * ([loadActiveSiteDetails]). Extracted verbatim from [SmugViewModel] as part of the
 * facade decomposition — the ViewModel keeps its public surface and delegates here.
 *
 * Session orchestration (loadUserProfile / selectSite / disconnectSite, the active
 * nickname, splash/browser/folder state, and the recent-sites list) stays in the
 * ViewModel; those methods call [loadActiveSiteDetails] and the clear* helpers here.
 * The hub load reaches back into ViewModel-owned state through injected lambdas:
 * [getRootNodeId], [getUnlockedPasswordSync], [getAlbumKeyFromWebUri], and
 * [setUserAlbums] (the shared album cache the ViewModel also reads).
 */
class SiteHubController(
    private val repository: SmugMugRepository,
    private val apiKey: String,
    private val scope: CoroutineScope,
    private val getRootNodeId: () -> String?,
    private val getUnlockedPasswordSync: (String) -> String?,
    private val getAlbumKeyFromWebUri: suspend (String?) -> String?,
    private val setUserAlbums: (List<AlbumDetails>) -> Unit
) {
    // Live validation / preview for the Site Explorer
    private val _sitePreview = MutableStateFlow<Result<UserData>?>(null)
    val sitePreview: StateFlow<Result<UserData>?> = _sitePreview.asStateFlow()

    private val _previewAlbums = MutableStateFlow<List<AlbumPreview>>(emptyList())
    val previewAlbums: StateFlow<List<AlbumPreview>> = _previewAlbums.asStateFlow()

    // Public-site discovery search
    private val _globalSearchState = MutableStateFlow<GlobalSearchUiState>(GlobalSearchUiState.Idle)
    val globalSearchState: StateFlow<GlobalSearchUiState> = _globalSearchState.asStateFlow()

    // Hub dashboard data for the active site
    private val _activeSiteRecentImages = MutableStateFlow<List<AlbumImageData>>(emptyList())
    val activeSiteRecentImages: StateFlow<List<AlbumImageData>> = _activeSiteRecentImages.asStateFlow()

    private val _activeSiteAlbums = MutableStateFlow<List<HubAlbumItem>>(emptyList())
    val activeSiteAlbums: StateFlow<List<HubAlbumItem>> = _activeSiteAlbums.asStateFlow()

    private val _activeSiteTopKeywords = MutableStateFlow<List<String>>(emptyList())
    val activeSiteTopKeywords: StateFlow<List<String>> = _activeSiteTopKeywords.asStateFlow()

    private val _activeSiteTotalGalleries = MutableStateFlow<Int?>(null)
    val activeSiteTotalGalleries: StateFlow<Int?> = _activeSiteTotalGalleries.asStateFlow()

    private val _activeSiteTotalPhotos = MutableStateFlow<Int?>(null)
    val activeSiteTotalPhotos: StateFlow<Int?> = _activeSiteTotalPhotos.asStateFlow()

    private val _isActiveSiteDetailsLoading = MutableStateFlow(false)
    val isActiveSiteDetailsLoading: StateFlow<Boolean> = _isActiveSiteDetailsLoading.asStateFlow()

    private var globalSearchJob: Job? = null

    fun loadActiveSiteDetails(nickname: String) {
        _isActiveSiteDetailsLoading.value = true
        _activeSiteRecentImages.value = emptyList()
        _activeSiteAlbums.value = emptyList()
        _activeSiteTopKeywords.value = emptyList()
        _activeSiteTotalGalleries.value = null
        _activeSiteTotalPhotos.value = null

        val rootNodeId = getRootNodeId()
        val password = rootNodeId?.let { getUnlockedPasswordSync(it) }
        if (BuildConfig.DEBUG) {
            android.util.Log.d("SmugViewModel", "loadActiveSiteDetails: starting for $nickname, rootNodeId=$rootNodeId, password=${password != null}")
        }

        scope.launch {
            try {
                coroutineScope {
                    val recentImagesDeferred = async {
                        try {
                            if (BuildConfig.DEBUG) {
                                android.util.Log.d("SmugViewModel", "loadActiveSiteDetails: fetching recent images")
                            }
                            val res = repository.getUserRecentImagesResponse(nickname, apiKey, count = 10, password = password)
                            if (BuildConfig.DEBUG) {
                                android.util.Log.d("SmugViewModel", "loadActiveSiteDetails: recent images response: ${res.response.images?.size} items")
                            }
                            res.response.images ?: emptyList()
                        } catch (e: Exception) {
                            if (BuildConfig.DEBUG) {
                                android.util.Log.e("SmugViewModel", "loadActiveSiteDetails recent images failed", e)
                            }
                            emptyList()
                        }
                    }
                    val albumsDeferred = async {
                        try {
                            if (BuildConfig.DEBUG) {
                                android.util.Log.d("SmugViewModel", "loadActiveSiteDetails: fetching albums")
                            }
                            val res = repository.getUserAlbumsResponse(nickname, apiKey, password = password)
                            if (BuildConfig.DEBUG) {
                                android.util.Log.d("SmugViewModel", "loadActiveSiteDetails: albums response: ${res.response.albums?.size} items")
                            }
                            _activeSiteTotalGalleries.value = res.response.pages?.total
                            val albums = res.response.albums ?: emptyList()
                            setUserAlbums(albums)
                            val expansions = res.expansions
                            albums.map { album ->
                                val highlightUri = album.uris?.highlightImage
                                val highlightUrl = if (highlightUri != null) {
                                    val expansion = expansions?.get(highlightUri)
                                    val thumb = expansion?.image?.thumbnailUrl
                                    thumb?.replace("/Th/", "/M/")?.replace("/th/", "/m/")?.replace("-Th.", "-M.")?.replace("-th.", "-m.")
                                } else null
                                HubAlbumItem(
                                    albumKey = album.albumKey,
                                    title = album.name,
                                    coverUrl = highlightUrl,
                                    imageCount = album.imageCount ?: 0,
                                    dateModified = album.dateModified,
                                    access = album.securityType,
                                    passwordHint = album.passwordHint
                                )
                            }
                        } catch (e: Exception) {
                            if (BuildConfig.DEBUG) {
                                android.util.Log.e("SmugViewModel", "loadActiveSiteDetails albums failed, falling back to persisted gallery index", e)
                            }
                            // Offline (or any other failure): fall back to the persisted gallery
                            // index built by buildInMemoryGalleryCache, so the Home tab still shows
                            // whatever galleries were already synced instead of going blank.
                            repository.albumsCache.value
                                .filter { it.type == "Album" }
                                .map { node ->
                                    HubAlbumItem(
                                        albumKey = node.getAlbumKey(),
                                        title = node.title,
                                        coverUrl = node.highlightImageUrl,
                                        imageCount = node.childCount ?: 0,
                                        dateModified = node.dateModified,
                                        access = node.access,
                                        passwordHint = node.passwordHint
                                    )
                                }
                        }
                    }
                    val topKeywordsDeferred = async {
                        try {
                            if (BuildConfig.DEBUG) {
                                android.util.Log.d("SmugViewModel", "loadActiveSiteDetails: fetching top keywords")
                            }
                            val res = repository.getUserTopKeywords(nickname, apiKey, nodeId = rootNodeId, password = password)
                            if (BuildConfig.DEBUG) {
                                android.util.Log.d("SmugViewModel", "loadActiveSiteDetails: top keywords response: ${res.response.userTopKeywords?.keywords?.size} items")
                            }
                            res.response.userTopKeywords?.keywords ?: emptyList()
                        } catch (e: Exception) {
                            if (BuildConfig.DEBUG) {
                                android.util.Log.e("SmugViewModel", "loadActiveSiteDetails top keywords failed", e)
                            }
                            emptyList()
                        }
                    }
                    val recentImages = recentImagesDeferred.await()
                    val albums = albumsDeferred.await()
                    val topKeywords = topKeywordsDeferred.await()
                    if (BuildConfig.DEBUG) {
                        android.util.Log.d("SmugViewModel", "loadActiveSiteDetails: completed. albums=${albums.size}, recent=${recentImages.size}, keywords=${topKeywords.size}")
                    }

                    // Resolve missing albumKeys for recentImages
                    val resolvedRecentImages = recentImages.map { img ->
                        val apiAlbumKey = img.uris?.imageAlbum?.substringAfterLast("/")
                            ?: img.uris?.album?.substringAfterLast("/")
                            ?: ""
                        if (apiAlbumKey.isNotEmpty()) {
                            img
                        } else {
                            val resolvedKey = getAlbumKeyFromWebUri(img.webUri)
                                ?: getAlbumKeyFromWebUri(img.thumbnailUrl)
                            if (resolvedKey != null && resolvedKey.isNotEmpty()) {
                                val finalUris = (img.uris ?: com.smugview.app.data.api.AlbumImageUris()).copy(
                                    imageAlbum = "/api/v2/album/$resolvedKey",
                                    album = "/api/v2/album/$resolvedKey"
                                )
                                img.copy(uris = finalUris)
                            } else {
                                img
                            }
                        }
                    }

                    // Compute actual total photos across all loaded albums
                    val computedTotalPhotos = albums.sumOf { it.imageCount }
                    _activeSiteTotalPhotos.value = computedTotalPhotos

                    _activeSiteRecentImages.value = resolvedRecentImages
                    _activeSiteAlbums.value = albums
                    _activeSiteTopKeywords.value = topKeywords.take(12)
                }
            } catch (e: Exception) {
                if (BuildConfig.DEBUG) {
                    android.util.Log.e("SmugViewModel", "loadActiveSiteDetails failed outer", e)
                }
            } finally {
                _isActiveSiteDetailsLoading.value = false
            }
        }
    }

    // Live validation for Site Explorer
    fun verifyAndPreviewNickname(nickname: String) {
        if (nickname.isBlank()) {
            _sitePreview.value = null
            _previewAlbums.value = emptyList()
            return
        }
        scope.launch {
            repository.getUserProfile(nickname, apiKey, ignoreErrors = "true").collect { result ->
                _sitePreview.value = result
                result.fold(
                    onSuccess = { userData ->
                        try {
                            val albums = repository.getUserAlbumsPreview(userData.nickName, apiKey)
                            _previewAlbums.value = albums.take(3)
                        } catch (e: Exception) {
                            _previewAlbums.value = emptyList()
                        }
                    },
                    onFailure = {
                        _previewAlbums.value = emptyList()
                    }
                )
            }
        }
    }

    fun searchPublicSites(query: String) {
        if (BuildConfig.DEBUG) {
            android.util.Log.d("SmugViewModel", "searchPublicSites called: query='$query'")
        }
        globalSearchJob?.cancel()
        if (query.isBlank()) {
            _globalSearchState.value = GlobalSearchUiState.Idle
            return
        }
        _globalSearchState.value = GlobalSearchUiState.Loading
        globalSearchJob = scope.launch {
            repository.searchPublicSites(query, apiKey).collect { result ->
                result.fold(
                    onSuccess = { sites ->
                        _globalSearchState.value = GlobalSearchUiState.Success(sites)
                    },
                    onFailure = { error ->
                        _globalSearchState.value = GlobalSearchUiState.Error(error.localizedMessage ?: "Failed to perform discovery search")
                    }
                )
            }
        }
    }

    fun clearGlobalSiteSearch() {
        globalSearchJob?.cancel()
        _globalSearchState.value = GlobalSearchUiState.Idle
    }

    /** Resets the hub dashboard flows (used by disconnectSite). */
    fun clearActiveSiteData() {
        _activeSiteRecentImages.value = emptyList()
        _activeSiteAlbums.value = emptyList()
        _activeSiteTopKeywords.value = emptyList()
        _activeSiteTotalGalleries.value = null
        _activeSiteTotalPhotos.value = null
    }

    /** Resets the site-preview flows (used by disconnectSite). */
    fun clearPreview() {
        _sitePreview.value = null
        _previewAlbums.value = emptyList()
    }
}
