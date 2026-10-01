package com.smugview.app.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Exercises the REAL recursive-CTE SQL in [CollectionDao] against Room's actual SQLite (via
 * Robolectric). Previously this logic was only covered by a hand-written Kotlin re-implementation
 * in the repository test's fake DAO, which diverged from the SQL (different NULL/date semantics and
 * a hard-coded "root" stop condition). This validates the shipped query directly.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class CollectionDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: CollectionDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        dao = db.collectionDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun node(
        id: String,
        parent: String?,
        type: String,
        dateModified: String? = null
    ) = CachedNode(
        nodeId = id,
        parentNodeId = parent,
        type = type,
        title = id,
        description = null,
        access = "Public",
        passwordHint = null,
        uri = "/api/v2/node/$id",
        childNodesUri = null,
        albumUri = if (type == "Album") "/api/v2/album/$id" else null,
        dateModified = dateModified
    )

    private fun recent() = java.time.OffsetDateTime.now().minusDays(2).toString()
    private fun old() = java.time.OffsetDateTime.now().minusDays(60).toString()

    // --- The dot (design 3.5, step 2-10) on fixture F -------------------------------------------
    // root 4zqWw -> 2sDN5x Family (Password) -> P4BKB School -> gallery NodeID LCdk7F / AlbumKey FfHCms
    // Public control: 4zqWw -> 3BxbFF Kentridge -> sXQz4G / N74KSK. The dot reads cached_albums
    // (ImagesLastUpdated) and bubbles through cached_nodes; the gallery needs no cached_nodes row.
    // These replace the five old dot tests that seeded from cached_nodes.dateModified:
    // activeUpdate_bubblesFromAlbumUpToAllAncestors, oldModification_isNotActive,
    // markingViewed_clearsActiveState_andReTriggersOnNewerModification,
    // folderWithOwnRecentModification_clearsWhenItsOnlyGalleryIsViewed, folderBulkBump_withNoRecentGallery_isNotActive.

    private val nick = "idzifamily"
    private fun daysAgo(d: Long, secs: Long = 0) =
        java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC).minusDays(d).plusSeconds(secs)
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssxxx"))

    private fun seedTree() = runBlocking {
        dao.insertNodes(
            listOf(
                node("2sDN5x", "4zqWw", "Folder"),
                node("P4BKB", "2sDN5x", "Folder"),
                node("3BxbFF", "4zqWw", "Folder")
            )
        )
    }

    private fun gallery(
        key: String, nodeId: String, parent: String?, ilu: String?, nickname: String = nick, lastUpdated: String? = ilu
    ) = album(key, lastUpdated, nickname).copy(nodeId = nodeId, parentNodeId = parent, imagesLastUpdated = ilu)

    private fun fGallery(ilu: String? = daysAgo(2)) = gallery("FfHCms", "LCdk7F", "P4BKB", ilu, lastUpdated = daysAgo(6))
    private fun lit(nickname: String = nick) = runBlocking { dao.getNodesWithActiveUpdates(nickname).first().toSet() }

    @Test
    fun recentIlu_lightsTheGalleryAndEveryAncestor_withNoNodeRowForTheGallery() = runBlocking {
        seedTree()
        dao.upsertAlbums(listOf(fGallery()))
        assertEquals(null, dao.getNodeById("LCdk7F"))

        val active = lit()

        assertTrue("the gallery lights by NodeID, not AlbumKey", "LCdk7F" in active)
        assertFalse("FfHCms is an API handle, not a node", "FfHCms" in active)
        assertTrue("School", "P4BKB" in active)
        assertTrue("Family", "2sDN5x" in active)
        assertTrue("the site root (no row of its own) lights too", "4zqWw" in active)
        assertFalse("the public folder holds nothing new", "3BxbFF" in active)
    }

    @Test
    fun parentFallsBackToTheCachedNodesParent_whenTheIndexHasNone() = runBlocking {
        seedTree()
        dao.insertNodes(listOf(node("LCdk7F", "P4BKB", "Album")))
        dao.upsertAlbums(listOf(fGallery().copy(parentNodeId = null)))

        val active = lit()

        assertTrue("P4BKB" in active && "2sDN5x" in active && "4zqWw" in active)
    }

    @Test
    fun oldIlu_isNotActive() = runBlocking {
        seedTree()
        dao.upsertAlbums(listOf(fGallery(ilu = daysAgo(60))))

        assertEquals(emptySet<String>(), lit())
    }

    @Test
    fun viewedEqualToIlu_clearsTheWholeChain_andANewerIluRelightsIt() = runBlocking {
        seedTree()
        dao.upsertAlbums(listOf(fGallery(daysAgo(2))))
        assertTrue("LCdk7F" in lit())

        dao.markViewedAtOrBelow("LCdk7F")
        assertEquals("viewing the gallery clears it and every folder above", emptySet<String>(), lit())

        // The owner adds photos: ILU moves forward past the viewed mark.
        dao.upsertAlbums(listOf(fGallery(daysAgo(1))))
        val active = lit()
        assertTrue("LCdk7F" in active && "P4BKB" in active && "2sDN5x" in active)
    }

    @Test
    fun folderMarkedViewed_clearsEveryGalleryBelow_butNotASiblingFolder() = runBlocking {
        seedTree()
        dao.upsertAlbums(
            listOf(
                fGallery(),
                gallery("AkSecond", "NdSecond", "P4BKB", daysAgo(3)),
                gallery("N74KSK", "sXQz4G", "3BxbFF", daysAgo(1))
            )
        )
        assertTrue("2sDN5x" in lit() && "3BxbFF" in lit())

        val marked = dao.markViewedAtOrBelow("2sDN5x")

        assertEquals("both School galleries, over index edges only (no node rows)", 2, marked)
        val active = lit()
        assertFalse("LCdk7F" in active || "NdSecond" in active || "P4BKB" in active || "2sDN5x" in active)
        assertTrue("the public gallery was never viewed", "sXQz4G" in active && "3BxbFF" in active)
    }

    @Test
    fun oneGalleryViewed_keepsTheFolderLit_whileASiblingIsStillNew() = runBlocking {
        seedTree()
        dao.upsertAlbums(listOf(fGallery(), gallery("AkSecond", "NdSecond", "P4BKB", daysAgo(3))))

        dao.markViewedAtOrBelow("LCdk7F")

        val active = lit()
        assertFalse("LCdk7F" in active)
        assertTrue("NdSecond" in active && "P4BKB" in active && "2sDN5x" in active)
    }

    @Test
    fun folderBulkBumpedDateModified_andANodeRowDateModified_lightNothing() = runBlocking {
        // Real data (idzifamily): a site-wide SmugMug event stamped nearly every folder and gallery
        // node with one recent DateModified (findings #2, #17). Only the index ILU may light a dot.
        val bulk = daysAgo(2)
        dao.insertNodes(
            listOf(
                node("2sDN5x", "4zqWw", "Folder", dateModified = bulk),
                node("P4BKB", "2sDN5x", "Folder", dateModified = bulk),
                node("3BxbFF", "4zqWw", "Folder", dateModified = bulk),
                node("LCdk7F", "P4BKB", "Album", dateModified = bulk)
            )
        )
        dao.upsertAlbums(listOf(fGallery(ilu = daysAgo(60))))

        assertEquals(emptySet<String>(), lit())
    }

    @Test
    fun anotherSitesGallery_lightsNothingOnThisSite_andItsOwnSiteStillSeesIt() = runBlocking {
        seedTree()
        dao.upsertAlbums(listOf(gallery("AkOther1", "NdOther1", "P4BKB", daysAgo(1), nickname = "someoneelse")))

        assertEquals(emptySet<String>(), lit(nick))
        assertTrue("NdOther1" in lit("someoneelse"))
    }

    @Test
    fun raiseAlbumImagesLastUpdated_movesForwardOnly() = runBlocking {
        val original = daysAgo(2)
        val newer = daysAgo(1)
        val older = daysAgo(5)
        dao.upsertAlbums(listOf(fGallery(original)))

        dao.raiseAlbumImagesLastUpdated("FfHCms", older)
        assertEquals("an older date never lowers the index", original, dao.getAlbumByKey("FfHCms")!!.imagesLastUpdated)

        dao.raiseAlbumImagesLastUpdated("FfHCms", newer)
        assertEquals(newer, dao.getAlbumByKey("FfHCms")!!.imagesLastUpdated)
    }

    @Test(timeout = 15_000)
    fun aParentCycle_doesNotHangTheDotQuery() = runBlocking {
        dao.insertNodes(listOf(node("loopA", "loopB", "Folder"), node("loopB", "loopA", "Folder"), node("self1", "self1", "Folder")))
        dao.upsertAlbums(
            listOf(
                fGallery().copy(parentNodeId = "loopA"),
                gallery("AkSelf", "NdSelf", "self1", daysAgo(1))
            )
        )

        val active = lit()
        assertTrue("LCdk7F" in active && "NdSelf" in active)
        assertTrue("walking down a cycle terminates", dao.markViewedAtOrBelow("loopA") >= 1)
    }

    @Test
    fun getAllDescendants_returnsFullSubtree() = runBlocking {
        dao.insertNodes(
            listOf(
                node("root", null, "Folder"),
                node("f1", "root", "Folder"),
                node("f2", "f1", "Folder"),
                node("a1", "f2", "Album"),
                node("sibling", "root", "Folder")
            )
        )
        val descendants = dao.getAllDescendants("f1").map { it.nodeId }.toSet()
        assertEquals(setOf("f2", "a1"), descendants)
    }

    @Test
    fun getAlbumsInScope_returnsOnlyAlbumsBelowScope() = runBlocking {
        dao.insertNodes(
            listOf(
                node("root", null, "Folder"),
                node("f1", "root", "Folder"),
                node("albumUnder", "f1", "Album"),
                node("nestedFolder", "f1", "Folder"),
                node("deepAlbum", "nestedFolder", "Album"),
                node("outsideAlbum", "root", "Album")
            )
        )
        val albums = dao.getAlbumsInScope("f1").map { it.nodeId }.toSet()
        assertEquals(setOf("albumUnder", "deepAlbum"), albums)
    }

    private fun album(key: String, dateModified: String?, nickname: String = "site1") = CachedAlbum(
        albumKey = key,
        nodeId = "node_$key",
        name = key,
        securityType = "None",
        passwordHint = null,
        uri = "/api/v2/album/$key",
        webUri = null,
        urlPath = "/$key",
        imageCount = 1,
        dateModified = dateModified,
        galleryStyle = null,
        highlightImageUrl = null,
        sortIndex = 0,
        nickname = nickname
    )

    @Test
    fun albumIndex_upsertReplacesByKey() = runBlocking {
        dao.upsertAlbums(
            listOf(
                album("a1", "2024-01-01T00:00:00+00:00"),
                album("a2", "2024-06-01T00:00:00+00:00")
            )
        )
        assertEquals(2, dao.getAlbumIndexCount("site1"))

        // Upsert a changed a2 (newer date) + a brand-new a3 — REPLACE keeps the index deduped by key.
        dao.upsertAlbums(
            listOf(
                album("a2", "2025-02-02T00:00:00+00:00"),
                album("a3", "2025-03-03T00:00:00+00:00")
            )
        )
        assertEquals(3, dao.getAlbumIndexCount("site1"))
    }

    @Test
    fun applyCrawl_upsertsAndPrunesInOneCall_beyondSqlitesVariableLimit() = runBlocking {
        // 1,200 stale keys exceed SQLite's 999-variable limit if deleted in one statement.
        val stale = (0 until 1200).map { album("old$it", "2024-01-01T00:00:00+00:00") }
        dao.upsertAlbums(stale + album("keep", "2024-01-01T00:00:00+00:00"))

        dao.applyCrawl(listOf(album("keep", "2025-01-01T00:00:00+00:00"), album("new", "2025-01-01T00:00:00+00:00")), stale.map { it.albumKey })

        assertEquals(listOf("keep", "new"), dao.getAlbumIndex("site1").map { it.albumKey }.sorted())
        assertEquals("2025-01-01T00:00:00+00:00", dao.getAlbumIndex("site1").first { it.albumKey == "keep" }.dateModified)
    }

    @Test
    fun albumIndex_isScopedByNickname() = runBlocking {
        dao.upsertAlbums(
            listOf(
                album("a1", "2024-01-01T00:00:00+00:00", nickname = "site1"),
                album("b1", "2024-01-01T00:00:00+00:00", nickname = "site2")
            )
        )
        assertEquals(setOf("a1"), dao.getAlbumIndex("site1").map { it.albumKey }.toSet())
        assertEquals(setOf("b1"), dao.getAlbumIndex("site2").map { it.albumKey }.toSet())
    }

    // R-02: SmugMug's ParentNode is the node's own "!parent" link, so a parser that trusts it writes a
    // self-parented row (and a mis-parented pair can form a 2-cycle). UNION ALL recursed forever on
    // those; UNION de-duplicates and terminates.
    @Test(timeout = 15_000)
    fun selfParentRow_doesNotHangDescendantQueries() = runBlocking {
        dao.insertNodes(
            listOf(
                node("top", null, "Folder"),
                node("loop", "loop", "Folder"),
                node("loopAlbum", "loop", "Album", dateModified = recent())
            )
        )
        assertEquals(listOf("loopAlbum"), dao.getAllDescendants("loop").map { it.nodeId })
        assertEquals(listOf("loopAlbum"), dao.getAlbumsInScope("loop").map { it.nodeId })
        assertEquals(
            listOf("loopAlbum"),
            dao.searchNodesInScope("loop", "loopAlbum", "Album").map { it.nodeId }
        )
    }

    @Test(timeout = 15_000)
    fun twoNodeParentCycle_doesNotHangDescendantQueries() = runBlocking {
        dao.insertNodes(
            listOf(
                node("a", "b", "Folder"),
                node("b", "a", "Folder"),
                node("leaf", "b", "Album", dateModified = recent())
            )
        )
        assertEquals(setOf("a", "leaf"), dao.getAllDescendants("b").map { it.nodeId }.toSet())
    }

    // R-07 (Phase 2 step 2-4): insertNodes is REPLACE, so a child that vanished server-side stayed in
    // the cache forever. A listing result now replaces the parent's children as one transaction.
    @Test
    fun replaceChildren_dropsAChildTheListingNoLongerHas_keepsTheRest_andOtherParents() = runBlocking {
        dao.insertNodes(
            listOf(
                node("P4BKB", "2sDN5x", "Folder"),
                node("LCdk7F", "P4BKB", "Album", dateModified = recent()),
                node("x2", "P4BKB", "Album", dateModified = recent()),
                node("sXQz4G", "3BxbFF", "Album", dateModified = recent()),
                node("virtual:v1", "P4BKB", "Folder")
            )
        )

        dao.replaceChildren("P4BKB", listOf(node("LCdk7F", "P4BKB", "Album", dateModified = recent()).copy(title = "renamed")))

        val children = dao.getCachedNodesByParent("P4BKB").first()
        assertEquals(listOf("LCdk7F"), children.map { it.nodeId })
        assertEquals("renamed", children.single().title)
        assertEquals(null, dao.getNodeById("x2"))
        // untouched: another parent's child, the parent itself, and a virtual row
        assertEquals("sXQz4G", dao.getNodeById("sXQz4G")?.nodeId)
        assertEquals("P4BKB", dao.getNodeById("P4BKB")?.nodeId)
        assertEquals("virtual:v1", dao.getNodeById("virtual:v1")?.nodeId)
    }

    @Test
    fun replaceChildren_withAnEmptyListing_clearsThatParentOnly() = runBlocking {
        dao.insertNodes(listOf(node("a", "P", "Album"), node("b", "Q", "Album")))
        dao.replaceChildren("P", emptyList())
        assertEquals(emptyList<String>(), dao.getCachedNodesByParent("P").first().map { it.nodeId })
        assertEquals(listOf("b"), dao.getCachedNodesByParent("Q").first().map { it.nodeId })
    }
}
