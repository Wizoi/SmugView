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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * R-06 (whole-app review): a cache hit was `isNotEmpty()`, so one stray row under a parent (a lineage or search write) was shown as the whole
 * listing and the real children were never fetched. A parent is a complete listing only once `replaceChildren` has written it (`node_listings`).
 * Fixture F: 4zqWw -> {2sDN5x Family -> P4BKB School -> ..., 3BxbFF}.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class ListingCompleteTest {
    private lateinit var db: AppDatabase
    private lateinit var dao: CollectionDao
    private val server = FakeSmugMugServer()
    private val nick = "idzifamily"

    @Before fun setUp() { db = TestDb.inMemory(); dao = db.collectionDao() }
    @After fun tearDown() = db.close()

    private fun repo() = SmugMugRepository(server.api(), dao, FakePasswordStore(), RecordingSyncReporter(), InMemorySyncStateStore())

    /** What a lineage or search write leaves behind: one row under Family that is not the listing. */
    private fun strayRowUnderFamily() = CachedNode(
        nodeId = "StRaY1", parentNodeId = "2sDN5x", type = "Album", title = "A gallery found by search", description = "", access = "Public",
        passwordHint = null, uri = "/api/v2/node/StRaY1", childNodesUri = null, albumUri = null, highlightImageUrl = null, childCount = null,
        sortIndex = 0, webUri = "https://gallery.idzifamily.com/Family/found"
    )

    @Test fun aStrayRowIsNotTheListing_theRealChildrenAreFetched() = runBlocking {
        dao.insertNodes(listOf(strayRowUnderFamily()))

        val rows = repo().getNodeChildren(nick, "2sDN5x", "k").first().getOrThrow()

        assertEquals("one real listing request", 1, server.requestsTo("node/2sDN5x!children").size)
        assertTrue("School comes from the server: ${rows.map { it.nodeId }}", rows.any { it.nodeId == "P4BKB" })
    }

    @Test fun aCompleteListingIsServedFromTheCacheWithoutAnotherRequest() = runBlocking {
        val repo = repo()
        repo.getNodeChildren(nick, "2sDN5x", "k").first().getOrThrow()
        val firstCount = server.requestsTo("node/2sDN5x!children").size

        val again = repo.getNodeChildren(nick, "2sDN5x", "k").first().getOrThrow()

        assertEquals("the second open is a cache hit", firstCount, server.requestsTo("node/2sDN5x!children").size)
        assertTrue(again.any { it.nodeId == "P4BKB" })
    }
}
