package com.smugview.app.ui.viewmodel

import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import androidx.paging.cachedIn
import androidx.paging.map

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.filter
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.smugview.app.BuildConfig
import com.smugview.app.data.api.AlbumImageData
import com.smugview.app.data.api.ExifData
import com.smugview.app.data.api.ImageSizeDetailsPayload
import com.smugview.app.data.api.UserData
import com.smugview.app.data.api.isVideo
import com.smugview.app.data.db.CachedNode
import com.smugview.app.data.db.CollectionBookmark
import com.smugview.app.data.db.CollectionPhoto
import com.smugview.app.data.db.OfflineCollection
import com.smugview.app.data.db.SearchHistory
import com.smugview.app.data.repository.SmugMugRepository
import com.smugview.app.data.db.toAlbumImageData
import com.smugview.app.data.worker.OfflineDownloadWorker
import com.smugview.app.data.cast.CastDevice
import com.smugview.app.data.cast.CastManager
import com.smugview.app.data.cast.ConnectionState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.collect
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileOutputStream
import okhttp3.OkHttpClient
import okhttp3.Request
import android.widget.Toast
import javax.inject.Inject

sealed interface SplashUiState {
    object Idle : SplashUiState
    object Loading : SplashUiState
    data class Success(val rootNodeId: String) : SplashUiState
    data class Error(val message: String) : SplashUiState
}

sealed interface BrowserUiState {
    object Loading : BrowserUiState
    data class Success(val nodes: List<CachedNode>) : BrowserUiState
    data class Error(val message: String) : BrowserUiState
}

data class SearchScope(
    val name: String,
    val nodeId: String? = null,
    val nodeUri: String? = null
)

sealed interface SearchUiState {
    object Idle : SearchUiState
    object Loading : SearchUiState
    data class Success(
        val photos: List<AlbumImageData>,
        val galleries: List<CachedNode>,
        val folders: List<CachedNode>,
        val photosError: String? = null
    ) : SearchUiState
    data class Error(val message: String) : SearchUiState
}

enum class BrowserTab {
    Folders,
    Search,
    TagSearch,
    Hub,
    Collections
}

enum class GalleryFilterType {
    ALL,
    IMAGES,
    VIDEOS
}

@HiltViewModel
class SmugViewModel @Inject constructor(
    application: Application,
    private val repository: SmugMugRepository,
    private val workManager: WorkManager,
    val castManager: CastManager,
    private val passwordStore: com.smugview.app.data.security.PasswordStore,
    /**
     * Backs the off-main-thread work below (the [unlockedNodeIds] combine and the
     * [getImageDetails] background resolution). Production gets the real [Dispatchers.Default]
     * via Hilt ([com.smugview.app.di.AppModule.provideDefaultDispatcher]); tests inject a
     * [kotlinx.coroutines.test.TestDispatcher] so this work runs under the test scheduler's
     * virtual time instead of the real thread pool — otherwise it escapes `Dispatchers.setMain`
     * (which only redirects `Dispatchers.Main`) and can leak a coroutine past its originating
     * test, surfacing as an `UncaughtExceptionsBeforeTest` failure on an unrelated later test.
     */
    private val defaultDispatcher: CoroutineDispatcher,
    /** Records the launch-unlock run (Phase 1b-3). The default keeps tests that don't care unchanged. */
    private val syncReporter: com.smugview.app.diag.SyncReporter = com.smugview.app.diag.SyncReporter.NOOP,
    /** Hilt supplies the real handle; it holds the navigation state across process death (Phase 3, step 3-9). */
    private val savedStateHandle: androidx.lifecycle.SavedStateHandle = androidx.lifecycle.SavedStateHandle()
) : AndroidViewModel(application) {

    private val apiKey = BuildConfig.SMUGMUG_API_KEY
    private val sharedPrefs = application.getSharedPreferences("smugview_prefs", Context.MODE_PRIVATE)
    // Encrypted at rest via PasswordStore; the compat adapter keeps existing call sites unchanged.
    private val passwordPrefs = com.smugview.app.data.security.PasswordPrefsCompat(passwordStore)
    private val searchStatusPrefs = application.getSharedPreferences("smugview_search_status", Context.MODE_PRIVATE)

    /**
     * Node IDs that are effectively unlocked — i.e. the node itself, or any ancestor in the
     * in-memory cache, has a saved password. Computed reactively off the main thread from the
     * encrypted password set and the cached node hierarchy, so UI can observe it (the lock icon
     * updates when a node is unlocked) and callers avoid the old main-thread `runBlocking` DB walk
     * that risked ANRs when rendering locked lists.
     */
    val unlockedNodeIds: StateFlow<Set<String>> =
        kotlinx.coroutines.flow.combine(
            passwordStore.unlockedKeys,
            repository.albumIndex
        ) { savedKeys, index ->
            computeUnlockedNodeIds(savedKeys, index.nodes)
        }
            .flowOn(defaultDispatcher)
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    private fun computeUnlockedNodeIds(
        savedKeys: Set<String>,
        nodes: List<com.smugview.app.data.db.CachedNode>
    ): Set<String> {
        if (savedKeys.isEmpty()) return emptySet()
        val byId = nodes.associateBy { it.nodeId }
        val result = HashSet<String>(savedKeys) // directly-saved keys are unlocked as-is
        for (node in nodes) {
            var current: com.smugview.app.data.db.CachedNode? = node
            var depth = 0
            while (current != null && depth < 20) {
                if (savedKeys.contains(current.nodeId) || savedKeys.contains(current.getAlbumKey())) {
                    result.add(node.nodeId)
                    break
                }
                val parentId = current.parentNodeId
                current = if (parentId == null || parentId == "root" || parentId == "search_result") {
                    null
                } else {
                    byId[parentId]
                }
                depth++
            }
        }
        return result
    }

    /**
     * Merges any buffered photo pages into [_rawPhotos] atomically and clears the buffer.
     * Using `update` (rather than assigning a locally-accumulated snapshot) means concurrent
     * publishers into _rawPhotos don't clobber each other.
     */
    private fun flushPendingPhotos(pending: MutableList<AlbumImageData>) {
        if (pending.isEmpty()) return
        val batch = pending.toList()
        pending.clear()
        _rawPhotos.update { current -> (current + batch).distinctBy { it.imageKey } }
    }

    /**
     * Non-blocking unlock check. Reads the reactively-maintained [unlockedNodeIds] set plus the
     * current navigation stack; performs no DB or network I/O so it is safe to call during
     * composition. Prefer observing [unlockedNodeIds] directly in Compose for recomposition.
     */
    fun isNodeUnlocked(nodeId: String): Boolean {
        if (nodeId.isBlank()) return false
        if (nodeId in unlockedNodeIds.value) return true
        // A parent currently on the navigation stack being unlocked also unlocks this node.
        val savedKeys = passwordStore.unlockedKeys.value
        return folderNavigationStack.any { savedKeys.contains(it.nodeId) }
    }

    /**
     * The scope of everything bound to the active site (design 3.1). Closed at tap time by [beginSite]
     * (a site switch) and [disconnectSite]; a fresh one is opened for the next site. Work that outlives
     * a site (casting, collections and downloads, history prefs) stays on [viewModelScope].
     */
    @Volatile
    private var session = SiteSession("", viewModelScope.coroutineContext[kotlinx.coroutines.Job])

    /**
     * The tap of "use this site" (also a launch and a retry): cancels every job of the site that was
     * active, drops its per-site holders, and opens the session of [nickname]. Runs before the new
     * site's profile is fetched, so a late answer from the old site cannot land on the new one (Q1).
     */
    private fun beginSite(nickname: String) {
        session.close()
        session = SiteSession(nickname, viewModelScope.coroutineContext[kotlinx.coroutines.Job])
        navigator = BrowserNavigator(session.scope, browserHost)
        browserHost.render(BrowserState())
        treeSyncJob = null
        unlockResyncJob = null
        unlockSubtreeIndexJob = null
        _userAlbums = null
        _exifStates.clear()
        _imageDetailsStates.clear()
        _imageSizeDetailsStates.clear()
        savedFolderStateBeforeSearch = null
        passwordPromptNode = null
        passwordError = null
        targetNodeToUnlockAfterSuccess = null
        _isBackgroundLoading.value = false
        _backgroundLoadingStatus.value = null
        siteHub.clearActiveSiteData()
        resetPerSiteState()
    }

    // Casting Integration — delegated to CastController (facade decomposition).
    private val cast = CastController(
        castManager = castManager,
        repository = repository,
        apiKey = apiKey,
        scope = viewModelScope,
        getUnlockedPassword = { albumKey -> getUnlockedPassword(albumKey) }
    )

    val discoveredDevices = cast.discoveredDevices
    val activeCastDevice = cast.activeCastDevice
    val isCasting = cast.isCasting
    val currentCastedImageUri = cast.currentCastedImageUri
    val castSlideshowInterval = cast.castSlideshowInterval
    val isCastSlideshowPlaying = cast.isCastSlideshowPlaying
    val castVolume = cast.castVolume
    val isCastMuted = cast.isCastMuted
    val isWebCompanionActive = cast.isWebCompanionActive
    val castedAlbumKeyFlow: StateFlow<String?> = cast.castedAlbumKeyFlow

    var castedAlbumKey: String?
        get() = cast.castedAlbumKey
        set(value) {
            cast.castedAlbumKey = value
        }

    fun startCastDiscovery() = cast.startCastDiscovery()

    fun stopCastDiscovery() = cast.stopCastDiscovery()

    fun getLocalIpAddress(): String? = cast.getLocalIpAddress()

    fun connectToCastDevice(device: CastDevice) = cast.connectToCastDevice(device)

    fun disconnectCast() = cast.disconnectCast()

    fun castImage(url: String, title: String) = cast.castImage(url, title)

    fun castSlideshow(urls: List<String>, intervalSeconds: Int = 5) = cast.castSlideshow(urls, intervalSeconds)

    fun castCollection(collectionId: Long) = cast.castCollection(collectionId)

    fun toggleCastSlideshowPlay() = cast.toggleCastSlideshowPlay()

    fun setCastSlideshowInterval(seconds: Int) = cast.setCastSlideshowInterval(seconds)

    fun castNextPhoto() = cast.castNextPhoto()

    fun castPreviousPhoto() = cast.castPreviousPhoto()

    fun setCastVolume(volume: Float) = cast.setCastVolume(volume)

    fun toggleCastMute() = cast.toggleCastMute()

    // Active Navigation Tab
    private val _activeTab = MutableStateFlow(BrowserTab.Folders)
    val activeTab: StateFlow<BrowserTab> = _activeTab.asStateFlow()

    fun setActiveTab(tab: BrowserTab, updateScopeFromBrowsing: Boolean = true) {
        val previousTab = _activeTab.value
        _activeTab.value = tab
        if (tab != BrowserTab.Search && previousTab == BrowserTab.Search) {
            cancelSearchJob()
        }
        if (tab != BrowserTab.TagSearch && previousTab == BrowserTab.TagSearch) {
            cancelTagSearchJob()
        }
        if (tab == BrowserTab.Search && previousTab == BrowserTab.Folders && updateScopeFromBrowsing) {
            val currentFolder = folderNavigationStack.lastOrNull()
            if (currentFolder != null) {
                setSearchScope(
                    SearchScope(
                        name = "Folder: ${currentFolder.title}",
                        nodeId = currentFolder.nodeId,
                        nodeUri = currentFolder.uri
                    )
                )
            } else {
                setSearchScope(
                    SearchScope(
                        name = "Entire Site",
                        nodeId = null,
                        nodeUri = null
                    )
                )
            }
        }
        if (tab == BrowserTab.TagSearch) {
            val currentFolder = folderNavigationStack.lastOrNull()
            val activeScope = if (currentFolder != null) {
                SearchScope(
                    name = "Folder: ${currentFolder.title}",
                    nodeId = currentFolder.nodeId,
                    nodeUri = currentFolder.uri
                )
            } else {
                SearchScope(
                    name = "Entire Site",
                    nodeId = null,
                    nodeUri = null
                )
            }
            val scopeChanged = activeScope != _searchScope.value
            setSearchScope(activeScope)
            triggerTagScopeScan(activeScope, clearSelected = scopeChanged)
        }
    }

    fun cancelSearchJob() = search.cancelSearchJob()

    fun cancelTagSearchJob() = tag.cancelTagSearchJob()

    fun clearScanProgress() = tag.clearScanProgress()

    // Splash State
    private val _splashState = MutableStateFlow<SplashUiState>(SplashUiState.Idle)
    val splashState: StateFlow<SplashUiState> = _splashState.asStateFlow()

    // Browser / Navigation State
    private val _browserState = MutableStateFlow<BrowserUiState>(BrowserUiState.Loading)
    val browserState: StateFlow<BrowserUiState> = _browserState.asStateFlow()

    var currentFolderId by mutableStateOf<String?>(null)
        private set

    val folderNavigationStack = mutableStateListOf<CachedNode>()

    /**
     * The services the Folders tab's [BrowserNavigator] needs. The three mirrors above
     * ([currentFolderId], [folderNavigationStack], [browserState]) and [savedFolderStateBeforeSearch]
     * are written only by [BrowserHost.render], on Main (design 3.2).
     */
    private val browserHost = object : BrowserHost {
        override fun rootId(): String? = _splashState.value.let { if (it is SplashUiState.Success) it.rootNodeId else null }

        override suspend fun savedPassword(nodeId: String): String? = getUnlockedPassword(nodeId)

        override fun requestPassword(node: CachedNode) {
            if (BuildConfig.DEBUG) {
                android.util.Log.d("SmugViewModel", "navigator: password prompt needed for nodeId=${node.nodeId}")
            }
            promptPassword(node)
        }

        override fun children(nodeId: String, force: Boolean, password: String?): Flow<Result<List<CachedNode>>> =
            repository.getNodeChildren(_activeNickname.value.orEmpty(), nodeId, apiKey, force, password)

        override suspend fun albumLineage(albumKey: String): List<CachedNode>? =
            repository.resolveAndCacheAlbumLineage(albumKey, apiKey, getUnlockedPassword(albumKey))

        override suspend fun nodeLineage(nodeId: String): List<CachedNode>? = repository.lineageOf(nodeId, apiKey)

        override suspend fun onLoadFailure(nodeId: String, password: String?, error: Throwable): LoadFailure {
            if (BuildConfig.DEBUG) {
                android.util.Log.e("SmugViewModel", "folder load failed for nodeId=$nodeId", error)
            }
            if (!password.isNullOrEmpty() && com.smugview.app.data.api.SmugMugErrorMapper.isPasswordRejection(error)) {
                // SmugMug explicitly rejected the saved password (401/403): it was changed or invalid
                passwordPrefs.edit().remove(nodeId).apply()
                val node = repository.getNodeById(nodeId)
                return if (node != null) {
                    passwordPromptNode = node
                    passwordError = "Saved password is no longer valid. Please re-enter."
                    LoadFailure(listing = null, popAfter = true)
                } else {
                    LoadFailure(
                        BrowserUiState.Error(com.smugview.app.data.api.SmugMugErrorMapper.userMessage(error, "Failed to load hierarchy")),
                        popAfter = true
                    )
                }
            }
            val isAccessDenied = error is retrofit2.HttpException && (error.code() == 401 || error.code() == 404)
            if (isAccessDenied && !nodeId.startsWith("virtual:")) {
                val node = repository.getNodeById(nodeId)
                if (node != null) {
                    promptPassword(node)
                    // Back out of the folder we navigated into; clear the loading state gracefully
                    return LoadFailure(BrowserUiState.Success(emptyList()), popAfter = true)
                }
                if (error is retrofit2.HttpException && error.code() == 404) {
                    viewModelScope.launch { repository.removeBookmarkGlobally(nodeId) }
                }
                return LoadFailure(
                    BrowserUiState.Error(com.smugview.app.data.api.SmugMugErrorMapper.userMessage(error, "Access Denied / Not Found")),
                    popAfter = false
                )
            }
            return LoadFailure(
                BrowserUiState.Error(com.smugview.app.data.api.SmugMugErrorMapper.userMessage(error, "Failed to load hierarchy")),
                popAfter = false
            )
        }

        override fun showTab(tab: BrowserTab) = setActiveTab(tab)

        override fun render(state: BrowserState) {
            if (folderNavigationStack.toList() != state.stack) {
                folderNavigationStack.clear()
                folderNavigationStack.addAll(state.stack)
            }
            currentFolderId = state.currentId
            savedFolderStateBeforeSearch = state.returnToSearch?.let { Pair(it.lastOrNull()?.nodeId ?: state.rootId, it) }
            _browserState.value = state.listing
        }
    }

    /** One per [SiteSession]; replaced, empty, by [beginSite]. */
    private var navigator = BrowserNavigator(session.scope, browserHost)

    // Unlocked nodes passwords map (cached in-memory for security)
    private val _unlockedPasswords = mutableMapOf<String, String>()

    // Password Prompt Dialog state
    var passwordPromptNode by mutableStateOf<CachedNode?>(null)
        private set
    var passwordError by mutableStateOf<String?>(null)
        private set

    private val _currentAlbumKey = MutableStateFlow("")
    val currentAlbumKey: StateFlow<String> = _currentAlbumKey.asStateFlow()

    var currentAlbumStyle by mutableStateOf<String?>("Collage")
        private set

    var currentAlbumTitle by mutableStateOf<String?>("")
        private set

    var currentAlbumWebUri by mutableStateOf<String?>("")
        private set

    private val _isBackgroundLoading = MutableStateFlow(false)
    val isBackgroundLoading: StateFlow<Boolean> = _isBackgroundLoading.asStateFlow()

    private val _backgroundLoadingStatus = MutableStateFlow<String?>(null)
    val backgroundLoadingStatus: StateFlow<String?> = _backgroundLoadingStatus.asStateFlow()

    private val _albumLoadError = MutableStateFlow<String?>(null)
    val albumLoadError: StateFlow<String?> = _albumLoadError.asStateFlow()

    private val _filterType = MutableStateFlow(GalleryFilterType.ALL)
    val filterType: StateFlow<GalleryFilterType> = _filterType.asStateFlow()

    fun updateFilterType(type: GalleryFilterType) {
        _filterType.value = type
    }

    // EXIF Metadata cache
    private val _exifStates = mutableMapOf<String, MutableStateFlow<Result<ExifData>?>>()

    // Image details cache
    private val _imageDetailsStates = mutableMapOf<String, MutableStateFlow<Result<AlbumImageData>?>>()

    // Image size details cache (used by pinch-to-zoom to pick real tiers)
    private val _imageSizeDetailsStates = mutableMapOf<String, MutableStateFlow<Result<ImageSizeDetailsPayload>?>>()

    // --- Dynamic Explorer & Validation States ---
    private val _activeNickname = MutableStateFlow<String?>(null)
    val activeNickname: StateFlow<String?> = _activeNickname.asStateFlow()

    /**
     * NodeIDs that carry a dot, for the active site only (design 3.5). Declared after
     * [_activeNickname]: a property initializer runs in declaration order.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val activeUpdateNodeIds: StateFlow<Set<String>> = _activeNickname
        .flatMapLatest { nick ->
            if (nick.isNullOrEmpty()) flowOf(emptyList()) else repository.getNodesWithActiveUpdates(nick)
        }
        .map { it.toSet() }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptySet()
        )

    // Active search/browsing scope — shared with the tag engine and tab switching.
    private val _searchScope = MutableStateFlow(SearchScope("Entire Site"))
    val searchScope: StateFlow<SearchScope> = _searchScope.asStateFlow()

    // Populated by the keyword tooling; kept in the ViewModel because that flow mutates it.
    val searchPhotosList = mutableStateListOf<AlbumImageData>()

    fun setSearchScope(scope: SearchScope) {
        _searchScope.value = scope
    }

    /** Reset per-site UI state (search results, keyword tags, scope) so switching the active
     *  site doesn't leak the previous site's results/tags. Called by selectSite/disconnectSite. */
    private fun resetPerSiteState() {
        search.reset()
        tag.reset()
        _searchScope.value = SearchScope("Entire Site")
    }

    // Image/gallery search — delegated to SearchController (facade decomposition).
    private val search = SearchController(
        repository = repository,
        apiKey = apiKey,
        scope = viewModelScope,
        siteScope = { session.scope },
        sharedPrefs = sharedPrefs,
        searchStatusPrefs = searchStatusPrefs,
        searchScope = searchScope,
        activeNickname = activeNickname,
        getUnlockedPassword = { key -> getUnlockedPassword(key) }
    )

    val searchQuery: String get() = search.searchQuery

    var searchResultTab: Int
        get() = search.searchResultTab
        set(value) { search.searchResultTab = value }

    val searchPhotosPagingFlow: StateFlow<Flow<PagingData<AlbumImageData>>> get() = search.searchPhotosPagingFlow

    val searchHistory: StateFlow<List<SearchHistory>> get() = search.searchHistory

    fun clearSearchHistory() = search.clearSearchHistory()

    fun deleteSearchQuery(query: String) = search.deleteSearchQuery(query)

    val searchState: StateFlow<SearchUiState> get() = search.searchState

    val isSearchPhotosLoading: StateFlow<Boolean> get() = search.isSearchPhotosLoading

    val isGalleriesFoldersLoading: StateFlow<Boolean> get() = search.isGalleriesFoldersLoading

    val searchGallerySortOrder: String get() = search.searchGallerySortOrder

    fun updateSearchGallerySortOrder(order: String) = search.updateSearchGallerySortOrder(order)

    val searchPhotosSortOrder: String get() = search.searchPhotosSortOrder

    fun updateSearchPhotosSortOrder(order: String) = search.updateSearchPhotosSortOrder(order)

    val keywordPhotosSortOrder: String get() = tag.keywordPhotosSortOrder

    fun updateKeywordPhotosSortOrder(order: String) = tag.updateKeywordPhotosSortOrder(order)

    private var treeSyncJob: kotlinx.coroutines.Job? = null
    private var unlockResyncJob: kotlinx.coroutines.Job? = null
    private var unlockSubtreeIndexJob: kotlinx.coroutines.Job? = null

    fun performSearch(query: String, forceRefresh: Boolean = false) = search.performSearch(query, forceRefresh)

    // Tag list and active selection state
    private val _availableTags = MutableStateFlow<Set<String>>(emptySet())
    val availableTags: StateFlow<Set<String>> = _availableTags.asStateFlow()

    private val _includedTags = MutableStateFlow<Set<String>>(emptySet())
    val includedTags: StateFlow<Set<String>> = _includedTags.asStateFlow()

    private val _excludedTags = MutableStateFlow<Set<String>>(emptySet())
    val excludedTags: StateFlow<Set<String>> = _excludedTags.asStateFlow()

    // Sorting state (default: Date Taken Recent First)
    private val _sortBy = MutableStateFlow("date_recent")
    val sortBy: StateFlow<String> = _sortBy.asStateFlow()

    // Local collections
    @OptIn(ExperimentalCoroutinesApi::class)
    val localCollections: StateFlow<List<OfflineCollection>> = activeNickname
        .flatMapLatest { nickname ->
            if (nickname.isNullOrEmpty()) {
                flowOf(emptyList())
            } else {
                repository.getLocalCollections(nickname)
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    // Recent site names + active site profile — driven by session lifecycle (stays here).
    private val _recentSites = MutableStateFlow<List<String>>(emptyList())
    val recentSites: StateFlow<List<String>> = _recentSites.asStateFlow()

    var lastSelectedCollectionIds by mutableStateOf<Set<Long>>(emptySet())

    private val _activeUserProfile = MutableStateFlow<UserData?>(null)
    val activeUserProfile: StateFlow<UserData?> = _activeUserProfile.asStateFlow()

    // The site's actual configured header/cover image (the root node's own HighlightImage), used
    // for the homepage banner instead of an arbitrary child album's thumbnail.
    private val _siteHeaderImageUrl = MutableStateFlow<String?>(null)
    val siteHeaderImageUrl: StateFlow<String?> = _siteHeaderImageUrl.asStateFlow()

    private fun loadSiteHeaderImage(nickname: String, rootId: String) {
        // Show whatever's cached immediately (works offline), then refresh from the network.
        session.scope.launch {
            repository.getNodeById(rootId)?.highlightImageUrl?.let { _siteHeaderImageUrl.value = it }
            repository.refreshSiteHeaderNode(nickname, rootId, apiKey)?.highlightImageUrl?.let {
                _siteHeaderImageUrl.value = it
            }
        }
    }

    // Site discovery + preview + hub dashboard — delegated to SiteHubController.
    // Declared before init{} because loadHistoryAndActiveSite() (in init) drives it.
    private val siteHub = SiteHubController(
        repository = repository,
        apiKey = apiKey,
        scope = viewModelScope,
        siteScope = { session.scope },
        getRootNodeId = {
            _splashState.value.let { if (it is SplashUiState.Success) it.rootNodeId else null }
        },
        getUnlockedPasswordSync = { nodeId -> getUnlockedPasswordSync(nodeId) },
        getAlbumKeyFromWebUri = { webUri -> getAlbumKeyFromWebUri(webUri) },
        setUserAlbums = { albums -> _userAlbums = albums }
    )

    val sitePreview: StateFlow<Result<UserData>?> get() = siteHub.sitePreview
    val previewAlbums: StateFlow<List<com.smugview.app.data.api.AlbumPreview>> get() = siteHub.previewAlbums
    val globalSearchState: StateFlow<GlobalSearchUiState> get() = siteHub.globalSearchState
    val activeSiteRecentImages: StateFlow<List<AlbumImageData>> get() = siteHub.activeSiteRecentImages
    val activeSiteAlbums: StateFlow<List<HubAlbumItem>> get() = siteHub.activeSiteAlbums
    val activeSiteTopKeywords: StateFlow<List<String>> get() = siteHub.activeSiteTopKeywords
    val activeSiteTotalGalleries: StateFlow<Int?> get() = siteHub.activeSiteTotalGalleries
    val activeSiteTotalPhotos: StateFlow<Int?> get() = siteHub.activeSiteTotalPhotos
    val isActiveSiteDetailsLoading: StateFlow<Boolean> get() = siteHub.isActiveSiteDetailsLoading

    fun verifyAndPreviewNickname(nickname: String) = siteHub.verifyAndPreviewNickname(nickname)

    fun searchPublicSites(query: String) = siteHub.searchPublicSites(query)

    fun clearGlobalSiteSearch() = siteHub.clearGlobalSiteSearch()

    private fun loadActiveSiteDetails(nickname: String) = siteHub.loadActiveSiteDetails(nickname)

    // Detail-view flag — shared with the photo-detail screens (stays in the ViewModel).
    private val _isViewingDetail = MutableStateFlow(false)
    val isViewingDetail: StateFlow<Boolean> = _isViewingDetail.asStateFlow()

    fun setViewingDetail(viewing: Boolean) {
        _isViewingDetail.value = viewing
    }

    // Keyword-search ("Tags" tab) — delegated to TagSearchController (facade decomposition).
    // Declared after its injected read-only deps (searchScope, isViewingDetail, activeNickname);
    // its init starts the selected-tags image-loading observe (previously launched in the VM init).
    private val tag = TagSearchController(
        repository = repository,
        apiKey = apiKey,
        viewModelScope = viewModelScope,
        siteScope = { session.scope },
        sharedPrefs = sharedPrefs,
        searchScope = searchScope,
        isViewingDetail = isViewingDetail,
        activeNickname = activeNickname,
        getUnlockedPassword = { key -> getUnlockedPassword(key) }
    )

    val isScanningTags: StateFlow<Boolean> get() = tag.isScanningTags
    val isLoadingPhotos: StateFlow<Boolean> get() = tag.isLoadingPhotos
    val scanProgress: StateFlow<String> get() = tag.scanProgress
    val allScopeTags: StateFlow<Map<String, Int>> get() = tag.allScopeTags
    val allScopePhotos: StateFlow<List<AlbumImageData>> get() = tag.allScopePhotos
    val keywordPhotosTotal: StateFlow<Int> get() = tag.keywordPhotosTotal

    private var targetNodeToUnlockAfterSuccess: CachedNode? = null

    // Per-site holders, declared before init{} because beginSite() (reached from init) resets them.
    var savedFolderStateBeforeSearch: Pair<String?, List<CachedNode>>? by mutableStateOf(null)
        private set

    private var _userAlbums: List<com.smugview.app.data.api.AlbumDetails>? = null

    enum class TagFilterState {
        INCLUDED,
        EXCLUDED
    }

    val selectedTags: StateFlow<Map<String, TagFilterState>> get() = tag.selectedTags

    var tagCloudLimit: Int
        get() = tag.tagCloudLimit
        set(value) { tag.tagCloudLimit = value }

    var tagSearchQuery: String
        get() = tag.tagSearchQuery
        set(value) { tag.tagSearchQuery = value }

    val tagCloudTags: StateFlow<List<Pair<String, Int>>> get() = tag.tagCloudTags
    val tagFilteredPhotos: StateFlow<List<AlbumImageData>> get() = tag.tagFilteredPhotos

    init {
        loadHistoryAndActiveSite()
        // The keyword image-loading observe is started by TagSearchController's own init.
        // No launch unlock here: the site sync unlocks the saved password roots first, then crawls,
        // so the crawl runs with the session cookie (design 3.8).
    }

    private fun loadHistoryAndActiveSite() {
        // Load recent site names
        val recentJoined = sharedPrefs.getString("recent_sites", "") ?: ""
        if (recentJoined.isNotEmpty()) {
            _recentSites.value = recentJoined.split(",").filter { it.isNotEmpty() }
        }

        // Load active site (if any)
        val active = sharedPrefs.getString("active_nickname", null)
        if (!active.isNullOrEmpty()) {
            _activeNickname.value = active
            repository.setActiveNickname(active)
            loadUserProfile(active)
            _activeTab.value = BrowserTab.Folders
        } else {
            _splashState.value = SplashUiState.Idle
            _activeTab.value = BrowserTab.Hub
        }
    }

    fun loadUserProfile(nickname: String) {
        if (BuildConfig.DEBUG) {
            android.util.Log.d("SmugViewModel", "loadUserProfile called: nickname=$nickname")
        }
        if (apiKey.isEmpty() || apiKey == "YOUR_API_KEY_HERE") {
            _splashState.value = SplashUiState.Error("API Key is missing or invalid. Set it in local.properties.")
            return
        }
        _splashState.value = SplashUiState.Loading
        beginSite(nickname)
        val mySession = session
        mySession.scope.launch {
            repository.getUserProfile(nickname, apiKey).collect { result ->
                if (!mySession.scope.isActive) return@collect
                result.fold(
                    onSuccess = { userData ->
                        if (BuildConfig.DEBUG) {
                            android.util.Log.d("SmugViewModel", "loadUserProfile success: $nickname")
                        }
                        _activeUserProfile.value = userData
                        val nodeUri = userData.uris.node
                        val rootId = repository.parseNodeIdFromUri(nodeUri)
                        _splashState.value = SplashUiState.Success(rootId)

                        // Unlock, gallery crawl and tree walk run as one job (startSiteSync, below).

                        navigator.navigate(NavIntent.Root)
                        startSiteSync(nickname, rootId)
                        loadSiteHeaderImage(nickname, rootId)

                        // Fetch active site recent images and top keywords in parallel
                        loadActiveSiteDetails(nickname)
                    },
                    onFailure = { error ->
                        _splashState.value = SplashUiState.Error(error.localizedMessage ?: "Connection error")
                    }
                )
            }
        }
    }

    fun retryActiveSite() {
        if (BuildConfig.DEBUG) {
            android.util.Log.d("SmugViewModel", "retryActiveSite called: activeNickname=${_activeNickname.value}")
        }
        _activeNickname.value?.let { loadUserProfile(it) }
    }

    // Selects and locks in a SmugMug nickname to browse
    fun selectSite(nickname: String) {
        if (BuildConfig.DEBUG) {
            android.util.Log.d("SmugViewModel", "selectSite called: nickname=$nickname")
        }
        val normalizedNickname = nickname.trim().lowercase()
        if (normalizedNickname.isEmpty()) return

        _splashState.value = SplashUiState.Loading
        // Q1: cancel everything bound to the old site NOW, at tap time, not when the new profile arrives.
        beginSite(normalizedNickname)
        val mySession = session
        mySession.scope.launch {
            repository.getUserProfile(normalizedNickname, apiKey).collect { result ->
                if (!mySession.scope.isActive) return@collect
                result.fold(
                    onSuccess = { userData ->
                        _activeUserProfile.value = userData
                        val nodeUri = userData.uris.node
                        val rootId = repository.parseNodeIdFromUri(nodeUri)

                        // Save as active site
                        sharedPrefs.edit().putString("active_nickname", normalizedNickname).apply()
                        _activeNickname.value = normalizedNickname
                        repository.setActiveNickname(normalizedNickname)

                        // Save to history
                        val currentList = _recentSites.value.toMutableList()
                        currentList.remove(normalizedNickname) // Remove duplicate if exists
                        currentList.add(0, normalizedNickname) // Add to top
                        val capped = currentList.take(5) // Limit history to top 5
                        sharedPrefs.edit().putString("recent_sites", capped.joinToString(",")).apply()
                        _recentSites.value = capped

                        // Transition state
                        _splashState.value = SplashUiState.Success(rootId)

                        // Unlock, gallery crawl and tree walk run as one job (startSiteSync, below).

                        if (BuildConfig.DEBUG) {
                            android.util.Log.d("SmugViewModel", "selectSite success: resolved rootId=$rootId")
                        }
                        navigator.navigate(NavIntent.Root)
                        startSiteSync(normalizedNickname, rootId)
                        loadSiteHeaderImage(normalizedNickname, rootId)

                        // Fetch active site details for the hub dashboard in parallel
                        loadActiveSiteDetails(normalizedNickname)
                        
                        _activeTab.value = BrowserTab.Folders
                    },
                    onFailure = { error ->
                        if (BuildConfig.DEBUG) {
                            android.util.Log.e("SmugViewModel", "selectSite failed: $error")
                        }
                        _splashState.value = SplashUiState.Error(error.localizedMessage ?: "Failed to resolve root node")
                    }
                )
            }
        }
    }

    // Clear active site and return to explorer
    fun disconnectSite() {
        if (BuildConfig.DEBUG) {
            android.util.Log.d("SmugViewModel", "disconnectSite called: activeNickname=${_activeNickname.value}")
        }
        beginSite("")
        sharedPrefs.edit().remove("active_nickname").apply()
        _activeNickname.value = null
        repository.setActiveNickname(null)
        _activeUserProfile.value = null
        _siteHeaderImageUrl.value = null
        siteHub.clearActiveSiteData()
        resetPerSiteState()
        // beginSite("") above replaced the navigator; its empty state is already rendered.
        _splashState.value = SplashUiState.Idle
        siteHub.clearPreview()
        _activeTab.value = BrowserTab.Hub
        clearGlobalSiteSearch()
    }

    suspend fun getNodeByAlbumKey(albumKey: String): CachedNode? {
        // Indexed lookup (nodeId PK, then a bounded albumUri match) instead of loading every
        // cached node into memory to scan — see the "getAllCachedNodes() full-table scan" rule.
        return repository.getNodeById(albumKey) ?: repository.getNodeByIdOrKey(albumKey)
    }

    // Browsing folder contents. Every move goes through the BrowserNavigator (design 3.2); these are
    // one-line delegates so callers and tests read the mirrors unchanged.
    fun clearEntireCacheAndReload() {
        viewModelScope.launch {
            repository.clearEntireCache()
            navigator.navigate(NavIntent.Refresh(force = true))
        }
    }

    /** Relist [nodeId]: the open folder in place, any other folder by its lineage (a shortcut). */
    fun loadFolderContents(nodeId: String, forceRefresh: Boolean = false) {
        if (BuildConfig.DEBUG) {
            android.util.Log.d("SmugViewModel", "loadFolderContents called: nodeId=$nodeId, forceRefresh=$forceRefresh")
        }
        if (nodeId == navigator.currentId) navigator.navigate(NavIntent.Refresh(forceRefresh))
        else navigator.navigate(NavIntent.Shortcut(nodeId))
    }

    /** Open a folder id from Collections: the Folders tab moves there, breadcrumb and listing together. */
    fun openFolderShortcut(nodeId: String) {
        setActiveTab(BrowserTab.Folders)
        navigator.navigate(NavIntent.Shortcut(nodeId))
    }

    fun navigateToFolderFromSearch(node: CachedNode) {
        if (BuildConfig.DEBUG) {
            android.util.Log.d("SmugViewModel", "navigateToFolderFromSearch: folderName=${node.title}, nodeId=${node.nodeId}")
        }
        navigator.navigate(NavIntent.FromSearch(node))
    }

    fun navigateBack(): Boolean {
        if (BuildConfig.DEBUG) {
            android.util.Log.d("SmugViewModel", "navigateBack called: savedStateExists=${savedFolderStateBeforeSearch != null}")
        }
        if (!navigator.canGoBack(viaSearch = true)) return false
        navigator.navigate(NavIntent.Back(viaSearch = true))
        return true
    }

    fun navigateToChildFolder(node: CachedNode) {
        if (BuildConfig.DEBUG) {
            android.util.Log.d("SmugViewModel", "navigateToChildFolder: title=${node.title}, nodeId=${node.nodeId}, access=${node.access}")
        }
        navigator.navigate(NavIntent.Child(node))
    }

    fun markNodeAsViewed(nodeId: String) {
        session.scope.launch {
            try {
                repository.markNodeAsViewed(nodeId)
            } catch (e: Exception) {
                if (BuildConfig.DEBUG) {
                    android.util.Log.e("SmugViewModel", "Failed to mark node as viewed: $nodeId", e)
                }
            }
        }
    }

    fun navigateBackFolder(): Boolean {
        if (BuildConfig.DEBUG) {
            android.util.Log.d("SmugViewModel", "navigateBackFolder called: stackSize=${folderNavigationStack.size}")
        }
        if (!navigator.canGoBack(viaSearch = false)) return false
        navigator.navigate(NavIntent.Back(viaSearch = false))
        return true
    }

    fun checkAndNavigateToAlbum(node: CachedNode, onNavigate: (albumKey: String) -> Unit) {
        session.scope.launch {
            val albumKey = node.getAlbumKey() // Always use helper — strips !images suffixes

            // Layer 1: DB lookup for access state
            val resolvedNode = repository.getNodeByIdOrKey(albumKey) ?: node
            var access = resolvedNode.access ?: node.access

            // Layer 2: Pre-flight API call if access is still unknown
            if (access == null) {
                if (BuildConfig.DEBUG) {
                    android.util.Log.d("SmugViewModel", "checkAndNavigateToAlbum: access unknown for $albumKey, performing pre-flight API check")
                }
                try {
                    val securityInfo = withTimeoutOrNull(3000L) {
                        repository.getAlbumSecurityInfo(albumKey, apiKey)
                    }
                    access = securityInfo?.securityType
                    if (BuildConfig.DEBUG) {
                        android.util.Log.d("SmugViewModel", "checkAndNavigateToAlbum: pre-flight result for $albumKey: securityType=$access, hint=${securityInfo?.passwordHint}")
                    }
                    // Cache the result back into DB if we got data
                    if (securityInfo != null) {
                        // Update existing cached node with resolved security info
                        repository.updateNodeAccess(resolvedNode.nodeId, securityInfo.securityType, securityInfo.passwordHint)
                    }
                } catch (e: Exception) {
                    if (BuildConfig.DEBUG) {
                        android.util.Log.w("SmugViewModel", "checkAndNavigateToAlbum: pre-flight check failed for $albumKey — falling back to navigate", e)
                    }
                    // Layer 3: Graceful fallback — navigate, handleAlbumLoadError recovers
                }
            }

            val isProtected = access == "Password" || access == "Inherited"
            // isNodeUnlocked() is intentionally non-blocking (in-memory only) so it is safe to call
            // during composition, but that means it can miss a password inherited from an ancestor
            // that lives in the DB rather than the in-memory cache. We're already in a coroutine
            // here, so fall back to the full suspend resolver before deciding to prompt — otherwise
            // we'd prompt for galleries the user has already unlocked.
            val alreadyUnlocked = isNodeUnlocked(resolvedNode.nodeId) ||
                getUnlockedPassword(resolvedNode.nodeId) != null
            if (isProtected && !alreadyUnlocked) {
                // Ensure prompt node has accurate password hint from pre-flight
                val promptNode = if (resolvedNode.access == null && access != null) {
                    resolvedNode.copy(access = access, passwordHint = resolvedNode.passwordHint ?: resolvedNode.passwordHint)
                } else resolvedNode
                val finalPromptNode = if (promptNode.access == null && access != null) {
                    promptNode.copy(access = access)
                } else promptNode
                promptPassword(finalPromptNode)
            } else {
                withContext(Dispatchers.Main) { onNavigate(albumKey) }
            }
        }
    }

    fun promptPassword(node: CachedNode) {
        if (BuildConfig.DEBUG) {
            android.util.Log.d("SmugViewModel", "promptPassword called: nodeId=${node.nodeId}, title=${node.title}")
        }
        session.scope.launch {
            targetNodeToUnlockAfterSuccess = node
            try {
                val resolution = repository.resolvePasswordRootNodeId(node.nodeId, apiKey)
                val rootNodeId = (resolution as? com.smugview.app.data.repository.RootResolution.Resolved)?.nodeId ?: node.nodeId
                if (rootNodeId != node.nodeId) {
                    var rootNode = repository.getNodeById(rootNodeId)
                    if (rootNode == null) {
                        val apiNode = repository.getNode(rootNodeId, apiKey)
                        // In memory only: Uris.ParentNode is the node's own !parent link (R-01), and only a
                        // listing may place a row, so the prompt node is not inserted.
                        rootNode = CachedNode(
                            nodeId = apiNode.nodeId,
                            parentNodeId = null,
                            type = apiNode.type,
                            title = apiNode.name ?: "Folder",
                            description = apiNode.description,
                            access = "Password",
                            passwordHint = apiNode.passwordHint,
                            uri = apiNode.uri,
                            childNodesUri = apiNode.uris.childNodes,
                            albumUri = apiNode.uris.album,
                            highlightImageUrl = null,
                            childCount = null,
                            sortIndex = 0,
                            webUri = apiNode.webUri
                        )
                    }
                    passwordPromptNode = rootNode
                } else {
                    passwordPromptNode = node
                }
            } catch (e: Exception) {
                passwordPromptNode = node
            }
            passwordError = null
        }
    }

    fun handleAlbumLoadError(albumKey: String, error: Throwable? = null) {
        session.scope.launch {
            val rejected = com.smugview.app.data.api.SmugMugErrorMapper.isPasswordRejection(error)
            val password = getUnlockedPassword(albumKey)
            if (!password.isNullOrEmpty() && rejected) {
                passwordPrefs.edit().remove(albumKey).apply()
            }
            // Also clear if nodeId is the albumKey
            val nodeKey = folderNavigationStack.lastOrNull { node ->
                val key = node.getAlbumKey()
                key == albumKey
            }?.nodeId
            if (nodeKey != null && rejected) {
                passwordPrefs.edit().remove(nodeKey).apply()
            }
            if (error is retrofit2.HttpException && error.code() == 404) {
                repository.removeBookmarkGlobally(albumKey)
            }
            
            val isAccessDenied = error is retrofit2.HttpException && (error.code() == 401 || error.code() == 404)
            if (isAccessDenied) {
                var node = repository.getNodeById(albumKey)
                if (node == null) {
                    node = repository.getNodeByIdOrKey(albumKey)
                }
                if (node == null) {
                    val matchedAlbum = _userAlbums?.find { it.albumKey == albumKey }
                    if (matchedAlbum != null) {
                        node = CachedNode(
                            nodeId = matchedAlbum.albumKey,
                            parentNodeId = "root",
                            type = "Album",
                            title = matchedAlbum.name,
                            description = null,
                            access = matchedAlbum.securityType,
                            passwordHint = matchedAlbum.passwordHint,
                            uri = matchedAlbum.uri,
                            childNodesUri = null,
                            albumUri = matchedAlbum.uri
                        )
                    }
                }
                if (node != null) {
                    promptPassword(node)
                }
            }
        }
    }

    // Password Submit Handler
    fun submitPassword(password: String, onSuccess: () -> Unit = {}) {
        if (BuildConfig.DEBUG) {
            android.util.Log.d("SmugViewModel", "submitPassword called for node=${passwordPromptNode?.nodeId}, target=${targetNodeToUnlockAfterSuccess?.nodeId}")
        }
        val promptNode = passwordPromptNode ?: return
        val targetNode = targetNodeToUnlockAfterSuccess ?: promptNode
        passwordError = null
        val normalizedPassword = password
            .replace('“', '"')
            .replace('”', '"')
            .replace('‘', '\'')
            .replace('’', '\'')
        session.scope.launch {
            try {
                val isCorrect = apiTestFetch(promptNode, normalizedPassword)
                if (isCorrect) {
                    val promptAlbumKey = promptNode.getAlbumKey()
                    passwordPrefs.edit()
                        .putString(promptNode.nodeId, normalizedPassword)
                        .putString(promptAlbumKey, normalizedPassword)
                        .apply()
                    
                    if (targetNode != promptNode) {
                        val targetAlbumKey = targetNode.getAlbumKey()
                        passwordPrefs.edit()
                            .putString(targetNode.nodeId, normalizedPassword)
                            .putString(targetAlbumKey, normalizedPassword)
                            .apply()
                    }

                    passwordPromptNode = null
                    targetNodeToUnlockAfterSuccess = null
                    
                    // Cascade: store this password for any descendant children nodes in DB
                    viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                        try {
                            val descendants = repository.getAllDescendants(promptNode.nodeId)
                            val editor = passwordPrefs.edit()
                            for (desc in descendants) {
                                editor.putString(desc.nodeId, normalizedPassword)
                                val key = desc.getAlbumKey()
                                if (key.isNotEmpty()) {
                                    editor.putString(key, normalizedPassword)
                                }
                            }
                            editor.apply()
                        } catch (e: Exception) {
                            // Ignore db query errors
                        }
                    }

                    // Re-load active site details if the active nickname is set
                    _activeNickname.value?.let { activeNick ->
                        loadActiveSiteDetails(activeNick)
                    }

                    if (targetNode.type == "Folder") {
                        navigateToChildFolder(targetNode)
                    } else {
                        selectAlbum(targetNode.getAlbumKey())
                        onSuccess()
                    }
                } else {
                    passwordError = "Incorrect password"
                }
            } catch (e: Exception) {
                passwordError = "Validation failed: ${e.localizedMessage}"
            }
        }
    }

    private suspend fun apiTestFetch(node: CachedNode, password: String): Boolean {
        return if (node.type == "Folder") {
            val isUnlocked = repository.unlockNode(node.nodeId, apiKey, password)
            if (isUnlocked) {
                try {
                    repository.getNodeChildren(_activeNickname.value.orEmpty(), node.nodeId, apiKey, true, password).first()
                } catch (e: Exception) {
                    // Ignore pre-fetch failures if unlock succeeded
                }
                // The synchronous fetch above only reaches the unlocked folder's DIRECT children,
                // which merges any galleries found at that level into the search index (see
                // SmugMugRepository.mergeAlbumsIntoIndex). Real sites commonly nest galleries under
                // sub-folders (e.g. locked "Family" -> "School" -> the actual gallery), so without
                // descending further those deeper galleries stay invisible to search even though the
                // folder itself now shows as unlocked. Index the rest of the subtree in the
                // background so unlocking doesn't block navigation into the folder just opened.
                indexUnlockedSubtreeInBackground(node.nodeId, password)
                resyncAfterUnlock()
                true
            } else {
                false
            }
        } else {
            val albumKey = node.getAlbumKey()
            repository.verifyAlbumPassword(albumKey, apiKey, password).also { if (it) resyncAfterUnlock() }
        }
    }

    /** A new session cookie makes more galleries visible to the index crawl, so crawl now, not at the next launch. */
    private fun resyncAfterUnlock() {
        val nickname = _activeNickname.value ?: return
        val rootId = session.rootNodeId ?: return
        unlockResyncJob?.cancel()
        unlockResyncJob = session.scope.launch(defaultDispatcher) {
            val invalidated = repository.resyncAfterUnlock(nickname, rootId, apiKey)
            val open = currentFolderId
            if (open != null && open in invalidated) navigator.navigate(NavIntent.Refresh(force = true, background = true))
        }
    }

    /**
     * Walks the rest of a just-unlocked folder's subtree so every nested gallery gets merged into
     * the search index, not just the folder's direct children — see
     * [SmugMugRepository.unlockAndIndexSubtree]. Runs in the background so unlocking doesn't block
     * navigation into the folder just opened; search results simply fill in as this progresses.
     */
    private fun indexUnlockedSubtreeInBackground(rootNodeId: String, password: String) {
        unlockSubtreeIndexJob?.cancel()
        val nickname = _activeNickname.value.orEmpty()
        unlockSubtreeIndexJob = session.scope.launch(defaultDispatcher) {
            repository.unlockAndIndexSubtree(nickname, rootNodeId, apiKey, password)
        }
    }

    fun dismissPasswordPrompt() {
        passwordPromptNode = null
        passwordError = null
    }

    fun getUnlockedPasswordSync(nodeId: String): String? {
        return passwordPrefs.getString(nodeId, null)
    }

    suspend fun getUnlockedPassword(nodeId: String): String? {
        if (nodeId == "site" || nodeId.isBlank()) return null
        // 1. Try direct lookup by nodeId or albumKey
        var pw = passwordPrefs.getString(nodeId, null)
        if (pw != null) return pw

        // 2. If nodeId is an album key, find the corresponding cached node to get its nodeId
        var currentId: String? = nodeId
        var node = repository.getNodeById(nodeId)
        if (node == null) {
            // Indexed lookup by nodeId OR album key. Previously this loaded the ENTIRE
            // cached_nodes table via getAllCachedNodes() and scanned it in memory, which hung
            // for many seconds on large caches (and is exactly the pattern AGENTS.md prohibits).
            node = repository.getNodeByIdOrKey(nodeId)
            if (node != null) {
                currentId = node.nodeId
                pw = passwordPrefs.getString(currentId, null)
                if (pw != null) return pw
            }
        } else {
            // If we found the node by nodeId, check if we have a password under its album key
            val albumKey = node.getAlbumKey()
            if (albumKey != nodeId) {
                pw = passwordPrefs.getString(albumKey, null)
                if (pw != null) return pw
            }
        }
        
        // 3. Inherited passwords: walk the ancestors from the lineage (`node/{id}!parents`, or the
        // cached rows offline). Read-only: nothing is written back to cached_nodes (R-04).
        return inheritedPasswordFor(currentId ?: nodeId, nodeId)
    }

    /** Nearest ancestor of [lineageId] with a saved password (by NodeID or AlbumKey); caches it under [cacheKey]. */
    private suspend fun inheritedPasswordFor(lineageId: String, cacheKey: String): String? {
        val chain = repository.lineageOf(lineageId, apiKey)
        for (i in 1 until chain.size) {
            val ancestor = chain[i]
            val found = passwordPrefs.getString(ancestor.nodeId, null)
                ?: ancestor.getAlbumKey().takeIf { it != ancestor.nodeId }?.let { passwordPrefs.getString(it, null) }
            if (found != null) {
                passwordPrefs.edit()
                    .putString(cacheKey, found)
                    .putString(chain[i - 1].nodeId, found)
                    .apply()
                return found
            }
        }
        return null
    }

    suspend fun getUnlockedPasswordForNode(node: CachedNode?): String? {
        if (node == null) return null
        val galleryKey = node.getAlbumKey()
        
        val pw = passwordPrefs.getString(galleryKey, null) ?: passwordPrefs.getString(node.nodeId, null)
        if (pw != null) return pw
        
        return inheritedPasswordFor(node.nodeId, node.nodeId)
    }

    private fun cleanAlbumKey(uri: String): String {
        return uri.substringAfterLast("/").substringBefore("!")
    }



    fun selectSingleTag(tag: String) = this.tag.selectSingleTag(tag)

    fun clearSelectedTags() = tag.clearSelectedTags()

    fun selectTag(tag: String, state: TagFilterState = TagFilterState.INCLUDED) = this.tag.selectTag(tag, state)

    fun removeTag(tag: String) = this.tag.removeTag(tag)

    fun toggleTagState(tag: String) = this.tag.toggleTagState(tag)

    fun setScopeLoadingSuppressed(suppressed: Boolean) = tag.setScopeLoadingSuppressed(suppressed)

    fun addKeywordToImage(imageKey: String, newKeyword: String, onComplete: (Boolean) -> Unit) =
        tag.addKeywordToImage(imageKey, newKeyword, onComplete)

    fun triggerTagScopeScan(scope: SearchScope, clearSelected: Boolean = true) = tag.triggerTagScopeScan(scope, clearSelected)

    // --- Photos Paging & Tag Filtering ---

    private val _rawPhotos = MutableStateFlow<List<AlbumImageData>>(emptyList())

    @OptIn(ExperimentalCoroutinesApi::class)
    val photosFlow: Flow<PagingData<AlbumImageData>> = combine(
        _rawPhotos,
        _includedTags,
        _excludedTags,
        _sortBy,
        _filterType
    ) { rawList, inc, exc, sort, typeFilter ->
        // 1. Filter by media type (Images / Videos)
        val typeFiltered = when (typeFilter) {
            GalleryFilterType.ALL -> rawList
            GalleryFilterType.IMAGES -> rawList.filter { !it.isVideo }
            GalleryFilterType.VIDEOS -> rawList.filter { it.isVideo }
        }

        // 2. Filter by tags
        val filtered = typeFiltered.filter { item ->
            val keywords = item.keywordsString?.split(",")?.map { it.trim().lowercase() }?.toSet() ?: emptySet()
            val isIncluded = inc.isEmpty() || keywords.any { it in inc }
            val isExcluded = exc.isNotEmpty() && keywords.any { it in exc }
            isIncluded && !isExcluded
        }

        // 3. Sort by method (only Date Taken)
        val sorted = when (sort) {
            "date_asc" -> filtered.sortedBy { it.date ?: "" }
            "date_desc" -> filtered.sortedByDescending { it.date ?: "" }
            else -> filtered
        }

        // 4. Wrap as PagingData
        PagingData.from(sorted)
    }.cachedIn(viewModelScope)

    private fun imagesUrlUpdate(images: List<AlbumImageData>, expansions: Map<String, com.smugview.app.data.api.ExpansionContainer>?) {
        images.forEach { img ->
            if (img.isVideo) {
                val largestVideoUri = img.uris?.largestVideo
                if (largestVideoUri != null && expansions != null) {
                    img.videoUrl = expansions[largestVideoUri]?.largestVideo?.url
                }
            }
        }
    }

    fun selectAlbum(albumKey: String, targetImageKey: String? = null) {
        if (albumKey == _currentAlbumKey.value && _rawPhotos.value.isNotEmpty()) {
            if (targetImageKey == null || _rawPhotos.value.any { it.imageKey == targetImageKey }) {
                return
            }
        }
        _currentAlbumKey.value = albumKey
        _includedTags.value = emptySet()
        _excludedTags.value = emptySet()
        _rawPhotos.value = emptyList()
        _availableTags.value = emptySet()
        _albumLoadError.value = null
        currentAlbumStyle = "Collage"
        currentAlbumTitle = ""

        if (!albumKey.startsWith("local_col_")) {
            session.scope.launch(com.smugview.app.diag.DiagContext.element(com.smugview.app.diag.DiagContext.newActionId("gallery"))) {
                try {
                    val node = repository.getNodeByIdOrKey(albumKey)
                    if (node != null) {
                        repository.markNodeAsViewed(node.nodeId)
                    }
                } catch (e: Exception) {
                    if (BuildConfig.DEBUG) {
                        android.util.Log.e("SmugViewModel", "Failed to mark album as viewed on selection: $albumKey", e)
                    }
                }
                // Q3: the Folders tab follows the gallery: breadcrumb and listing move to its folder together.
                navigator.navigate(NavIntent.Reveal(albumKey))

                if (targetImageKey != null) {
                    try {
                        val dbPhoto = repository.getCollectionPhotoByKey(targetImageKey)
                        val placeholder = if (dbPhoto != null) {
                            AlbumImageData(
                                imageKey = dbPhoto.imageKey,
                                title = dbPhoto.title,
                                caption = dbPhoto.title,
                                thumbnailUrl = dbPhoto.localFilePath ?: dbPhoto.thumbnailUrl,
                                archivedUri = dbPhoto.localFilePath ?: dbPhoto.archivedUri,
                                date = dbPhoto.dateTaken,
                                dateTime = dbPhoto.dateTaken,
                                keywords = dbPhoto.keywords,
                                webUri = null,
                                originalWidth = null,
                                originalHeight = null,
                                format = if (dbPhoto.localFilePath?.lowercase()?.endsWith(".mp4") == true || dbPhoto.archivedUri?.lowercase()?.contains(".mp4") == true) "MP4" else "JPG",
                                videoUrl = dbPhoto.localFilePath ?: dbPhoto.archivedUri
                            )
                        } else {
                            val dbBookmark = repository.getBookmarkByItemKey(targetImageKey)
                            if (dbBookmark != null) {
                                AlbumImageData(
                                    imageKey = dbBookmark.itemKey,
                                    title = dbBookmark.title,
                                    caption = dbBookmark.title,
                                    thumbnailUrl = dbBookmark.thumbnailUrl,
                                    archivedUri = dbBookmark.extraData ?: dbBookmark.thumbnailUrl,
                                    date = null,
                                    dateTime = null,
                                    keywords = null,
                                    webUri = null,
                                    originalWidth = null,
                                    originalHeight = null,
                                    format = "JPG",
                                    videoUrl = null
                                )
                            } else null
                        }

                        if (placeholder != null) {
                            _rawPhotos.value = listOf(placeholder)
                        }

                        val password = getUnlockedPassword(albumKey)
                        repository.getImage(targetImageKey, apiKey, password).collect { result ->
                            result.getOrNull()?.let { apiImg ->
                                // Atomic: several coroutines in selectAlbum publish into
                                // _rawPhotos concurrently; a read-modify-write on .value here
                                // would lose updates depending on scheduling.
                                _rawPhotos.update { current ->
                                    val updated = current.toMutableList()
                                    val index = updated.indexOfFirst { it.imageKey == targetImageKey }
                                    if (index >= 0) {
                                        updated[index] = apiImg
                                    } else {
                                        updated.add(apiImg)
                                    }
                                    updated
                                }
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        }

        if (albumKey.startsWith("local_col_")) {
            val collectionId = albumKey.removePrefix("local_col_").toLongOrNull() ?: 0L
            _isBackgroundLoading.value = true
            _backgroundLoadingStatus.value = "Loading offline collection..."
            viewModelScope.launch {
                val dbCollection = repository.getCollectionById(collectionId)
                currentAlbumTitle = dbCollection?.name ?: "Local Collection"
                
                repository.getPhotosInCollection(collectionId).collect { dbPhotos ->
                    val images = dbPhotos.map { dbPhoto ->
                        AlbumImageData(
                            imageKey = dbPhoto.imageKey,
                            title = dbPhoto.title,
                            caption = dbPhoto.title,
                            thumbnailUrl = dbPhoto.localFilePath ?: dbPhoto.thumbnailUrl,
                            archivedUri = dbPhoto.localFilePath ?: dbPhoto.archivedUri,
                            date = dbPhoto.dateTaken,
                            dateTime = dbPhoto.dateTaken,
                            keywords = dbPhoto.keywords,
                            webUri = null,
                            originalWidth = null,
                            originalHeight = null,
                            format = if (dbPhoto.localFilePath?.lowercase()?.endsWith(".mp4") == true || dbPhoto.archivedUri?.lowercase()?.contains(".mp4") == true) "MP4" else "JPG",
                            videoUrl = dbPhoto.localFilePath ?: dbPhoto.archivedUri
                        )
                    }
                    _rawPhotos.value = images
                    val tagsSet = images.flatMap { item ->
                        item.keywordsString?.split(",")?.map { it.trim().lowercase() } ?: emptyList<String>()
                    }.filter { it.isNotEmpty() }.toSet()
                    _availableTags.value = tagsSet
                    _isBackgroundLoading.value = false
                    _backgroundLoadingStatus.value = null
                }
            }
            return
        }

        _isBackgroundLoading.value = true
        _backgroundLoadingStatus.value = "Fetching album photos..."
        session.scope.launch {
            // Resolve any saved/inherited password, but bound it: the hierarchy walk can be starved
            // for several seconds under concurrent load (e.g. arriving here via "Jump to Gallery"
            // while image-detail resolution is still running). On timeout we treat it as "no saved
            // password" and fall through to the lock detection below, which prompts — far better
            // than an indefinitely spinning grid.
            val password = kotlinx.coroutines.withTimeoutOrNull(4000) {
                getUnlockedPassword(albumKey)
            }
            var albumDetails: com.smugview.app.data.api.AlbumDetails? = null
            try {
                albumDetails = repository.getAlbum(albumKey, apiKey, password)
                currentAlbumTitle = albumDetails?.name ?: ""
                currentAlbumStyle = albumDetails?.galleryStyle ?: "Collage"
                currentAlbumWebUri = albumDetails?.webUri ?: ""
                val nodeIdToMark = albumDetails?.nodeId
                if (nodeIdToMark != null) {
                    repository.markNodeAsViewed(nodeIdToMark)
                }
            } catch (e: Exception) {
                // Fallback to Collage
            }

            // If the album is password-protected and we have no working password yet, prompt for
            // it instead of silently rendering an empty grid. This is the landing point for
            // "Jump to Gallery" from a search/keyword image whose gallery was never browsed or
            // unlocked — the images come back redacted (empty) with no thrown error, so nothing
            // else triggers the prompt. Album metadata (incl. SecurityType) is public, so
            // getAlbum above still resolves it without a password.
            val securityType = albumDetails?.securityType
            if ((securityType == "Password" || securityType == "Inherited") && password.isNullOrEmpty()) {
                val promptNode = repository.getNodeByIdOrKey(albumKey) ?: CachedNode(
                    nodeId = albumDetails?.nodeId ?: albumKey,
                    parentNodeId = null,
                    type = "Album",
                    title = albumDetails?.name ?: "Gallery",
                    description = null,
                    access = securityType,
                    passwordHint = albumDetails?.passwordHint,
                    uri = albumDetails?.uri ?: "/api/v2/album/$albumKey",
                    childNodesUri = null,
                    albumUri = albumDetails?.uri ?: "/api/v2/album/$albumKey"
                )
                _isBackgroundLoading.value = false
                _backgroundLoadingStatus.value = null
                promptPassword(promptNode)
                return@launch
            }

            try {
                val firstPageResponse = repository.getAlbumImagesPage(albumKey, apiKey, password)
                val firstPageImages = firstPageResponse.response.images ?: emptyList()
                
                val expectedCount = albumDetails?.imageCount ?: 0
                if (firstPageImages.isEmpty() && expectedCount > 0) {
                    throw Exception("Failed to find any images in the gallery.")
                }

                val firstPageExpansions = firstPageResponse.expansions
                imagesUrlUpdate(firstPageImages, firstPageExpansions)
                if (targetImageKey != null && firstPageImages.any { it.imageKey == targetImageKey }) {
                    // Deliberate reset: the requested image is on this page.
                    _rawPhotos.value = firstPageImages
                } else {
                    _rawPhotos.update { current ->
                        (current + firstPageImages).distinctBy { it.imageKey }
                    }
                }

                var tagsSet = firstPageImages.flatMap { item ->
                    item.keywordsString?.split(",")?.map { it.trim().lowercase() } ?: emptyList<String>()
                }.filter { it.isNotEmpty() }.toSet()
                _availableTags.value = tagsSet

                val nextUrl = firstPageResponse.response.pages?.next
                if (nextUrl == null) {
                    _isBackgroundLoading.value = false
                    _backgroundLoadingStatus.value = null
                    return@launch
                }

                launch {
                    _backgroundLoadingStatus.value = "Streaming more photos..."
                    try {
                        var pageIndex = 2
                        var currentNextUrl: String? = nextUrl
                        // Buffer new pages and merge them into _rawPhotos atomically. Snapshotting
                        // the list into a local accumulator and writing it back wholesale would
                        // clobber concurrent writes from the target-image loader above.
                        val pendingImages = mutableListOf<AlbumImageData>()
                        var tagsUpdated = false
                        while (currentNextUrl != null && pageIndex <= repository.maxPagesPerFetch) {
                            try {
                                _backgroundLoadingStatus.value = "Downloading page $pageIndex..."
                                val nextPageResponse = repository.getAlbumImagesPageByUri(currentNextUrl, apiKey, password)
                                val nextPageImages = nextPageResponse.response.images ?: emptyList()
                                if (nextPageImages.isNotEmpty()) {
                                    val nextPageExpansions = nextPageResponse.expansions
                                    imagesUrlUpdate(nextPageImages, nextPageExpansions)
                                    pendingImages.addAll(nextPageImages)
                                    val newTags = nextPageImages.flatMap { item ->
                                        item.keywordsString?.split(",")?.map { it.trim().lowercase() } ?: emptyList<String>()
                                    }.filter { it.isNotEmpty() }
                                    tagsSet = tagsSet + newTags
                                    tagsUpdated = true
                                }
                                pageIndex++
                                currentNextUrl = nextPageResponse.response.pages?.next
                                
                                // Batch updates to prevent main thread recomposition storms
                                if (pageIndex % 3 == 0 || currentNextUrl == null) {
                                    flushPendingPhotos(pendingImages)
                                    if (tagsUpdated) {
                                        _availableTags.value = tagsSet
                                        tagsUpdated = false
                                    }
                                }
                            } catch (e: Exception) {
                                currentNextUrl = null
                            }
                        }
                        // Final safety updates
                        flushPendingPhotos(pendingImages)
                        _availableTags.value = tagsSet
                    } finally {
                        _isBackgroundLoading.value = false
                        _backgroundLoadingStatus.value = null
                    }
                }
            } catch (e: Exception) {
                _isBackgroundLoading.value = false
                _backgroundLoadingStatus.value = null
                _rawPhotos.value = emptyList() // Clear raw photos on failure
                _albumLoadError.value = com.smugview.app.data.api.SmugMugErrorMapper.userMessage(e, "Failed to load album images")
                handleAlbumLoadError(albumKey, e)
            }
        }
    }

    fun cycleTag(tag: String) {
        val tagLower = tag.lowercase()
        val inc = _includedTags.value
        val exc = _excludedTags.value

        when {
            tagLower in inc -> {
                _includedTags.value = inc - tagLower
                _excludedTags.value = exc + tagLower
            }
            tagLower in exc -> {
                _excludedTags.value = exc - tagLower
            }
            else -> {
                _includedTags.value = inc + tagLower
            }
        }
    }

    fun clearAllTags() {
        _includedTags.value = emptySet()
        _excludedTags.value = emptySet()
    }

    fun updateSort(method: String) {
        _sortBy.value = method
    }

    // --- Image EXIF Metadata ---

    fun getImageExif(imageKey: String): StateFlow<Result<ExifData>?> {
        val flow = _exifStates.getOrPut(imageKey) {
            val stateFlow = MutableStateFlow<Result<ExifData>?>(null)
            session.scope.launch {
                val albumKey = _currentAlbumKey.value
                val password = getUnlockedPassword(albumKey)
                repository.getImageExif(imageKey, apiKey, password).collect {
                    stateFlow.value = it
                }
            }
            stateFlow
        }
        return flow.asStateFlow()
    }

    fun getImageSizeDetails(imageKey: String, uri: String): StateFlow<Result<ImageSizeDetailsPayload>?> {
        val flow = _imageSizeDetailsStates.getOrPut(imageKey) {
            val stateFlow = MutableStateFlow<Result<ImageSizeDetailsPayload>?>(null)
            session.scope.launch {
                val albumKey = _currentAlbumKey.value
                val password = getUnlockedPassword(albumKey)
                repository.getImageSizeDetails(uri, apiKey, password).collect {
                    stateFlow.value = it
                }
            }
            stateFlow
        }
        return flow.asStateFlow()
    }

    fun getImageDetails(imageKey: String): StateFlow<Result<AlbumImageData>?> {
        val flow = _imageDetailsStates.getOrPut(imageKey) {
            val stateFlow = MutableStateFlow<Result<AlbumImageData>?>(null)
            session.scope.launch(defaultDispatcher) {
                val albumKey = _currentAlbumKey.value
                val searchPhoto = searchPhotosList.find { it.imageKey == imageKey }
                val webUri = searchPhoto?.webUri
                val thumbnailUrl = searchPhoto?.thumbnailUrl ?: ""
                
                val resolvedPassword = getPasswordForPhotoUrl(webUri) ?: getPasswordForPhotoUrl(thumbnailUrl)
                val password = resolvedPassword ?: getUnlockedPassword(albumKey)
                
                var finalResult: Result<AlbumImageData>? = null
                repository.getImage(imageKey, apiKey, password).collect { result ->
                    finalResult = result
                }

                var detailedImage = finalResult?.getOrNull()
                val finalWebUri = detailedImage?.webUri ?: webUri
                
                // If fetch failed (detailedImage is null) or was successful but album links are redacted
                if (detailedImage == null || (detailedImage.uris?.album == null && detailedImage.uris?.imageAlbum == null)) {
                    val finalThumbnailUrl = detailedImage?.thumbnailUrl ?: thumbnailUrl
                    
                    // 1. Resolve the parent album key using finalWebUri or finalThumbnailUrl
                    var resolvedKey = getAlbumKeyFromWebUri(finalWebUri)
                    if (resolvedKey.isNullOrEmpty() && finalThumbnailUrl.isNotEmpty()) {
                        resolvedKey = getAlbumKeyFromWebUri(finalThumbnailUrl)
                    }
                    
                    // Fallback to segment matching in cached nodes if still unresolved
                    if (resolvedKey.isNullOrEmpty() && finalThumbnailUrl.isNotEmpty()) {
                        val delimiter = "/i-$imageKey"
                        if (finalThumbnailUrl.contains(delimiter)) {
                            val partBefore = finalThumbnailUrl.substringBefore(delimiter)
                            val albumSegment = partBefore.substringAfterLast('/')
                            if (albumSegment.isNotEmpty()) {
                                val allNodes = repository.getCachedNodesForSite(_activeNickname.value.orEmpty())
                                val matchedNode = allNodes.find { it.webUri?.contains(albumSegment) == true }
                                resolvedKey = matchedNode?.getAlbumKey() ?: matchedNode?.nodeId
                            }
                        }
                    }

                    // Fail-safe fallback: match against the nearest cached ancestor folder (e.g. locked Family parent)
                    if (resolvedKey.isNullOrEmpty() && finalThumbnailUrl.isNotEmpty()) {
                        resolvedKey = getNearestCachedAncestorNode(finalThumbnailUrl)?.nodeId
                    }

                    if (com.smugview.app.BuildConfig.DEBUG) {
                        android.util.Log.d("SmugViewRCA", "getImageDetails: resolvedKey=$resolvedKey for imageKey=$imageKey (detailedImage is null=${detailedImage == null})")
                    }

                    if (!resolvedKey.isNullOrEmpty()) {
                        val matchedNode = repository.getNodeByIdOrKey(resolvedKey)
                        val accessType = matchedNode?.access ?: "Public"

                        if (accessType != "Password") {
                            // Public category: if initial fetch failed, retry it now
                            if (detailedImage == null) {
                                repository.getImage(imageKey, apiKey, null).collect { result ->
                                    finalResult = result
                                }
                                detailedImage = finalResult?.getOrNull()
                            }
                            
                            // Attach the resolved key to guarantee navigation
                            detailedImage?.let { img ->
                                val finalUris = (img.uris ?: com.smugview.app.data.api.AlbumImageUris()).copy(
                                    album = "/api/v2/album/$resolvedKey",
                                    imageAlbum = "/api/v2/album/$resolvedKey"
                                )
                                val updatedImg = img.copy(uris = finalUris)
                                finalResult = Result.success(updatedImg)
                                detailedImage = updatedImg
                            }
                        } else {
                            // Password-locked category: resolve THIS node's credential properly by
                            // walking its own ancestry (direct key -> album key -> parent chain,
                            // with an API fallback for uncached nodes). getUnlockedPassword covers
                            // inherited passwords from grandparent folders and search-result nodes
                            // that aren't in the local cache yet.
                            //
                            // Deliberately NOT a brute-force over every saved password (the old
                            // behaviour): replaying unrelated albums' credentials cross-contaminates
                            // them and can trip API rate limiting.
                            val candidatePw = getUnlockedPasswordForNode(matchedNode)
                                ?: getUnlockedPassword(resolvedKey)
                            if (!candidatePw.isNullOrEmpty()) {
                                val unlocked = repository.unlockAlbum(resolvedKey, apiKey, candidatePw) || repository.unlockNode(resolvedKey, apiKey, candidatePw)
                                if (unlocked) {
                                    passwordPrefs.edit().putString(resolvedKey, candidatePw).apply()
                                    var tempResult: Result<AlbumImageData>? = null
                                    repository.getImage(imageKey, apiKey, candidatePw).collect { result ->
                                        tempResult = result
                                    }
                                    val tempImg = tempResult?.getOrNull()
                                    if (tempImg != null) {
                                        val realAlbumKey = tempImg.uris?.imageAlbum?.substringAfterLast("/")
                                            ?: tempImg.uris?.album?.substringAfterLast("/")
                                            ?: resolvedKey
                                        val finalUris = (tempImg.uris ?: com.smugview.app.data.api.AlbumImageUris()).copy(
                                            album = "/api/v2/album/$realAlbumKey",
                                            imageAlbum = "/api/v2/album/$realAlbumKey"
                                        )
                                        val updatedImg = tempImg.copy(uris = finalUris)
                                        finalResult = Result.success(updatedImg)
                                        detailedImage = updatedImg
                                    }
                                }
                            }
                        }
                    }
                }

                stateFlow.value = finalResult

                detailedImage?.let { img ->
                    val apiAlbumKey = img.uris?.imageAlbum?.substringAfterLast("/")
                        ?: img.uris?.album?.substringAfterLast("/")
                        ?: ""
                    val resolvedAlbumKey = if (apiAlbumKey.isNotEmpty()) {
                        apiAlbumKey
                    } else {
                        getAlbumKeyFromWebUri(img.webUri) 
                            ?: getAlbumKeyFromWebUri(img.thumbnailUrl) 
                            ?: getNearestCachedAncestorNode(img.thumbnailUrl)?.nodeId 
                            ?: ""
                    }
                    val finalUris = (img.uris ?: com.smugview.app.data.api.AlbumImageUris()).copy(
                        album = if (resolvedAlbumKey.isNotEmpty()) "/api/v2/album/$resolvedAlbumKey" else null
                    )
                    
                    val updatedImg = img.copy(uris = finalUris)
                    stateFlow.value = Result.success(updatedImg)

                    withContext(Dispatchers.Main) {
                        val index = searchPhotosList.indexOfFirst { it.imageKey == imageKey }
                        if (index >= 0) {
                            searchPhotosList[index] = searchPhotosList[index].copy(
                                title = img.title,
                                caption = img.caption,
                                archivedUri = img.archivedUri,
                                date = img.date,
                                dateTime = img.dateTime,
                                originalWidth = img.originalWidth,
                                originalHeight = img.originalHeight,
                                format = img.format,
                                uris = finalUris,
                                videoUrl = img.videoUrl
                            )
                        }
                    }
                }
            }
            stateFlow
        }
        return flow.asStateFlow()
    }

    // --- Local Collections & Sync ---

    // Saved content (collections, bookmarks, offline) — delegated to CollectionsController.
    private val collections = CollectionsController(
        application = getApplication(),
        repository = repository,
        workManager = workManager,
        sharedPrefs = sharedPrefs,
        apiKey = apiKey,
        scope = viewModelScope,
        backgroundLoadingStatus = _backgroundLoadingStatus,
        isBackgroundLoading = _isBackgroundLoading,
        getActiveNickname = { _activeNickname.value },
        getCurrentAlbumKey = { _currentAlbumKey.value },
        getUnlockedPassword = { key -> getUnlockedPassword(key) }
    )

    fun createCollection(name: String) = collections.createCollection(name)

    fun deleteCollection(collectionId: Long) = collections.deleteCollection(collectionId)

    fun renameCollection(collectionId: Long, newName: String) = collections.renameCollection(collectionId, newName)

    fun getBookmarksForCollection(collectionId: Long): Flow<List<CollectionBookmark>> =
        collections.getBookmarksForCollection(collectionId)

    fun addBookmark(collectionId: Long, type: String, itemKey: String, title: String, albumKey: String = "", albumTitle: String = "", thumbnailUrl: String? = null, imageUrl: String? = null) =
        collections.addBookmark(collectionId, type, itemKey, title, albumKey, albumTitle, thumbnailUrl, imageUrl)

    fun removeBookmark(collectionId: Long, type: String, itemKey: String) =
        collections.removeBookmark(collectionId, type, itemKey)

    fun downloadPhotoOffline(imageKey: String, imageUrl: String) = collections.downloadPhotoOffline(imageKey, imageUrl)

    fun deleteOfflinePhoto(imageKey: String) = collections.deleteOfflinePhoto(imageKey)

    suspend fun isBookmarked(collectionId: Long, type: String, itemKey: String): Boolean =
        collections.isBookmarked(collectionId, type, itemKey)

    suspend fun isBookmarkedAnywhere(type: String, itemKey: String): Boolean =
        collections.isBookmarkedAnywhere(type, itemKey)

    fun addPhotoToCollection(photo: AlbumImageData, collectionId: Long) =
        collections.addPhotoToCollection(photo, collectionId)

    fun getPhotosInCollection(collectionId: Long): Flow<List<CollectionPhoto>> =
        collections.getPhotosInCollection(collectionId)

    fun navigateToHome() {
        _activeTab.value = BrowserTab.Folders
        val rootNodeId = _splashState.value.let {
            if (it is SplashUiState.Success) it.rootNodeId else null
        }
        if (rootNodeId != null) navigator.navigate(NavIntent.Root)
    }

    fun navigateToStackFolder(index: Int) {
        if (index < 0) {
            navigateToHome()
        } else {
            navigator.navigate(NavIntent.ToIndex(index))
        }
    }

    fun isAlbumDownloaded(albumKey: String): Boolean = collections.isAlbumDownloaded(albumKey)

    fun downloadAlbumOffline(albumKey: String, apiKey: String, password: String? = null) =
        collections.downloadAlbumOffline(albumKey, apiKey, password)

    fun deleteOfflineAlbum(albumKey: String, apiKey: String, password: String? = null) =
        collections.deleteOfflineAlbum(albumKey, apiKey, password)

    fun downloadPhotoOffline(imageKey: String, imageUrl: String, onSuccess: () -> Unit, onFailure: (String) -> Unit) =
        collections.downloadPhotoOffline(imageKey, imageUrl, onSuccess, onFailure)

    fun deleteOfflinePhoto(imageKey: String, onSuccess: () -> Unit) =
        collections.deleteOfflinePhoto(imageKey, onSuccess)

    fun removePhotoFromCollection(imageKey: String, collectionId: Long) =
        collections.removePhotoFromCollection(imageKey, collectionId)

    suspend fun getAlbumKeyFromWebUri(webUri: String?): String? {
        val targetUri = webUri ?: return null
        if (targetUri.isEmpty()) return null
        
        if (_userAlbums == null) {
            try {
                val nickname = activeNickname.value?.ifEmpty { null } ?: return null
                val response = repository.getUserAlbums(nickname, apiKey)
                _userAlbums = response
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        val path = try {
            val cleanedUriStr = targetUri.substringBefore("?")
            val pathStr = if (cleanedUriStr.contains("://")) {
                cleanedUriStr.substringAfter("://").substringAfter("/")
            } else {
                cleanedUriStr
            }
            val pathBeforeImage = if (pathStr.contains("/i-")) {
                pathStr.substringBefore("/i-")
            } else {
                pathStr
            }
            java.net.URLDecoder.decode(pathBeforeImage, "UTF-8")
        } catch (e: Exception) {
            null
        } ?: return null

        val normalizedPath = path.trim('/').lowercase()
        
        val matchedAlbum = _userAlbums?.find { album ->
            val albumPath = album.urlPath?.trim('/')?.lowercase() ?: ""
            albumPath == normalizedPath
        }

        return matchedAlbum?.albumKey
    }

    suspend fun getPasswordForPhotoUrl(photoUrl: String?): String? {
        if (photoUrl.isNullOrEmpty()) return null
        
        val path = try {
            val cleanedUriStr = photoUrl.substringBefore("?")
            val pathStr = if (cleanedUriStr.contains("://")) {
                cleanedUriStr.substringAfter("://").substringAfter("/")
            } else {
                cleanedUriStr
            }
            val pathBeforeImage = if (pathStr.contains("/i-")) {
                pathStr.substringBefore("/i-")
            } else {
                pathStr
            }
            java.net.URLDecoder.decode(pathBeforeImage, "UTF-8")
        } catch (e: Exception) {
            null
        } ?: return null

        val segments = path.trim('/').split('/')
        val allNodes = repository.getCachedNodesForSite(_activeNickname.value.orEmpty())
        
        for (i in segments.indices.reversed()) {
            val parentPath = segments.subList(0, i + 1).joinToString("/").lowercase()
            val matchedNode = allNodes.find { node ->
                val nodePath = node.webUri?.substringAfter("://")?.substringAfter("/")?.trim('/')?.lowercase() ?: ""
                nodePath == parentPath
            }
            if (matchedNode != null) {
                val pw = passwordPrefs.getString(matchedNode.nodeId, null) 
                    ?: passwordPrefs.getString(matchedNode.getAlbumKey(), null)
                if (!pw.isNullOrEmpty()) return pw
            }
        }
        return null
    }

    suspend fun getNearestCachedAncestorNode(photoUrl: String?): com.smugview.app.data.db.CachedNode? {
        if (photoUrl.isNullOrEmpty()) return null
        
        val path = try {
            val cleanedUriStr = photoUrl.substringBefore("?")
            val pathStr = if (cleanedUriStr.contains("://")) {
                cleanedUriStr.substringAfter("://").substringAfter("/")
            } else {
                cleanedUriStr
            }
            val pathBeforeImage = if (pathStr.contains("/i-")) {
                pathStr.substringBefore("/i-")
            } else {
                pathStr
            }
            java.net.URLDecoder.decode(pathBeforeImage, "UTF-8")
        } catch (e: Exception) {
            null
        } ?: return null

        val segments = path.trim('/').split('/')
        val allNodes = repository.getCachedNodesForSite(_activeNickname.value.orEmpty())
        
        for (i in segments.indices.reversed()) {
            val parentPath = segments.subList(0, i + 1).joinToString("/").lowercase()
            val matchedNode = allNodes.find { node ->
                val nodePath = node.webUri?.substringAfter("://")?.substringAfter("/")?.trim('/')?.lowercase() ?: ""
                nodePath == parentPath
            }
            if (matchedNode != null) {
                return matchedNode
            }
        }
        return null
    }

    suspend fun getCachedNodeById(nodeId: String): com.smugview.app.data.db.CachedNode? {
        return repository.getNodeById(nodeId)
    }

    suspend fun getAlbumName(albumKey: String): String {
        val cachedNode = getCachedNodeById(albumKey)
        if (cachedNode != null) return cachedNode.title
        return try {
            repository.getAlbum(albumKey, apiKey, null)?.name ?: "Gallery"
        } catch (e: Exception) {
            "Gallery"
        }
    }



    private fun <T> Flow<T>.cachedStateFlow(initialValue: T): StateFlow<T> {
        val flow = this
        val stateFlow = MutableStateFlow(initialValue)
        viewModelScope.launch {
            flow.collect {
                stateFlow.value = it
            }
        }
        return stateFlow.asStateFlow()
    }

    /**
     * The one background job per site (design 3.8): unlock the saved password roots, crawl the
     * gallery index, walk the folder tree. Replaced on a site switch. If the sync relisted the
     * folder on screen, it is reloaded so a new gallery is not stuck behind a manual refresh.
     */
    private fun startSiteSync(nickname: String, rootNodeId: String) {
        session.rootNodeId = rootNodeId
        treeSyncJob?.cancel()
        treeSyncJob = session.scope.launch {
            val invalidatedParents = repository.runSiteSync(nickname, rootNodeId, apiKey)
            if (currentFolderId != null && currentFolderId in invalidatedParents.orEmpty()) {
                navigator.navigate(NavIntent.Refresh(force = true, background = true))
            }
        }
    }
}

sealed interface GlobalSearchUiState {
    object Idle : GlobalSearchUiState
    object Loading : GlobalSearchUiState
    data class Success(val sites: List<com.smugview.app.data.repository.DiscoveredSite>) : GlobalSearchUiState
    data class Error(val message: String) : GlobalSearchUiState
}

data class HubAlbumItem(
    val albumKey: String,
    val title: String,
    val coverUrl: String?,
    val imageCount: Int,
    val dateModified: String?,
    val access: String? = null,
    val passwordHint: String? = null,
    /** The gallery's NodeID (AlbumKey is only an API handle): what the dot set and viewed table hold. */
    val nodeId: String? = null
) {
    /** Whether this gallery carries a "new" dot: [activeNodeIds] are NodeIDs (design 3.5, findings #5). */
    fun hasActiveUpdate(activeNodeIds: Set<String>): Boolean = nodeId != null && nodeId in activeNodeIds

    /** Whether the lock shows open; [unlockedIds] holds NodeIDs of everything under an unlocked root, plus saved keys. */
    fun isUnlocked(unlockedIds: Set<String>): Boolean = albumKey in unlockedIds || (nodeId != null && nodeId in unlockedIds)
}

