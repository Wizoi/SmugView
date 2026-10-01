package com.smugview.app.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        CachedNode::class,
        OfflineCollection::class,
        CollectionPhoto::class,
        CollectionBookmark::class,
        SearchHistory::class,
        SearchResult::class,
        ViewedGalleryUpdate::class,
        CachedAlbum::class
    ],
    version = 16,
    // Schemas are exported to app/schemas (see the KSP room.schemaLocation arg in
    // build.gradle.kts) so migrations can be validated with MigrationTestHelper.
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun collectionDao(): CollectionDao
    abstract fun doctorDao(): DoctorDao

    companion object {
        /**
         * v12 -> v13: Site-scoping for search history and cached nodes.
         *
         * What changes:
         *   - cached_nodes: ADD COLUMN nickname (additive, no data loss)
         *   - search_history: RECREATED with composite PK (query, nickname)
         *     Old unscoped history is discarded (non-critical, had no site context).
         *   - search_results: CLEARED (stale cross-site cache, repopulated on next search)
         *
         * What is PRESERVED (untouched):
         *   - offline_collections, collection_photos, collection_bookmarks (user personal data)
         *   - viewed_gallery_updates
         */
        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // 1. Add nickname column to cached_nodes (safe ALTER TABLE, no PK change needed)
                database.execSQL(
                    "ALTER TABLE cached_nodes ADD COLUMN nickname TEXT NOT NULL DEFAULT ''"
                )

                // Create index on nickname column as expected by Room
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_cached_nodes_nickname` ON `cached_nodes` (`nickname`)"
                )

                // 2. Recreate search_history with composite PK (query + nickname)
                //    SQLite cannot ALTER a primary key — must DROP and recreate.
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS search_history_new (
                        `query` TEXT NOT NULL,
                        `nickname` TEXT NOT NULL DEFAULT '',
                        `timestamp` INTEGER NOT NULL,
                        PRIMARY KEY (`query`, `nickname`)
                    )
                """.trimIndent())
                database.execSQL("DROP TABLE IF EXISTS search_history")
                database.execSQL("ALTER TABLE search_history_new RENAME TO search_history")

                // 3. Clear search_results — stale cross-site cache, repopulated on next search
                database.execSQL("DELETE FROM search_results")

                // offline_collections, collection_photos, collection_bookmarks,
                // viewed_gallery_updates are NOT touched — they contain user personal data.
            }
        }

        /**
         * v13 -> v14: Persisted flat album index (cached_albums) so the full gallery list is
         * synced incrementally instead of re-crawled from the API on every launch.
         */
        val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS cached_albums (
                        `albumKey` TEXT NOT NULL,
                        `nodeId` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `securityType` TEXT,
                        `passwordHint` TEXT,
                        `uri` TEXT NOT NULL,
                        `webUri` TEXT,
                        `urlPath` TEXT,
                        `imageCount` INTEGER,
                        `dateModified` TEXT,
                        `galleryStyle` TEXT,
                        `highlightImageUrl` TEXT,
                        `sortIndex` INTEGER NOT NULL DEFAULT 0,
                        `nickname` TEXT NOT NULL DEFAULT '',
                        PRIMARY KEY(`albumKey`)
                    )
                """.trimIndent())
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_cached_albums_nickname` ON `cached_albums` (`nickname`)"
                )
            }
        }

        /**
         * v14 -> v15: cached_albums gains parentNodeId (the containing folder's nodeId).
         *
         * Lets the incremental gallery sync in [com.smugview.app.data.repository.SmugMugRepository
         * .buildInMemoryGalleryCache] invalidate a specific parent folder's cached_nodes listing
         * when one of its galleries is new or its LastUpdated changes, instead of the Folders tab
         * showing stale content until a manual refresh.
         */
        val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE cached_albums ADD COLUMN parentNodeId TEXT")
            }
        }

        /**
         * v15 -> v16 (Phase 2, design section 4): adds the gallery index's `imagesLastUpdated`, deletes the
         * `cached_nodes` rows the old ParentNode parsers wrote wrongly (a row that is its own parent, or
         * whose parent is an `X!parent` link; R-01, R-04), and clears the index rows' guessed
         * `parentNodeId` (`user!albums` has no parent, R-05). User data (collections, bookmarks, viewed
         * rows, search history, saved passwords) is untouched. Good rows stay; listings repair the rest.
         */
        val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE cached_albums ADD COLUMN imagesLastUpdated TEXT")
                db.execSQL("DELETE FROM cached_nodes WHERE parentNodeId = nodeId OR instr(parentNodeId, '!') > 0")
                db.execSQL("UPDATE cached_albums SET parentNodeId = NULL")
            }
        }
    }
}
