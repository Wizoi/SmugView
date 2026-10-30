package com.smugview.app.data.db

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** A fresh in-memory database for the platform under test (bundled SQLite on both). */
expect fun newInMemoryDatabase(): AppDatabase

/**
 * iOS spike, Stage 0 step 3 (c) and (d): the ported database behaves as the Android one does.
 *  - (c) `insertNodes` is REPLACE, not append (findings #8);
 *  - (d) the recursive-CTE "new dot" query lights the gallery and every folder above it, over the real topology of the owner's site:
 *    root -> Family (password) -> School -> gallery, depth 3 under the root; dates are relative to now (findings #9).
 */
class NodeDaoTest {
    /** SmugMug's date shape, relative to now (findings #9): 2026-10-25T10:00:00+00:00. Plain arithmetic, no date library. */
    private fun daysAgo(days: Int): String {
        val ms = nowMillis() - days * 86_400_000L
        val secondsOfDay = (ms / 1000).mod(86_400L).toInt()
        // days since 1970-01-01 to a civil date (Howard Hinnant's algorithm)
        val z = ms.floorDiv(86_400_000L) + 719_468
        val era = z.floorDiv(146_097L)
        val doe = z - era * 146_097
        val yoe = (doe - doe / 1_460 + doe / 36_524 - doe / 146_096) / 365
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val d = (doy - (153 * mp + 2) / 5 + 1).toInt()
        val m = (if (mp < 10) mp + 3 else mp - 9).toInt()
        val y = (yoe + era * 400 + if (m <= 2) 1 else 0).toInt()
        fun two(n: Int) = n.toString().padStart(2, '0')
        return "$y-${two(m)}-${two(d)}T${two(secondsOfDay / 3600)}:${two(secondsOfDay % 3600 / 60)}:${two(secondsOfDay % 60)}+00:00"
    }

    private fun node(id: String, parent: String?, title: String, type: String = "Folder", access: String? = "Public") = CachedNode(
        nodeId = id, parentNodeId = parent, type = type, title = title, description = null, access = access, passwordHint = null,
        uri = "/api/v2/node/$id", childNodesUri = null, albumUri = null, nickname = "idzifamily"
    )

    private fun album(key: String, nodeId: String, name: String, parent: String, ilu: String?, nickname: String = "idzifamily") = CachedAlbum(
        albumKey = key, nodeId = nodeId, name = name, securityType = null, passwordHint = null, uri = "/api/v2/album/$key", webUri = null,
        urlPath = null, imageCount = 10, dateModified = ilu, galleryStyle = null, highlightImageUrl = null, nickname = nickname,
        parentNodeId = parent, imagesLastUpdated = ilu
    )

    @Test fun theSameNodeInsertedTwiceIsOneRowAndTheLaterWins() = runTest {
        val db = newInMemoryDatabase()
        val dao = db.collectionDao()
        dao.insertNodes(listOf(node("LCdk7F", "P4BKB", "Old title", "Album")))
        dao.insertNodes(listOf(node("LCdk7F", "P4BKB", "New title", "Album"), node("P4BKB", "2sDN5x", "School")))
        // Two ids were inserted (one twice): REPLACE leaves the new title on the first and the second present.
        assertNotNull(dao.getNodeById("P4BKB"))
        assertEquals("New title", assertNotNull(dao.getNodeById("LCdk7F")).title)
        db.close()
    }

    @Test fun theDotLightsTheGalleryAndEveryFolderAboveItAndNothingElse() = runTest {
        val db = newInMemoryDatabase()
        val dao = db.collectionDao()
        dao.insertNodes(listOf(
            node("2sDN5x", "4zqWw", "Family", access = "Password"),
            node("P4BKB", "2sDN5x", "School"),
            node("3BxbFF", "4zqWw", "Kentridge")
        ))
        dao.upsertAlbums(listOf(
            album("FfHCms", "LCdk7F", "New School Year", parent = "P4BKB", ilu = daysAgo(2)),
            album("N74KSK", "sXQz4G", "Public Gallery", parent = "3BxbFF", ilu = daysAgo(40)),
            album("jX9wQe", "Vt4Kp8", "Another site's gallery", parent = "3BxbFF", ilu = daysAgo(1), nickname = "siteb")
        ))
        val lit = dao.getNodesWithActiveUpdates("idzifamily").first().toSet()
        // the root (4zqWw) has no row of its own and is included as the last parent
        assertEquals(setOf("LCdk7F", "P4BKB", "2sDN5x", "4zqWw"), lit)
        assertTrue("sXQz4G" !in lit, "a gallery older than 30 days lights nothing")
        assertTrue("Vt4Kp8" !in lit, "another site's gallery lights nothing here")
        db.close()
    }

    @Test fun aViewedGalleryNoLongerLightsTheDot() = runTest {
        val db = newInMemoryDatabase()
        val dao = db.collectionDao()
        val ilu = daysAgo(2)
        dao.insertNodes(listOf(node("2sDN5x", "4zqWw", "Family", access = "Password"), node("P4BKB", "2sDN5x", "School")))
        dao.upsertAlbums(listOf(album("FfHCms", "LCdk7F", "New School Year", parent = "P4BKB", ilu = ilu)))
        dao.insertViewedUpdates(listOf(ViewedGalleryUpdate("LCdk7F", ilu)))
        assertEquals(emptySet(), dao.getNodesWithActiveUpdates("idzifamily").first().toSet())
        db.close()
    }
}
