package com.smugview.app.ui.viewmodel

import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import com.smugview.app.BuildConfig
import com.smugview.app.data.api.AlbumImageData
import com.smugview.app.data.db.CachedNode
import com.smugview.app.data.repository.SmugMugRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Owns the keyword-search ("Tags" tab) feature: the scope tag scan, the selected-tag
 * state, and the paged loading of images matching the selected keywords. Extracted
 * verbatim from [SmugViewModel] as part of the facade decomposition — the ViewModel
 * keeps its public surface and delegates here.
 *
 * This is a distinct system from the in-gallery tag *filter* (availableTags /
 * includedTags / excludedTags / photosFlow), which stays in the ViewModel because it
 * is welded to selectAlbum.
 *
 * The three cross-feature flows it observes ([searchScope], [isViewingDetail],
 * [activeNickname]) are injected read-only and remain ViewModel-owned;
 * [getUnlockedPassword] is injected so scoped scans can unlock gated roots. The
 * image-loading observe starts in this controller's init, replacing the call the
 * ViewModel's init block previously made.
 */
class TagSearchController(
    private val repository: SmugMugRepository,
    private val apiKey: String,
    private val viewModelScope: CoroutineScope,
    private val sharedPrefs: SharedPreferences,
    private val searchScope: StateFlow<SearchScope>,
    private val isViewingDetail: StateFlow<Boolean>,
    private val activeNickname: StateFlow<String?>,
    private val getUnlockedPassword: suspend (String) -> String?
) {
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

    // Tag Search Optimizations Caching & Pagination
    private var lastLoadedKeywords: String = ""
    private var lastLoadedScope: String = ""
    private var nextStartToLoad: Int = 1
    private var nextUrlToLoad: String? = null

    private val _keywordPhotosTotal = MutableStateFlow(0)
    val keywordPhotosTotal: StateFlow<Int> = _keywordPhotosTotal.asStateFlow()

    // Tag Search Optimizations Caching
    private val _albumKeywordsMap = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    private val loadedAlbumImages = ConcurrentHashMap<String, List<AlbumImageData>>()
    private var scopeAlbums = emptyList<CachedNode>()

    private val _selectedTags = MutableStateFlow<Map<String, SmugViewModel.TagFilterState>>(emptyMap())
    val selectedTags: StateFlow<Map<String, SmugViewModel.TagFilterState>> = _selectedTags.asStateFlow()

    // Bumped to re-fire the image-loading observe when scope loading is resumed.
    private val _scopeReloadTrigger = MutableStateFlow(0)

    var tagCloudLimit by mutableStateOf(25)

    var tagSearchQuery by mutableStateOf("")

    // Keyword Photos Sort Order: "Descending" or "Ascending"
    var keywordPhotosSortOrder by mutableStateOf(sharedPrefs.getString("keyword_photos_sort_order", "Descending") ?: "Descending")
        private set

    fun updateKeywordPhotosSortOrder(order: String) {
        keywordPhotosSortOrder = order
        sharedPrefs.edit().putString("keyword_photos_sort_order", order).apply()
    }

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
            val included = selected.filter { it.value == SmugViewModel.TagFilterState.INCLUDED }.keys
            val excluded = selected.filter { it.value == SmugViewModel.TagFilterState.EXCLUDED }.keys
            val filtered = photos.filter { photo ->
                val titleTokens = photo.title?.lowercase()?.split(Regex("[^a-zA-Z0-9]+"))?.filter { it.isNotEmpty() } ?: emptyList()
                val captionTokens = photo.caption?.lowercase()?.split(Regex("[^a-zA-Z0-9]+"))?.filter { it.isNotEmpty() } ?: emptyList()
                val fileNameTokens = photo.fileName?.lowercase()?.split(Regex("[^a-zA-Z0-9]+"))?.filter { it.isNotEmpty() } ?: emptyList()
                val keywordTokens = photo.keywordsString?.lowercase()?.split(Regex("[^a-zA-Z0-9]+"))?.filter { it.isNotEmpty() } ?: emptyList()
                val allTokens = (titleTokens + captionTokens + fileNameTokens + keywordTokens).toSet()

                val hasAllIncluded = included.all { tag ->
                    val tagTokens = tag.lowercase().split(Regex("[^a-zA-Z0-9]+")).filter { it.isNotEmpty() }
                    tagTokens.isEmpty() || tagTokens.all { allTokens.contains(it) }
                }
                val hasNoExcluded = excluded.none { tag ->
                    val tagTokens = tag.lowercase().split(Regex("[^a-zA-Z0-9]+")).filter { it.isNotEmpty() }
                    tagTokens.isNotEmpty() && tagTokens.all { allTokens.contains(it) }
                }
                hasAllIncluded && hasNoExcluded
            }.distinctBy { it.imageKey }

            if (sortOrder == "Ascending") {
                filtered.sortedWith(compareBy<AlbumImageData> { it.dateTime ?: it.date ?: "" })
            } else {
                filtered.sortedWith(compareByDescending<AlbumImageData> { it.dateTime ?: it.date ?: "" })
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun selectSingleTag(tag: String) {
        val current = mutableMapOf<String, SmugViewModel.TagFilterState>()
        current[tag.lowercase().trim()] = SmugViewModel.TagFilterState.INCLUDED
        _selectedTags.value = current
    }

    fun clearSelectedTags() {
        if (BuildConfig.DEBUG) {
            android.util.Log.d("SmugViewModel", "clearSelectedTags called")
        }
        _selectedTags.value = emptyMap()
    }

    fun selectTag(tag: String, state: SmugViewModel.TagFilterState = SmugViewModel.TagFilterState.INCLUDED) {
        if (BuildConfig.DEBUG) {
            android.util.Log.d("SmugViewModel", "selectTag called: tag=$tag, state=$state")
        }
        val current = _selectedTags.value.toMutableMap()
        if (current.isEmpty()) {
            current[tag.lowercase()] = SmugViewModel.TagFilterState.INCLUDED
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
                current[tag.lowercase()] = if (currentState == SmugViewModel.TagFilterState.INCLUDED) SmugViewModel.TagFilterState.EXCLUDED else SmugViewModel.TagFilterState.INCLUDED
            }
            _selectedTags.value = current
        }
    }

    private var imageLoadJob: Job? = null

    // When true, the scoped-tag image loader is halted. Set while the user navigates away from the
    // keyword screen (e.g. "Jump to Gallery") so its multi-thousand-image pagination doesn't flood
    // the API and starve the destination gallery's load. Bumping [_scopeReloadTrigger] re-fires the
    // observe flow so loading resumes when the user returns to the keyword screen.
    @Volatile private var scopeLoadingSuppressed = false

    /**
     * Pause or resume the keyword/scope image loader. Call with true before navigating away from
     * the keyword results (jump-to-gallery); call with false when the keyword screen resumes.
     */
    fun setScopeLoadingSuppressed(suppressed: Boolean) {
        if (scopeLoadingSuppressed == suppressed) return
        scopeLoadingSuppressed = suppressed
        if (suppressed) {
            imageLoadJob?.cancel()
        } else {
            // Nudge the observe flow so loading resumes for the still-selected tags.
            _scopeReloadTrigger.value = _scopeReloadTrigger.value + 1
        }
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

    /** Clear all keyword-search state. Called when the active site changes so one site's
     *  tags/photos don't leak onto another. Sort-order and tag-cloud-limit prefs are kept. */
    fun reset() {
        imageLoadJob?.cancel()
        imageLoadJob = null
        tagScanJob?.cancel()
        tagScanJob = null
        _selectedTags.value = emptyMap()
        _allScopeTags.value = emptyMap()
        _allScopePhotos.value = emptyList()
        _keywordPhotosTotal.value = 0
        _isScanningTags.value = false
        _isLoadingPhotos.value = false
        _scanProgress.value = ""
        tagSearchQuery = ""
        lastLoadedKeywords = ""
        lastLoadedScope = ""
        nextStartToLoad = 1
        nextUrlToLoad = null
        scopeLoadingSuppressed = false
        _albumKeywordsMap.value = emptyMap()
        loadedAlbumImages.clear()
        scopeAlbums = emptyList()
    }

    init {
        observeSelectedTagsToLoadImages()
    }

    private fun observeSelectedTagsToLoadImages() {
        viewModelScope.launch {
            combine(
                _selectedTags,
                searchScope,
                isViewingDetail,
                _scopeReloadTrigger
            ) { selected, scope, isViewing, _ ->
                Triple(selected, scope, isViewing)
            }.collect { (selected, scope, isViewing) ->
                if (scopeLoadingSuppressed) {
                    // Halted while the user is off the keyword screen (see setScopeLoadingSuppressed).
                    imageLoadJob?.cancel()
                    _isLoadingPhotos.value = false
                    return@collect
                }
                val included = selected.filter { it.value == SmugViewModel.TagFilterState.INCLUDED }.keys
                if (included.isEmpty()) {
                    imageLoadJob?.cancel()
                    _allScopePhotos.value = emptyList()
                    _keywordPhotosTotal.value = 0
                    lastLoadedKeywords = ""
                    lastLoadedScope = ""
                    nextStartToLoad = 1
                    nextUrlToLoad = null
                    _isLoadingPhotos.value = false
                    return@collect
                }

                val nickname = activeNickname.value ?: return@collect
                val resolvedRootId = if (scope.nodeUri == null || scope.nodeId == null) {
                    repository.getUserRootNodeId(nickname = nickname, apiKey = apiKey).first().getOrNull()
                } else {
                    null
                }
                val scopeUri = scope.nodeUri ?: resolvedRootId?.let { "/api/v2/node/$it" } ?: "/api/v2/user/$nickname"
                val keywordsQuery = included.joinToString(separator = ",")

                if (isViewing) {
                    // Halt loading when opening detail view
                    imageLoadJob?.cancel()
                    _isLoadingPhotos.value = false
                    return@collect
                }

                // If keywords or scope changed, or we are not resuming, reset progress
                if (keywordsQuery != lastLoadedKeywords || scopeUri != lastLoadedScope) {
                    imageLoadJob?.cancel()
                    lastLoadedKeywords = keywordsQuery
                    lastLoadedScope = scopeUri
                    nextStartToLoad = 1
                    nextUrlToLoad = null
                    _keywordPhotosTotal.value = 0
                    _allScopePhotos.value = emptyList()
                } else if (imageLoadJob?.isActive == true) {
                    // Already loading the correct query, let it continue
                    return@collect
                }

                // Start or resume loading
                _isLoadingPhotos.value = true
                _scanProgress.value = "Loading photos for selected tags..."
                imageLoadJob = viewModelScope.launch(context = Dispatchers.IO) imageSearchLaunch@{
                    try {
                        val targetScopeId = scope.nodeId ?: resolvedRootId
                        if (targetScopeId != null) {
                            val savedPassword = getUnlockedPassword(targetScopeId)
                            if (savedPassword != null) {
                                repository.unlockInheritedPasswordRoot(targetScopeId, apiKey, savedPassword)
                            }
                        }

                        var currentStart = nextStartToLoad
                        var currentNextUrl = nextUrlToLoad
                        var isFirstPage = (currentStart == 1 && currentNextUrl == null)

                        // SmugMug's Elasticsearch-backed search refuses pagination past ~10,000
                        // results (from + size <= 10000). If we've already loaded up to that window,
                        // stop rather than triggering the "Result window is too large" error.
                        if (currentStart > MAX_SEARCH_START) {
                            _keywordPhotosTotal.value = _allScopePhotos.value.size
                            _isLoadingPhotos.value = false
                            return@imageSearchLaunch
                        }

                        // Load page by page
                        val (pageImages, nextUrlToken, total) = repository.getImagesByKeywordPage(
                            scope = scopeUri,
                            keywords = keywordsQuery,
                            apiKey = apiKey,
                            count = 500,
                            start = currentStart,
                            nextUrl = currentNextUrl
                        )

                        val mappedPage = pageImages.map { img ->
                            if (img.keywordsString.isNullOrEmpty()) {
                                img.copy(keywordArray = included.toList())
                            } else {
                                img
                            }
                        }

                        if (isFirstPage) {
                            _allScopePhotos.value = mappedPage
                        } else {
                            _allScopePhotos.value = (_allScopePhotos.value + mappedPage).distinctBy { it.imageKey }
                        }

                        _keywordPhotosTotal.value = total
                        currentNextUrl = nextUrlToken
                        currentStart += pageImages.size
                        nextStartToLoad = currentStart
                        nextUrlToLoad = currentNextUrl

                        var pageCount = 1
                        while (currentNextUrl != null && currentStart <= MAX_SEARCH_START && pageCount < repository.maxPagesPerFetch) {
                            if (!isActive) break

                            val (nextPageImages, nextPageToken, nextPageTotal) = repository.getImagesByKeywordPage(
                                scope = scopeUri,
                                keywords = keywordsQuery,
                                apiKey = apiKey,
                                count = 500,
                                start = currentStart,
                                nextUrl = currentNextUrl
                            )

                            val mappedNextPage = nextPageImages.map { img ->
                                if (img.keywordsString.isNullOrEmpty()) {
                                    img.copy(keywordArray = included.toList())
                                } else {
                                    img
                                }
                            }

                            _allScopePhotos.value = (_allScopePhotos.value + mappedNextPage).distinctBy { it.imageKey }

                            _keywordPhotosTotal.value = nextPageTotal
                            currentNextUrl = nextPageToken
                            currentStart += nextPageImages.size
                            nextStartToLoad = currentStart
                            nextUrlToLoad = currentNextUrl
                            pageCount++
                            kotlinx.coroutines.delay(100)
                        }

                        // If we stopped at the result-window cap rather than the true end of
                        // results, report the loaded count as the total so the determinate progress
                        // bar settles instead of stalling short of 100%.
                        if (currentNextUrl != null && currentStart > MAX_SEARCH_START) {
                            _keywordPhotosTotal.value = _allScopePhotos.value.size
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("SmugViewModel", "Failed to load/resume photos for keywords: $keywordsQuery", e)
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

    private var tagScanJob: Job? = null

    fun triggerTagScopeScan(scope: SearchScope, clearSelected: Boolean = true) {
        tagScanJob?.cancel()
        tagScanJob = viewModelScope.launch {
            _isScanningTags.value = true
            _scanProgress.value = "Starting scan..."
            _allScopePhotos.value = emptyList()
            _allScopeTags.value = emptyMap()
            if (clearSelected) {
                _selectedTags.value = emptyMap()
            }
            _albumKeywordsMap.value = emptyMap()
            loadedAlbumImages.clear()
            scopeAlbums = emptyList()

            try {
                if (activeNickname.value == null) return@launch

                val rootNodeId = if (scope.nodeId == null || scope.nodeUri == null) {
                    repository.getUserRootNodeId(activeNickname.value!!, apiKey).first().getOrNull()
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
                    val response = repository.getUserTopKeywords(activeNickname.value!!, apiKey, targetScopeId)
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

    companion object {
        // SmugMug's Elasticsearch search backend caps deep pagination at from + size <= 10000.
        // With a page size of 500, the last safe 1-indexed start is 9501.
        private const val MAX_SEARCH_START = 9501
    }
}
