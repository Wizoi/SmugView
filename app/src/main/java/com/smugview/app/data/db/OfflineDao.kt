package com.smugview.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * The offline tables (phase 5, step 5-2). Only what the migration step needs: a way to put rows in and read them
 * back, so the schema is proven with a real DAO. The readers and writers of the later steps (5-3 on) are added
 * with the code that uses them.
 */
@Dao
interface OfflineDao {
    /** `OnConflictStrategy.IGNORE`: an existing row for the key wins, like the migration's `INSERT OR IGNORE`. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertFile(file: OfflineFile): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertGallery(gallery: OfflineGallery)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertGalleryItems(items: List<OfflineGalleryItem>)

    @Query("SELECT * FROM offline_files WHERE fileKey = :fileKey")
    suspend fun getFile(fileKey: String): OfflineFile?

    @Query("SELECT * FROM offline_files ORDER BY fileKey")
    suspend fun allFiles(): List<OfflineFile>

    @Query("SELECT * FROM offline_galleries ORDER BY collectionId, albumKey")
    suspend fun allGalleries(): List<OfflineGallery>

    @Query("SELECT * FROM offline_gallery_items WHERE collectionId = :collectionId AND albumKey = :albumKey ORDER BY sortIndex")
    suspend fun itemsOf(collectionId: Long, albumKey: String): List<OfflineGalleryItem>
}
