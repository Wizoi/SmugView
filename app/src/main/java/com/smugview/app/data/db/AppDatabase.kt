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
        CachedAlbum::class,
        OfflineFile::class,
        OfflineGallery::class,
        OfflineGalleryItem::class
    ],
    version = 17,
    // Schemas are exported to app/schemas (see the KSP room.schemaLocation arg in
    // build.gradle.kts) so migrations can be validated with MigrationTestHelper.
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun collectionDao(): CollectionDao
    abstract fun doctorDao(): DoctorDao
    abstract fun offlineDao(): OfflineDao

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

        /**
         * v16 -> v17 (phase 5, design 2.2 and 6.1): the offline tables. Purely additive.
         *
         * What changes: three new tables (`offline_files`, `offline_galleries`, `offline_gallery_items`) and their
         * indexes, backfilled from today's rows: one file row per image key held in `collection_photos` (whatever the
         * number of collections) or as an Image bookmark, and one gallery row per Album bookmark (state `LEGACY`,
         * `wifiOnly = 1` by the owner's sign-off, design 8.1).
         *
         * What is NOT touched: every existing table, row and column. `collection_photos.localFilePath` and
         * `isDownloaded` stay and are not read here: R-40 made them wrong, so whether a file exists is decided by
         * looking at the file (step 5-8), never by that flag. No file is written or deleted. Every backfilled file
         * starts `PENDING` with `legacyPath = 'offline_photos/{key}.jpg'`.
         */
        val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `offline_files` (`fileKey` TEXT NOT NULL, `imageKey` TEXT NOT NULL, " +
                        "`albumKey` TEXT, `nickname` TEXT NOT NULL DEFAULT '', `sourceUrl` TEXT, `expectedBytes` INTEGER, " +
                        "`md5` TEXT, `title` TEXT, `thumbnailUrl` TEXT, `format` TEXT, `dateTaken` TEXT, " +
                        "`state` TEXT NOT NULL, `failure` TEXT, `retryable` INTEGER NOT NULL DEFAULT 0, " +
                        "`attempts` INTEGER NOT NULL DEFAULT 0, `nextAttemptAt` INTEGER, `httpCode` INTEGER, " +
                        "`wifiOnly` INTEGER NOT NULL DEFAULT 0, `claim` TEXT, `relPath` TEXT, `bytes` INTEGER, " +
                        "`legacyPath` TEXT, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`fileKey`))"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_offline_files_imageKey` ON `offline_files` (`imageKey`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_offline_files_state` ON `offline_files` (`state`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `offline_galleries` (`collectionId` INTEGER NOT NULL, `albumKey` TEXT NOT NULL, " +
                        "`nickname` TEXT NOT NULL DEFAULT '', `title` TEXT, `state` TEXT NOT NULL, `failure` TEXT, " +
                        "`retryable` INTEGER NOT NULL DEFAULT 0, `listedAt` INTEGER, `listedIlu` TEXT, `photoCount` INTEGER, " +
                        "`wifiOnly` INTEGER NOT NULL DEFAULT 1, PRIMARY KEY(`collectionId`, `albumKey`), " +
                        "FOREIGN KEY(`collectionId`) REFERENCES `offline_collections`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_offline_galleries_collectionId` ON `offline_galleries` (`collectionId`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `offline_gallery_items` (`collectionId` INTEGER NOT NULL, `albumKey` TEXT NOT NULL, " +
                        "`imageKey` TEXT NOT NULL, `sortIndex` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`collectionId`, `albumKey`, `imageKey`), " +
                        "FOREIGN KEY(`collectionId`, `albumKey`) REFERENCES `offline_galleries`(`collectionId`, `albumKey`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE )"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_offline_gallery_items_imageKey` ON `offline_gallery_items` (`imageKey`)")

                val now = System.currentTimeMillis()
                // Photos of the Favorites path: one file row per key, whatever the number of collections.
                db.execSQL(
                    "INSERT OR IGNORE INTO offline_files (fileKey, imageKey, albumKey, nickname, sourceUrl, title, thumbnailUrl, " +
                        "dateTaken, state, retryable, attempts, wifiOnly, legacyPath, createdAt, updatedAt) " +
                        "SELECT cp.imageKey || '/orig', cp.imageKey, MAX(cp.albumKey), MAX(c.siteNickname), " +
                        "MAX(NULLIF(cp.archivedUri, '')), MAX(cp.title), MAX(cp.thumbnailUrl), MAX(cp.dateTaken), " +
                        "'PENDING', 0, 0, 0, 'offline_photos/' || cp.imageKey || '.jpg', $now, $now " +
                        "FROM collection_photos cp JOIN offline_collections c ON c.id = cp.collectionId GROUP BY cp.imageKey"
                )
                // Image bookmarks (source unknown: resolved later with image/{key}-0). A key already above wins.
                db.execSQL(
                    "INSERT OR IGNORE INTO offline_files (fileKey, imageKey, albumKey, nickname, sourceUrl, title, thumbnailUrl, " +
                        "dateTaken, state, retryable, attempts, wifiOnly, legacyPath, createdAt, updatedAt) " +
                        "SELECT b.itemKey || '/orig', b.itemKey, MAX(b.albumKey), MAX(c.siteNickname), NULL, MAX(b.title), " +
                        "MAX(b.thumbnailUrl), NULL, 'PENDING', 0, 0, 0, 'offline_photos/' || b.itemKey || '.jpg', $now, $now " +
                        "FROM collection_bookmarks b JOIN offline_collections c ON c.id = b.collectionId " +
                        "WHERE b.type = 'Image' GROUP BY b.itemKey"
                )
                // Gallery bookmarks: resolved once in Kotlin by step 5-8 (the "finished" flag is in SharedPreferences).
                db.execSQL(
                    "INSERT OR IGNORE INTO offline_galleries (collectionId, albumKey, nickname, title, state, retryable, wifiOnly) " +
                        "SELECT b.collectionId, b.itemKey, c.siteNickname, b.title, 'LEGACY', 0, 1 " +
                        "FROM collection_bookmarks b JOIN offline_collections c ON c.id = b.collectionId WHERE b.type = 'Album'"
                )
            }
        }
    }
}
