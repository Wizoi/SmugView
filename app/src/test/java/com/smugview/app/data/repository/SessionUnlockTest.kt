package com.smugview.app.data.repository

import com.smugview.app.data.db.AppDatabase
import com.smugview.app.data.db.CachedNode
import com.smugview.app.data.db.CollectionDao
import com.smugview.app.data.db.TestDb
import com.smugview.app.data.security.FakePasswordStore
import com.smugview.app.diag.StopReason
import com.smugview.app.diag.SyncKind
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
 * Phase 2 step 2-7 (design 3.2, 3.8): the launch unlock opens one session per password ROOT before
 * the crawl, with no "already unlocked" skip (findings #16, R-22). Fixture F with the saved keys the
 * owner really has: the password folder 2sDN5x and the gallery node LCdk7F (SmugMug's password copy
 * saves the password under both, R-26).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class SessionUnlockTest {
    private lateinit var db: AppDatabase
    private lateinit var dao: CollectionDao
    private val server = FakeSmugMugServer().also { it.cookieGate = true }
    private val reporter = RecordingSyncReporter()
    private val nick = "idzifamily"
    private val store = FakePasswordStore(mapOf("2sDN5x" to "fake-pw-1", "LCdk7F" to "fake-pw-1"))

    @Before fun setUp() {
        db = TestDb.inMemory()
        dao = db.collectionDao()
    }

    @After fun tearDown() = db.close()

    private fun repo() = SmugMugRepository(
        server.api(), dao, store,
        org.mockito.Mockito.mock(android.content.Context::class.java), reporter, InMemorySyncStateStore()
    ).also { it.treeSyncDelayMs = 0; it.unlockDelayMs = 0 }

    private fun node(id: String, parent: String?, type: String, access: String, albumKey: String? = null) = CachedNode(
        nodeId = id, parentNodeId = parent, type = type, title = id, description = null, access = access,
        passwordHint = null, uri = "/api/v2/node/$id", childNodesUri = null,
        albumUri = albumKey?.let { "/api/v2/album/$it" }, nickname = nick
    )

    @Test fun savedKeysFolderAndGallery_unlockTheOneRootOnce_beforeTheCrawl() {
        runBlocking { repo().runSiteSync(nick, "4zqWw", "k") }

        assertEquals(listOf("POST node/2sDN5x!unlock"), server.requestsTo("!unlock"))
        val run = reporter.runsOf(SyncKind.LaunchUnlock).single()
        assertEquals(2, run.savedKeys)
        assertEquals(emptyList<String>(), run.skippedAlreadyUnlocked)
        assertEquals("roots=1 ok=1 rejected=0 transient=0", run.notes)
        assertEquals(StopReason.Completed, run.stop)

        val unlockAt = server.requests.indexOf("POST node/2sDN5x!unlock")
        val firstCrawlAt = server.requests.indexOfFirst { it.startsWith("GET user/$nick!albums") }
        assertTrue("crawl made a request", firstCrawlAt >= 0)
        assertTrue("unlock ($unlockAt) before the crawl ($firstCrawlAt)", unlockAt in 0 until firstCrawlAt)
        assertTrue(
            "the crawl's first request carries the session cookie",
            server.requestsWithSession.contains("GET user/$nick!albums")
        )
        // The tree sync ran after the unlock too: School (under the password folder) listed, no 401.
        assertEquals(1, server.requestsTo("node/P4BKB!children").size)
        assertTrue(reporter.runsOf(SyncKind.FolderTreeSync).single().notes!!.contains("complete=true"))
    }

    @Test fun cachedRows_resolveTheRootOffline_noParentsCall() {
        runBlocking {
            dao.insertNodes(
                listOf(
                    node("4zqWw", null, "Folder", "None"),
                    node("2sDN5x", "4zqWw", "Folder", "Password"),
                    node("P4BKB", "2sDN5x", "Folder", "None"),
                    node("LCdk7F", "P4BKB", "Album", "None", albumKey = "FfHCms")
                )
            )
            repo().unlockSavedRoots(nick, "k")
        }

        assertEquals(listOf("POST node/2sDN5x!unlock"), server.requestsTo("!unlock"))
        assertTrue("no !parents when the chain is cached", server.requestsTo("!parents").isEmpty())
    }

    @Test fun uncachedGalleryKey_isMappedToItsRoot_withOneParentsCall() {
        runBlocking { repo().unlockSavedRoots(nick, "k") }

        assertEquals(1, server.requestsTo("node/LCdk7F!parents").size)
        assertEquals(listOf("POST node/2sDN5x!unlock"), server.requestsTo("!unlock"))
    }

    @Test fun rejected401_keepsTheSavedPassword_andIsCounted() {
        server.unlockCode = 401

        val summary = runBlocking { repo().unlockSavedRoots(nick, "k") }

        assertEquals(1, summary.rejected)
        assertEquals(false, summary.allSucceeded)
        assertEquals("fake-pw-1", store.getPassword("2sDN5x"))
        assertEquals("fake-pw-1", store.getPassword("LCdk7F"))
        assertEquals("roots=1 ok=0 rejected=1 transient=0", reporter.runsOf(SyncKind.LaunchUnlock).single().notes)
    }

    @Test fun transient503_keepsTheSavedPassword_andIsCounted() {
        server.unlockCode = 503

        val summary = runBlocking { repo().unlockSavedRoots(nick, "k") }

        assertEquals(1, summary.transient)
        assertEquals("fake-pw-1", store.getPassword("2sDN5x"))
        assertEquals("roots=1 ok=0 rejected=0 transient=1", reporter.runsOf(SyncKind.LaunchUnlock).single().notes)
    }

    @Test fun noSavedPasswords_makesNoUnlockCall() {
        val empty = SmugMugRepository(
            server.api(), dao, FakePasswordStore(),
            org.mockito.Mockito.mock(android.content.Context::class.java), reporter, InMemorySyncStateStore()
        )

        val summary = runBlocking { empty.unlockSavedRoots(nick, "k") }

        assertEquals(0, summary.roots)
        assertTrue(summary.allSucceeded)
        assertTrue(server.requestsTo("!unlock").isEmpty())
    }
}
