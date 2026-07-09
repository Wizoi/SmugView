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

    @Query("SELECT * FROM cached_nodes WHERE parentNodeId = :parentNodeId AND nodeId NOT LIKE 'virtual:%' ORDER BY sortIndex ASC")
    fun getCachedNodesByParent(parentNodeId: String?): Flow<List<CachedNode>>

    @Query("SELECT * FROM cached_nodes WHERE nodeId = :nodeId LIMIT 1")
    suspend fun getNodeById(nodeId: String): CachedNode?

    @Query("UPDATE cached_nodes SET childCount = :count WHERE nodeId = :nodeId")
    suspend fun updateChildCount(nodeId: String, count: Int)

    @Query("DELETE FROM cached_nodes")
    suspend fun clearAllCachedNodes()

    @Query("""
        WITH RECURSIVE descendants(nodeId) AS (
            SELECT :scopeNodeId
            UNION ALL
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
            UNION ALL
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
    """)
    suspend fun searchNodesGlobal(query: String, type: String): List<CachedNode>

    @Query("""
        WITH RECURSIVE descendants(nodeId) AS (
            SELECT :nodeId
            UNION ALL
            SELECT n.nodeId FROM cached_nodes n
            JOIN descendants d ON n.parentNodeId = d.nodeId
        )
        SELECT * FROM cached_nodes
        WHERE nodeId IN descendants AND nodeId != :nodeId
    """)
    suspend fun getAllDescendants(nodeId: String): List<CachedNode>

    @Query("SELECT * FROM cached_nodes")
    suspend fun getAllCachedNodes(): List<CachedNode>

    @Query("SELECT * FROM cached_nodes WHERE albumUri IN (:albumUris)")
    suspend fun getNodesByAlbumUris(albumUris: List<String>): List<CachedNode>

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

    @Query("SELECT * FROM search_history ORDER BY timestamp DESC LIMIT 20")
    fun getSearchHistory(): Flow<List<SearchHistory>>

    @Query("DELETE FROM search_history WHERE `query` = :query")
    suspend fun deleteSearchQuery(query: String)

    @Query("DELETE FROM search_history")
    suspend fun clearSearchHistory()

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
}
