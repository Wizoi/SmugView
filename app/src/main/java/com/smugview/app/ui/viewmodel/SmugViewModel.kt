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
import com.smugview.app.BuildConfig
import com.smugview.app.data.api.AlbumImageData
import com.smugview.app.data.api.ExifData
import com.smugview.app.data.api.ImageSizeDetailsPayload
import com.smugview.app.data.api.Pager
import com.smugview.app.data.api.UserData
import com.smugview.app.data.api.toPage
import com.smugview.app.data.api.isVideo
import com.smugview.app.data.db.CachedNode
import com.smugview.app.data.db.CollectionBookmark
import com.smugview.app.data.db.CollectionPhoto
import com.smugview.app.data.db.OfflineCollection
import com.smugview.app.data.db.SearchHistory
import com.smugview.app.data.repository.SmugMugRepository
import com.smugview.app.data.db.toAlbumImageData
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
import kotlinx.coroutines.CompletableDeferred
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
import com.smugview.app.data.repository.AlbumLockedException
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
    private val offline: com.smugview.app.data.offline.OfflineCollections,
    private val offlineReader: com.smugview.app.data.offline.OfflineReader,
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
        if (pendingRestore?.nickname != nickname) pendingRestore = null
        session.close()
        session = SiteSession(nickname, viewModelScope.coroutineContext[kotlinx.coroutines.Job])
        navigator = BrowserNavigator(session.scope, browserHost)
        browserHost.render(BrowserState())
        treeSyncJob = null
        unlockResyncJob = null
        unlockEpochJob = null
        unlockSubtreeIndexJob = null
        _userAlbums = null
        _exifStates.clear()
        _imageDetailsStates.clear()
        _imageSizeDetailsStates.clear()
        savedFolderStateBeforeSearch = null
        passwordPromptNode = null
        passwordError = null
        pendingOpen = null
        albums.reset()
        siteHub.clearActiveSiteData()
        resetPerSiteState()
        saveNavState()
    }

    // Casting Integration — delegated to CastController (facade decomposition).
    private val cast = CastController(
        castManager = castManager,
        repository = repository,
        apiKey = apiKey,
        scope = viewModelScope,
        getUnlockedPassword = { albumKey -> getUnlockedPassword(albumKey) },
        onMessage = { message -> Toast.makeText(getApplication(), message, Toast.LENGTH_SHORT).show() }
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
        saveNavState()
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

        override suspend fun cachedNode(nodeId: String): CachedNode? = repository.getNodeById(nodeId)

        override suspend fun savedPassword(nodeId: String): String? = getUnlockedPassword(nodeId)

        override suspend fun needsPassword(node: CachedNode): Boolean = this@SmugViewModel.needsPassword(node)

        override fun requestPassword(node: CachedNode) {
            if (BuildConfig.DEBUG) {
                android.util.Log.d("SmugViewModel", "navigator: password prompt needed for nodeId=${node.nodeId}")
            }
            this@SmugViewModel.requestPassword(node)
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
                // SmugMug explicitly rejected the saved password (401/403). The repository's read retry has
                // already asked UnlockManager, which marked the root Invalid. The password is KEPT: only
                // the prompt for that root deletes it (design 3.4, Q5).
                val node = repository.getNodeById(nodeId)
                return if (node != null) {
                    this@SmugViewModel.requestPassword(node, error = "Saved password is no longer valid. Please re-enter.")
                    LoadFailure(listing = null, popAfter = true)
                } else {
                    LoadFailure(
                        BrowserUiState.Error(com.smugview.app.data.api.SmugMugErrorMapper.userMessage(error, "Failed to load hierarchy", com.smugview.app.ui.text.Subject.Folder)),
                        popAfter = true
                    )
                }
            }
            val isAccessDenied = error is retrofit2.HttpException && (error.code() == 401 || error.code() == 404)
            if (isAccessDenied && !nodeId.startsWith("virtual:")) {
                val node = repository.getNodeById(nodeId)
                if (node != null) {
                    this@SmugViewModel.requestPassword(node)
                    // Back out of the folder we navigated into; clear the loading state gracefully
                    return LoadFailure(BrowserUiState.Success(emptyList()), popAfter = true)
                }
                if (error is retrofit2.HttpException && error.code() == 404) {
                    collections.removeBookmarkGlobally(nodeId)
                }
                return LoadFailure(
                    BrowserUiState.Error(com.smugview.app.data.api.SmugMugErrorMapper.userMessage(error, "Access Denied / Not Found", com.smugview.app.ui.text.Subject.Folder)),
                    popAfter = false
                )
            }
            return LoadFailure(
                BrowserUiState.Error(com.smugview.app.data.api.SmugMugErrorMapper.userMessage(error, "Failed to load hierarchy", com.smugview.app.ui.text.Subject.Folder)),
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
            saveNavState(state)
        }
    }

    /**
     * What survives process death (R-17, design 3.2, Q4): the folder stack as NodeIDs, the back-to-search
     * marker, the active tab and the search query text. Not results, photos, tag selection or passwords.
     * Written on every render, tab change and search. Skipped while a restore is pending: the empty
     * state a site begin renders would otherwise overwrite what the restore is about to read.
     */
    private fun saveNavState(state: BrowserState = navigator.state.value) {
        if (pendingRestore != null) return
        val nickname = session.nickname.takeIf { it.isNotEmpty() } ?: return
        savedStateHandle[NAV_NICKNAME] = nickname
        savedStateHandle[NAV_STACK] = ArrayList(state.stack.map { it.nodeId })
        val back = state.returnToSearch
        if (back != null) savedStateHandle[NAV_RETURN_TO_SEARCH] = ArrayList(back.map { it.nodeId })
        else savedStateHandle.remove<ArrayList<String>>(NAV_RETURN_TO_SEARCH)
        savedStateHandle[NAV_TAB] = _activeTab.value.name
        savedStateHandle[SEARCH_QUERY] = search.searchQuery
    }

    private class SavedNav(
        val nickname: String,
        val stack: List<String>,
        val returnToSearch: List<String>?,
        val tab: BrowserTab?,
        val query: String
    )

    private fun readSavedNav(): SavedNav? {
        val nickname = savedStateHandle.get<String>(NAV_NICKNAME) ?: return null
        return SavedNav(
            nickname = nickname,
            stack = savedStateHandle.get<ArrayList<String>>(NAV_STACK).orEmpty(),
            returnToSearch = savedStateHandle.get<ArrayList<String>>(NAV_RETURN_TO_SEARCH),
            tab = savedStateHandle.get<String>(NAV_TAB)?.let { name -> BrowserTab.values().firstOrNull { it.name == name } },
            query = savedStateHandle.get<String>(SEARCH_QUERY).orEmpty()
        )
    }

    /** The saved navigation of a process that died, until the profile resolves and the Folders tab is rebuilt from it. */
    private var pendingRestore: SavedNav? = null

    /** One per [SiteSession]; replaced, empty, by [beginSite]. */
    private var navigator = BrowserNavigator(session.scope, browserHost)

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

    /**
     * The albums behind the gallery grid (design 3.4): one state per album key, the current one shown,
     * the last few finished ones kept. [currentAlbumTitle], [currentAlbumStyle] and [currentAlbumWebUri]
     * are Compose state, so the loader pushes the current album into them. [isBackgroundLoading] and
     * [backgroundLoadingStatus] merge the current album with collection downloads (declared below).
     */
    private val albums = AlbumLoader(
        defaultScope = { session.scope },
        onChange = { s ->
            currentAlbumTitle = s?.title ?: ""
            currentAlbumStyle = s?.style ?: "Collage"
            currentAlbumWebUri = s?.webUri ?: ""
        }
    )

    /** The error that replaces the grid: the current album failed before any photo arrived. */
    val albumLoadError: StateFlow<String?> get() = albums.blockingError

    /** The line shown above a gallery that opened from saved photos (offline), or null. */
    val albumNotice: StateFlow<String?> get() = albums.notice

    /** The current album's state, for tests and diagnostics. */
    internal val albumState: StateFlow<AlbumState?> get() = albums.state
    internal val albumLoader: AlbumLoader get() = albums

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
    private var unlockEpochJob: kotlinx.coroutines.Job? = null
    private var unlockSubtreeIndexJob: kotlinx.coroutines.Job? = null

    fun performSearch(query: String, forceRefresh: Boolean = false) {
        search.performSearch(query, forceRefresh)
        saveNavState()
    }

    // Tag list and active selection state
    val availableTags: StateFlow<Set<String>> get() = albums.tags

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

    /** What the open password prompt is for: the target to open after a good password (R-24). */
    private class PendingOpen(val target: CachedNode, val onUnlocked: ((albumKey: String) -> Unit)?)

    private var pendingOpen: PendingOpen? = null

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
            // R-17: what a killed process saved is used only for the site that is still active.
            val restored = readSavedNav()?.takeIf { it.nickname == active }
            pendingRestore = restored
            loadUserProfile(active)
            _activeTab.value = restored?.tab ?: BrowserTab.Folders
            if (restored != null && restored.query.isNotEmpty()) search.restoreQuery(restored.query)
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

                        val restore = pendingRestore
                        pendingRestore = null
                        if (restore != null) {
                            // Cache-first, so the owner is back where they were even offline (R-17).
                            navigator.navigate(NavIntent.Restore(restore.stack, restore.returnToSearch))
                        } else {
                            navigator.navigate(NavIntent.Root)
                        }
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

    /** Gallery taps whose pre-flight is still running, by album key: a second tap on one is dropped. */
    private val pendingAlbumTaps: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    fun checkAndNavigateToAlbum(node: CachedNode, onNavigate: (albumKey: String) -> Unit) {
        val tapKey = node.getAlbumKey() // Always use helper — strips !images suffixes
        if (!pendingAlbumTaps.add(tapKey)) return
        val tap = session.scope.launch {
            val albumKey = tapKey

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

            // The node as the pre-flight described it, so the prompt shows the right hint.
            val target = if (resolvedNode.access == null && access != null) resolvedNode.copy(access = access) else resolvedNode
            // One owner decides: UnlockManager (session or saved password on the password ROOT; a gallery
            // whose own SecurityType is None under a password folder is protected too, findings #19).
            // Bounded: the lineage read can be slow, and a gallery that loads is better than a stuck tap.
            val locked = withTimeoutOrNull(3000L) { needsPassword(target) } ?: false
            if (locked) {
                requestPassword(target, onUnlocked = onNavigate)
            } else {
                withContext(Dispatchers.Main) { onNavigate(albumKey) }
            }
        }
        tap.invokeOnCompletion { pendingAlbumTaps.remove(tapKey) }
    }

    /**
     * The one way to ask for a password (design 3.4). [target] is what the user wants to open; the
     * dialog shows the password ROOT that protects it (a sub-folder or gallery shares its root's
     * password). After a good password [target] opens (R-24): a folder via `navigate(Child)`, a gallery
     * via [onUnlocked] (the caller's own navigation) or, without one, in the grid. A site switch
     * dismisses the prompt ([beginSite]).
     */
    fun requestPassword(
        target: CachedNode,
        error: String? = null,
        onUnlocked: ((albumKey: String) -> Unit)? = null
    ) {
        if (BuildConfig.DEBUG) {
            android.util.Log.d("SmugViewModel", "requestPassword: nodeId=${target.nodeId}, title=${target.title}")
        }
        session.scope.launch {
            pendingOpen = PendingOpen(target, onUnlocked)
            passwordPromptNode = try {
                val rootId = repository.unlocks.rootOf(target.nodeId, apiKey)
                if (rootId == null || rootId == target.nodeId) {
                    target
                } else {
                    repository.getNodeById(rootId) ?: run {
                        val apiNode = repository.getNode(rootId, apiKey)
                        // In memory only: Uris.ParentNode is the node's own !parent link (R-01), and only a
                        // listing may place a row, so the prompt node is not inserted.
                        CachedNode(
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
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                target
            }
            passwordError = error
        }
    }

    /**
     * Does opening [node] need a typed password? UnlockManager's answer: its password root has no
     * session and no saved password, or the saved one was rejected. Replaces the old
     * `access == "Password" || "Inherited"` checks (SmugMug never sends "Inherited", findings #19).
     * Reads the network (`!parents`) only when the cache can't say.
     */
    suspend fun needsPassword(node: CachedNode): Boolean = try {
        repository.unlocks.needsPassword(node, apiKey)
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        false
    }

    /** The lock a list row shows. Cache-only (no network), so it is safe on every recomposition. */
    suspend fun lockOf(node: CachedNode): com.smugview.app.data.repository.UnlockManager.RowLock = try {
        repository.unlocks.lockOf(node, null)
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        com.smugview.app.data.repository.UnlockManager.RowLock.None
    }

    /** Per password root: None, Saved, Session or Invalid. Rows recompute their lock when it changes. */
    val passwordAccess: StateFlow<Map<String, com.smugview.app.data.repository.UnlockManager.Access>>
        get() = repository.unlocks.access

    fun handleAlbumLoadError(albumKey: String, error: Throwable? = null) {
        // R-11: an album that is no longer on screen has no say about passwords or prompts.
        albums.currentKey?.let { if (it != albumKey) return }
        session.scope.launch {
            // A rejected saved password is NOT deleted here (design 3.4, Q5): the repository's read retry
            // already asked UnlockManager, which marked the root Invalid. Only the prompt deletes.
            if (error is retrofit2.HttpException && error.code() == 404) {
                collections.removeBookmarkGlobally(albumKey)
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
                    requestPassword(node)
                }
            }
        }
    }

    /**
     * The user typed [password] at the prompt. UnlockManager unlocks the password ROOT and saves the
     * password under the root's key only; then the TARGET opens (R-24), not the prompt's root.
     */
    fun submitPassword(password: String) {
        val promptNode = passwordPromptNode ?: return
        val pending = pendingOpen
        val target = pending?.target ?: promptNode
        passwordError = null
        val normalizedPassword = password
            .replace('“', '"')
            .replace('”', '"')
            .replace('‘', '\'')
            .replace('’', '\'')
        session.scope.launch {
            try {
                when (val result = repository.unlocks.submit(target, normalizedPassword, apiKey)) {
                    is com.smugview.app.data.repository.UnlockManager.Submit.Opened -> {
                        passwordPromptNode = null
                        pendingOpen = null
                        // Galleries under the unlocked folder are now visible: index them and refresh
                        // what the user sees, off the tap.
                        // The resync is not started here: the session epoch this unlock bumped starts it
                        // (see [watchUnlockEpoch]), so every unlock path gets one and none gets two.
                        if (result.rootIsFolder) indexUnlockedSubtreeInBackground(result.rootId, normalizedPassword)
                        _activeNickname.value?.let { activeNick -> loadActiveSiteDetails(activeNick) }
                        if (target.type == "Folder") {
                            navigator.navigate(NavIntent.Child(target))
                        } else {
                            val albumKey = target.getAlbumKey()
                            val open = pending?.onUnlocked
                            if (open != null) {
                                // Marked here, once: the caller only navigates (R-24).
                                try {
                                    repository.markNodeAsViewed(target.nodeId)
                                } catch (e: kotlinx.coroutines.CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    // a failed mark must not stop the gallery opening
                                }
                                withContext(Dispatchers.Main) { open(albumKey) }
                            } else {
                                selectAlbum(albumKey)
                            }
                        }
                    }
                    com.smugview.app.data.repository.UnlockManager.Submit.Rejected -> passwordError = "Incorrect password"
                    com.smugview.app.data.repository.UnlockManager.Submit.Transient ->
                        passwordError = "Couldn't check the password. Check the connection and try again."
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                passwordError = "Validation failed: ${e.localizedMessage}"
            }
        }
    }

    /**
     * A root became Session while this site is open: the new cookie makes more galleries visible to the
     * index crawl, so crawl now, not at the next launch (design 3.6). Driven by the epoch, not by a
     * caller. The value at the start is the baseline: the launch unlock bumps the epoch too, and the
     * repository skips a resync for an epoch the launch crawl already started under. Runs in the
     * site's session scope, so a site switch cancels it.
     */
    private fun watchUnlockEpoch() {
        unlockEpochJob?.cancel()
        unlockEpochJob = session.scope.launch {
            var seen = repository.unlocks.sessionEpoch.value
            repository.unlocks.sessionEpoch.collect { epoch ->
                if (epoch != seen) {
                    seen = epoch
                    resyncAfterUnlock()
                }
            }
        }
    }

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
        pendingOpen = null
    }

    fun getUnlockedPasswordSync(nodeId: String): String? {
        return passwordPrefs.getString(nodeId, null)
    }

    /**
     * The saved password for [nodeId] (a NodeID or an AlbumKey), from UnlockManager: the password
     * root's key first, legacy copies as the fallback. Read-only: nothing is written back (R-04).
     */
    suspend fun getUnlockedPassword(nodeId: String): String? {
        if (nodeId == "site" || nodeId.isBlank()) return null
        return repository.unlocks.cachedPasswordFor(nodeId)
    }

    suspend fun getUnlockedPasswordForNode(node: CachedNode?): String? {
        if (node == null) return null
        return getUnlockedPassword(node.nodeId)
            ?: node.getAlbumKey().takeIf { it.isNotEmpty() && it != node.nodeId }?.let { getUnlockedPassword(it) }
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

    @OptIn(ExperimentalCoroutinesApi::class)
    val photosFlow: Flow<PagingData<AlbumImageData>> = combine(
        albums.photos,
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

    /** The lower-cased keywords of [images], for the tag filter. */
    private fun tagsOf(images: List<AlbumImageData>): Set<String> = images.flatMap { item ->
        item.keywordsString?.split(",")?.map { it.trim().lowercase() } ?: emptyList<String>()
    }.filter { it.isNotEmpty() }.toSet()

    /**
     * Opens [albumKey] in the gallery grid (design 3.4). The [albums] loader owns the state: choosing
     * another album cancels this one's stream, and nothing a cancelled or stale load does can reach the
     * album on screen. A finished album you come back to is shown from memory with no request.
     */
    fun selectAlbum(albumKey: String, targetImageKey: String? = null, force: Boolean = false) {
        val diag = com.smugview.app.diag.DiagContext.element(com.smugview.app.diag.DiagContext.newActionId("gallery"))
        _currentAlbumKey.value = albumKey
        // Step 4-6: the load decides "is this gallery lit?" (and so whether it goes to the network) BEFORE
        // the viewed mark below clears the dot. The mark waits for that answer.
        val litDecision = CompletableDeferred<Boolean>()
        val selection = albums.select(
            albumKey = albumKey,
            target = targetImageKey,
            force = force,
            scope = session.scope,
            context = diag,
            initialStatus = "Fetching album photos..."
        ) { run ->
            try {
                loadAlbum(run, targetImageKey, force, litDecision)
            } finally {
                litDecision.complete(false) // a load that never got as far as the check
            }
        }
        if (selection == AlbumSelection.Same) return
        if (selection != AlbumSelection.Started) litDecision.complete(false) // restored from memory: no load runs
        _includedTags.value = emptySet()
        _excludedTags.value = emptySet()

        // Not part of the album's load: switching to another album must not cancel the "viewed" mark.
        session.scope.launch(diag) {
            try {
                // Bounded: a load cancelled before it started never answers.
                withTimeoutOrNull(5000) { litDecision.await() }
                val node = repository.getNodeByIdOrKey(albumKey)
                if (node != null) {
                    repository.markNodeAsViewed(node.nodeId)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (BuildConfig.DEBUG) {
                    android.util.Log.e("SmugViewModel", "Failed to mark album as viewed on selection: $albumKey", e)
                }
            }
            // Q3: the Folders tab follows the gallery: breadcrumb and listing move to its folder together.
            navigator.navigate(NavIntent.Reveal(albumKey))
        }
    }

    private suspend fun loadAlbum(
        run: AlbumRun,
        targetImageKey: String?,
        force: Boolean,
        litDecision: CompletableDeferred<Boolean>
    ) = coroutineScope {
        if (targetImageKey != null) launch { loadTargetImage(run, targetImageKey) }
        loadAlbumPages(run, targetImageKey, force, litDecision)
    }

    /**
     * The image the detail screen was opened on: a placeholder from the local DB, then the API's copy.
     * 5-9: a saved copy (a DONE `offline_files` row) rides along as [AlbumImageData.localUri], so the viewer opens it
     * with no network (Q2). The placeholder is always a still (`format` "JPG", no `videoUrl`, N2): a video's saved
     * copy is a picture, and a collection photo never was a stream.
     */
    private suspend fun loadTargetImage(run: AlbumRun, targetImageKey: String) {
        try {
            val saved = offlineReader.savedPhoto(targetImageKey)
            val localUri = saved?.let { android.net.Uri.fromFile(it.file).toString() }
            val dbPhoto = repository.getCollectionPhotoByKey(targetImageKey)
            val placeholder = if (dbPhoto != null) {
                AlbumImageData(
                    imageKey = dbPhoto.imageKey,
                    title = dbPhoto.title,
                    caption = dbPhoto.title,
                    thumbnailUrl = dbPhoto.thumbnailUrl,
                    archivedUri = dbPhoto.archivedUri,
                    date = dbPhoto.dateTaken,
                    dateTime = dbPhoto.dateTaken,
                    keywords = dbPhoto.keywords,
                    webUri = null,
                    originalWidth = null,
                    originalHeight = null,
                    format = "JPG",
                    videoUrl = null,
                    localUri = localUri
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
                        videoUrl = null,
                        localUri = localUri
                    )
                } else saved?.let { savedImageData(it) } // a photo of a kept gallery: only the file row knows it
            }

            if (placeholder != null) {
                run.update { it.copy(photos = listOf(placeholder)) }
            }

            repository.getImage(targetImageKey, apiKey).collect { result ->
                result.getOrNull()?.let { apiImg ->
                    run.update { s ->
                        val updated = s.photos.toMutableList()
                        val index = updated.indexOfFirst { it.imageKey == targetImageKey }
                        // The API copy replaces the placeholder but not what is on this phone.
                        apiImg.localUri = apiImg.localUri ?: updated.getOrNull(index)?.localUri ?: localUri
                        if (index >= 0) {
                            updated[index] = apiImg
                        } else {
                            updated.add(apiImg)
                        }
                        s.copy(photos = updated)
                    }
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private suspend fun loadAlbumPages(
        run: AlbumRun,
        targetImageKey: String?,
        force: Boolean,
        litDecision: CompletableDeferred<Boolean>
    ) {
        val albumKey = run.albumKey
        // Step 4-6: a lit gallery (the dot says something new is in it) and Retry open from the network;
        // anything else follows the cache policy (reuse for 5 minutes, never across a session change).
        val lit = repository.isGalleryLit(albumKey, _activeNickname.value.orEmpty())
        litDecision.complete(lit)
        val cacheControl = if (force || lit) "no-cache" else null
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
            val details = repository.getAlbum(albumKey, apiKey, password, cacheControl)
            albumDetails = details
            run.update {
                it.copy(
                    title = details?.name ?: "",
                    style = details?.galleryStyle ?: "Collage",
                    webUri = details?.webUri ?: ""
                )
            }
            val nodeIdToMark = details?.nodeId
            if (nodeIdToMark != null) {
                repository.markNodeAsViewed(nodeIdToMark)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
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
        val locked = kotlinx.coroutines.withTimeoutOrNull(3000) {
            needsPassword(promptNode.copy(access = promptNode.access ?: securityType))
        } ?: false
        if (locked) {
            // R-11: never prompt for an album the user has already left.
            if (!run.isCurrent) return
            run.update { it.copy(loading = false, status = null) }
            requestPassword(promptNode.copy(access = promptNode.access ?: securityType))
            return
        }

        val firstPageResponse = try {
            repository.getAlbumImagesPage(albumKey, apiKey, password, cacheControl = cacheControl)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: AlbumLockedException) {
            // R-32: the gallery answered 200 with no photos because it is locked (the album's ResponseLevel
            // says so). Not an empty gallery: ask for the password. A saved password that could not be
            // tried just now (unlockPending) is no reason to ask: say so and keep it.
            if (e.unlockPending) {
                failFirstPage(run, e)
            } else {
                // R-11: never prompt for an album the user has already left.
                if (!run.isCurrent) return
                run.update { it.copy(loading = false, status = null) }
                requestPassword(promptNode.copy(access = promptNode.access ?: securityType ?: "Password"))
            }
            return
        } catch (e: Exception) {
            failFirstPage(run, e)
            return
        }
        val firstPageImages = firstPageResponse.response.images ?: emptyList()
        val expectedCount = albumDetails?.imageCount ?: 0
        if (firstPageImages.isEmpty() && expectedCount > 0) {
            failFirstPage(run, Exception("Failed to find any images in the gallery."))
            return
        }

        imagesUrlUpdate(firstPageImages, firstPageResponse.expansions)
        var tagsSet = tagsOf(firstPageImages)
        run.update { s ->
            // Deliberate reset when the requested image is on this page; otherwise merge with the placeholder.
            val merged = if (targetImageKey != null && firstPageImages.any { it.imageKey == targetImageKey }) {
                firstPageImages
            } else {
                (s.photos + firstPageImages).distinctBy { it.imageKey }
            }
            s.copy(photos = merged, tags = tagsSet)
        }

        // More pages exist when the server's own Count/Total say so (never a followed NextPage, which
        // drops `_expand=LargestVideo`: videos on page 2+ had no videoUrl, R-27).
        val nextStart = firstPageResponse.toPage(1).nextStart()
        if (nextStart == null) {
            run.update { it.copy(complete = true) }
            return
        }

        run.update { it.copy(status = "Streaming more photos...") }
        var pageIndex = 2
        // Buffer new pages and merge them in batches to prevent main-thread recomposition storms.
        val pendingImages = mutableListOf<AlbumImageData>()
        var tagsUpdated = false
        var failure: Exception? = null

        fun flush() {
            val batch = pendingImages.toList()
            pendingImages.clear()
            val tagsNow = tagsSet
            val withTags = tagsUpdated
            tagsUpdated = false
            if (batch.isEmpty() && !withTags) return
            run.update { s ->
                s.copy(
                    photos = if (batch.isEmpty()) s.photos else (s.photos + batch).distinctBy { it.imageKey },
                    tags = if (withTags) tagsNow else s.tags
                )
            }
        }

        if (pageIndex <= repository.maxPagesPerFetch) {
            try {
                Pager.each(
                    first = nextStart,
                    pageSize = SmugMugRepository.ALBUM_IMAGES_PAGE,
                    delayMs = 0,
                    fetch = { start, _ ->
                        run.update { it.copy(status = "Downloading page $pageIndex...") }
                        val response = repository.getAlbumImagesPage(albumKey, apiKey, password, start = start, cacheControl = cacheControl)
                        val images = response.response.images ?: emptyList()
                        if (images.isNotEmpty()) {
                            imagesUrlUpdate(images, response.expansions)
                            pendingImages.addAll(images)
                            tagsSet = tagsSet + tagsOf(images)
                            tagsUpdated = true
                        }
                        response.toPage(start)
                    },
                    onPage = { page ->
                        pageIndex++
                        if (pageIndex % 3 == 0 || page.nextStart() == null) flush()
                        pageIndex <= repository.maxPagesPerFetch
                    }
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                failure = e
            }
        }
        flush()
        val failed = failure
        if (failed != null) {
            // R-46: keep the pages that arrived, say the album is incomplete (it restarts if selected again).
            val message = com.smugview.app.data.api.SmugMugErrorMapper.userMessage(failed, "Failed to load all photos")
            run.update { it.copy(error = message) }
        } else {
            run.update { it.copy(complete = true) }
        }
    }

    /**
     * A photo built from its saved file and the row that saved it (5-9): nothing here is a network address.
     * The file is also its own thumbnail. A video's saved copy is a still picture (Q7), so it is shown as one.
     */
    private fun savedImageData(saved: com.smugview.app.data.offline.OfflineReader.SavedPhoto): AlbumImageData {
        val uri = android.net.Uri.fromFile(saved.file).toString()
        val row = saved.row
        return AlbumImageData(
            imageKey = row.imageKey,
            title = row.title,
            caption = row.title,
            thumbnailUrl = uri,
            archivedUri = null,
            date = row.dateTaken,
            dateTime = row.dateTaken,
            keywords = null,
            webUri = null,
            originalWidth = null,
            originalHeight = null,
            format = "JPG",
            videoUrl = null,
            localUri = uri
        )
    }

    /**
     * Page 1 failed: the grid is empty, the error says why, and the password logic decides about a prompt.
     * 5-9 (Q2): when the phone is offline, what is saved opens instead of the error: the photos of this gallery that
     * were kept offline, or (opened from a collection) the saved copy of the photo that was asked for. Only when
     * nothing is saved does the error show.
     */
    private suspend fun failFirstPage(run: AlbumRun, e: Exception) {
        val message = com.smugview.app.data.api.SmugMugErrorMapper.userMessage(e, "Failed to load album images")
        var shownOffline = false
        val applied = if (com.smugview.app.data.api.SmugMugErrorMapper.isOffline(e)) {
            val keptPhotos = try {
                offlineReader.savedGallery(run.albumKey).map { savedImageData(it) }
            } catch (c: kotlinx.coroutines.CancellationException) {
                throw c
            } catch (_: Exception) {
                emptyList()
            }
            run.update { s ->
                val saved = keptPhotos.ifEmpty { s.photos.filter { it.localUri != null } }
                if (saved.isEmpty()) {
                    s.copy(photos = emptyList(), tags = emptySet(), loading = false, status = null, error = message)
                } else {
                    shownOffline = true
                    // Not complete: coming back to this gallery once online must load it for real.
                    s.copy(
                        photos = saved, tags = emptySet(), loading = false, status = null, error = null,
                        notice = com.smugview.app.data.offline.OfflineMessages.offlineGallery(saved.size), complete = false
                    )
                }
            }
        } else {
            run.update {
                it.copy(photos = emptyList(), tags = emptySet(), loading = false, status = null, error = message)
            }
        }
        // A stale run (the user moved on) must not touch the password state of the album on screen (R-11).
        if (applied && !shownOffline) handleAlbumLoadError(run.albumKey, e)
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
                repository.getImageExif(imageKey, apiKey).collect {
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
                repository.getImageSizeDetails(uri, apiKey).collect {
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

                var finalResult: Result<AlbumImageData>? = null
                repository.getImage(imageKey, apiKey).collect { result ->
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
                                repository.getImage(imageKey, apiKey).collect { result ->
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
                                val unlocked = repository.unlocks.ensureSession(resolvedKey, apiKey, candidatePw) == com.smugview.app.data.repository.SmugMugRepository.UnlockResult.Success
                                if (unlocked) {
                                    var tempResult: Result<AlbumImageData>? = null
                                    repository.getImage(imageKey, apiKey).collect { result ->
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
        repository = repository,
        offline = offline,
        scope = viewModelScope,
        getActiveNickname = { _activeNickname.value },
        getCurrentAlbumKey = { _currentAlbumKey.value }
    )

    /** The spinner of the gallery screen: the current album is still loading (R-20). Saving photos has no spinner here: it runs in the worker. */
    val isBackgroundLoading: StateFlow<Boolean> = albums.loading

    /** The status line under it: the current album's own. */
    val backgroundLoadingStatus: StateFlow<String?> = albums.status

    fun createCollection(name: String) = collections.createCollection(name)

    fun deleteCollection(collectionId: Long) = collections.deleteCollection(collectionId)

    fun renameCollection(collectionId: Long, newName: String) = collections.renameCollection(collectionId, newName)

    fun getBookmarksForCollection(collectionId: Long): Flow<List<CollectionBookmark>> =
        collections.getBookmarksForCollection(collectionId)

    fun addBookmark(collectionId: Long, type: String, itemKey: String, title: String, albumKey: String = "", albumTitle: String = "", thumbnailUrl: String? = null, imageUrl: String? = null) =
        collections.addBookmark(collectionId, type, itemKey, title, albumKey, albumTitle, thumbnailUrl, imageUrl)

    fun removeBookmark(collectionId: Long, type: String, itemKey: String) =
        collections.removeBookmark(collectionId, type, itemKey)

    fun keepGalleryOffline(collectionId: Long, albumKey: String, title: String?) =
        collections.keepGalleryOffline(collectionId, albumKey, title)

    fun stopKeepingGalleryOffline(collectionId: Long, albumKey: String) =
        collections.stopKeepingGalleryOffline(collectionId, albumKey)

    fun setGalleryWifiOnly(collectionId: Long, albumKey: String, wifiOnly: Boolean) =
        collections.setGalleryWifiOnly(collectionId, albumKey, wifiOnly)

    // --- What is saved on this phone (5-9): reads come from OfflineReader, writes from OfflineCollections ---

    /**
     * Every saved photo: image key to the `file:` URI of its copy. One Flow for the whole app, started on first use
     * (the viewer reads it, so a saved photo opens with no network, Q2).
     */
    val localFiles: StateFlow<Map<String, String>> by lazy {
        offlineReader.localFiles()
            .map { files -> files.mapValues { (_, file) -> android.net.Uri.fromFile(file).toString() } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())
    }

    /** The row states and kept-gallery summaries of one collection (phase 5 design 3 and 4). */
    fun offlineOf(collectionId: Long): Flow<com.smugview.app.data.offline.OfflineReader.CollectionOffline> =
        offlineReader.collection(collectionId)

    /** "Try again" on a failed row. */
    fun tryAgain(imageKey: String) {
        viewModelScope.launch { offline.tryAgain(imageKey) }
    }

    /** "Remove" on a row: the photo and its Image bookmark leave the collection; an unshared copy goes (Q8). */
    fun removeSavedRow(imageKey: String, collectionId: Long) {
        viewModelScope.launch { offline.removeFromCollection(imageKey, collectionId) }
    }

    /** Q8: what deleting [collectionId] would remove, or null when no saved photo would go (no question needed). */
    suspend fun deleteConfirm(collectionId: Long, name: String): com.smugview.app.data.offline.OfflineReader.DeleteConfirm? =
        offlineReader.deleteConfirm(collectionId, name)

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

    fun removePhotoFromCollection(imageKey: String, collectionId: Long) =
        collections.removePhotoFromCollection(imageKey, collectionId)

    suspend fun getAlbumKeyFromWebUri(webUri: String?): String? {
        val targetUri = webUri ?: return null
        if (targetUri.isEmpty()) return null

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

        // The gallery index has every gallery of the site (design 3.6); `user!albums` page 1 has 100 at most,
        // so a photo in gallery 101+ is only found here.
        activeNickname.value?.ifEmpty { null }?.let { nickname ->
            repository.getAlbumByUrlPath(nickname, normalizedPath)?.let { return it.albumKey }
        }

        if (_userAlbums == null) {
            try {
                val nickname = activeNickname.value?.ifEmpty { null } ?: return null
                val response = repository.getUserAlbums(nickname, apiKey)
                _userAlbums = response
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        val matchedAlbum = _userAlbums?.find { album ->
            val albumPath = album.urlPath?.trim('/')?.lowercase() ?: ""
            albumPath == normalizedPath
        }

        return matchedAlbum?.albumKey
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
        watchUnlockEpoch()
        treeSyncJob?.cancel()
        treeSyncJob = session.scope.launch {
            val invalidatedParents = repository.runSiteSync(nickname, rootNodeId, apiKey)
            if (currentFolderId != null && currentFolderId in invalidatedParents.orEmpty()) {
                navigator.navigate(NavIntent.Refresh(force = true, background = true))
            }
        }
    }

    private companion object {
        const val NAV_NICKNAME = "nav.nickname"
        const val NAV_STACK = "nav.stack"
        const val NAV_RETURN_TO_SEARCH = "nav.returnToSearch"
        const val NAV_TAB = "nav.tab"
        const val SEARCH_QUERY = "search.query"
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

    /** The row as a node, for the lock check (the index lists galleries at the top: no cached lineage). */
    fun asRowNode(): CachedNode = CachedNode(
        nodeId = nodeId ?: albumKey,
        parentNodeId = "root",
        type = "Album",
        title = title,
        description = null,
        access = access,
        passwordHint = passwordHint,
        uri = "/api/v2/album/$albumKey",
        childNodesUri = null,
        albumUri = "/api/v2/album/$albumKey"
    )
}

