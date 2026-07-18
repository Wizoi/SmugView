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

    @Test
    fun activeUpdate_bubblesFromAlbumUpToAllAncestors() = runBlocking {
        // root -> folderA -> album1 (recently modified)
        dao.insertNodes(
            listOf(
                node("root", null, "Folder"),
                node("folderA", "root", "Folder"),
                node("album1", "folderA", "Album", dateModified = recent())
            )
        )

        val active = dao.getNodesWithActiveUpdates().first().toSet()
        // The album AND every ancestor folder bubble up.
        assertTrue(active.contains("album1"))
        assertTrue(active.contains("folderA"))
        assertTrue(active.contains("root"))
    }

    @Test
    fun oldModification_isNotActive() = runBlocking {
        dao.insertNodes(
            listOf(
                node("root", null, "Folder"),
                node("albumOld", "root", "Album", dateModified = old())
            )
        )
        val active = dao.getNodesWithActiveUpdates().first().toSet()
        assertFalse(active.contains("albumOld"))
        assertFalse(active.contains("root"))
    }

    @Test
    fun markingViewed_clearsActiveState_andReTriggersOnNewerModification() = runBlocking {
        val modified = recent()
        dao.insertNodes(
            listOf(
                node("root", null, "Folder"),
                node("album2", "root", "Album", dateModified = modified)
            )
        )
        assertTrue(dao.getNodesWithActiveUpdates().first().contains("album2"))

        // User views it: record the modification date they saw.
        dao.insertViewedUpdates(listOf(ViewedGalleryUpdate("album2", modified)))
        assertFalse(
            "Viewing the current modification should clear the active flag",
            dao.getNodesWithActiveUpdates().first().contains("album2")
        )

        // A NEWER modification arrives -> becomes active again.
        val newer = java.time.OffsetDateTime.now().minusDays(1).toString()
        dao.insertNodes(listOf(node("album2", "root", "Album", dateModified = newer)))
        assertTrue(
            "A newer modification than last-viewed should re-trigger",
            dao.getNodesWithActiveUpdates().first().contains("album2")
        )
    }

    @Test
    fun folderWithOwnRecentModification_clearsWhenItsOnlyGalleryIsViewed() = runBlocking {
        val modified = recent()
        // A folder whose own dateModified is recent (its contents changed) containing one album.
        dao.insertNodes(
            listOf(
                node("root", null, "Folder", dateModified = recent()),
                node("folderB", "root", "Folder", dateModified = modified),
                node("albumX", "folderB", "Album", dateModified = modified)
            )
        )
        assertTrue(dao.getNodesWithActiveUpdates().first().contains("folderB"))

        // Viewing only the gallery must also clear the folder — the folder's own recent
        // dateModified must NOT keep a dot on it forever.
        dao.insertViewedUpdates(listOf(ViewedGalleryUpdate("albumX", modified)))
        val active = dao.getNodesWithActiveUpdates().first().toSet()
        assertFalse("folder should clear once its only updated gallery is viewed", active.contains("folderB"))
        assertFalse(active.contains("albumX"))
        assertFalse(active.contains("root"))
    }

    // Mimics repository.markFolderVisited: mark the folder + its direct Album children as viewed.
    private suspend fun enterFolder(folderId: String) {
        val direct = dao.getDirectChildren(folderId).filter { it.type == "Album" }
        val self = dao.getNodeById(folderId)
        val updates = (direct + listOfNotNull(self)).mapNotNull { n ->
            n.dateModified?.let { ViewedGalleryUpdate(n.nodeId, it) }
        }
        if (updates.isNotEmpty()) dao.insertViewedUpdates(updates)
    }

    @Test
    fun enteringLeafFolder_marksDirectGalleries_clearsFolderDot() = runBlocking {
        val m = recent()
        dao.insertNodes(
            listOf(
                node("root", null, "Folder", dateModified = m),
                node("folderF", "root", "Folder", dateModified = m),
                node("g1", "folderF", "Album", dateModified = m),
                node("g2", "folderF", "Album", dateModified = m)
            )
        )
        assertTrue(dao.getNodesWithActiveUpdates().first().contains("folderF"))

        enterFolder("folderF")

        val active = dao.getNodesWithActiveUpdates().first().toSet()
        assertFalse("entering a leaf folder clears its dot", active.contains("folderF"))
        assertFalse(active.contains("g1"))
        assertFalse(active.contains("g2"))
    }

    @Test
    fun enteringFolder_leavesNestedSubfolderGalleryDot() = runBlocking {
        val m = recent()
        dao.insertNodes(
            listOf(
                node("folderF", null, "Folder", dateModified = m),
                node("g1", "folderF", "Album", dateModified = m),
                node("sub", "folderF", "Folder", dateModified = m),
                node("g2", "sub", "Album", dateModified = m)
            )
        )
        // Entering folderF marks only its direct gallery (g1), not the nested g2.
        enterFolder("folderF")

        val active = dao.getNodesWithActiveUpdates().first().toSet()
        assertFalse("directly-marked gallery clears", active.contains("g1"))
        assertTrue("nested unviewed gallery keeps its own dot", active.contains("g2"))
        assertTrue("...and keeps the ancestor folder's dot until that subfolder is entered", active.contains("folderF"))
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
    fun albumIndex_upsertReplacesByKey_andLatestDateDrivesIncrementalStop() = runBlocking {
        dao.upsertAlbums(
            listOf(
                album("a1", "2024-01-01T00:00:00+00:00"),
                album("a2", "2024-06-01T00:00:00+00:00")
            )
        )
        assertEquals(2, dao.getAlbumIndexCount("site1"))
        // The stop-marker for the incremental sync is the newest LastUpdated we hold.
        assertEquals("2024-06-01T00:00:00+00:00", dao.getLatestAlbumDateModified("site1"))

        // Upsert a changed a2 (newer date) + a brand-new a3 — REPLACE keeps the index deduped by key.
        dao.upsertAlbums(
            listOf(
                album("a2", "2025-02-02T00:00:00+00:00"),
                album("a3", "2025-03-03T00:00:00+00:00")
            )
        )
        assertEquals(3, dao.getAlbumIndexCount("site1"))
        assertEquals("2025-03-03T00:00:00+00:00", dao.getLatestAlbumDateModified("site1"))
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
}
