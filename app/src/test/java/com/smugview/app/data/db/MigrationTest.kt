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
            .addMigrations(AppDatabase.MIGRATION_12_13, AppDatabase.MIGRATION_13_14, AppDatabase.MIGRATION_14_15)
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
