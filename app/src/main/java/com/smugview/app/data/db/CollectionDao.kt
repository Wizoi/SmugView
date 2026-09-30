package com.smugview.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface CollectionDao {

    // Cached Nodes Operations
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNodes(nodes: List<CachedNode>)

    // --- Persisted flat album index (see CachedAlbum) ---
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAlbums(albums: List<CachedAlbum>)

    @Query("SELECT * FROM cached_albums WHERE nickname = :nickname OR nickname = '' ORDER BY sortIndex ASC")
    suspend fun getAlbumIndex(nickname: String): List<CachedAlbum>

    /** Most-recent LastUpdated we have cached — the stop marker for the incremental sync. */
    @Query("SELECT MAX(dateModified) FROM cached_albums WHERE nickname = :nickname OR nickname = ''")
    suspend fun getLatestAlbumDateModified(nickname: String): String?

    @Query("SELECT COUNT(*) FROM cached_albums WHERE nickname = :nickname OR nickname = ''")
    suspend fun getAlbumIndexCount(nickname: String): Int

    @Query("SELECT * FROM cached_nodes WHERE parentNodeId = :parentNodeId AND nodeId NOT LIKE 'virtual:%' ORDER BY sortIndex ASC")
    fun getCachedNodesByParent(parentNodeId: String?): Flow<List<CachedNode>>

    @Query("SELECT * FROM cached_nodes WHERE nodeId = :nodeId LIMIT 1")
    suspend fun getNodeById(nodeId: String): CachedNode?

    @Query("SELECT * FROM cached_nodes WHERE nodeId = :idOrKey OR albumUri LIKE '%' || :idOrKey LIMIT 1")
    suspend fun getNodeByIdOrKey(idOrKey: String): CachedNode?

    @Query("UPDATE cached_nodes SET childCount = :count WHERE nodeId = :nodeId")
    suspend fun updateChildCount(nodeId: String, count: Int)

    @Query("DELETE FROM cached_nodes")
    suspend fun clearAllCachedNodes()

    /** Evicts a folder's cached child listing so the next [getCachedNodesByParent] read misses
     *  cache and `getNodeChildren` refetches from the API instead of serving stale data. */
    @Query("DELETE FROM cached_nodes WHERE parentNodeId = :parentNodeId")
    suspend fun deleteNodesByParent(parentNodeId: String)

    @Query("""
        WITH RECURSIVE descendants(nodeId) AS (
            SELECT :scopeNodeId
            UNION
            SELECT n.nodeId FROM cached_nodes n
            JOIN descendants d ON n.parentNodeId = d.nodeId
        )
        SELECT * FROM cached_nodes
        WHERE nodeId IN descendants
          AND (title LIKE '%' || :query || '%' OR description LIKE '%' || :query || '%')
          AND type = :type
          AND nodeId NOT LIKE 'virtual:%'
    """)
    suspend fun searchNodesInScope(scopeNodeId: String, query: String, type: String): List<CachedNode>

    @Query("""
        WITH RECURSIVE descendants(nodeId) AS (
            SELECT :scopeNodeId
            UNION
            SELECT n.nodeId FROM cached_nodes n
            JOIN descendants d ON n.parentNodeId = d.nodeId
        )
        SELECT * FROM cached_nodes
        WHERE nodeId IN descendants AND type = 'Album'
    """)
    suspend fun getAlbumsInScope(scopeNodeId: String): List<CachedNode>

    @Query("""
        SELECT * FROM cached_nodes
        WHERE (title LIKE '%' || :query || '%' OR description LIKE '%' || :query || '%')
          AND type = :type
          AND nodeId NOT LIKE 'virtual:%'
          AND (nickname = :nickname OR nickname = '')
    """)
    suspend fun searchNodesGlobal(query: String, type: String, nickname: String = ""): List<CachedNode>

    @Query("""
        WITH RECURSIVE descendants(nodeId) AS (
            SELECT :nodeId
            UNION
            SELECT n.nodeId FROM cached_nodes n
            JOIN descendants d ON n.parentNodeId = d.nodeId
        )
        SELECT * FROM cached_nodes
        WHERE nodeId IN descendants AND nodeId != :nodeId
    """)
    suspend fun getAllDescendants(nodeId: String): List<CachedNode>

    @Query("SELECT * FROM cached_nodes")
    suspend fun getAllCachedNodes(): List<CachedNode>

    /** Same shape as [getAllCachedNodes] but scoped to one site via the existing nickname index,
     *  so a multi-site user's fallback lookups (webUri path matching) don't scan every node
     *  they've ever cached across every SmugMug site they've ever browsed — just the active one. */
    @Query("SELECT * FROM cached_nodes WHERE nickname = :nickname OR nickname = ''")
    suspend fun getCachedNodesForNickname(nickname: String): List<CachedNode>

    @Query("SELECT * FROM cached_nodes WHERE albumUri IN (:albumUris)")
    suspend fun getNodesByAlbumUris(albumUris: List<String>): List<CachedNode>

    @Query("UPDATE cached_nodes SET access = :access, passwordHint = :passwordHint WHERE nodeId = :nodeId")
    suspend fun updateNodeAccess(nodeId: String, access: String?, passwordHint: String?)

    // Offline Collections Operations
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun createCollection(collection: OfflineCollection): Long

    @Query("SELECT * FROM offline_collections WHERE siteNickname = :siteNickname ORDER BY name ASC")
    fun getCollectionsForSite(siteNickname: String): Flow<List<OfflineCollection>>

    @Query("SELECT * FROM offline_collections WHERE id = :collectionId LIMIT 1")
    suspend fun getCollectionById(collectionId: Long): OfflineCollection?

    @Query("DELETE FROM offline_collections WHERE id = :collectionId")
    suspend fun deleteCollection(collectionId: Long)

    // Collection Photos Operations
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addPhotoToCollection(photo: CollectionPhoto)

    @Query("SELECT * FROM collection_photos WHERE collectionId = :collectionId ORDER BY dateTaken DESC")
    fun getPhotosInCollection(collectionId: Long): Flow<List<CollectionPhoto>>

    @Query("SELECT * FROM collection_photos WHERE imageKey = :imageKey AND collectionId = :collectionId LIMIT 1")
    suspend fun getCollectionPhoto(imageKey: String, collectionId: Long): CollectionPhoto?

    @Query("DELETE FROM collection_photos WHERE imageKey = :imageKey AND collectionId = :collectionId")
    suspend fun removePhotoFromCollection(imageKey: String, collectionId: Long)

    @Query("UPDATE collection_photos SET localFilePath = :filePath, isDownloaded = :downloaded WHERE imageKey = :imageKey AND collectionId = :collectionId")
    suspend fun updateDownloadStatus(imageKey: String, collectionId: Long, filePath: String, downloaded: Boolean)

    @Query("UPDATE collection_photos SET localFilePath = :filePath, isDownloaded = :downloaded WHERE imageKey = :imageKey")
    suspend fun updateDownloadStatusForAll(imageKey: String, filePath: String?, downloaded: Boolean)

    @Query("SELECT * FROM collection_photos WHERE isDownloaded = 0")
    suspend fun getPendingDownloads(): List<CollectionPhoto>

    // Collection Bookmarks Operations
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addBookmark(bookmark: CollectionBookmark): Long

    @Query("SELECT * FROM collection_bookmarks WHERE collectionId = :collectionId")
    fun getBookmarksForCollection(collectionId: Long): Flow<List<CollectionBookmark>>

    @Query("DELETE FROM collection_bookmarks WHERE collectionId = :collectionId AND type = :type AND itemKey = :itemKey")
    suspend fun removeBookmark(collectionId: Long, type: String, itemKey: String)

    @Query("DELETE FROM collection_bookmarks WHERE itemKey = :itemKey")
    suspend fun removeBookmarkGlobally(itemKey: String)

    @Query("SELECT EXISTS(SELECT 1 FROM collection_bookmarks WHERE collectionId = :collectionId AND type = :type AND itemKey = :itemKey)")
    suspend fun isBookmarked(collectionId: Long, type: String, itemKey: String): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM collection_bookmarks WHERE type = :type AND itemKey = :itemKey)")
    suspend fun isBookmarkedAnywhere(type: String, itemKey: String): Boolean

    @Query("UPDATE offline_collections SET name = :newName WHERE id = :collectionId")
    suspend fun renameCollection(collectionId: Long, newName: String)

    @Query("SELECT * FROM collection_bookmarks WHERE itemKey = :itemKey LIMIT 1")
    suspend fun getBookmarkByItemKey(itemKey: String): CollectionBookmark?

    @Query("SELECT * FROM collection_photos WHERE imageKey = :imageKey LIMIT 1")
    suspend fun getCollectionPhotoByKey(imageKey: String): CollectionPhoto?

    // Search History Operations
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSearchQuery(searchHistory: SearchHistory)

    @Query("SELECT * FROM search_history WHERE nickname = :nickname ORDER BY timestamp DESC LIMIT 20")
    fun getSearchHistory(nickname: String): Flow<List<SearchHistory>>

    @Query("DELETE FROM search_history WHERE `query` = :query AND nickname = :nickname")
    suspend fun deleteSearchQuery(query: String, nickname: String)

    @Query("DELETE FROM search_history WHERE nickname = :nickname")
    suspend fun clearSearchHistory(nickname: String)

    @Query("DELETE FROM search_history")
    suspend fun clearAllSearchHistory()

    // Search Results Cache Operations
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSearchResults(results: List<SearchResult>)

    @Query("SELECT * FROM search_results WHERE searchQuery = :query AND searchScope = :scope AND itemType = :type ORDER BY sortIndex ASC")
    suspend fun getSearchResults(query: String, scope: String, type: String): List<SearchResult>

    @Query("DELETE FROM search_results WHERE searchQuery = :query AND searchScope = :scope AND itemType = :type")
    suspend fun deleteSearchResultsForQueryAndType(query: String, scope: String, type: String)
    @Query("SELECT * FROM search_results WHERE searchQuery = :query AND searchScope = :scope AND itemType = :type ORDER BY date DESC")
    fun getPagedSearchResultsDesc(query: String, scope: String, type: String): androidx.paging.PagingSource<Int, SearchResult>

    @Query("SELECT * FROM search_results WHERE searchQuery = :query AND searchScope = :scope AND itemType = :type ORDER BY date ASC")
    fun getPagedSearchResultsAsc(query: String, scope: String, type: String): androidx.paging.PagingSource<Int, SearchResult>

    // Viewed Gallery Updates Operations
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertViewedUpdates(updates: List<ViewedGalleryUpdate>)

    @Query("DELETE FROM viewed_gallery_updates")
    suspend fun clearViewedUpdates()

    @Query("""
        WITH RECURSIVE active_nodes(nodeId, parentNodeId) AS (
            -- Base case: only galleries (Albums) are "updated" on their own; folders light up
            -- only by bubbling from a gallery inside them. A folder's own DateModified is NOT a
            -- usable signal: it bumps on content changes AND on site-wide SmugMug events (real
            -- data, 2026-08-24: nearly every node on the site got the same stamp at once), so
            -- seeding from folders would dot every folder simultaneously. See
            -- CollectionDaoTest.folderBulkBump_withNoRecentGallery_isNotActive.
            SELECT n.nodeId, n.parentNodeId
            FROM cached_nodes n
            LEFT JOIN viewed_gallery_updates v ON n.nodeId = v.nodeId
            WHERE n.type = 'Album'
              AND n.dateModified IS NOT NULL
              AND datetime(n.dateModified) >= datetime('now', '-30 days')
              AND (v.lastViewedDateModified IS NULL OR n.dateModified > v.lastViewedDateModified)

            UNION

            -- Bubble the indicator up to ancestor folders.
            SELECT p.nodeId, p.parentNodeId
            FROM cached_nodes p
            JOIN active_nodes a ON p.nodeId = a.parentNodeId
        )
        SELECT DISTINCT nodeId FROM active_nodes
    """)
    fun getNodesWithActiveUpdates(): Flow<List<String>>
}
