package com.smugview.app.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Room migration tests on the exported schemas in app/schemas (wired as unit-test assets in
 * build.gradle.kts), run under Robolectric. Schema 12 is not exported, so 12->13 cannot be
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

    private companion object {
        const val TEST_DB = "migration-test"
    }
}
