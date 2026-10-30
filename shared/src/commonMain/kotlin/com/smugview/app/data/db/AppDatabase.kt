package com.smugview.app.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.ConstructedBy
import androidx.room.RoomDatabaseConstructor

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
    // Schemas are exported to app/schemas (see the KSP room.schemaLocation arg in
    // build.gradle.kts) so migrations can be validated with MigrationTestHelper.
    exportSchema = true
)
@ConstructedBy(AppDatabaseConstructor::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun collectionDao(): CollectionDao
    abstract fun doctorDao(): DoctorDao
    abstract fun offlineDao(): OfflineDao
    abstract fun notifyDao(): NotifyDao

    companion object {
        // The migrations live in [Migrations] (SQLiteConnection based, so they run on every platform); these keep the old names.
        val MIGRATION_12_13 = Migrations.MIGRATION_12_13
        val MIGRATION_13_14 = Migrations.MIGRATION_13_14
        val MIGRATION_14_15 = Migrations.MIGRATION_14_15
        val MIGRATION_15_16 = Migrations.MIGRATION_15_16
        val MIGRATION_16_17 = Migrations.MIGRATION_16_17
        val MIGRATION_17_18 = Migrations.MIGRATION_17_18
    }
}

// Room generates the actual of this on every platform.
@Suppress("KotlinNoActualForExpect")
expect object AppDatabaseConstructor : RoomDatabaseConstructor<AppDatabase> {
    override fun initialize(): AppDatabase
}
