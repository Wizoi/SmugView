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
 *
 * [scope] is the ViewModel scope (history prefs, paging cache); [siteScope] yields the active
 * site session's scope (design 3.1), where the search jobs run so a site switch cancels them.
 */
class SearchController(
    private val repository: SmugMugRepository,
    private val apiKey: String,
    private val scope: CoroutineScope,
    private val siteScope: () -> CoroutineScope,
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

    /**
     * True while galleries/folders are still resolving — specifically, while waiting on
     * [SmugMugRepository.isAlbumsCacheLoaded] / [SmugMugRepository.isIndexingSubtree]. Without
     * this, [SearchUiState.Success] with empty `galleries`/`folders` is indistinguishable from
     * "search found nothing": since photos are decoupled and can arrive first, the Galleries/
     * Folders tabs would show "(0)" — reading as zero results — for as long as that wait takes
     * (verified live: up to 18+ seconds after unlocking a folder with several sub-folders), with
     * no indication anything is still happening. UI should show a loading state for those two
     * tabs specifically while this is true, not a "no results" empty state.
     */
    private val _isGalleriesFoldersLoading = MutableStateFlow(false)
    val isGalleriesFoldersLoading: StateFlow<Boolean> = _isGalleriesFoldersLoading.asStateFlow()

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

    /** Why the photo search failed, kept here so every later state publish of the same search carries it (N1). */
    private var photosProblem: com.smugview.app.ui.text.Problem? = null

    private fun publishPhotosProblem(problem: com.smugview.app.ui.text.Problem?) {
        photosProblem = problem
        val current = _searchState.value
        if (current is SearchUiState.Success) _searchState.value = current.copy(photosProblem = problem)
    }
    private var backgroundSearchJob: Job? = null

    /** Clear all search results/state. Called when the active site changes so one site's
     *  search results don't leak onto another. Sort-order prefs are intentionally kept. */
    fun reset() {
        cancelSearchJob()
        searchQuery = ""
        searchResultTab = 0
        _searchPhotosPagingFlow.value = kotlinx.coroutines.flow.emptyFlow()
        _searchState.value = SearchUiState.Idle
        _isSearchPhotosLoading.value = false
        photosProblem = null
        _isGalleriesFoldersLoading.value = false
    }

    /** Process death (R-17): the query text comes back into the box; the search is not run again. */
    fun restoreQuery(query: String) {
        searchQuery = query
    }

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
            _isGalleriesFoldersLoading.value = false
            return
        }
        _searchState.value = SearchUiState.Loading
        photosProblem = null
        searchJob?.cancel()
        backgroundSearchJob?.cancel()
        scope.launch {
            repository.insertSearchQuery(query, activeNickname.value ?: "")
        }
        searchJob = siteScope().launch {
            try {
                val nickname = activeNickname.value
                if (nickname.isNullOrEmpty()) {
                    _searchState.value = SearchUiState.Error(com.smugview.app.ui.text.Problem.Unexpected(com.smugview.app.ui.text.Subject.Search, "no site"))
                    _isGalleriesFoldersLoading.value = false
                    return@launch
                }

                // The search scope: the picked folder, else the whole site as its own user. A user URI needs no
                // lookup and can never be another site's root (design 3.6, R-33).
                val activeScope = searchScope.value
                val scopeUri = activeScope.nodeUri
                val scopeKey = activeScope.nodeId ?: "site:$nickname"
                val apiScopeUri = scopeUri ?: "/api/v2/user/$nickname"

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

                // --- Photos: a live/paged API-backed flow (performBackgroundSearchImages,
                // getPagedSearchPhotos) that never reads albumsCache/cached_nodes, so it must not
                // be held up by the gallery/folder cache wait below. Wire the Pager and kick off
                // the background fetch right away, then flip _searchState out of Loading so the UI
                // (which gates the whole results shell — tabs + Photos content — behind
                // SearchUiState.Success; see SearchTabView.kt) can start rendering photos as they
                // arrive instead of sitting on one spinner until galleries/folders are also ready.
                // Galleries/Folders tabs briefly show their real counts as 0 and backfill via a
                // second _searchState update once the cache wait below clears.
                _searchPhotosPagingFlow.value = androidx.paging.Pager(
                    config = androidx.paging.PagingConfig(pageSize = 60, enablePlaceholders = true)
                ) {
                    repository.getPagedSearchPhotos(query, scopeKey, searchPhotosSortOrder)
                }.flow.map { pagingData ->
                    pagingData.map { it.toAlbumImageData() }
                }.cachedIn(scope)

                _searchState.value = SearchUiState.Success(
                    photos = emptyList(), // Replaced by Pager
                    galleries = emptyList(),
                    folders = emptyList(),
                    photosProblem = photosProblem
                )
                _isGalleriesFoldersLoading.value = true

                if (isFullySearched && !forceRefresh) {
                    if (BuildConfig.DEBUG) {
                        android.util.Log.d("SmugViewModel", "performSearch: photos cache is valid (fully searched in past 24h); skipping background photo fetch")
                    }
                    _isSearchPhotosLoading.value = false
                } else {
                    _isSearchPhotosLoading.value = true
                    backgroundSearchJob?.cancel()
                    backgroundSearchJob = siteScope().launch {
                        try {
                            repository.performBackgroundSearchImages(nickname, apiScopeUri, scopeKey, query, apiKey)
                            searchStatusPrefs.edit().putLong("${scopeKey}_${query}_ts", System.currentTimeMillis()).apply()
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            // N1: this used to escape into a scope with no handler and close the app. The search is
                            // not marked "fully searched" (no timestamp), so the next search asks again.
                            com.smugview.app.util.SmugLog.w("search", "photo search failed: ${e.javaClass.simpleName}", e)
                            publishPhotosProblem(com.smugview.app.ui.text.Problem.from(e, com.smugview.app.ui.text.Subject.Search))
                        } finally {
                            _isSearchPhotosLoading.value = false
                        }
                    }
                }

                // --- Galleries/Folders: DO depend on the local node/album cache being complete,
                // so this part waits.
                if (!repository.isAlbumsCacheLoaded.value) {
                    if (BuildConfig.DEBUG) {
                        android.util.Log.d("SmugViewModel", "performSearch waiting for gallery cache to finish loading")
                    }
                    repository.isAlbumsCacheLoaded.first { it }
                }

                // Wait for any in-progress post-unlock subtree indexing (SmugMugRepository
                // .unlockAndIndexSubtree) to finish. Without this, searching right after unlocking
                // a folder can read repository.albumsCache.value mid-write — matching only
                // whatever galleries the background walk has reached so far — and then cache that
                // incomplete snapshot as "fully searched" for 24h (below), silently hiding
                // galleries the walk hadn't indexed yet until the cache expires or a manual
                // refresh. See the "search reads incomplete data mid-background-sync" fix in
                // AGENTS.md.
                if (repository.isIndexingSubtree.value) {
                    if (BuildConfig.DEBUG) {
                        android.util.Log.d("SmugViewModel", "performSearch waiting for post-unlock subtree indexing to finish")
                    }
                    repository.isIndexingSubtree.first { !it }
                }

                // 1. Load cached search results from the database (Folders)
                val cachedFolders = repository.getSearchResultNodes(query, scopeKey, "Folder")
                if (BuildConfig.DEBUG) {
                    android.util.Log.d("SmugViewModel", "performSearch: found ${cachedFolders.size} folders in database cache")
                }

                // Fetch galleries from in-memory cache
                val lowerQuery = query.lowercase()
                val cachedGalleries = repository.albumsCacheFor(nickname).filter {
                    it.title.lowercase().contains(lowerQuery)
                }
                if (BuildConfig.DEBUG) {
                    android.util.Log.d("SmugViewModel", "performSearch: matched ${cachedGalleries.size} galleries from in-memory cache")
                }
                val sortedGalleries = sortGalleries(cachedGalleries, searchGallerySortOrder)

                _searchState.value = SearchUiState.Success(
                    photos = emptyList(),
                    galleries = sortedGalleries,
                    folders = cachedFolders,
                    photosProblem = photosProblem
                )
                _isGalleriesFoldersLoading.value = false

                if (isFullySearched && !forceRefresh) {
                    if (BuildConfig.DEBUG) {
                        android.util.Log.d("SmugViewModel", "performSearch cache is valid (fully searched in past 24h). Skipping remote gallery/folder refresh.")
                    }
                    return@launch
                }

                // 2. Search folders and galleries from API (node!search)
                try {
                    // Note: searchNodesRemote still fetches folders if available from SmugMug search API.
                    // Galleries are exclusively handled by the in-memory cache.
                    repository.searchNodesRemote(nickname, apiScopeUri, scopeKey, query, apiKey).collect {}
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    e.printStackTrace()
                }

                val updatedFolders = repository.getSearchResultNodes(query, scopeKey, "Folder")

                _searchState.value = SearchUiState.Success(
                    photos = emptyList(),
                    galleries = sortedGalleries, // using the memory cache galleries again
                    folders = updatedFolders,
                    photosProblem = photosProblem
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                e.printStackTrace()
                _isSearchPhotosLoading.value = false
                _isGalleriesFoldersLoading.value = false
                _searchState.value = SearchUiState.Error(com.smugview.app.ui.text.Problem.from(e, com.smugview.app.ui.text.Subject.Search))
            }
        }
    }
}
