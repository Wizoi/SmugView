package com.smugview.app.ui.viewmodel

import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.map
import com.smugview.app.BuildConfig
import com.smugview.app.data.api.AlbumImageData
import com.smugview.app.data.db.CachedNode
import com.smugview.app.data.db.SearchHistory
import com.smugview.app.data.db.toAlbumImageData
import com.smugview.app.data.repository.SmugMugRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Owns the image/gallery search feature: the query, result state, paging flow,
 * sort orders, and search history. Extracted verbatim from [SmugViewModel] as
 * part of the facade decomposition — the ViewModel keeps its public search
 * surface and delegates here, so screens and tests are unaffected.
 *
 * [searchScope] is the cross-feature browsing scope (shared with the tag engine
 * and tab switching); it is injected read-only and remains owned by the
 * ViewModel. [getUnlockedPassword] is injected so scoped searches can unlock
 * gated galleries without this controller touching the node/album cache.
 */
class SearchController(
    private val repository: SmugMugRepository,
    private val apiKey: String,
    private val scope: CoroutineScope,
    private val sharedPrefs: SharedPreferences,
    private val searchStatusPrefs: SharedPreferences,
    private val searchScope: StateFlow<SearchScope>,
    private val activeNickname: StateFlow<String?>,
    private val getUnlockedPassword: suspend (String) -> String?
) {
    var searchQuery by mutableStateOf("")
        private set

    var searchResultTab by mutableStateOf(0)

    private val _searchPhotosPagingFlow = MutableStateFlow<Flow<PagingData<AlbumImageData>>>(kotlinx.coroutines.flow.emptyFlow())
    val searchPhotosPagingFlow: StateFlow<Flow<PagingData<AlbumImageData>>> = _searchPhotosPagingFlow

    @OptIn(ExperimentalCoroutinesApi::class)
    val searchHistory: StateFlow<List<SearchHistory>> = activeNickname
        .flatMapLatest { nickname ->
            repository.getSearchHistory(nickname ?: "")
        }
        .stateIn(
            scope = scope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    fun clearSearchHistory() {
        scope.launch {
            repository.clearSearchHistory(activeNickname.value ?: "")
        }
    }

    fun deleteSearchQuery(query: String) {
        scope.launch {
            repository.deleteSearchQuery(query, activeNickname.value ?: "")
        }
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

    private var searchJob: Job? = null
    private var backgroundSearchJob: Job? = null

    fun cancelSearchJob() {
        searchJob?.cancel()
        searchJob = null
        backgroundSearchJob?.cancel()
        backgroundSearchJob = null
    }

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
        scope.launch {
            repository.insertSearchQuery(query, activeNickname.value ?: "")
        }
        searchJob = scope.launch {
            try {
                val nickname = activeNickname.value
                if (nickname.isNullOrEmpty()) {
                    _searchState.value = SearchUiState.Error("No active site profile loaded")
                    return@launch
                }

                // Resolve search scope URI
                val activeScope = searchScope.value
                val resolvedRootId = if (activeScope.nodeUri == null || activeScope.nodeId == null) {
                    repository.getUserRootNodeId(nickname, apiKey).first().getOrNull()
                } else {
                    null
                }

                val scopeUri = activeScope.nodeUri
                val scopeKey = activeScope.nodeId ?: "site:$nickname"

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
                }.cachedIn(scope)

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
                backgroundSearchJob = scope.launch {
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
}
