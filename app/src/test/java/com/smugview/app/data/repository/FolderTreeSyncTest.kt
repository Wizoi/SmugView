package com.smugview.app.data.repository

import com.smugview.app.data.db.AppDatabase
import com.smugview.app.data.db.CachedNode
import com.smugview.app.data.db.CollectionDao
import com.smugview.app.data.db.TestDb
import com.smugview.app.data.security.FakePasswordStore
import com.smugview.app.diag.StopReason
import com.smugview.app.diag.SyncKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Phase 2 step 2-6 (design 3.6): the first tree walk on a v16 database is forced, once per site,
 * and the flag `treeRepairDone.<nick>` is set only when the whole walk finished.
 * Fixture F: 4zqWw -> {2sDN5x Family -> P4BKB School -> gallery LCdk7F, 3BxbFF -> gallery sXQz4G}.
 * Four folders are reachable (galleries are not listed).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class FolderTreeSyncTest {
    private lateinit var db: AppDatabase
    private lateinit var dao: CollectionDao
    private val server = FakeSmugMugServer()
    private val reporter = RecordingSyncReporter()
    private val state = InMemorySyncStateStore()
    private val nick = "idzifamily"
    private val folders = listOf("4zqWw", "2sDN5x", "P4BKB", "3BxbFF")

    @Before fun setUp() {
        db = TestDb.inMemory()
        dao = db.collectionDao()
    }

    @After fun tearDown() = db.close()

    private fun repo() = SmugMugRepository(
        server.api(), dao, FakePasswordStore(),
        org.mockito.Mockito.mock(android.content.Context::class.java), reporter, state
    ).also { it.treeSyncDelayMs = 0 }

    private fun status(req: okhttp3.Request, code: Int) =
        Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(code).message("m")
            .body("""{"Code":$code}""".toResponseBody("application/json".toMediaTypeOrNull())).build()

    private fun childrenRequests() = server.requestsTo("!children")

    @Test fun firstWalk_forcesEveryReachableFolderOnce_andSetsTheFlag() {
        val result = runBlocking { repo().syncFolderTree(nick, "4zqWw", "k") }

        assertTrue(result.forced)
        assertTrue(result.complete)
        assertEquals(4, childrenRequests().size)
        for (f in folders) {
            assertEquals("folder $f fetched exactly once", 1, server.requestsTo("node/$f!children").size)
            assertEquals("folder $f sent no-cache", listOf<String?>("no-cache"), server.childrenCacheControls(f))
        }
        assertTrue(state.getBoolean("treeRepairDone.$nick"))
        val run = reporter.runsOf(SyncKind.FolderTreeSync).single()
        assertEquals(StopReason.Completed, run.stop)
        assertTrue(run.notes!!, run.notes!!.startsWith("forced=true complete=true"))
    }

    @Test fun walkThatHits504Midway_leavesTheFlagUnset_andIsRetriedForced() {
        server.childrenOverride = { id, req -> if (id == "2sDN5x") status(req, 504) else null }

        val first = runBlocking { repo().syncFolderTree(nick, "4zqWw", "k") }

        assertTrue(first.forced)
        assertFalse(first.complete)
        assertFalse(state.getBoolean("treeRepairDone.$nick"))
        val run = reporter.runsOf(SyncKind.FolderTreeSync).single()
        assertEquals(StopReason.Error, run.stop)
        assertTrue(run.notes!!, run.notes!!.startsWith("forced=true complete=false"))

        // Next launch, network back: forced again, and now it finishes and sets the flag.
        server.childrenOverride = null
        val second = runBlocking { repo().syncFolderTree(nick, "4zqWw", "k") }
        assertTrue(second.forced)
        assertTrue(second.complete)
        assertTrue(state.getBoolean("treeRepairDone.$nick"))
    }

    @Test fun flagSet_noForcedFetch_andCachedFoldersMakeNoRequest() {
        state.putBoolean("treeRepairDone.$nick", true)

        val first = runBlocking { repo().syncFolderTree(nick, "4zqWw", "k") }
        assertFalse(first.forced)
        // Nothing cached yet: cache-first fetches what is missing, but never with no-cache.
        for (f in folders) assertEquals(listOf<String?>(null), server.childrenCacheControls(f))

        val before = childrenRequests().size
        val second = runBlocking { repo().syncFolderTree(nick, "4zqWw", "k") }
        assertFalse(second.forced)
        assertTrue(second.complete)
        assertEquals("a cached tree is not fetched again", before, childrenRequests().size)
        assertTrue(reporter.runsOf(SyncKind.FolderTreeSync).last().notes!!.startsWith("forced=false complete=true"))
    }

    @Test fun lockedFolderWithNoSession_isSkipped_notAnError() {
        server.childrenOverride = { id, req -> if (id == "2sDN5x") status(req, 401) else null }

        val result = runBlocking { repo().syncFolderTree(nick, "4zqWw", "k") }

        assertTrue("a 401 is a locked folder, not a failed walk", result.complete)
        assertEquals(1, result.skippedLocked)
        assertTrue(state.getBoolean("treeRepairDone.$nick"))
        // Its subtree is not walked (School is only reachable through Family).
        assertTrue(server.requestsTo("node/P4BKB!children").isEmpty())
    }

    @Test fun forcedWalk_dropsStaleChildrenFromEarlierVersions() {
        // R-04: a listing written by an older version holds a row the server no longer lists.
        runBlocking {
            dao.insertNodes(
                listOf(
                    CachedNode(
                        nodeId = "P4BKB!parent", parentNodeId = "P4BKB", type = "Folder", title = "stale",
                        description = null, access = "None", passwordHint = null,
                        uri = "/api/v2/node/P4BKB!parent", childNodesUri = null, albumUri = null, nickname = nick
                    ),
                    CachedNode(
                        nodeId = "LCdk7F", parentNodeId = "P4BKB", type = "Album", title = "old title",
                        description = null, access = "None", passwordHint = null,
                        uri = "/api/v2/node/LCdk7F", childNodesUri = null, albumUri = "/api/v2/album/FfHCms", nickname = nick
                    )
                )
            )
        }

        runBlocking { repo().syncFolderTree(nick, "4zqWw", "k") }

        val kids = runBlocking { dao.getCachedNodesByParent("P4BKB").first() }
        assertEquals(listOf("LCdk7F"), kids.map { it.nodeId })
        assertNull(runBlocking { dao.getNodeById("P4BKB!parent") })
    }

    // Phase 6-1 (design 3.6, N3): a locked folder answers 404 anonymously (L2, live), not 401.

    /** Real topology (L2): public Memories -> {sNWzhX Password (404), public sibling Tn8Vc3 -> gallery}. */
    @Test fun lockedFolderAnswering404_isSkipped_andTheWalkCompletes() {
        server.listMemories()
        server.lockedChildrenCode = 404

        val result = runBlocking { repo().syncFolderTree(nick, "4zqWw", "k") }

        assertTrue("the walk finished: a 404 on a Password folder is a locked folder", result.complete)
        assertEquals(1, result.skippedLocked)
        assertEquals("the public sibling after the locked folder was listed", 1, server.requestsTo("node/Tn8Vc3!children").size)
        assertEquals(listOf("Dw7Lx5"), runBlocking { dao.getCachedNodesByParent("Tn8Vc3").first() }.map { it.nodeId })
        assertTrue(state.getBoolean("treeRepairDone.$nick"))
        val run = reporter.runsOf(SyncKind.FolderTreeSync).single()
        assertEquals(StopReason.Completed, run.stop)
        assertTrue(run.notes!!, run.notes!!.endsWith("skippedLocked=1 skippedMissing=0 skippedFailed=0"))
    }

    /** Depth 2: Family (Password, locked) -> School -> gallery is never reached; the public sibling still is. */
    @Test fun lockedPasswordRootAnswering404_endsItsSubtreeOnly() {
        server.lockedChildrenCode = 404
        server.cookieGate = true   // Family answers lockedChildrenCode until `!unlock`

        val result = runBlocking { repo().syncFolderTree(nick, "4zqWw", "k") }

        assertTrue(result.complete)
        assertEquals(1, result.skippedLocked)
        assertTrue("School is only reachable through Family", server.requestsTo("node/P4BKB!children").isEmpty())
        assertEquals(1, server.requestsTo("node/3BxbFF!children").size)
        assertTrue(state.getBoolean("treeRepairDone.$nick"))
    }

    /** A 404 on a folder the listing said is public (deleted between the listing and the fetch) is "missing", not "locked". */
    @Test fun missingPublicFolderAnswering404_isSkippedAsMissing_andTheWalkCompletes() {
        server.childrenOverride = { id, req -> if (id == "3BxbFF") status(req, 404) else null }

        val result = runBlocking { repo().syncFolderTree(nick, "4zqWw", "k") }

        assertTrue(result.complete)
        assertEquals(0, result.skippedLocked)
        assertTrue(reporter.runsOf(SyncKind.FolderTreeSync).single().notes!!.endsWith("skippedLocked=0 skippedMissing=1 skippedFailed=0"))
        assertTrue(state.getBoolean("treeRepairDone.$nick"))
    }

    @Test fun a503OnOneFolder_isSkipped_theSiblingIsListed_andTheFlagStaysUnset() {
        server.listMemories()
        server.lockedChildrenCode = 404
        server.childrenOverride = { id, req -> if (id == "2sDN5x") status(req, 503) else null }

        val result = runBlocking { repo().syncFolderTree(nick, "4zqWw", "k") }

        assertFalse("a 5xx leaves the walk incomplete", result.complete)
        assertEquals("the sibling after the failed folder was still listed", 1, server.requestsTo("node/3BxbFF!children").size)
        assertEquals("and so was the folder after that", 1, server.requestsTo("node/6KHfpq!children").size)
        assertFalse("the next launch retries", state.getBoolean("treeRepairDone.$nick"))
        val run = reporter.runsOf(SyncKind.FolderTreeSync).single()
        assertEquals(StopReason.Error, run.stop)
        assertEquals("HttpException 503", run.stopDetail)
        // sNWzhX is locked (404) and 2sDN5x answered 503.
        assertTrue(run.notes!!, run.notes!!.endsWith("skippedLocked=1 skippedMissing=0 skippedFailed=1"))
    }

    /** Pin (green before and after): offline means nothing can be reached, so the walk stops instead of hammering. */
    @Test fun offlineOnTheWalk_abortsAtOnce_andListsNothingMore() {
        server.childrenOverride = { id, _ -> if (id == "2sDN5x") throw java.net.UnknownHostException("api.smugmug.com") else null }

        val result = runBlocking { repo().syncFolderTree(nick, "4zqWw", "k") }

        assertFalse(result.complete)
        assertTrue("no request after the offline one", server.requestsTo("node/3BxbFF!children").isEmpty())
        assertFalse(state.getBoolean("treeRepairDone.$nick"))
    }

    /** Pin (green before and after): a 429 aborts, it is not retried by the walk and nothing after it is requested. */
    @Test fun a429OnTheWalk_abortsAtOnce() {
        server.childrenOverride = { id, req -> if (id == "2sDN5x") status(req, 429) else null }

        val result = runBlocking { repo().syncFolderTree(nick, "4zqWw", "k") }

        assertFalse(result.complete)
        assertTrue(server.requestsTo("node/3BxbFF!children").isEmpty())
        assertFalse(state.getBoolean("treeRepairDone.$nick"))
    }
}
