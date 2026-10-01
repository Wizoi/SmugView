package com.smugview.app.diag

import com.smugview.app.data.db.AppDatabase
import com.smugview.app.data.db.CachedAlbum
import com.smugview.app.data.db.CachedNode
import com.smugview.app.data.db.CollectionDao
import com.smugview.app.data.db.TestDb
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Runs the real doctor SQL on real Room. The fixture mirrors the real topology (site root ->
 * password folder 2sDN5x -> sub-folder P4BKB -> gallery NodeID LCdk7F / AlbumKey FfHCms, so
 * AlbumKey != NodeID) plus one example of each invariant violation.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class CacheDoctorTest {
    private lateinit var db: AppDatabase
    private lateinit var dao: CollectionDao

    @Before fun setUp() { db = TestDb.inMemory(); dao = db.collectionDao() }
    @After fun tearDown() { db.close() }

    /** SmugMug's date shape, e.g. 2026-09-12T07:38:11+00:00, relative to now (findings #9). */
    private fun daysAgo(days: Long): String = OffsetDateTime.now(ZoneOffset.UTC).minusDays(days)
        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssxxx"))

    private fun node(
        id: String, parent: String?, type: String = "Folder", date: String? = null, nickname: String = "nick",
        albumUri: String? = if (type == "Album") "/api/v2/album/K-$id" else null
    ) = CachedNode(
        nodeId = id, parentNodeId = parent, type = type, title = "t-$id", description = null,
        access = "Public", passwordHint = null, uri = "/api/v2/node/$id", childNodesUri = null,
        albumUri = albumUri, dateModified = date, nickname = nickname
    )

    private fun album(
        key: String, nodeId: String, date: String?, nickname: String = "nick",
        urlPath: String? = "/p/$key", parent: String? = null
    ) = CachedAlbum(
        albumKey = key, nodeId = nodeId, name = "n-$key", securityType = "Public", passwordHint = null,
        uri = "/api/v2/album/$key", webUri = null, urlPath = urlPath, imageCount = 3, dateModified = date,
        galleryStyle = null, highlightImageUrl = null, nickname = nickname, parentNodeId = parent,
        imagesLastUpdated = date
    )

    private suspend fun seedRealShape() {
        dao.insertNodes(
            listOf(
                node("SITE01", null),                              // site root, parent NULL
                node("2sDN5x", "SITE01"),                          // password folder
                node("P4BKB", "2sDN5x"),                           // sub-folder
                node("LCdk7F", "P4BKB", "Album", daysAgo(2)),      // gallery (AlbumKey FfHCms)
                node("OKNODE", "SITE01", "Album", daysAgo(100)),   // healthy, indexed
                node("SAMEID", "SITE01", "Album", daysAgo(90)),    // index row with nodeId == albumKey
                node("SENT01", "root"),                            // sentinel parent: not an orphan
                node("SRCH01", "search_result"),                   // search result row
                node("SELF01", "SELF01"),                          // self-parent (R-01)
                node("BANG01", "BANG01!parent"),                   // X!parent (R-01)
                node("ORPH01", "MISSING1"),                        // orphan under a missing parent
                node("MISSAL", "SITE01", "Album", "not-a-date"),   // album node missing from the index
                node("CYC_A", "CYC_B"), node("CYC_B", "CYC_A")     // 2-cycle
            )
        )
        dao.upsertAlbums(
            listOf(
                album("FfHCms", "LCdk7F", daysAgo(2)),
                album("OkAlbm", "OKNODE", daysAgo(100)),
                album("SAMEID", "SAMEID", null),                   // nodeId == albumKey, null date
                album("PubKey", "PUBN01", daysAgo(3))              // recent, no node: invisible to the dot
            )
        )
    }

    private fun DoctorReport.count(id: String): Int = checks.firstOrNull { it.id == id }?.count ?: -1
    private fun DoctorReport.severity(id: String): Severity? = checks.firstOrNull { it.id == id }?.severity

    private fun totalChanges(): Long = db.openHelper.writableDatabase.query("SELECT total_changes()").use {
        it.moveToFirst(); it.getLong(0)
    }

    @Test
    fun realShapedFixture_countsEachInvariantExactly_andNeverWrites() = runBlocking {
        seedRealShape()
        val doctor = CacheDoctor(db.doctorDao())
        val before = totalChanges()

        val r = doctor.run(activeRootId = "SITE01")

        assertEquals("self_parent", 1, r.count("self_parent"))
        assertEquals("bang_parent", 1, r.count("bang_parent"))
        assertEquals("bang_parent_index", 0, r.count("bang_parent_index"))
        assertEquals("two_cycle", 1, r.count("two_cycle"))
        // self-parent + both cycle members never reach a root
        assertEquals("deep_chain", 3, r.count("deep_chain"))
        assertEquals("orphan_rows", 1, r.count("orphan_rows"))
        assertEquals("orphan_parents", 1, r.count("orphan_parents"))
        assertEquals("album_node_not_indexed", 1, r.count("album_node_not_indexed"))
        assertEquals("index_album_no_node", 1, r.count("index_album_no_node"))
        assertEquals("recent_index_invisible_to_dot", 1, r.count("recent_index_invisible_to_dot"))
        assertEquals("index_nodeid_is_albumkey", 1, r.count("index_nodeid_is_albumkey"))
        assertEquals("dup_index_nodeid", 0, r.count("dup_index_nodeid"))
        assertEquals("dup_album_uri", 0, r.count("dup_album_uri"))
        assertEquals("dup_index_urlpath", 0, r.count("dup_index_urlpath"))
        assertEquals("empty_nickname_nodes", 0, r.count("empty_nickname_nodes"))
        assertEquals("empty_nickname_index", 0, r.count("empty_nickname_index"))
        assertEquals("index_parent_set", 0, r.count("index_parent_set"))
        assertEquals("index_null_date", 1, r.count("index_null_date"))
        assertEquals("unparseable_album_date", 1, r.count("unparseable_album_date"))
        assertEquals("search_result_parented", 1, r.count("search_result_parented"))
        assertEquals("rows_cached_nodes", 14, r.count("rows_cached_nodes"))
        assertEquals("rows_cached_albums", 4, r.count("rows_cached_albums"))
        assertEquals("rows_viewed_gallery_updates", 0, r.count("rows_viewed_gallery_updates"))
        assertEquals("site_root_row", 1, r.count("site_root_row"))

        assertEquals(Severity.ERROR, r.severity("self_parent"))
        assertEquals(Severity.WARN, r.severity("orphan_rows"))
        assertEquals(Severity.OK, r.severity("bang_parent_index"))
        assertEquals(before, totalChanges())
    }

    @Test
    fun recentIndexInvisibleToDot_meansNoParentToBubbleThrough_notNoNodeRow() = runBlocking {
        // Phase 2 (design 3.5): the dot seeds from the index alone, so a gallery without a cached_nodes
        // row is visible when the index knows its parent. Old definition: "no node row" (counted both).
        dao.insertNodes(listOf(node("P4BKB", "2sDN5x"), node("NODEPAR", "P4BKB", "Album", daysAgo(2))))
        dao.upsertAlbums(
            listOf(
                album("IdxPar", "NDPAR1", daysAgo(2), parent = "P4BKB"),      // no node row, parent known: visible
                album("NodePar", "NODEPAR", daysAgo(2)),                      // parent from its node row: visible
                album("NoPar", "NDNOPAR", daysAgo(2)),                        // no parent anywhere: invisible
                album("Viewed", "NDVIEW1", daysAgo(2))                        // no parent, but already viewed
            )
        )
        dao.insertViewedUpdates(listOf(com.smugview.app.data.db.ViewedGalleryUpdate("NDVIEW1", daysAgo(2))))

        assertEquals(1, CacheDoctor(db.doctorDao()).run(null).count("recent_index_invisible_to_dot"))
    }

    @Test
    fun missingSiteRootRow_isReportedAsZero() = runBlocking {
        seedRealShape()
        assertEquals(0, CacheDoctor(db.doctorDao()).run("NOSUCH").count("site_root_row"))
        // no active site: the check is simply absent
        assertEquals(-1, CacheDoctor(db.doctorDao()).run(null).count("site_root_row"))
    }

    @Test
    fun duplicatesAndEmptyNicknames_areCounted() = runBlocking {
        dao.insertNodes(
            listOf(
                node("A1", null, "Album", albumUri = "/api/v2/album/SAME", nickname = ""),
                node("A2", null, "Album", albumUri = "/api/v2/album/SAME")
            )
        )
        dao.upsertAlbums(
            listOf(
                album("K1", "N1", daysAgo(1), nickname = "", urlPath = "/same"),
                album("K2", "N1", daysAgo(1), urlPath = "/same", parent = "ABC!parent"),
                album("K3", "N3", daysAgo(1), urlPath = "/same", parent = "P1")
            )
        )
        val r = CacheDoctor(db.doctorDao()).run(null)
        assertEquals(1, r.count("dup_album_uri"))
        assertEquals(1, r.count("dup_index_nodeid"))
        assertEquals(1, r.count("dup_index_urlpath")) // K2 + K3 share (nick, /same); K1 has nickname ''
        assertEquals(1, r.count("empty_nickname_nodes"))
        assertEquals(1, r.count("empty_nickname_index"))
        assertEquals(1, r.count("bang_parent_index"))
        assertEquals(2, r.count("index_parent_set"))
    }

    @Test
    fun runDuringSync_isFlagged() = runBlocking {
        assertTrue(CacheDoctor(db.doctorDao(), syncRunning = { true }).run(null).ranDuringSync)
    }

    @Test
    fun fiveThousandRowFixture_runsUnderOneSecond() = runBlocking {
        val nodes = (0 until 5000).map {
            node("N$it", if (it == 0) null else "N${it / 2}", "Album", daysAgo((it % 40).toLong()))
        }
        dao.insertNodes(nodes)
        dao.upsertAlbums((0 until 2000).map { album("K$it", "N$it", daysAgo((it % 40).toLong())) })
        val t0 = System.nanoTime()
        CacheDoctor(db.doctorDao()).run("N0")
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue("doctor took ${ms}ms", ms < 1000)
    }
}
