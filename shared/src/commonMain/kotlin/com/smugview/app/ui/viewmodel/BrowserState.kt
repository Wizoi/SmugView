package com.smugview.app.ui.viewmodel

import com.smugview.app.data.api.AlbumImageData
import com.smugview.app.data.db.CachedNode

// The states the browsing screens draw and the controllers produce (they used to sit in SmugViewModel.kt).

sealed interface SplashUiState {
    object Idle : SplashUiState
    object Loading : SplashUiState
    data class Success(val rootNodeId: String) : SplashUiState
    /** The profile could not be fetched (design 3.3, N2): what went wrong, as a kind the screens put in words. */
    data class Error(val problem: com.smugview.app.ui.text.Problem) : SplashUiState
}

sealed interface BrowserUiState {
    object Loading : BrowserUiState
    data class Success(val nodes: List<CachedNode>) : BrowserUiState
    data class Error(val problem: com.smugview.app.ui.text.Problem) : BrowserUiState
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
        /** The photo search failed (N1, R-47): said above the results; galleries and folders still come from the index. */
        val photosProblem: com.smugview.app.ui.text.Problem? = null
    ) : SearchUiState
    data class Error(val problem: com.smugview.app.ui.text.Problem) : SearchUiState
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

sealed interface GlobalSearchUiState {
    object Idle : GlobalSearchUiState
    object Loading : GlobalSearchUiState
    data class Success(val sites: List<com.smugview.app.data.repository.DiscoveredSite>) : GlobalSearchUiState
    data class Error(val problem: com.smugview.app.ui.text.Problem) : GlobalSearchUiState
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
