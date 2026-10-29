package com.smugview.shared.db

import androidx.room.ConstructedBy
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import kotlinx.coroutines.flow.Flow

/** The clock for entity defaults: `System.currentTimeMillis()` on the JVM, `NSDate` on iOS. */
expect fun nowMillis(): Long

/**
 * iOS spike: the app's database (schema 18) as Room KMP. The entities are a faithful copy of the app's, so the exported schema must carry
 * the same identity hash as `app/schemas/com.smugview.app.data.db.AppDatabase/18.json` (checked by SchemaParityTest). Only the DAO slice the
 * spike tests needs is ported; the rest of `CollectionDao` follows with Stage 1e.
 */
@Database(
    entities = [
        CachedNode::class,
        OfflineCollection::class,
        CollectionPhoto::class,
        CollectionBookmark::class,
        SearchHistory::class,
        SearchResult::class,
        ViewedGalleryUpdate::class,
        CachedAlbum::class,
        OfflineFile::class,
        OfflineGallery::class,
        OfflineGalleryItem::class,
        NotifySite::class,
        NotifyKnownAlbum::class
    ],
    version = 18,
    exportSchema = true
)
@ConstructedBy(AppDatabaseConstructor::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun nodeDao(): NodeDao
}

// Room generates the actual of this on every platform.
@Suppress("KotlinNoActualForExpect")
expect object AppDatabaseConstructor : RoomDatabaseConstructor<AppDatabase> {
    override fun initialize(): AppDatabase
}

@Dao
interface NodeDao {
    /** REPLACE, not append (findings #8): the same NodeID twice leaves one row. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNodes(nodes: List<CachedNode>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAlbums(albums: List<CachedAlbum>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertViewedUpdates(updates: List<ViewedGalleryUpdate>)

    @Query("SELECT * FROM cached_nodes WHERE nodeId = :nodeId LIMIT 1")
    suspend fun getNodeById(nodeId: String): CachedNode?

    @Query("SELECT COUNT(*) FROM cached_nodes")
    suspend fun nodeCount(): Int

    /**
     * The dot (design 3.5): copied verbatim from `CollectionDao.getNodesWithActiveUpdates`, recursive CTE and SQLite date functions
     * included; it must return the same NodeIDs on the iOS SQLite as on Android's.
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
}
