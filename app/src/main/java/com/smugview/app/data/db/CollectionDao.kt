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

    /**
     * R-07: a parent's listing REPLACES its cached children in one transaction (REPLACE alone kept a
     * child that vanished server-side forever). Other parents' rows and `virtual:` rows are untouched.
     */
    @androidx.room.Transaction
    suspend fun replaceChildren(parentNodeId: String, rows: List<CachedNode>) {
        deleteRealChildren(parentNodeId)
        insertNodes(rows)
    }

    @Query("DELETE FROM cached_nodes WHERE parentNodeId = :parentNodeId AND nodeId NOT LIKE 'virtual:%'")
    suspend fun deleteRealChildren(parentNodeId: String)

    // --- Persisted flat album index (see CachedAlbum) ---
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAlbums(albums: List<CachedAlbum>)

    @Query("SELECT * FROM cached_albums WHERE nickname = :nickname OR nickname = '' ORDER BY sortIndex ASC")
    suspend fun getAlbumIndex(nickname: String): List<CachedAlbum>

    /**
     * The crawl's single write (design 3.3): upserts and prunes in one transaction, so a failure
     * leaves the index as it was. Keys are deleted in chunks (SQLite's variable limit).
     */
    @androidx.room.Transaction
    suspend fun applyCrawl(upserts: List<CachedAlbum>, pruneKeys: List<String>) {
        if (upserts.isNotEmpty()) upsertAlbums(upserts)
        pruneKeys.chunked(500).forEach { deleteAlbumsByKeys(it) }
    }

    @Query("UPDATE cached_albums SET parentNodeId = :parentNodeId WHERE albumKey = :albumKey")
    suspend fun updateAlbumParent(albumKey: String, parentNodeId: String)

    /** The resolver's write (design 3.4): album key to its parent folder NodeID, in one transaction. */
    @androidx.room.Transaction
    suspend fun applyAlbumParents(parents: Map<String, String>) {
        for ((key, parent) in parents) updateAlbumParent(key, parent)
    }

    @Query("DELETE FROM cached_albums WHERE albumKey IN (:albumKeys)")
    suspend fun deleteAlbumsByKeys(albumKeys: List<String>)

    @Query("SELECT COUNT(*) FROM cached_albums WHERE nickname = :nickname OR nickname = ''")
    suspend fun getAlbumIndexCount(nickname: String): Int

    /**
     * Home's totals (design 3.6, Q3): every gallery the index holds for the site and the photos in them.
     * A Flow, so they follow each crawl. Zero galleries means the index is not built yet (first launch).
     */
    @Query("SELECT COUNT(*) AS galleries, COALESCE(SUM(imageCount), 0) AS photos FROM cached_albums WHERE nickname = :nickname OR nickname = ''")
    fun siteTotals(nickname: String): Flow<SiteTotals>

    /**
     * The gallery whose UrlPath is [path] (leading and trailing slashes ignored; [path] is already
     * lower-cased the way SQLite's LOWER does it, A-Z only), for the site. A photo's WebUri has no key.
     */
    @Query("SELECT * FROM cached_albums WHERE (nickname = :nickname OR nickname = '') AND LOWER(TRIM(urlPath, '/')) = :path LIMIT 1")
    suspend fun getAlbumByUrlPath(nickname: String, path: String): CachedAlbum?

    @Query("SELECT * FROM cached_nodes WHERE parentNodeId = :parentNodeId AND nodeId NOT LIKE 'virtual:%' ORDER BY sortIndex ASC")
    fun getCachedNodesByParent(parentNodeId: String?): Flow<List<CachedNode>>

    @Query("SELECT * FROM cached_nodes WHERE nodeId = :nodeId LIMIT 1")
    suspend fun getNodeById(nodeId: String): CachedNode?

    @Query("SELECT nodeId FROM cached_albums WHERE albumKey = :albumKey LIMIT 1")
    suspend fun getAlbumNodeIdByKey(albumKey: String): String?

    @Query("SELECT * FROM cached_nodes WHERE nodeId = :idOrKey OR albumUri LIKE '%' || :idOrKey LIMIT 1")
    suspend fun getNodeByIdOrKey(idOrKey: String): CachedNode?

    @Query("UPDATE cached_nodes SET childCount = :count WHERE nodeId = :nodeId")
    suspend fun updateChildCount(nodeId: String, count: Int)

    @Query("DELETE FROM cached_nodes")
    suspend fun clearAllCachedNodes()

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
    suspend fun deleteCollectionRow(collectionId: Long)

    /**
     * Deletes a collection and, in the same transaction, every offline reference it held (R-41): the kept galleries
     * and their items are deleted explicitly (not left to the foreign-key CASCADE alone), next to the CASCADE of
     * `collection_photos` and `collection_bookmarks`. No file is touched here: the files nothing refers to any more
     * are collected by the next offline pass, which the caller kicks. No network.
     */
    @androidx.room.Transaction
    suspend fun deleteCollection(collectionId: Long) {
        deleteOfflineGalleryItemsOfCollection(collectionId)
        deleteOfflineGalleriesOfCollection(collectionId)
        deleteCollectionRow(collectionId)
    }

    @Query("DELETE FROM offline_gallery_items WHERE collectionId = :collectionId")
    suspend fun deleteOfflineGalleryItemsOfCollection(collectionId: Long)

    @Query("DELETE FROM offline_galleries WHERE collectionId = :collectionId")
    suspend fun deleteOfflineGalleriesOfCollection(collectionId: Long)

    // Collection Photos Operations
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addPhotoToCollection(photo: CollectionPhoto)

    @Query("SELECT * FROM collection_photos WHERE collectionId = :collectionId ORDER BY dateTaken DESC")
    fun getPhotosInCollection(collectionId: Long): Flow<List<CollectionPhoto>>

    @Query("SELECT * FROM collection_photos WHERE imageKey = :imageKey AND collectionId = :collectionId LIMIT 1")
    suspend fun getCollectionPhoto(imageKey: String, collectionId: Long): CollectionPhoto?

    @Query("DELETE FROM collection_photos WHERE imageKey = :imageKey AND collectionId = :collectionId")
    suspend fun removePhotoFromCollection(imageKey: String, collectionId: Long)

    // Collection Bookmarks Operations
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addBookmark(bookmark: CollectionBookmark): Long

    @Query("SELECT * FROM collection_bookmarks WHERE collectionId = :collectionId")
    fun getBookmarksForCollection(collectionId: Long): Flow<List<CollectionBookmark>>

    @Query("DELETE FROM collection_bookmarks WHERE collectionId = :collectionId AND type = :type AND itemKey = :itemKey")
    suspend fun deleteBookmarkRow(collectionId: Long, type: String, itemKey: String)

    @Query("DELETE FROM collection_bookmarks WHERE itemKey = :itemKey")
    suspend fun deleteBookmarkRowsByKey(itemKey: String)

    @Query("DELETE FROM offline_gallery_items WHERE collectionId = :collectionId AND albumKey = :albumKey")
    suspend fun deleteOfflineGalleryItems(collectionId: Long, albumKey: String)

    @Query("DELETE FROM offline_galleries WHERE collectionId = :collectionId AND albumKey = :albumKey")
    suspend fun deleteOfflineGalleryRow(collectionId: Long, albumKey: String)

    @Query("DELETE FROM offline_gallery_items WHERE albumKey = :albumKey")
    suspend fun deleteOfflineGalleryItemsByKey(albumKey: String)

    @Query("DELETE FROM offline_galleries WHERE albumKey = :albumKey")
    suspend fun deleteOfflineGalleryRowsByKey(albumKey: String)

    /**
     * Unbookmarks, and for a gallery also stops keeping it offline, in ONE transaction and with no network (R-41):
     * the `offline_galleries` row and its items go with the bookmark, so what the gallery alone referenced becomes
     * garbage for the next pass. An Image bookmark is itself a reference (its row), so deleting it is enough. A file
     * another collection, a saved photo or another gallery still refers to stays (N5).
     */
    @androidx.room.Transaction
    suspend fun removeBookmark(collectionId: Long, type: String, itemKey: String) {
        deleteBookmarkRow(collectionId, type, itemKey)
        if (type == "Album") {
            deleteOfflineGalleryItems(collectionId, itemKey)
            deleteOfflineGalleryRow(collectionId, itemKey)
        }
    }

    /** [removeBookmark] for every collection (a gallery that is gone from SmugMug: `handleAlbumLoadError`). */
    @androidx.room.Transaction
    suspend fun removeBookmarkGlobally(itemKey: String) {
        deleteBookmarkRowsByKey(itemKey)
        deleteOfflineGalleryItemsByKey(itemKey)
        deleteOfflineGalleryRowsByKey(itemKey)
    }

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

    /**
     * The dot (design 3.5): the ONE place that owns "is something new here?". Seeds come only from the
     * gallery index (`cached_albums`, so no `cached_nodes` row is needed for the gallery itself):
     * ImagesLastUpdated within 30 days and later than the viewed mark. A folder's DateModified is
     * never read (findings #2, #17). The seed's parent is the index's `parentNodeId`, else the cached
     * node's. The chain then bubbles up through `cached_nodes` parents, and the final parent id (the
     * site root, which has no row of its own) is included too. Results are NodeIDs, scoped to one
     * site so another site's gallery lights nothing here.
     */
    @Query("""
        WITH RECURSIVE active(nodeId, parentNodeId, d) AS (
            SELECT a.nodeId, COALESCE(a.parentNodeId, n.parentNodeId), 0
            FROM cached_albums a
            LEFT JOIN cached_nodes n ON n.nodeId = a.nodeId
            LEFT JOIN viewed_gallery_updates v ON v.nodeId = a.nodeId
            WHERE (a.nickname = :nickname OR a.nickname = '')
              AND a.imagesLastUpdated IS NOT NULL
              AND datetime(a.imagesLastUpdated) >= datetime('now', '-30 days')
              AND (v.lastViewedDateModified IS NULL
                   OR datetime(a.imagesLastUpdated) > datetime(v.lastViewedDateModified))

            UNION

            SELECT p.nodeId, p.parentNodeId, a.d + 1
            FROM cached_nodes p JOIN active a ON p.nodeId = a.parentNodeId
            WHERE a.d < 32
        )
        SELECT nodeId FROM active
        UNION
        SELECT parentNodeId FROM active WHERE parentNodeId IS NOT NULL AND parentNodeId <> 'root'
    """)
    fun getNodesWithActiveUpdates(nickname: String): Flow<List<String>>

    @Query("SELECT * FROM cached_albums WHERE nodeId = :nodeId LIMIT 1")
    suspend fun getAlbumByNodeId(nodeId: String): CachedAlbum?

    @Query("SELECT * FROM cached_albums WHERE albumKey = :albumKey LIMIT 1")
    suspend fun getAlbumByKey(albumKey: String): CachedAlbum?

    /** Moves an index row's ImagesLastUpdated forward only (a fresh read beats the crawl, never the reverse). */
    @Query("""
        UPDATE cached_albums SET imagesLastUpdated = :ilu
        WHERE albumKey = :albumKey
          AND (imagesLastUpdated IS NULL OR datetime(:ilu) > datetime(imagesLastUpdated))
    """)
    suspend fun raiseAlbumImagesLastUpdated(albumKey: String, ilu: String)

    /**
     * Every index gallery at or below [nodeId] with a known ImagesLastUpdated. The walk goes down over
     * both edges (cached folder children, and index galleries by their parent), because a gallery may
     * have no `cached_nodes` row. One recursive term, over a non-recursive `edges` CTE (older SQLite).
     */
    @Query("""
        WITH RECURSIVE edges(parentId, childId) AS (
            SELECT parentNodeId, nodeId FROM cached_nodes WHERE parentNodeId IS NOT NULL
            UNION
            SELECT parentNodeId, nodeId FROM cached_albums WHERE parentNodeId IS NOT NULL
        ),
        below(id, d) AS (
            SELECT :nodeId, 0
            UNION
            SELECT e.childId, b.d + 1 FROM edges e JOIN below b ON e.parentId = b.id WHERE b.d < 32
        )
        SELECT DISTINCT a.nodeId AS nodeId, a.imagesLastUpdated AS imagesLastUpdated
        FROM cached_albums a JOIN below b ON a.nodeId = b.id
        WHERE a.imagesLastUpdated IS NOT NULL
    """)
    suspend fun getGalleryIlusAtOrBelow(nodeId: String): List<GalleryIlu>

    /** "Viewed" for a gallery, or for every gallery below a folder: viewed = the index ImagesLastUpdated. */
    @androidx.room.Transaction
    suspend fun markViewedAtOrBelow(nodeId: String): Int {
        val rows = getGalleryIlusAtOrBelow(nodeId)
        if (rows.isNotEmpty()) insertViewedUpdates(rows.map { ViewedGalleryUpdate(it.nodeId, it.imagesLastUpdated) })
        return rows.size
    }
}
