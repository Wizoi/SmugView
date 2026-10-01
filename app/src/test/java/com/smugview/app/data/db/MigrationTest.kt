package com.smugview.app.data.db

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import com.smugview.app.data.offline.OfflineFixture
import com.smugview.app.data.repository.FakeOriginals
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Room migration tests on the exported schemas in app/schemas, run under Robolectric. The schemas
 * are wired as debug-variant assets in build.gradle.kts (Robolectric reads the debug variant's
 * merged assets, not the "test" source set). Schema 12 is not exported, so 12->13 cannot be
 * validated here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java
    )

    @Test
    fun migrate14To15() {
        helper.createDatabase(TEST_DB, 14).apply {
            // Real shape: AlbumKey (FfHCms) != NodeID (LCdk7F), as in the live API.
            execSQL(
                "INSERT INTO cached_albums (albumKey, nodeId, name, securityType, uri, urlPath, " +
                    "dateModified, sortIndex, nickname) VALUES " +
                    "('FfHCms', 'LCdk7F', 'Class photos', 'None', '/api/v2/album/FfHCms', " +
                    "'/Family/School/Class-photos', '2026-09-28T12:00:00+00:00', 3, 'someuser')"
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 15, true, AppDatabase.MIGRATION_14_15)

        db.query("SELECT albumKey, nodeId, name, nickname, parentNodeId FROM cached_albums").use { c ->
            assertEquals(1, c.count)
            c.moveToFirst()
            assertEquals("FfHCms", c.getString(0))
            assertEquals("LCdk7F", c.getString(1))
            assertEquals("Class photos", c.getString(2))
            assertEquals("someuser", c.getString(3))
            assertNull(c.getString(4)) // new column defaults to NULL
        }
    }

    @Test
    fun migrate13To14_createsAlbumIndex_andKeepsUserData() {
        helper.createDatabase(TEST_DB, 13).apply {
            insertV13UserData()
            close()
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 14, true, AppDatabase.MIGRATION_13_14)

        assertV13UserDataSurvived(db)
        db.query("SELECT COUNT(*) FROM cached_albums").use { c ->
            c.moveToFirst()
            assertEquals(0, c.getInt(0)) // new table, filled by the next gallery sync
        }
    }

    @Test
    fun migrate13To15_keepsUserData() {
        helper.createDatabase(TEST_DB, 13).apply {
            insertV13UserData()
            close()
        }

        val db = helper.runMigrationsAndValidate(
            TEST_DB, 15, true, AppDatabase.MIGRATION_13_14, AppDatabase.MIGRATION_14_15
        )

        assertV13UserDataSurvived(db)
        // The new table and column are usable after the chained migration.
        db.execSQL(
            "INSERT INTO cached_albums (albumKey, nodeId, name, uri, sortIndex, nickname, parentNodeId) " +
                "VALUES ('FfHCms', 'LCdk7F', 'Class photos', '/api/v2/album/FfHCms', 0, 'someuser', 'P4BKB')"
        )
        db.query("SELECT parentNodeId FROM cached_albums WHERE albumKey = 'FfHCms'").use { c ->
            c.moveToFirst()
            assertEquals("P4BKB", c.getString(0))
        }
    }

    /** Opens a v13 file with the real Room builder and AppModule's migration list, then reads via the DAO. */
    @Test
    fun migratedDatabase_opensWithRoom_andDaoReadsOldRows() {
        helper.createDatabase(TEST_DB, 13).apply {
            insertV13UserData()
            close()
        }

        val context = ApplicationProvider.getApplicationContext<Context>()
        val room = Room.databaseBuilder(context, AppDatabase::class.java, TEST_DB)
            .addMigrations(AppDatabase.MIGRATION_12_13, AppDatabase.MIGRATION_13_14, AppDatabase.MIGRATION_14_15, AppDatabase.MIGRATION_15_16, AppDatabase.MIGRATION_16_17)
            .allowMainThreadQueries()
            .build()
        try {
            val dao = room.collectionDao()
            runBlocking {
                val node = dao.getNodeById("2sDN5x")
                assertEquals("someuser", node?.nickname)
                assertEquals("Folder", node?.type)
                assertEquals(0, dao.getAlbumIndexCount("someuser"))
            }
        } finally {
            room.close()
        }
    }

    /** Fixture "F" at v15, with the two rows the old ParentNode parsers wrote wrongly (R-01, R-04). */
    private fun SupportSQLiteDatabase.insertV15TreeAndUserData() {
        val now = Instant.now()
        val modified = now.minus(2, ChronoUnit.DAYS).toString()
        execSQL(
            "INSERT INTO cached_nodes (nodeId, parentNodeId, type, title, access, uri, albumUri, " +
                "sortIndex, dateModified, nickname) VALUES " +
                "('4zqWw', NULL, 'Folder', 'Home', 'None', '/api/v2/node/4zqWw', NULL, 0, '$modified', 'someuser'), " +
                "('2sDN5x', '4zqWw', 'Folder', 'Family', 'Password', '/api/v2/node/2sDN5x', NULL, 0, '$modified', 'someuser'), " +
                "('P4BKB', '2sDN5x', 'Folder', 'School', 'None', '/api/v2/node/P4BKB', NULL, 0, '$modified', 'someuser'), " +
                "('LCdk7F', 'P4BKB', 'Album', 'Class photos', 'None', '/api/v2/node/LCdk7F', '/api/v2/album/FfHCms', 1, '$modified', 'someuser'), " +
                "('kZ9xQp', 'kZ9xQp', 'Album', 'Self parent', 'None', '/api/v2/node/kZ9xQp', '/api/v2/album/Rt5vBn', 0, '$modified', 'someuser'), " +
                "('Wd3Rt2', 'P4BKB!parent', 'Album', 'Bang parent', 'None', '/api/v2/node/Wd3Rt2', '/api/v2/album/Hj8mLs', 0, '$modified', 'someuser')"
        )
        // Index row: AlbumKey FfHCms != NodeID LCdk7F; its parent was guessed from a `!parent` link.
        execSQL(
            "INSERT INTO cached_albums (albumKey, nodeId, name, uri, dateModified, sortIndex, nickname, parentNodeId) " +
                "VALUES ('FfHCms', 'LCdk7F', 'Class photos', '/api/v2/album/FfHCms', '$modified', 3, 'someuser', 'P4BKB!parent')"
        )
        insertUserTables(now, modified)
    }

    private val userTables = listOf(
        "offline_collections", "collection_photos", "collection_bookmarks", "viewed_gallery_updates", "search_history"
    )

    /** Every row of the five user tables as text, so "byte-equal" is a string comparison. */
    private fun SupportSQLiteDatabase.dumpUserTables(): List<String> = userTables.map { t ->
        query("SELECT * FROM $t ORDER BY 1, 2").use { c ->
            buildString {
                append(t).append(':')
                while (c.moveToNext()) {
                    for (i in 0 until c.columnCount) append(c.getString(i)).append('|')
                    append('\n')
                }
            }
        }
    }

    private fun SupportSQLiteDatabase.nodeIds(): List<String> =
        query("SELECT nodeId FROM cached_nodes ORDER BY nodeId").use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }

    @Test
    fun migrate15To16_addsIlu_repairsTree_keepsUserData() {
        val before = helper.createDatabase(TEST_DB, 15).run {
            insertV15TreeAndUserData()
            dumpUserTables().also { close() }
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 16, true, AppDatabase.MIGRATION_15_16)

        // The 4 good nodes stay; the self-parent row and the `X!parent` row are gone.
        assertEquals(listOf("2sDN5x", "4zqWw", "LCdk7F", "P4BKB"), db.nodeIds())
        db.query("SELECT parentNodeId FROM cached_nodes WHERE nodeId = 'LCdk7F'").use { c ->
            c.moveToFirst()
            assertEquals("P4BKB", c.getString(0))
        }
        // The index row survives; its guessed parent is cleared and the new column is NULL.
        db.query("SELECT albumKey, nodeId, name, nickname, parentNodeId, imagesLastUpdated FROM cached_albums").use { c ->
            assertEquals(1, c.count)
            c.moveToFirst()
            assertEquals("FfHCms", c.getString(0))
            assertEquals("LCdk7F", c.getString(1))
            assertEquals("Class photos", c.getString(2))
            assertEquals("someuser", c.getString(3))
            assertNull(c.getString(4))
            assertNull(c.getString(5))
        }
        assertEquals(before, db.dumpUserTables())
    }

    @Test
    fun migrate13To16_keepsUserData() {
        val before = helper.createDatabase(TEST_DB, 13).run {
            insertV13UserData()
            dumpUserTables().also { close() }
        }

        val db = helper.runMigrationsAndValidate(
            TEST_DB, 16, true,
            AppDatabase.MIGRATION_13_14, AppDatabase.MIGRATION_14_15, AppDatabase.MIGRATION_15_16
        )

        assertV13UserDataSurvived(db)
        assertEquals(before, db.dumpUserTables())
        // The v13 tree rows are good ones (no self parent, no `!`), so all three survive.
        assertEquals(listOf("2sDN5x", "LCdk7F", "P4BKB"), db.nodeIds())
    }

    /** A real Room open at v16 over a repaired v15 file: the DAO reads the surviving rows and writes the new column. */
    @Test
    fun migratedV15Database_opensWithRoomAtV16_andDaoWorks() {
        helper.createDatabase(TEST_DB, 15).apply {
            insertV15TreeAndUserData()
            close()
        }
        val context = ApplicationProvider.getApplicationContext<Context>()
        val room = Room.databaseBuilder(context, AppDatabase::class.java, TEST_DB)
            .addMigrations(
                AppDatabase.MIGRATION_12_13, AppDatabase.MIGRATION_13_14,
                AppDatabase.MIGRATION_14_15, AppDatabase.MIGRATION_15_16, AppDatabase.MIGRATION_16_17
            )
            .allowMainThreadQueries()
            .build()
        try {
            val dao = room.collectionDao()
            runBlocking {
                assertEquals("P4BKB", dao.getNodeById("LCdk7F")?.parentNodeId)
                assertNull(dao.getNodeById("kZ9xQp"))
                assertNull(dao.getNodeById("Wd3Rt2"))
                val idx = dao.getAlbumIndex("someuser").single()
                assertNull(idx.parentNodeId)
                assertNull(idx.imagesLastUpdated)
                dao.upsertAlbums(listOf(idx.copy(imagesLastUpdated = "2026-09-28T12:00:00+00:00")))
                assertEquals("2026-09-28T12:00:00+00:00", dao.getAlbumIndex("someuser").single().imagesLastUpdated)
            }
        } finally {
            room.close()
        }
    }

    /**
     * Phase 5 step 5-2: 16 -> 17 creates the three offline tables and backfills them from today's rows. The
     * fixture is the real-shaped one (a photo in two collections, a legacy `''` failure, an Image bookmark and a
     * gallery bookmark whose AlbumKey differs from its NodeID). The five user tables must come through untouched.
     */
    @Test
    fun migrate16To17_backfillsFilesAndGalleries_keepsUserData() {
        val before = helper.createDatabase(TEST_DB, 16).run {
            OfflineFixture.insert(this)
            dumpUserTables().also { close() }
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 17, true, AppDatabase.MIGRATION_16_17)

        assertEquals(before, db.dumpUserTables())

        // One file per image, however many collections or bookmarks name it: fileKey = imageKey/orig.
        val files = db.query(
            "SELECT fileKey, imageKey, albumKey, nickname, state, legacyPath, sourceUrl, wifiOnly, relPath, attempts " +
                "FROM offline_files ORDER BY fileKey"
        ).use { c ->
            buildList { while (c.moveToNext()) add((0 until c.columnCount).map { c.getString(it) }) }
        }
        assertEquals(
            listOf("${OfflineFixture.PENDING}/orig", "${OfflineFixture.SHARED}/orig", "${OfflineFixture.BROKEN}/orig"),
            files.map { it[0] }
        )
        for (f in files) {
            assertEquals("PENDING", f[4])
            assertEquals("a file's wifiOnly is a per-file override, off by default", "0", f[7])
            assertNull("nothing is on disk under the new layout yet", f[8])
            assertEquals("0", f[9])
        }
        val broken = files.single { it[1] == OfflineFixture.BROKEN }
        assertEquals(OfflineFixture.legacyPath(OfflineFixture.BROKEN), broken[5])
        assertEquals(FakeOriginals.archivedUri(OfflineFixture.BROKEN), broken[6])
        assertEquals("idzifamily", broken[3])

        // The Album bookmark becomes a gallery, keyed by the AlbumKey (itemKey), wifiOnly = 1 (owner sign-off 8.1).
        val galleries = db.query(
            "SELECT collectionId, albumKey, nickname, title, state, retryable, wifiOnly, listedAt FROM offline_galleries"
        ).use { c ->
            buildList { while (c.moveToNext()) add((0 until c.columnCount).map { c.getString(it) }) }
        }
        assertEquals(
            listOf(listOf("2", OfflineFixture.GALLERY_ALBUM_KEY, "idzifamily", "Class photos", "LEGACY", "0", "1", null)),
            galleries
        )
        db.query("SELECT COUNT(*) FROM offline_gallery_items").use { c ->
            c.moveToFirst()
            assertEquals("items are filled by the first listing, not by the migration", 0, c.getInt(0))
        }
    }

    /** The whole chain from the oldest exported schema, so a v13 phone that skipped releases still arrives intact. */
    @Test
    fun migrate13To17_keepsUserData() {
        val before = helper.createDatabase(TEST_DB, 13).run {
            insertV13UserData()
            dumpUserTables().also { close() }
        }

        val db = helper.runMigrationsAndValidate(
            TEST_DB, 17, true,
            AppDatabase.MIGRATION_13_14, AppDatabase.MIGRATION_14_15, AppDatabase.MIGRATION_15_16, AppDatabase.MIGRATION_16_17
        )

        assertV13UserDataSurvived(db)
        assertEquals(before, db.dumpUserTables())
        fun count(sql: String): Int = db.query(sql).use { it.moveToFirst(); it.getInt(0) }
        assertEquals(2, count("SELECT COUNT(*) FROM offline_files")) // i-AbCd and i-EfGh
        assertEquals(1, count("SELECT COUNT(*) FROM offline_galleries WHERE collectionId = 7 AND albumKey = 'FfHCms' AND wifiOnly = 1"))
    }

    /** A real Room open at v17 over a v16 file: Room's own validation passes and the new DAO reads the backfill. */
    @Test
    fun migratedV16Database_opensWithRoomAtV17_andDaoWorks() {
        helper.createDatabase(TEST_DB, 16).apply {
            OfflineFixture.insert(this)
            close()
        }
        val context = ApplicationProvider.getApplicationContext<Context>()
        val room = Room.databaseBuilder(context, AppDatabase::class.java, TEST_DB)
            .addMigrations(
                AppDatabase.MIGRATION_12_13, AppDatabase.MIGRATION_13_14,
                AppDatabase.MIGRATION_14_15, AppDatabase.MIGRATION_15_16, AppDatabase.MIGRATION_16_17
            )
            .allowMainThreadQueries()
            .build()
        try {
            val dao = room.offlineDao()
            runBlocking {
                assertEquals(3, dao.allFiles().size)
                val shared = dao.getFile("${OfflineFixture.SHARED}/orig")!!
                assertEquals(OfflineFixture.SHARED, shared.imageKey)
                assertEquals("PENDING", shared.state)
                assertEquals(false, shared.wifiOnly)
                val gallery = dao.allGalleries().single()
                assertEquals(OfflineFixture.GALLERY_ALBUM_KEY, gallery.albumKey)
                assertEquals(true, gallery.wifiOnly)

                // The new tables take writes.
                dao.insertGalleryItems(listOf(OfflineGalleryItem(2, OfflineFixture.GALLERY_ALBUM_KEY, OfflineFixture.SHARED, 0)))
                assertEquals(1, dao.itemsOf(2, OfflineFixture.GALLERY_ALBUM_KEY).size)
            }
            // The legacy columns are untouched and still readable.
            room.openHelper.readableDatabase.query(
                "SELECT COUNT(*) FROM collection_photos WHERE imageKey = '${OfflineFixture.BROKEN}' AND isDownloaded = 1 AND localFilePath = ''"
            ).use { c ->
                c.moveToFirst()
                assertEquals(1, c.getInt(0))
            }
        } finally {
            room.close()
        }
    }

    private fun SupportSQLiteDatabase.insertV13UserData() {
        val now = Instant.now()
        val modified = now.minus(2, ChronoUnit.DAYS).toString()
        // Password folder 2sDN5x -> sub-folder P4BKB -> gallery node LCdk7F (AlbumKey FfHCms).
        execSQL(
            "INSERT INTO cached_nodes (nodeId, parentNodeId, type, title, access, uri, albumUri, " +
                "sortIndex, dateModified, nickname) VALUES " +
                "('2sDN5x', NULL, 'Folder', 'Family', 'Password', '/api/v2/node/2sDN5x', NULL, 0, '$modified', 'someuser'), " +
                "('P4BKB', '2sDN5x', 'Folder', 'School', 'Inherited', '/api/v2/node/P4BKB', NULL, 0, '$modified', 'someuser'), " +
                "('LCdk7F', 'P4BKB', 'Album', 'Class photos', 'Inherited', '/api/v2/node/LCdk7F', " +
                "'/api/v2/album/FfHCms', 1, '$modified', 'someuser')"
        )
        insertUserTables(now, modified)
    }

    /** Rows in all five user tables (never touched by any migration). */
    private fun SupportSQLiteDatabase.insertUserTables(now: Instant, modified: String) {
        execSQL(
            "INSERT INTO offline_collections (id, name, siteNickname, createdAt) " +
                "VALUES (7, 'Trip', 'someuser', ${now.toEpochMilli()})"
        )
        execSQL(
            "INSERT INTO collection_photos (imageKey, collectionId, albumKey, title, isDownloaded) " +
                "VALUES ('i-AbCd', 7, 'FfHCms', 'IMG_1', 1), ('i-EfGh', 7, 'FfHCms', 'IMG_2', 0)"
        )
        execSQL(
            "INSERT INTO collection_bookmarks (id, collectionId, type, itemKey, title, albumKey, albumTitle) " +
                "VALUES (3, 7, 'Album', 'FfHCms', 'Class photos', 'FfHCms', 'Class photos')"
        )
        execSQL("INSERT INTO viewed_gallery_updates (nodeId, lastViewedDateModified) VALUES ('LCdk7F', '$modified')")
        execSQL(
            "INSERT INTO search_history (query, nickname, timestamp) " +
                "VALUES ('school', 'someuser', ${now.toEpochMilli()})"
        )
    }

    private fun assertV13UserDataSurvived(db: SupportSQLiteDatabase) {
        fun count(sql: String): Int = db.query(sql).use { it.moveToFirst(); it.getInt(0) }
        assertEquals(3, count("SELECT COUNT(*) FROM cached_nodes WHERE nickname = 'someuser'"))
        assertEquals(1, count("SELECT COUNT(*) FROM offline_collections WHERE id = 7 AND name = 'Trip'"))
        assertEquals(2, count("SELECT COUNT(*) FROM collection_photos WHERE collectionId = 7"))
        assertEquals(1, count("SELECT COUNT(*) FROM collection_photos WHERE imageKey = 'i-AbCd' AND isDownloaded = 1"))
        assertEquals(1, count("SELECT COUNT(*) FROM collection_bookmarks WHERE id = 3 AND itemKey = 'FfHCms'"))
        assertEquals(1, count("SELECT COUNT(*) FROM viewed_gallery_updates WHERE nodeId = 'LCdk7F'"))
        assertEquals(1, count("SELECT COUNT(*) FROM search_history WHERE query = 'school'"))
        db.query("SELECT parentNodeId FROM cached_nodes WHERE nodeId = 'LCdk7F'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("P4BKB", c.getString(0))
        }
    }

    private companion object {
        const val TEST_DB = "migration-test"
    }
}
