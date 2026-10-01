package com.smugview.app.diag

import com.smugview.app.data.db.DoctorDao

enum class Severity { OK, INFO, WARN, ERROR }

data class CheckResult(
    val id: String,
    val label: String,
    val count: Int,
    val severity: Severity,
    val ms: Long
)

data class DoctorReport(val checks: List<CheckResult>, val totalMs: Long, val ranDuringSync: Boolean)

/**
 * Read-only invariant checks over the real cache (design phase-1-observability.md §2.5). Each check
 * is one SELECT in [DoctorDao]; nothing here writes. All counts are global; no titles, paths or
 * example ids are produced (owner decision Q4: counts only in Phase 1).
 *
 * [syncRunning] is polled once at the end: if a sync was open the counts may be transient.
 */
class CacheDoctor(
    private val dao: DoctorDao,
    private val syncRunning: () -> Boolean = { false }
) {
    suspend fun run(activeRootId: String?): DoctorReport {
        val start = System.nanoTime()
        val out = ArrayList<CheckResult>()
        val syncAtStart = runCatching { syncRunning() }.getOrDefault(false)

        suspend fun check(id: String, label: String, level: Severity, q: suspend () -> Int) {
            val t0 = System.nanoTime()
            // One failing query must not hide the other checks; -1 marks "query failed".
            val n = runCatching { q() }.getOrElse { -1 }
            val sev = when {
                n < 0 -> Severity.WARN
                n == 0 -> Severity.OK
                else -> level
            }
            out.add(CheckResult(id, label, n, sev, (System.nanoTime() - t0) / 1_000_000))
        }

        check("self_parent", "Nodes whose parent is themselves (R-01)", Severity.ERROR, dao::countSelfParent)
        check("bang_parent", "Nodes whose parent is an 'X!parent' link (R-01)", Severity.ERROR, dao::countBangParent)
        check("bang_parent_index", "Index albums with an 'X!parent' parent", Severity.ERROR, dao::countBangParentIndex)
        check("two_cycle", "Parent cycles of length 2", Severity.ERROR, dao::countTwoCycles)
        check(
            "deep_chain", "Nodes whose parent chain exceeds 32 hops (includes self-parents and cycles)",
            Severity.ERROR, dao::countDeepChain
        )
        check("orphan_rows", "Nodes whose parent row is missing", Severity.WARN, dao::countOrphanRows)
        check("orphan_parents", "Distinct missing parents", Severity.WARN, dao::countOrphanParents)
        check(
            "album_node_not_indexed", "Album nodes missing from the album index",
            Severity.WARN, dao::countAlbumNodeNotIndexed
        )
        check(
            "index_album_no_node", "Index albums with no node row (expected: nodes fill lazily)",
            Severity.INFO, dao::countIndexAlbumNoNode
        )
        check(
            "recent_index_invisible_to_dot", "Recent unviewed index albums with no parent the dot can bubble through",
            Severity.WARN, dao::countRecentIndexInvisibleToDot
        )
        check(
            "index_nodeid_is_albumkey", "Index rows whose nodeId equals their albumKey (fallback id)",
            Severity.WARN, dao::countIndexNodeIdIsAlbumKey
        )
        check("dup_index_nodeid", "Duplicate nodeId in the album index", Severity.ERROR, dao::countDupIndexNodeId)
        check("dup_album_uri", "Duplicate albumUri among nodes", Severity.WARN, dao::countDupAlbumUri)
        check("dup_index_urlpath", "Duplicate urlPath in the album index", Severity.WARN, dao::countDupIndexUrlPath)
        check("empty_nickname_nodes", "Nodes with an empty nickname", Severity.WARN, dao::countEmptyNicknameNodes)
        check("empty_nickname_index", "Index albums with an empty nickname", Severity.WARN, dao::countEmptyNicknameIndex)
        check("index_parent_set", "Index albums with a parent set (R-05: the sync never sets it)", Severity.INFO, dao::countIndexParentSet)
        check("index_null_date", "Index albums with no LastUpdated (#1)", Severity.INFO, dao::countIndexNullDate)
        check(
            "unparseable_album_date", "Album nodes with an unparseable date",
            Severity.WARN, dao::countUnparseableAlbumDate
        )
        check("search_result_parented", "Nodes parented to search_result", Severity.INFO, dao::countSearchResultParented)
        check("rows_cached_nodes", "Rows in cached_nodes", Severity.INFO, dao::countNodes)
        check("rows_cached_albums", "Rows in cached_albums", Severity.INFO, dao::countIndexAlbums)
        check("rows_viewed_gallery_updates", "Rows in viewed_gallery_updates", Severity.INFO, dao::countViewedRows)
        if (activeRootId != null) {
            // Presence, not a violation: 1 = the active site's root node row is cached.
            check("site_root_row", "Active site root row cached (1 = yes)", Severity.INFO) {
                dao.countNodeById(activeRootId)
            }
        }

        val syncAtEnd = runCatching { syncRunning() }.getOrDefault(false)
        return DoctorReport(out, (System.nanoTime() - start) / 1_000_000, syncAtStart || syncAtEnd)
    }
}
