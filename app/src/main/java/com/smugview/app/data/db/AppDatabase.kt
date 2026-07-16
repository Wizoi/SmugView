package com.smugview.app.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        CachedNode::class,
        OfflineCollection::class,
        CollectionPhoto::class,
        CollectionBookmark::class,
        SearchHistory::class,
        SearchResult::class,
        ViewedGalleryUpdate::class
    ],
    version = 12,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun collectionDao(): CollectionDao
}
