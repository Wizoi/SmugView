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
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.combine
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    private val workManager: WorkManager
) : AndroidViewModel(application) {

    private val apiKey = BuildConfig.SMUGMUG_API_KEY
    private val sharedPrefs = application.getSharedPreferences("smugview_prefs", Context.MODE_PRIVATE)
    private val passwordPrefs = application.getSharedPreferences("smugview_passwords", Context.MODE_PRIVATE)
    private val searchStatusPrefs = application.getSharedPreferences("smugview_search_status", Context.MODE_PRIVATE)

    fun isNodeUnlocked(nodeId: String): Boolean {
        if (nodeId.isBlank()) return false
        if (!passwordPrefs.getString(nodeId, null).isNullOrEmpty()) {
            if (BuildConfig.DEBUG) {
                android.util.Log.d("SmugViewUnlock", "isNodeUnlocked: nodeId=$nodeId is directly unlocked")
            }
            return true
        }
        for (parent in folderNavigationStack) {
            if (!passwordPrefs.getString(parent.nodeId, null).isNullOrEmpty()) {
                if (BuildConfig.DEBUG) {
                    android.util.Log.d("SmugViewUnlock", "isNodeUnlocked: nodeId=$nodeId unlocked via navigation stack parent=${parent.nodeId}")
                }
                return true
            }
        }
        
        // Traverse parent hierarchy via local database / memory cache only (no network calls to prevent UI thread blocks)
        try {
            return kotlinx.coroutines.runBlocking {
                var currentId: String? = nodeId
                var depth = 0
                while (currentId != null && depth < 10) {
                    val id = currentId
                    var parentNode = repository.getNodeById(id)
                    if (BuildConfig.DEBUG) {
                        android.util.Log.d("SmugViewUnlock", "isNodeUnlocked loop: id=$id, dbNodeFound=${parentNode != null}, parentIdInDb=${parentNode?.parentNodeId}")
                    }
                    if (parentNode == null && !id.startsWith("virtual:")) {
                        val memNode = repository.albumsCache.value.find { it.nodeId == id }
                        if (memNode != null) {
                            parentNode = memNode
                            if (BuildConfig.DEBUG) {
                                android.util.Log.d("SmugViewUnlock", "isNodeUnlocked loop: id=$id found in memNode, parentIdInMem=${memNode.parentNodeId}")
                            }
                        }
                    }
                    
                    if (parentNode == null) {
                        if (BuildConfig.DEBUG) {
                            android.util.Log.d("SmugViewUnlock", "isNodeUnlocked loop: parentNode is null, stopping")
                        }
                        break
                    }
                    
                    val parentId = parentNode.parentNodeId
                    if (parentId == null || parentId == "root" || parentId == "search_result") {
                        if (BuildConfig.DEBUG) {
                            android.util.Log.d("SmugViewUnlock", "isNodeUnlocked loop: parentId=$parentId is root/search_result/null, stopping")
                        }
                        break
                    }
                    
                    val pw = passwordPrefs.getString(parentId, null)
                    if (BuildConfig.DEBUG) {
                        android.util.Log.d("SmugViewUnlock", "isNodeUnlocked loop: checking parentId=$parentId password in prefs: hasPassword=${!pw.isNullOrEmpty()}")
                    }
                    if (!pw.isNullOrEmpty()) {
                        if (BuildConfig.DEBUG) {
                            android.util.Log.d("SmugViewUnlock", "isNodeUnlocked: nodeId=$nodeId unlocked via parentId=$parentId in hierarchy")
                        }
                        return@runBlocking true
                    }
                    currentId = parentId
                    depth++
                }
                if (BuildConfig.DEBUG) {
                    android.util.Log.d("SmugViewUnlock", "isNodeUnlocked: nodeId=$nodeId is LOCKED")
                }
                false
            }
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) {
                android.util.Log.e("SmugViewUnlock", "isNodeUnlocked: error during check for $nodeId", e)
            }
            return false
        }
    }

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
            setSearchScope(activeScope)
            triggerTagScopeScan(activeScope)
        }
    }

    fun cancelSearchJob() {
        searchJob?.cancel()
        searchJob = null
        backgroundSearchJob?.cancel()
        backgroundSearchJob = null
    }

    fun cancelTagSearchJob() {
        imageLoadJob?.cancel()
        imageLoadJob = null
        _isLoadingPhotos.value = false
        _scanProgress.value = "Tag search was cancelled because you navigated away."
    }

    fun clearScanProgress() {
        _scanProgress.value = ""
    }

    fun unlockAllSavedPasswords() {
        if (BuildConfig.DEBUG) {
            android.util.Log.d("SmugViewModel", "unlockAllSavedPasswords started")
        }
        viewModelScope.launch(Dispatchers.IO) {
            val allPasswords = passwordPrefs.all
            
            val depthMappedEntries = mutableListOf<Triple<String, String, Int>>()
            for ((nodeId, passwordObj) in allPasswords) {
                val password = passwordObj as? String ?: continue
                if (password.isNotEmpty()) {
                    try {
                        var currentId: String? = nodeId
                        var depth = 0
                        while (currentId != null && currentId != "root" && depth < 10) {
                            val node = repository.getNodeById(currentId) ?: repository.albumsCache.value.find { it.nodeId == currentId }
                            currentId = node?.parentNodeId
                            depth++
                        }
                        depthMappedEntries.add(Triple(nodeId, password, depth))
                    } catch (e: Exception) {
                        if (BuildConfig.DEBUG) {
                            android.util.Log.e("SmugViewModel", "unlockAllSavedPasswords: error calculating depth for nodeId=$nodeId", e)
                        }
                        // Fallback to max depth if depth resolution fails
                        depthMappedEntries.add(Triple(nodeId, password, 99))
                    }
                }
            }

            // Sort entries by depth ascending (shallowest/roots first)
            val sortedEntries = depthMappedEntries.sortedBy { it.third }

            val unlockedRoots = mutableSetOf<String>()
            for ((nodeId, password, _) in sortedEntries) {
                // If already unlocked via hierarchy traversal, skip explicit unlock
                if (isNodeUnlocked(nodeId)) {
                    if (BuildConfig.DEBUG) {
                        android.util.Log.d("SmugViewModel", "unlockAllSavedPasswords: skipping nodeId=$nodeId as it is already unlocked via parent hierarchy")
                    }
                    continue
                }

                try {
                    val cachedNode = repository.getNodeById(nodeId)
                    if (BuildConfig.DEBUG) {
                        android.util.Log.d("SmugViewModel", "unlockAllSavedPasswords: processing nodeId=$nodeId, cachedFound=${cachedNode != null}, type=${cachedNode?.type}")
                    }
                    if (cachedNode != null) {
                        if (cachedNode.type == "Folder") {
                            if (unlockedRoots.add(nodeId)) {
                                val success = repository.unlockNode(nodeId, apiKey, password)
                                if (BuildConfig.DEBUG) {
                                    android.util.Log.d("SmugViewModel", "unlockAllSavedPasswords: unlockNode for Folder nodeId=$nodeId success=$success")
                                }
                                kotlinx.coroutines.delay(400)
                            }
                        } else {
                            val albumKey = cachedNode.getAlbumKey()
                            if (albumKey.isNotEmpty()) {
                                if (unlockedRoots.add(albumKey)) {
                                    val success = repository.unlockAlbum(albumKey, apiKey, password)
                                    if (BuildConfig.DEBUG) {
                                        android.util.Log.d("SmugViewModel", "unlockAllSavedPasswords: unlockAlbum for Album key=$albumKey success=$success")
                                    }
                                    kotlinx.coroutines.delay(400)
                                }
                            } else {
                                if (unlockedRoots.add(nodeId)) {
                                    val success = repository.unlockNode(nodeId, apiKey, password)
                                    if (BuildConfig.DEBUG) {
                                        android.util.Log.d("SmugViewModel", "unlockAllSavedPasswords: unlockNode fallback for nodeId=$nodeId success=$success")
                                    }
                                    kotlinx.coroutines.delay(400)
                                }
                            }
                        }
                    } else {
                        if (unlockedRoots.add(nodeId)) {
                            val success = repository.unlockNode(nodeId, apiKey, password)
                            if (BuildConfig.DEBUG) {
                                android.util.Log.d("SmugViewModel", "unlockAllSavedPasswords: unlockNode for uncached nodeId=$nodeId success=$success")
                            }
                            if (!success) {
                                val successAlbum = repository.unlockAlbum(nodeId, apiKey, password)
                                if (BuildConfig.DEBUG) {
                                    android.util.Log.d("SmugViewModel", "unlockAllSavedPasswords: unlockAlbum fallback for uncached nodeId=$nodeId success=$successAlbum")
                                }
                            }
                            kotlinx.coroutines.delay(400)
                        }
                    }
                } catch (e: Exception) {
                    if (BuildConfig.DEBUG) {
                        android.util.Log.e("SmugViewModel", "unlockAllSavedPasswords error for nodeId=$nodeId", e)
                    }
                }
            }
        }
    }

    // Splash State
    private val _splashState = MutableStateFlow<SplashUiState>(SplashUiState.Idle)
    val splashState: StateFlow<SplashUiState> = _splashState.asStateFlow()

    // Browser / Navigation State
    private val _browserState = MutableStateFlow<BrowserUiState>(BrowserUiState.Loading)
    val browserState: StateFlow<BrowserUiState> = _browserState.asStateFlow()

    var currentFolderId by mutableStateOf<String?>(null)
        private set

    val folderNavigationStack = mutableStateListOf<CachedNode>()

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

    // Search query and search state
    var searchQuery by mutableStateOf("")
        private set

    var searchResultTab by mutableStateOf(0)

    private val _searchScope = MutableStateFlow(SearchScope("Entire Site"))
    val searchScope: StateFlow<SearchScope> = _searchScope.asStateFlow()

    val searchPhotosList = mutableStateListOf<AlbumImageData>()

    private val _searchPhotosPagingFlow = kotlinx.coroutines.flow.MutableStateFlow<kotlinx.coroutines.flow.Flow<androidx.paging.PagingData<AlbumImageData>>>(kotlinx.coroutines.flow.emptyFlow())
    val searchPhotosPagingFlow: kotlinx.coroutines.flow.StateFlow<kotlinx.coroutines.flow.Flow<androidx.paging.PagingData<AlbumImageData>>> = _searchPhotosPagingFlow

    val searchHistory: StateFlow<List<SearchHistory>> = repository.getSearchHistory()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    fun clearSearchHistory() {
        viewModelScope.launch {
            repository.clearSearchHistory()
        }
    }

    fun deleteSearchQuery(query: String) {
        viewModelScope.launch {
            repository.deleteSearchQuery(query)
        }
    }

    fun setSearchScope(scope: SearchScope) {
        _searchScope.value = scope
    }

    private val _searchState = MutableStateFlow<SearchUiState>(SearchUiState.Idle)
    val searchState: StateFlow<SearchUiState> = _searchState.asStateFlow()

    private val _isSearchPhotosLoading = MutableStateFlow(false)
    val isSearchPhotosLoading: StateFlow<Boolean> = _isSearchPhotosLoading.asStateFlow()

    // Search Gallery Sort Order: "Ascending" or "Descending"
    var searchGallerySortOrder by mutableStateOf(sharedPrefs.getString("search_gallery_sort_order", "Ascending") ?: "Ascending")
        private set

    fun updateSearchGallerySortOrder(order: String) {
        searchGallerySortOrder = order
        sharedPrefs.edit().putString("search_gallery_sort_order", order).apply()
        val currentState = _searchState.value
        if (currentState is SearchUiState.Success) {
            val sortedGalleries = sortGalleries(currentState.galleries, order)
            _searchState.value = currentState.copy(galleries = sortedGalleries)
        }
    }

    // Search Photos Sort Order: "Descending" or "Ascending"
    var searchPhotosSortOrder by mutableStateOf(sharedPrefs.getString("search_photos_sort_order", "Descending") ?: "Descending")
        private set

    fun updateSearchPhotosSortOrder(order: String) {
        searchPhotosSortOrder = order
        sharedPrefs.edit().putString("search_photos_sort_order", order).apply()
        val currentState = _searchState.value
        if (currentState is SearchUiState.Success) {
            val sortedPhotos = sortPhotos(currentState.photos, order)
            _searchState.value = currentState.copy(photos = sortedPhotos)
        } else if (searchQuery.isNotBlank()) {
            performSearch(searchQuery)
        }
    }

    // Keyword Photos Sort Order: "Descending" or "Ascending"
    var keywordPhotosSortOrder by mutableStateOf(sharedPrefs.getString("keyword_photos_sort_order", "Descending") ?: "Descending")
        private set

    fun updateKeywordPhotosSortOrder(order: String) {
        keywordPhotosSortOrder = order
        sharedPrefs.edit().putString("keyword_photos_sort_order", order).apply()
    }

    fun sortGalleries(galleries: List<CachedNode>, order: String): List<CachedNode> {
        return if (order == "Descending") {
            galleries.sortedByDescending { it.title.lowercase() }
        } else {
            galleries.sortedBy { it.title.lowercase() }
        }
    }

    fun sortPhotos(photos: List<AlbumImageData>, order: String): List<AlbumImageData> {
        return if (order == "Descending") {
            photos.sortedByDescending { it.date ?: "" }
        } else {
            photos.sortedBy { it.date ?: "" }
        }
    }

    private var searchJob: kotlinx.coroutines.Job? = null
    private var backgroundSearchJob: kotlinx.coroutines.Job? = null
    private var treeSyncJob: kotlinx.coroutines.Job? = null

    fun performSearch(query: String, forceRefresh: Boolean = false) {
        if (BuildConfig.DEBUG) {
            android.util.Log.d("SmugViewModel", "performSearch called: query='$query', forceRefresh=$forceRefresh")
        }
        searchQuery = query
        if (query.isBlank()) {
            searchJob?.cancel()
            backgroundSearchJob?.cancel()
            _searchState.value = SearchUiState.Idle
            return
        }
        _searchState.value = SearchUiState.Loading
        searchJob?.cancel()
        backgroundSearchJob?.cancel()
        viewModelScope.launch {
            repository.insertSearchQuery(query)
        }
        searchJob = viewModelScope.launch {
            try {
                val nickname = _activeNickname.value
                if (nickname.isNullOrEmpty()) {
                    _searchState.value = SearchUiState.Error("No active site profile loaded")
                    return@launch
                }

                // Resolve search scope URI
                val activeScope = _searchScope.value
                val resolvedRootId = if (activeScope.nodeUri == null || activeScope.nodeId == null) {
                    repository.getUserRootNodeId(nickname, apiKey).first().getOrNull()
                } else {
                    null
                }

                val scopeUri = activeScope.nodeUri
                val scopeKey = activeScope.nodeId ?: "site"
                
                // Wait for the gallery cache to finish loading
                if (!repository.isAlbumsCacheLoaded.value) {
                    if (BuildConfig.DEBUG) {
                        android.util.Log.d("SmugViewModel", "performSearch waiting for gallery cache to finish loading")
                    }
                    _searchState.value = SearchUiState.Loading
                    repository.isAlbumsCacheLoaded.first { it }
                }

                // 1. Load cached search results from the database IMMEDIATELY (Folders)
                val cachedFolders = repository.getSearchResultNodes(query, scopeKey, "Folder")
                if (BuildConfig.DEBUG) {
                    android.util.Log.d("SmugViewModel", "performSearch: found ${cachedFolders.size} folders in database cache")
                }
                
                // Fetch galleries from in-memory cache
                val lowerQuery = query.lowercase()
                val cachedGalleries = repository.albumsCache.value.filter {
                    it.title.lowercase().contains(lowerQuery)
                }
                if (BuildConfig.DEBUG) {
                    android.util.Log.d("SmugViewModel", "performSearch: matched ${cachedGalleries.size} galleries from in-memory cache")
                }
                val sortedGalleries = sortGalleries(cachedGalleries, searchGallerySortOrder)

                val lastSearchedAt = searchStatusPrefs.getLong("${scopeKey}_${query}_ts", 0L)
                val cacheAgeMs = System.currentTimeMillis() - lastSearchedAt
                val cacheMaxAgeMs = 24 * 60 * 60 * 1000L // 24 hours
                var isFullySearched = lastSearchedAt > 0L && cacheAgeMs < cacheMaxAgeMs
                if (isFullySearched && !repository.hasSearchPhotosInDb(query, scopeKey)) {
                    if (BuildConfig.DEBUG) {
                        android.util.Log.d("SmugViewModel", "performSearch: cache timestamp exists but database has no photos. Bypassing isFullySearched to fetch from API.")
                    }
                    isFullySearched = false
                }

                if (forceRefresh) {
                    repository.deleteSearchResultsForQueryAndType(query, scopeKey, "Photo")
                    _searchPhotosPagingFlow.value = kotlinx.coroutines.flow.emptyFlow()
                }

                // Start observing paging flow immediately for cached or fresh photos
                _searchPhotosPagingFlow.value = androidx.paging.Pager(
                    config = androidx.paging.PagingConfig(pageSize = 60, enablePlaceholders = true)
                ) {
                    repository.getPagedSearchPhotos(query, scopeKey, searchPhotosSortOrder)
                }.flow.map { pagingData ->
                    pagingData.map { it.toAlbumImageData() }
                }.cachedIn(viewModelScope)

                if (isFullySearched && !forceRefresh) {
                    if (BuildConfig.DEBUG) {
                        android.util.Log.d("SmugViewModel", "performSearch cache is valid (fully searched in past 24h). Displaying cached results.")
                    }
                    _isSearchPhotosLoading.value = false
                    _searchState.value = SearchUiState.Success(
                        photos = emptyList(),
                        galleries = sortedGalleries,
                        folders = cachedFolders,
                        photosError = null
                    )
                    return@launch
                }

                _searchState.value = SearchUiState.Success(
                    photos = emptyList(), // Replaced by Pager
                    galleries = sortedGalleries,
                    folders = cachedFolders,
                    photosError = null
                )

                // 2. Search folders and galleries from API (node!search) first
                val password = getUnlockedPassword(scopeKey)
                val apiScopeUri = scopeUri ?: resolvedRootId?.let { "/api/v2/node/$it" }
                if (apiScopeUri != null) {
                    try {
                        // Note: searchNodesRemote still fetches folders if available from SmugMug search API.
                        // Galleries are exclusively handled by the in-memory cache.
                        repository.searchNodesRemote(apiScopeUri, scopeKey, query, apiKey, password).collect {}
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }

                val updatedFolders = repository.getSearchResultNodes(query, scopeKey, "Folder")

                _searchState.value = SearchUiState.Success(
                    photos = emptyList(),
                    galleries = sortedGalleries, // using the memory cache galleries again
                    folders = updatedFolders,
                    photosError = null
                )

                // 3. Trigger background API fetcher
                _isSearchPhotosLoading.value = true
                backgroundSearchJob?.cancel()
                backgroundSearchJob = viewModelScope.launch {
                    try {
                        repository.performBackgroundSearchImages(nickname, apiScopeUri, scopeKey, query, apiKey, password)
                        searchStatusPrefs.edit().putLong("${scopeKey}_${query}_ts", System.currentTimeMillis()).apply()
                    } finally {
                        _isSearchPhotosLoading.value = false
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                e.printStackTrace()
                _isSearchPhotosLoading.value = false
                _searchState.value = SearchUiState.Error(e.localizedMessage ?: "Error during search job")
            }
        }
    }

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

    // --- Dynamic Explorer & Validation States ---
    private val _activeNickname = MutableStateFlow<String?>(null)
    val activeNickname: StateFlow<String?> = _activeNickname.asStateFlow()

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

    private val _recentSites = MutableStateFlow<List<String>>(emptyList())
    val recentSites: StateFlow<List<String>> = _recentSites.asStateFlow()

    private val _sitePreview = MutableStateFlow<Result<UserData>?>(null)
    val sitePreview: StateFlow<Result<UserData>?> = _sitePreview.asStateFlow()

    private val _previewAlbums = MutableStateFlow<List<com.smugview.app.data.api.AlbumPreview>>(emptyList())
    val previewAlbums: StateFlow<List<com.smugview.app.data.api.AlbumPreview>> = _previewAlbums.asStateFlow()

    var lastSelectedCollectionIds by mutableStateOf<Set<Long>>(emptySet())

    private val _activeUserProfile = MutableStateFlow<UserData?>(null)
    val activeUserProfile: StateFlow<UserData?> = _activeUserProfile.asStateFlow()

    // Tag Search UI States
    private val _isScanningTags = MutableStateFlow(false)
    val isScanningTags: StateFlow<Boolean> = _isScanningTags.asStateFlow()

    private val _isLoadingPhotos = MutableStateFlow(false)
    val isLoadingPhotos: StateFlow<Boolean> = _isLoadingPhotos.asStateFlow()

    private val _scanProgress = MutableStateFlow("")
    val scanProgress: StateFlow<String> = _scanProgress.asStateFlow()

    private val _allScopeTags = MutableStateFlow<Map<String, Int>>(emptyMap())
    val allScopeTags: StateFlow<Map<String, Int>> = _allScopeTags.asStateFlow()

    private val _allScopePhotos = MutableStateFlow<List<AlbumImageData>>(emptyList())
    val allScopePhotos: StateFlow<List<AlbumImageData>> = _allScopePhotos.asStateFlow()

    // Tag Search Optimizations Caching
    private val _albumKeywordsMap = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    private val loadedAlbumImages = ConcurrentHashMap<String, List<AlbumImageData>>()
    private var scopeAlbums = emptyList<CachedNode>()
    private var targetNodeToUnlockAfterSuccess: CachedNode? = null

    enum class TagFilterState {
        INCLUDED,
        EXCLUDED
    }

    private val _selectedTags = MutableStateFlow<Map<String, TagFilterState>>(emptyMap())
    val selectedTags: StateFlow<Map<String, TagFilterState>> = _selectedTags.asStateFlow()

    var tagCloudLimit by mutableStateOf(25)

    var tagSearchQuery by mutableStateOf("")

    @OptIn(ExperimentalCoroutinesApi::class)
    val tagCloudTags: StateFlow<List<Pair<String, Int>>> = combine(
        _allScopeTags,
        snapshotFlow { tagCloudLimit }
    ) { tags, limit ->
        tags.entries
            .sortedByDescending { it.value }
            .take(limit)
            .map { Pair(it.key, it.value) }
            .sortedBy { it.first } // sort by name ascending
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    @OptIn(ExperimentalCoroutinesApi::class)
    val tagFilteredPhotos: StateFlow<List<AlbumImageData>> = combine(
        _allScopePhotos,
        _selectedTags,
        snapshotFlow { keywordPhotosSortOrder }
    ) { photos, selected, sortOrder ->
        if (selected.isEmpty()) {
            emptyList()
        } else {
            val included = selected.filter { it.value == TagFilterState.INCLUDED }.keys
            val excluded = selected.filter { it.value == TagFilterState.EXCLUDED }.keys
            val filtered = photos.filter { photo ->
                val keywords = photo.keywordsString?.split(",")?.map { it.trim().lowercase() } ?: emptyList()
                val hasFirst = if (included.isNotEmpty()) keywords.contains(included.first()) else true
                val hasAllIncluded = included.all { keywords.contains(it) }
                val hasNoExcluded = excluded.none { keywords.contains(it) }
                hasFirst && hasAllIncluded && hasNoExcluded
            }.distinctBy { it.imageKey }
            
            if (sortOrder == "Ascending") {
                filtered.sortedWith(compareBy<AlbumImageData> { it.dateTime ?: it.date ?: "" })
            } else {
                filtered.sortedWith(compareByDescending<AlbumImageData> { it.dateTime ?: it.date ?: "" })
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        loadHistoryAndActiveSite()
        observeSelectedTagsToLoadImages()
        unlockAllSavedPasswords()
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
            loadUserProfile(active)
            _activeTab.value = BrowserTab.Folders
        } else {
            _splashState.value = SplashUiState.Idle
            _activeTab.value = BrowserTab.Hub
        }
    }

    fun loadUserProfile(nickname: String) {
        if (apiKey.isEmpty() || apiKey == "YOUR_API_KEY_HERE") {
            _splashState.value = SplashUiState.Error("API Key is missing or invalid. Set it in local.properties.")
            return
        }
        _splashState.value = SplashUiState.Loading
        viewModelScope.launch {
            repository.getUserProfile(nickname, apiKey).collect { result ->
                result.fold(
                    onSuccess = { userData ->
                        _activeUserProfile.value = userData
                        val nodeUri = userData.uris.node
                        val rootId = repository.parseNodeIdFromUri(nodeUri)
                        _splashState.value = SplashUiState.Success(rootId)
                        currentFolderId = rootId
                        
                        // Launch the thin gallery load cache in the background
                        viewModelScope.launch {
                            repository.buildInMemoryGalleryCache(nickname, apiKey)
                        }
                        
                        loadFolderContents(rootId)
                        startFolderTreeSync(rootId)
                    },
                    onFailure = { error ->
                        _splashState.value = SplashUiState.Error(error.localizedMessage ?: "Connection error")
                    }
                )
            }
        }
    }

    fun retryActiveSite() {
        _activeNickname.value?.let { loadUserProfile(it) }
    }

    // Live validation for Site Explorer
    fun verifyAndPreviewNickname(nickname: String) {
        if (nickname.isBlank()) {
            _sitePreview.value = null
            _previewAlbums.value = emptyList()
            return
        }
        viewModelScope.launch {
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

    // Selects and locks in a SmugMug nickname to browse
    fun selectSite(nickname: String) {
        val normalizedNickname = nickname.trim().lowercase()
        if (normalizedNickname.isEmpty()) return

        _splashState.value = SplashUiState.Loading
        viewModelScope.launch {
            repository.getUserProfile(normalizedNickname, apiKey).collect { result ->
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
                        currentFolderId = rootId
                        folderNavigationStack.clear()
                        
                        // Launch the thin gallery load cache in the background
                        viewModelScope.launch {
                            repository.buildInMemoryGalleryCache(normalizedNickname, apiKey)
                        }
                        
                        loadFolderContents(rootId)
                        startFolderTreeSync(rootId)
                        _activeTab.value = BrowserTab.Folders
                    },
                    onFailure = { error ->
                        _splashState.value = SplashUiState.Error(error.localizedMessage ?: "Failed to resolve root node")
                    }
                )
            }
        }
    }

    // Clear active site and return to explorer
    fun disconnectSite() {
        treeSyncJob?.cancel()
        treeSyncJob = null
        sharedPrefs.edit().remove("active_nickname").apply()
        _activeNickname.value = null
        _activeUserProfile.value = null
        currentFolderId = null
        folderNavigationStack.clear()
        _splashState.value = SplashUiState.Idle
        _sitePreview.value = null
        _previewAlbums.value = emptyList()
        _activeTab.value = BrowserTab.Hub
    }

    suspend fun getNodeByAlbumKey(albumKey: String): CachedNode? {
        val directNode = repository.getNodeById(albumKey)
        if (directNode != null) return directNode
        val allNodes = repository.getAllCachedNodes()
        return allNodes.find { it.getAlbumKey() == albumKey }
    }

    // Browsing folder contents
    fun clearEntireCacheAndReload() {
        viewModelScope.launch {
            repository.clearEntireCache()
            loadFolderContents("root", forceRefresh = true)
        }
    }

    fun loadFolderContents(nodeId: String, forceRefresh: Boolean = false) {
        if (BuildConfig.DEBUG) {
            android.util.Log.d("SmugViewModel", "loadFolderContents called: nodeId=$nodeId, forceRefresh=$forceRefresh")
        }
        _browserState.value = BrowserUiState.Loading
        viewModelScope.launch {
            val password = getUnlockedPassword(nodeId)
            repository.getNodeChildren(nodeId, apiKey, forceRefresh, password).collect { result ->
                result.fold(
                    onSuccess = { nodes ->
                        if (BuildConfig.DEBUG) {
                            android.util.Log.d("SmugViewModel", "loadFolderContents success: retrieved ${nodes.size} nodes for nodeId=$nodeId")
                        }
                        _browserState.value = BrowserUiState.Success(nodes)
                    },
                    onFailure = { error ->
                        if (BuildConfig.DEBUG) {
                            android.util.Log.e("SmugViewModel", "loadFolderContents failed for nodeId=$nodeId", error)
                        }
                        if (!password.isNullOrEmpty()) {
                            // If a saved password failed, it was probably changed or invalid
                            passwordPrefs.edit().remove(nodeId).apply()
                            
                            // Revert navigation if we are inside the stack
                            if (currentFolderId == nodeId) {
                                navigateBackFolder()
                            }
                            
                            // Fetch node details to prompt user
                            val node = repository.getNodeById(nodeId)
                            if (node != null) {
                                passwordPromptNode = node
                                passwordError = "Saved password is no longer valid. Please re-enter."
                            } else {
                                _browserState.value = BrowserUiState.Error(error.localizedMessage ?: "Failed to load hierarchy")
                            }
                        } else {
                            val isAccessDenied = error is retrofit2.HttpException && (error.code() == 401 || error.code() == 404)
                            if (isAccessDenied && !nodeId.startsWith("virtual:")) {
                                val node = repository.getNodeById(nodeId)
                                if (node != null) {
                                    promptPassword(node)
                                    // Back out if we navigated into it
                                    if (currentFolderId == nodeId) {
                                        navigateBackFolder()
                                    }
                                    _browserState.value = BrowserUiState.Success(emptyList()) // clear loading state gracefully
                                } else {
                                    if (error is retrofit2.HttpException && error.code() == 404) {
                                        viewModelScope.launch {
                                            repository.removeBookmarkGlobally(nodeId)
                                        }
                                    }
                                    _browserState.value = BrowserUiState.Error(error.localizedMessage ?: "Access Denied / Not Found")
                                }
                            } else {
                                _browserState.value = BrowserUiState.Error(error.localizedMessage ?: "Failed to load hierarchy")
                            }
                        }
                    }
                )
            }
        }
    }
    var savedFolderStateBeforeSearch: Pair<String?, List<CachedNode>>? by mutableStateOf(null)
        private set

    fun navigateToFolderFromSearch(node: CachedNode) {
        if (BuildConfig.DEBUG) {
            android.util.Log.d("SmugViewModel", "navigateToFolderFromSearch: folderName=${node.title}, nodeId=${node.nodeId}")
        }
        if (savedFolderStateBeforeSearch == null) {
            savedFolderStateBeforeSearch = Pair(currentFolderId, folderNavigationStack.toList())
        }
        navigateToChildFolder(node)
        setActiveTab(BrowserTab.Folders)
    }

    fun navigateBack(): Boolean {
        if (BuildConfig.DEBUG) {
            android.util.Log.d("SmugViewModel", "navigateBack called: savedStateExists=${savedFolderStateBeforeSearch != null}")
        }
        val savedState = savedFolderStateBeforeSearch
        if (savedState != null) {
            val savedStack = savedState.second
            if (folderNavigationStack.size > savedStack.size + 1) {
                return navigateBackFolder()
            } else {
                if (BuildConfig.DEBUG) {
                    android.util.Log.d("SmugViewModel", "navigateBack: restoring search tab state")
                }
                folderNavigationStack.clear()
                folderNavigationStack.addAll(savedStack)
                currentFolderId = savedState.first
                if (currentFolderId != null) {
                    loadFolderContents(currentFolderId!!)
                } else {
                    splashState.value.let {
                        if (it is SplashUiState.Success) {
                            currentFolderId = it.rootNodeId
                            loadFolderContents(it.rootNodeId)
                        }
                    }
                }
                savedFolderStateBeforeSearch = null
                setActiveTab(BrowserTab.Search)
                return true
            }
        } else {
            return navigateBackFolder()
        }
    }

    fun navigateToChildFolder(node: CachedNode) {
        if (BuildConfig.DEBUG) {
            android.util.Log.d("SmugViewModel", "navigateToChildFolder: title=${node.title}, nodeId=${node.nodeId}, access=${node.access}")
        }
        viewModelScope.launch {
            val savedPassword = getUnlockedPassword(node.nodeId)
            if (node.access == "Password" && savedPassword == null) {
                if (BuildConfig.DEBUG) {
                    android.util.Log.d("SmugViewModel", "navigateToChildFolder: password prompt needed for nodeId=${node.nodeId}")
                }
                promptPassword(node)
                return@launch
            }
            currentFolderId = node.nodeId
            folderNavigationStack.add(node)
            loadFolderContents(node.nodeId)
        }
    }

    fun navigateBackFolder(): Boolean {
        if (BuildConfig.DEBUG) {
            android.util.Log.d("SmugViewModel", "navigateBackFolder called: stackSize=${folderNavigationStack.size}")
        }
        if (folderNavigationStack.isNotEmpty()) {
            folderNavigationStack.removeAt(folderNavigationStack.size - 1)
            val previousNodeId = folderNavigationStack.lastOrNull()?.nodeId ?: splashState.value.let {
                if (it is SplashUiState.Success) it.rootNodeId else null
            }
            if (BuildConfig.DEBUG) {
                android.util.Log.d("SmugViewModel", "navigateBackFolder: popped stack, previousNodeId=$previousNodeId")
            }
            if (previousNodeId != null) {
                currentFolderId = previousNodeId
                loadFolderContents(previousNodeId)
                return true
            }
        }
        return false
    }

    fun promptPassword(node: CachedNode) {
        if (BuildConfig.DEBUG) {
            android.util.Log.d("SmugViewModel", "promptPassword called: nodeId=${node.nodeId}, title=${node.title}")
        }
        viewModelScope.launch {
            targetNodeToUnlockAfterSuccess = node
            try {
                val rootNodeId = repository.resolvePasswordRootNodeId(node.nodeId, apiKey)
                if (rootNodeId != node.nodeId) {
                    var rootNode = repository.getNodeById(rootNodeId)
                    if (rootNode == null) {
                        val apiNode = repository.getNode(rootNodeId, apiKey)
                        rootNode = CachedNode(
                            nodeId = apiNode.nodeId,
                            parentNodeId = apiNode.uris.parentNode?.substringAfterLast("/")?.substringBefore("!") ?: "root",
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
                        repository.insertNodes(listOf(rootNode))
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
        viewModelScope.launch {
            val password = getUnlockedPassword(albumKey)
            if (!password.isNullOrEmpty()) {
                passwordPrefs.edit().remove(albumKey).apply()
            }
            // Also clear if nodeId is the albumKey
            val nodeKey = folderNavigationStack.lastOrNull { node ->
                val key = node.getAlbumKey()
                key == albumKey
            }?.nodeId
            if (nodeKey != null) {
                passwordPrefs.edit().remove(nodeKey).apply()
            }
            if (error is retrofit2.HttpException && error.code() == 404) {
                repository.removeBookmarkGlobally(albumKey)
            }
            
            val isAccessDenied = error is retrofit2.HttpException && (error.code() == 401 || error.code() == 404)
            if (isAccessDenied) {
                var node = repository.getNodeById(albumKey)
                if (node == null) {
                    val allNodes = repository.getAllCachedNodes()
                    node = allNodes.find { it.nodeId == albumKey || it.getAlbumKey() == albumKey }
                }
                if (node != null) {
                    promptPassword(node)
                }
            }
        }
    }

    // Password Submit Handler
    fun submitPassword(password: String, onSuccess: () -> Unit = {}) {
        val promptNode = passwordPromptNode ?: return
        val targetNode = targetNodeToUnlockAfterSuccess ?: promptNode
        passwordError = null
        val normalizedPassword = password
            .replace('“', '"')
            .replace('”', '"')
            .replace('‘', '\'')
            .replace('’', '\'')
        viewModelScope.launch {
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
                    repository.getNodeChildren(node.nodeId, apiKey, true, password).first()
                } catch (e: Exception) {
                    // Ignore pre-fetch failures if unlock succeeded
                }
                true
            } else {
                false
            }
        } else {
            val albumKey = node.getAlbumKey()
            repository.verifyAlbumPassword(albumKey, apiKey, password)
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
            val allNodes = repository.getAllCachedNodes()
            node = allNodes.find { it.nodeId == nodeId || it.getAlbumKey() == nodeId }
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
        
        // 3. Traverse parent nodes to check for inherited passwords
        while (currentId != null) {
            var parentNode = repository.getNodeById(currentId)
            
            // If parentNode is in the DB but has parentNodeId = "search_result", resolve its real parent from the API
            if (parentNode != null && parentNode.parentNodeId == "search_result") {
                try {
                    val apiNode = repository.getNode(currentId, apiKey)
                    val realParentId = apiNode.uris.parentNode?.substringAfterLast("/")?.substringBefore("!") ?: "root"
                    parentNode = parentNode.copy(parentNodeId = realParentId)
                    repository.insertNodes(listOf(parentNode))
                } catch (e: Exception) {
                    // Ignore API errors
                }
            }
            
            if (parentNode == null && !currentId.startsWith("virtual:")) {
                try {
                    val apiNode = repository.getNode(currentId, apiKey)
                    parentNode = CachedNode(
                        nodeId = apiNode.nodeId,
                        parentNodeId = apiNode.uris.parentNode?.substringAfterLast("/")?.substringBefore("!") ?: "root",
                        type = apiNode.type,
                        title = apiNode.name ?: "Folder",
                        description = apiNode.description,
                        access = apiNode.privacy ?: apiNode.securityType ?: "Public",
                        passwordHint = apiNode.passwordHint,
                        uri = apiNode.uri,
                        childNodesUri = apiNode.uris.childNodes,
                        albumUri = apiNode.uris.album,
                        highlightImageUrl = null,
                        childCount = null,
                        sortIndex = 0,
                        webUri = apiNode.webUri
                    )
                    repository.insertNodes(listOf(parentNode))
                } catch (e: Exception) {
                    break
                }
            }
            
            if (parentNode == null) break
            
            // Check the parent node itself (both its nodeId and its albumKey)
            val parentId = parentNode.parentNodeId
            if (parentId == null || parentId == "root" || parentId == "search_result") break
            
            pw = passwordPrefs.getString(parentId, null)
            if (pw != null) {
                // Cache it for quick future lookup
                passwordPrefs.edit()
                    .putString(nodeId, pw)
                    .putString(parentNode.nodeId, pw)
                    .apply()
                return pw
            }
            
            // Also check by the parent's album key if applicable
            val parentNodeObj = repository.getNodeById(parentId)
            if (parentNodeObj != null) {
                val parentAlbumKey = parentNodeObj.getAlbumKey()
                if (parentAlbumKey != parentId) {
                    pw = passwordPrefs.getString(parentAlbumKey, null)
                    if (pw != null) {
                        passwordPrefs.edit()
                            .putString(nodeId, pw)
                            .putString(parentNode.nodeId, pw)
                            .apply()
                        return pw
                    }
                }
            }
            
            currentId = parentId
        }
        return null
    }

    suspend fun getUnlockedPasswordForNode(node: CachedNode?): String? {
        if (node == null) return null
        val galleryKey = node.getAlbumKey()
        
        var pw = passwordPrefs.getString(galleryKey, null) ?: passwordPrefs.getString(node.nodeId, null)
        if (pw != null) return pw
        
        var currentId: String? = node.parentNodeId
        if (currentId == "search_result") {
            try {
                val apiNode = repository.getNode(node.nodeId, apiKey)
                val realParentId = apiNode.uris.parentNode?.substringAfterLast("/")?.substringBefore("!") ?: "root"
                val updatedNode = node.copy(parentNodeId = realParentId)
                repository.insertNodes(listOf(updatedNode))
                currentId = realParentId
            } catch (e: Exception) {
                // Ignore
            }
        }
        
        while (currentId != null && currentId != "root" && currentId != "search_result") {
            pw = passwordPrefs.getString(currentId, null)
            if (pw != null) {
                passwordPrefs.edit().putString(node.nodeId, pw).apply()
                return pw
            }
            var parentNode = repository.getNodeById(currentId)
            if (parentNode == null) {
                try {
                    val apiNode = repository.getNode(currentId, apiKey)
                    parentNode = CachedNode(
                        nodeId = apiNode.nodeId,
                        parentNodeId = apiNode.uris.parentNode?.substringAfterLast("/")?.substringBefore("!") ?: "root",
                        type = apiNode.type,
                        title = apiNode.name ?: "Folder",
                        description = apiNode.description,
                        access = apiNode.privacy ?: apiNode.securityType ?: "Public",
                        passwordHint = apiNode.passwordHint,
                        uri = apiNode.uri,
                        childNodesUri = apiNode.uris.childNodes,
                        albumUri = apiNode.uris.album,
                        highlightImageUrl = null,
                        childCount = null,
                        sortIndex = 0,
                        webUri = apiNode.webUri
                    )
                    repository.insertNodes(listOf(parentNode))
                } catch (e: Exception) {
                    break
                }
            }
            currentId = parentNode.parentNodeId
        }
        return null
    }

    private fun cleanAlbumKey(uri: String): String {
        return uri.substringAfterLast("/").substringBefore("!")
    }



    fun selectSingleTag(tag: String) {
        val current = mutableMapOf<String, TagFilterState>()
        current[tag.lowercase().trim()] = TagFilterState.INCLUDED
        _selectedTags.value = current
    }

    fun clearSelectedTags() {
        _selectedTags.value = emptyMap()
    }

    fun selectTag(tag: String, state: TagFilterState = TagFilterState.INCLUDED) {
        val current = _selectedTags.value.toMutableMap()
        if (current.isEmpty()) {
            current[tag.lowercase()] = TagFilterState.INCLUDED
        } else {
            current[tag.lowercase()] = state
        }
        _selectedTags.value = current
    }

    fun removeTag(tag: String) {
        val current = _selectedTags.value.toMutableMap()
        current.remove(tag.lowercase())
        _selectedTags.value = current
    }

    fun toggleTagState(tag: String) {
        val current = _selectedTags.value.toMutableMap()
        val currentState = current[tag.lowercase()]
        if (currentState != null) {
            val keys = current.keys.toList()
            if (keys.firstOrNull() != tag.lowercase()) {
                current[tag.lowercase()] = if (currentState == TagFilterState.INCLUDED) TagFilterState.EXCLUDED else TagFilterState.INCLUDED
            }
            _selectedTags.value = current
        }
    }

    private var imageLoadJob: kotlinx.coroutines.Job? = null

    private fun observeSelectedTagsToLoadImages() {
        viewModelScope.launch {
            _selectedTags.collect { selected ->
                val included = selected.filter { it.value == TagFilterState.INCLUDED }.keys
                if (included.isEmpty()) {
                    _allScopePhotos.value = emptyList()
                    return@collect
                }

                imageLoadJob?.cancel()
                _isLoadingPhotos.value = true
                _scanProgress.value = "Loading photos for selected tags..."
                imageLoadJob = viewModelScope.launch(context = Dispatchers.IO) imageSearchLaunch@{
                    try {
                        val nickname = _activeNickname.value ?: return@imageSearchLaunch
                        val activeScope = _searchScope.value
                        val resolvedRootId = if (activeScope.nodeUri == null || activeScope.nodeId == null) {
                            repository.getUserRootNodeId(nickname = nickname, apiKey = apiKey).first().getOrNull()
                        } else {
                            null
                        }
                        val targetScopeId = activeScope.nodeId ?: resolvedRootId
                        if (targetScopeId != null) {
                            val savedPassword = getUnlockedPassword(targetScopeId)
                            if (savedPassword != null) {
                                repository.unlockInheritedPasswordRoot(targetScopeId, apiKey, savedPassword)
                            }
                        }
                        val scopeUri = activeScope.nodeUri ?: resolvedRootId?.let { "/api/v2/node/$it" } ?: "/api/v2/user/$nickname"
                        val keywordsQuery = included.joinToString(separator = ",")
                        
                        _allScopePhotos.value = emptyList()
                        try {
                            val results = repository.getImagesByKeyword(
                                scope = scopeUri,
                                keywords = keywordsQuery,
                                apiKey = apiKey,
                                count = 500,
                                start = 1
                            )
                            _allScopePhotos.value = results
                        } catch (e: Exception) {
                            android.util.Log.e("SmugViewModel", "Failed to load photos for keywords: $keywordsQuery", e)
                        }
                    } finally {
                        _isLoadingPhotos.value = false
                    }
                }
            }
        }
    }

    fun addKeywordToImage(imageKey: String, newKeyword: String, onComplete: (Boolean) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val imageResponse = repository.getImage(imageKey, apiKey).first().getOrNull()
                val currentKeywordsStr = imageResponse?.keywords ?: ""
                val currentKeywords = currentKeywordsStr.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toMutableSet()
                
                val cleanNewKeyword = newKeyword.trim()
                if (cleanNewKeyword.isNotEmpty() && currentKeywords.add(cleanNewKeyword)) {
                    val updatedKeywordsStr = currentKeywords.joinToString(", ")
                    val success = repository.updateImageMetadata(imageKey, apiKey, updatedKeywordsStr)
                    withContext(Dispatchers.Main) {
                        onComplete(success)
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        onComplete(false)
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("SmugViewModel", "Failed to add keyword to image", e)
                withContext(Dispatchers.Main) {
                    onComplete(false)
                }
            }
        }
    }

    private fun updateAllScopePhotosFlow(matchingAlbumKeys: Set<String>) {
        val photos = matchingAlbumKeys.flatMap { loadedAlbumImages[it] ?: emptyList<AlbumImageData>() }
        _allScopePhotos.value = photos
    }

    private var tagScanJob: kotlinx.coroutines.Job? = null

    fun triggerTagScopeScan(scope: SearchScope) {
        tagScanJob?.cancel()
        tagScanJob = viewModelScope.launch {
            _isScanningTags.value = true
            _scanProgress.value = "Starting scan..."
            _allScopePhotos.value = emptyList()
            _allScopeTags.value = emptyMap()
            _selectedTags.value = emptyMap()
            _albumKeywordsMap.value = emptyMap()
            loadedAlbumImages.clear()
            scopeAlbums = emptyList()

            try {
                if (_activeNickname.value == null) return@launch
                
                val rootNodeId = if (scope.nodeId == null || scope.nodeUri == null) {
                    repository.getUserRootNodeId(_activeNickname.value!!, apiKey).first().getOrNull()
                } else {
                    null
                }
                
                val targetScopeId = scope.nodeId ?: rootNodeId
                if (targetScopeId == null) {
                    _scanProgress.value = "Failed to resolve scope root node."
                    _isScanningTags.value = false
                    return@launch
                }
                
                val savedPassword = getUnlockedPassword(targetScopeId)
                if (savedPassword != null) {
                    _scanProgress.value = "Unlocking scope..."
                    repository.unlockInheritedPasswordRoot(targetScopeId, apiKey, savedPassword)
                }

                _scanProgress.value = "Fetching keywords..."
                try {
                    val response = repository.getUserTopKeywords(_activeNickname.value!!, apiKey, targetScopeId)
                    val keywords = response.response.userTopKeywords?.keywords ?: emptyList()
                    val tagCounts = mutableMapOf<String, Int>()
                    keywords.forEach { keyword ->
                        val clean = keyword.trim().lowercase()
                        if (clean.isNotEmpty()) {
                            tagCounts[clean] = tagCounts.getOrDefault(clean, 0) + 1
                        }
                    }
                    _allScopeTags.value = tagCounts
                    _scanProgress.value = "Scan complete. Found ${tagCounts.size} unique tags."
                } catch (e: Exception) {
                    _scanProgress.value = "Failed to fetch top keywords: ${e.localizedMessage}"
                }
                _isScanningTags.value = false
                return@launch
            } catch (e: Exception) {
                android.util.Log.e("SmugViewModel", "Tag scope scan failed", e)
                _scanProgress.value = "Scan failed: ${e.localizedMessage}"
                _isScanningTags.value = false
            }
        }
    }

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
            viewModelScope.launch {
                try {
                    val password = getUnlockedPassword(albumKey)
                    val list = repository.resolveAndCacheAlbumLineage(albumKey, apiKey, password)
                    folderNavigationStack.clear()
                    folderNavigationStack.addAll(list)
                    currentFolderId = list.lastOrNull()?.nodeId ?: _splashState.value.let {
                        if (it is SplashUiState.Success) it.rootNodeId else null
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }

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
                                val currentList = _rawPhotos.value.toMutableList()
                                val index = currentList.indexOfFirst { it.imageKey == targetImageKey }
                                if (index >= 0) {
                                    currentList[index] = apiImg
                                } else {
                                    currentList.add(apiImg)
                                }
                                _rawPhotos.value = currentList
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
        viewModelScope.launch {
            val password = getUnlockedPassword(albumKey)
            var albumDetails: com.smugview.app.data.api.AlbumDetails? = null
            try {
                albumDetails = repository.getAlbum(albumKey, apiKey, password)
                currentAlbumTitle = albumDetails?.name ?: ""
                currentAlbumStyle = albumDetails?.galleryStyle ?: "Collage"
                currentAlbumWebUri = albumDetails?.webUri ?: ""
            } catch (e: Exception) {
                // Fallback to Collage
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
                val merged = if (targetImageKey != null && firstPageImages.any { it.imageKey == targetImageKey }) {
                    firstPageImages
                } else {
                    (_rawPhotos.value + firstPageImages).distinctBy { it.imageKey }
                }
                _rawPhotos.value = merged

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
                        val accumulatedImages = _rawPhotos.value.toMutableList()
                        var tagsUpdated = false
                        while (currentNextUrl != null && (!com.smugview.app.data.repository.SmugMugRepository.isTesting || pageIndex <= 2)) {
                            try {
                                _backgroundLoadingStatus.value = "Downloading page $pageIndex..."
                                val nextPageResponse = repository.getAlbumImagesPageByUri(currentNextUrl, apiKey, password)
                                val nextPageImages = nextPageResponse.response.images ?: emptyList()
                                if (nextPageImages.isNotEmpty()) {
                                    val nextPageExpansions = nextPageResponse.expansions
                                    imagesUrlUpdate(nextPageImages, nextPageExpansions)
                                    accumulatedImages.addAll(nextPageImages)
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
                                    _rawPhotos.value = accumulatedImages.distinctBy { it.imageKey }
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
                        _rawPhotos.value = accumulatedImages.distinctBy { it.imageKey }
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
                _albumLoadError.value = e.localizedMessage ?: "Failed to load album images"
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
            viewModelScope.launch {
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

    fun getImageDetails(imageKey: String): StateFlow<Result<AlbumImageData>?> {
        val flow = _imageDetailsStates.getOrPut(imageKey) {
            val stateFlow = MutableStateFlow<Result<AlbumImageData>?>(null)
            viewModelScope.launch {
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
                                val allNodes = repository.getAllCachedNodes()
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
                        val allNodes = repository.getAllCachedNodes()
                        val matchedNode = allNodes.find { it.nodeId == resolvedKey || it.getAlbumKey() == resolvedKey }
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
                            // Password locked category: try unlocking using saved credentials
                            val savedPasswords = passwordPrefs.all.values.filterIsInstance<String>().distinct()
                            for (savedPw in savedPasswords) {
                                val unlocked = repository.unlockAlbum(resolvedKey, apiKey, savedPw) || repository.unlockNode(resolvedKey, apiKey, savedPw)
                                if (unlocked) {
                                    passwordPrefs.edit().putString(resolvedKey, savedPw).apply()
                                    var tempResult: Result<AlbumImageData>? = null
                                    repository.getImage(imageKey, apiKey, savedPw).collect { result ->
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
                                        break
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
            stateFlow
        }
        return flow.asStateFlow()
    }

    // --- Local Collections & Sync ---

    fun createCollection(name: String) {
        viewModelScope.launch {
            val nickname = _activeNickname.value ?: ""
            repository.createLocalCollection(name, nickname)
        }
    }

    fun deleteCollection(collectionId: Long) {
        viewModelScope.launch {
            repository.deleteLocalCollection(collectionId)
        }
    }

    fun renameCollection(collectionId: Long, newName: String) {
        viewModelScope.launch {
            repository.renameLocalCollection(collectionId, newName)
        }
    }

    fun getBookmarksForCollection(collectionId: Long): Flow<List<CollectionBookmark>> {
        return repository.getBookmarksForCollection(collectionId)
    }

    fun addBookmark(collectionId: Long, type: String, itemKey: String, title: String, albumKey: String = "", albumTitle: String = "", thumbnailUrl: String? = null, imageUrl: String? = null) {
        if (BuildConfig.DEBUG) {
            android.util.Log.d("SmugViewModel", "addBookmark: collectionId=$collectionId, type=$type, itemKey=$itemKey, title='$title'")
        }
        viewModelScope.launch {
            val bookmark = CollectionBookmark(
                collectionId = collectionId,
                type = type,
                itemKey = itemKey,
                title = title,
                albumKey = albumKey,
                albumTitle = albumTitle,
                thumbnailUrl = thumbnailUrl
            )
            repository.addBookmark(bookmark)

            // Automatically mark as offline (download)
            if (type == "Image" && !imageUrl.isNullOrEmpty()) {
                downloadPhotoOffline(itemKey, imageUrl)
            } else if (type == "Album") {
                downloadAlbumOffline(itemKey, apiKey, getUnlockedPassword(itemKey))
            }
        }
    }

    fun removeBookmark(collectionId: Long, type: String, itemKey: String) {
        if (BuildConfig.DEBUG) {
            android.util.Log.d("SmugViewModel", "removeBookmark: collectionId=$collectionId, type=$type, itemKey=$itemKey")
        }
        viewModelScope.launch {
            repository.removeBookmark(collectionId, type, itemKey)

            // If not bookmarked anywhere else, clear the offline file
            val isBookmarkedAnywhere = repository.isBookmarkedAnywhere(type, itemKey)
            if (!isBookmarkedAnywhere) {
                if (type == "Image") {
                    deleteOfflinePhoto(itemKey)
                } else if (type == "Album") {
                    deleteOfflineAlbum(itemKey, apiKey)
                }
            }
        }
    }

    fun downloadPhotoOffline(imageKey: String, imageUrl: String) {
        downloadPhotoOffline(imageKey, imageUrl, {}, {})
    }

    fun deleteOfflinePhoto(imageKey: String) {
        deleteOfflinePhoto(imageKey, {})
    }

    suspend fun isBookmarked(collectionId: Long, type: String, itemKey: String): Boolean {
        return repository.isBookmarked(collectionId, type, itemKey)
    }

    suspend fun isBookmarkedAnywhere(type: String, itemKey: String): Boolean {
        return repository.isBookmarkedAnywhere(type, itemKey)
    }

    fun addPhotoToCollection(photo: AlbumImageData, collectionId: Long) {
        if (BuildConfig.DEBUG) {
            android.util.Log.d("SmugViewModel", "addPhotoToCollection: imageKey=${photo.imageKey}, collectionId=$collectionId")
        }
        viewModelScope.launch {
            val dbPhoto = CollectionPhoto(
                imageKey = photo.imageKey,
                collectionId = collectionId,
                albumKey = _currentAlbumKey.value,
                title = photo.title ?: photo.caption,
                thumbnailUrl = photo.thumbnailUrl,
                archivedUri = photo.archivedUri,
                localFilePath = null,
                dateTaken = photo.date,
                keywords = photo.keywords,
                isDownloaded = false
            )
            repository.addPhotoToCollection(dbPhoto)
            
            val syncRequest = OneTimeWorkRequestBuilder<OfflineDownloadWorker>().build()
            workManager.enqueue(syncRequest)
        }
    }

    fun getPhotosInCollection(collectionId: Long): Flow<List<CollectionPhoto>> {
        return repository.getPhotosInCollection(collectionId)
    }

    fun navigateToHome() {
        _activeTab.value = BrowserTab.Folders
        val rootNodeId = _splashState.value.let {
            if (it is SplashUiState.Success) it.rootNodeId else null
        }
        if (rootNodeId != null) {
            currentFolderId = rootNodeId
            folderNavigationStack.clear()
            loadFolderContents(rootNodeId)
        }
    }

    fun navigateToStackFolder(index: Int) {
        if (index < 0) {
            navigateToHome()
        } else if (index < folderNavigationStack.size) {
            while (folderNavigationStack.size > index + 1) {
                folderNavigationStack.removeAt(folderNavigationStack.size - 1)
            }
            val targetNode = folderNavigationStack[index]
            currentFolderId = targetNode.nodeId
            loadFolderContents(targetNode.nodeId)
        }
    }

    fun isAlbumDownloaded(albumKey: String): Boolean {
        return sharedPrefs.getBoolean("offline_album_$albumKey", false)
    }

    fun downloadAlbumOffline(albumKey: String, apiKey: String, password: String? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _backgroundLoadingStatus.value = "Starting album download..."
                _isBackgroundLoading.value = true
                val photos = repository.getAllAlbumImages(albumKey, apiKey, password)
                if (photos.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(getApplication(), "No photos to download", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }
                val directory = File(getApplication<Application>().filesDir, "offline_photos")
                if (!directory.exists()) {
                    directory.mkdirs()
                }
                val client = okhttp3.OkHttpClient()
                var successCount = 0
                photos.forEachIndexed { index, photo ->
                    _backgroundLoadingStatus.value = "Downloading ${index + 1}/${photos.size}..."
                    val url = photo.archivedUri ?: photo.thumbnailUrl
                    if (!url.isNullOrEmpty()) {
                        try {
                            val request = okhttp3.Request.Builder().url(url).build()
                            val response = client.newCall(request).execute()
                            if (response.isSuccessful) {
                                val body = response.body
                                if (body != null) {
                                    val file = File(directory, "${photo.imageKey}.jpg")
                                    body.byteStream().use { input ->
                                        FileOutputStream(file).use { output ->
                                            input.copyTo(output)
                                        }
                                    }
                                    repository.updateDownloadStatusForAll(photo.imageKey, file.absolutePath, true)
                                    successCount++
                                }
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }
                sharedPrefs.edit().putBoolean("offline_album_$albumKey", true).apply()
                withContext(Dispatchers.Main) {
                    Toast.makeText(getApplication(), "Album downloaded offline ($successCount photos)", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(getApplication(), "Album download failed: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                }
            } finally {
                _backgroundLoadingStatus.value = null
                _isBackgroundLoading.value = false
            }
        }
    }

    fun deleteOfflineAlbum(albumKey: String, apiKey: String, password: String? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _backgroundLoadingStatus.value = "Deleting offline files..."
                _isBackgroundLoading.value = true
                val photos = repository.getAllAlbumImages(albumKey, apiKey, password)
                val directory = File(getApplication<Application>().filesDir, "offline_photos")
                photos.forEach { photo ->
                    val file = File(directory, "${photo.imageKey}.jpg")
                    if (file.exists()) {
                        file.delete()
                    }
                    repository.updateDownloadStatusForAll(photo.imageKey, null, false)
                }
                sharedPrefs.edit().remove("offline_album_$albumKey").apply()
                withContext(Dispatchers.Main) {
                    Toast.makeText(getApplication(), "Offline files deleted", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                _backgroundLoadingStatus.value = null
                _isBackgroundLoading.value = false
            }
        }
    }

    fun downloadPhotoOffline(imageKey: String, imageUrl: String, onSuccess: () -> Unit, onFailure: (String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val client = okhttp3.OkHttpClient()
                val request = okhttp3.Request.Builder().url(imageUrl).build()
                val response = client.newCall(request).execute()
                if (!response.isSuccessful) {
                    withContext(Dispatchers.Main) { onFailure("Download failed: HTTP ${response.code}") }
                    return@launch
                }
                val body = response.body
                if (body == null) {
                    withContext(Dispatchers.Main) { onFailure("Empty response body") }
                    return@launch
                }
                val directory = File(getApplication<Application>().filesDir, "offline_photos")
                if (!directory.exists()) {
                    directory.mkdirs()
                }
                val file = File(directory, "$imageKey.jpg")
                body.byteStream().use { input ->
                    FileOutputStream(file).use { output ->
                        input.copyTo(output)
                    }
                }
                repository.updateDownloadStatusForAll(imageKey, file.absolutePath, true)
                withContext(Dispatchers.Main) {
                    onSuccess()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    onFailure(e.localizedMessage ?: "Unknown error")
                }
            }
        }
    }

    fun deleteOfflinePhoto(imageKey: String, onSuccess: () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val directory = File(getApplication<Application>().filesDir, "offline_photos")
                val file = File(directory, "$imageKey.jpg")
                if (file.exists()) {
                    file.delete()
                }
                repository.updateDownloadStatusForAll(imageKey, null, false)
                withContext(Dispatchers.Main) {
                    onSuccess()
                }
            } catch (e: Exception) {
                // Ignore
            }
        }
    }

    fun removePhotoFromCollection(imageKey: String, collectionId: Long) {
        viewModelScope.launch {
            repository.removePhotoFromCollection(imageKey, collectionId)
        }
    }

    private var _userAlbums: List<com.smugview.app.data.api.AlbumDetails>? = null

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
        val allNodes = repository.getAllCachedNodes()
        
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
        val allNodes = repository.getAllCachedNodes()
        
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

    private fun startFolderTreeSync(rootNodeId: String) {
        treeSyncJob?.cancel()
        treeSyncJob = viewModelScope.launch {
            val visited = mutableSetOf<String>()
            val queue = mutableListOf<String>()
            queue.add(rootNodeId)

            while (queue.isNotEmpty() && isActive) {
                val currentNodeId = queue.removeAt(0)
                if (visited.contains(currentNodeId)) continue
                visited.add(currentNodeId)

                try {
                    val password = getUnlockedPassword(currentNodeId)
                    // Load children nodes, using cache if available (forceRefresh = false)
                    repository.getNodeChildren(currentNodeId, apiKey, forceRefresh = false, password = password, ignoreErrors = "true")
                        .first()
                        .fold(
                            onSuccess = { children ->
                                for (child in children) {
                                    if (child.type == "Folder") {
                                        queue.add(child.nodeId)
                                    }
                                }
                            },
                            onFailure = {
                                // Ignore password failures
                            }
                        )
                } catch (e: Exception) {
                    e.printStackTrace()
                }

                // Stagger requests to yield threads and respect rate limits
                delay(200)
            }
        }
    }
}

