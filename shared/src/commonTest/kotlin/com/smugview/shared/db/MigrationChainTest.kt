package com.smugview.shared.db

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

/** The exported schema JSON of [version] (`shared/schemas/.../<version>.json`, the app's own files). Each platform reads it its own way. */
expect fun readSchema(version: Int): String

/** A path for a new, empty database file. */
expect fun tempDbPath(): String

/** The real database on [path] with all six migrations registered, as the app builds it. */
expect fun openDatabaseAt(path: String): AppDatabase

/**
 * iOS spike, Stage 0 step 3 (b): a version-13 database, rebuilt from the app's real exported `13.json`, with realistic user data in it, is
 * opened by Room KMP with the shared migrations and ends as a valid version-18 database with the user's rows intact.
 * Schema 12 was never exported (the app's MigrationTest says the same), so 12 -> 13 is compiled but not exercised here.
 */
class MigrationChainTest {
    private val driver = BundledSQLiteDriver()

    private fun createOldDatabase(path: String, version: Int) {
        val schema = Json.parseToJsonElement(readSchema(version)).jsonObject["database"]!!.jsonObject
        val connection = driver.open(path)
        try {
            for (entity in schema["entities"]!!.jsonArray) {
                val e = entity.jsonObject
                val table = e["tableName"]!!.jsonPrimitive.content
                connection.execSQL(e["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                for (index in e["indices"]?.jsonArray.orEmpty()) {
                    connection.execSQL(index.jsonObject["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                }
            }
            for (q in schema["setupQueries"]!!.jsonArray) connection.execSQL(q.jsonPrimitive.content)
            connection.execSQL("PRAGMA user_version = $version")
            insertV13UserData(connection)
        } finally {
            connection.close()
        }
    }

    // The same rows as the app's MigrationTest.insertV13UserData: password folder 2sDN5x -> sub-folder P4BKB -> gallery node LCdk7F (AlbumKey FfHCms).
    private fun insertV13UserData(c: SQLiteConnection) {
        val now = nowMillis()
        val modified = "2026-10-27T12:00:00+00:00"
        c.execSQL(
            "INSERT INTO cached_nodes (nodeId, parentNodeId, type, title, access, uri, albumUri, sortIndex, dateModified, nickname) VALUES " +
                "('2sDN5x', NULL, 'Folder', 'Family', 'Password', '/api/v2/node/2sDN5x', NULL, 0, '$modified', 'someuser'), " +
                "('P4BKB', '2sDN5x', 'Folder', 'School', 'Inherited', '/api/v2/node/P4BKB', NULL, 0, '$modified', 'someuser'), " +
                "('LCdk7F', 'P4BKB', 'Album', 'Class photos', 'Inherited', '/api/v2/node/LCdk7F', '/api/v2/album/FfHCms', 1, '$modified', 'someuser')"
        )
        c.execSQL("INSERT INTO offline_collections (id, name, siteNickname, createdAt) VALUES (7, 'Trip', 'someuser', $now)")
        c.execSQL(
            "INSERT INTO collection_photos (imageKey, collectionId, albumKey, title, isDownloaded) " +
                "VALUES ('i-AbCd', 7, 'FfHCms', 'IMG_1', 1), ('i-EfGh', 7, 'FfHCms', 'IMG_2', 0)"
        )
        c.execSQL(
            "INSERT INTO collection_bookmarks (id, collectionId, type, itemKey, title, albumKey, albumTitle) " +
                "VALUES (3, 7, 'Album', 'FfHCms', 'Class photos', 'FfHCms', 'Class photos')"
        )
        c.execSQL("INSERT INTO viewed_gallery_updates (nodeId, lastViewedDateModified) VALUES ('LCdk7F', '$modified')")
        c.execSQL("INSERT INTO search_history (query, nickname, timestamp) VALUES ('school', 'someuser', $now)")
    }

    private fun count(c: SQLiteConnection, sql: String): Long {
        val s = c.prepare(sql)
        try {
            s.step()
            return s.getLong(0)
        } finally {
            s.close()
        }
    }

    @Test fun version13MigratesToTheCurrentSchemaAndKeepsTheUsersRows() = runTest {
        val path = tempDbPath()
        createOldDatabase(path, 13)

        // Opening runs the chain 13 -> 18 and Room validates the result against the entities (it throws on any difference).
        val db = openDatabaseAt(path)
        assertEquals(3, db.nodeDao().nodeCount())
        db.close()

        val c = driver.open(path)
        try {
            assertEquals(18, count(c, "PRAGMA user_version"))
            assertEquals(1, count(c, "SELECT COUNT(*) FROM offline_collections WHERE id = 7 AND name = 'Trip'"))
            assertEquals(2, count(c, "SELECT COUNT(*) FROM collection_photos WHERE collectionId = 7"))
            assertEquals(1, count(c, "SELECT COUNT(*) FROM collection_photos WHERE imageKey = 'i-AbCd' AND isDownloaded = 1"))
            assertEquals(1, count(c, "SELECT COUNT(*) FROM collection_bookmarks WHERE id = 3 AND itemKey = 'FfHCms'"))
            assertEquals(1, count(c, "SELECT COUNT(*) FROM viewed_gallery_updates WHERE nodeId = 'LCdk7F'"))
            assertEquals(1, count(c, "SELECT COUNT(*) FROM search_history WHERE query = 'school'"))
            assertEquals(1, count(c, "SELECT COUNT(*) FROM cached_nodes WHERE nodeId = 'LCdk7F' AND parentNodeId = 'P4BKB'"))
            // the tables the later versions added exist and are usable
            for (t in listOf("cached_albums", "offline_files", "offline_galleries", "offline_gallery_items", "notify_sites", "notify_known_albums")) {
                count(c, "SELECT COUNT(*) FROM $t")
            }
            // the 16 -> 17 backfill gave each saved photo a file row (one per image key) and the album bookmark a kept-gallery row
            assertEquals(2, count(c, "SELECT COUNT(*) FROM offline_files"))
            assertEquals(1, count(c, "SELECT COUNT(*) FROM offline_galleries"))
        } finally {
            c.close()
        }
    }

    @Test fun theSchemaJsonOfEveryVersionIsReadable() {
        for (v in 13..18) {
            val schema: JsonObject = Json.parseToJsonElement(readSchema(v)).jsonObject["database"]!!.jsonObject
            assertEquals(v, schema["version"]!!.jsonPrimitive.content.toInt())
        }
    }
}
