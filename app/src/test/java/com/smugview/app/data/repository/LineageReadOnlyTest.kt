package com.smugview.app.data.repository

import com.smugview.app.data.db.AppDatabase
import com.smugview.app.data.db.CachedNode
import com.smugview.app.data.db.CollectionDao
import com.smugview.app.data.db.TestDb
import com.smugview.app.data.security.FakePasswordStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

/**
 * Phase 2 step 2-3 (R-01, R-03, R-04, R-06): a breadcrumb or a search never writes a row under a
 * real parent. `Uris.ParentNode` is the node's OWN `!parent` link, so every parent parsed from it was
 * the node itself (or `X!parent`). Only a listing may place a row.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class LineageReadOnlyTest {
    private lateinit var db: AppDatabase
    private lateinit var dao: CollectionDao
    private val server = FakeSmugMugServer()

    @Before fun setUp() {
        db = TestDb.inMemory()
        dao = db.collectionDao()
    }

    @After fun tearDown() = db.close()

    private fun repo() = SmugMugRepository(
        server.api(), dao, FakePasswordStore(), org.mockito.Mockito.mock(android.content.Context::class.java)
    )

    private fun row(id: String, parent: String?, type: String, title: String, albumKey: String? = null, sortIndex: Int = 0) =
        CachedNode(
            nodeId = id, parentNodeId = parent, type = type, title = title, description = null, access = "None",
            passwordHint = null, uri = "/api/v2/node/$id", childNodesUri = null,
            albumUri = albumKey?.let { "/api/v2/album/$it" }, sortIndex = sortIndex,
            dateModified = java.time.OffsetDateTime.now().minusDays(2).toString()
        )

    /** The tree as a listing would have cached it. */
    private fun seedFixtureTree() = runBlocking {
        dao.insertNodes(
            listOf(
                row("4zqWw", null, "Folder", "Home"),
                row("2sDN5x", "4zqWw", "Folder", "Family"),
                row("P4BKB", "2sDN5x", "Folder", "School"),
                row("LCdk7F", "P4BKB", "Album", "New School Year", albumKey = "FfHCms", sortIndex = 7)
            )
        )
    }

    @Test fun coldCache_breadcrumbComesFromParents_andNothingIsWritten() {
        val crumbs = runBlocking { repo().resolveAndCacheAlbumLineage("FfHCms", "k") }
        // Red today: the old code inserts LCdk7F with a parent parsed from "LCdk7F!parent".
        assertEquals(emptyList<CachedNode>(), runBlocking { dao.getAllCachedNodes() })
        // Site root and the gallery itself are not part of the breadcrumb; order is root-most first.
        assertEquals(listOf("2sDN5x", "P4BKB"), crumbs.map { it.nodeId })
        assertEquals(listOf("Family", "School"), crumbs.map { it.title })
        assertEquals(listOf("4zqWw", "2sDN5x"), crumbs.map { it.parentNodeId })
    }

    @Test fun warmCache_isByteEqualAfterTheBreadcrumb() {
        seedFixtureTree()
        val before = runBlocking { dao.getAllCachedNodes() }.sortedBy { it.nodeId }
        val crumbs = runBlocking { repo().resolveAndCacheAlbumLineage("FfHCms", "k") }
        assertEquals(listOf("2sDN5x", "P4BKB"), crumbs.map { it.nodeId })
        assertEquals(before, runBlocking { dao.getAllCachedNodes() }.sortedBy { it.nodeId })
    }

    @Test fun offline_breadcrumbFallsBackToCachedRows_readOnly() {
        seedFixtureTree()
        server.parentsOverride = { throw IOException("offline") }
        val before = runBlocking { dao.getAllCachedNodes() }.sortedBy { it.nodeId }
        val crumbs = runBlocking { repo().resolveAndCacheAlbumLineage("FfHCms", "k") }
        assertEquals(listOf("2sDN5x", "P4BKB"), crumbs.map { it.nodeId })
        assertEquals(before, runBlocking { dao.getAllCachedNodes() }.sortedBy { it.nodeId })
    }

    @Test fun lineageOf_isSelfFirst_toTheSiteRoot_andWritesNothing() {
        val chain = runBlocking { repo().lineageOf("LCdk7F", "k") }
        assertEquals(listOf("LCdk7F", "P4BKB", "2sDN5x", "4zqWw"), chain.map { it.nodeId })
        assertEquals(listOf("P4BKB", "2sDN5x", "4zqWw", null), chain.map { it.parentNodeId })
        assertEquals(emptyList<CachedNode>(), runBlocking { dao.getAllCachedNodes() })
    }

    @Test fun searchHit_forAnExistingListedGallery_keepsItsRow() {
        seedFixtureTree()
        val before = runBlocking { dao.getNodeById("LCdk7F") }!!
        server.searchResultIds = listOf("LCdk7F")
        val result = runBlocking { repo().searchNodesRemote("idzifamily", "/api/v2/node/4zqWw", "4zqWw", "school", "k").first() }
        assertEquals(listOf("LCdk7F"), result.getOrThrow().map { it.nodeId })
        val after = runBlocking { dao.getNodeById("LCdk7F") }!!
        assertEquals("P4BKB", after.parentNodeId)
        // Red today: the search wrote sortIndex = its own result position (0), not the listing's 7.
        assertEquals(7, after.sortIndex)
        assertEquals(before, after)
    }

    @Test fun searchHit_forAnUnknownNode_isParkedAsSearchResult_neverUnderItself() {
        server.searchResultIds = listOf("LCdk7F")
        runBlocking { repo().searchNodesRemote("idzifamily", "/api/v2/node/4zqWw", "4zqWw", "school", "k").first() }
        val stored = runBlocking { dao.getNodeById("LCdk7F") }!!
        assertNotEquals("LCdk7F", stored.parentNodeId)
        assertEquals("search_result", stored.parentNodeId)
    }
}
