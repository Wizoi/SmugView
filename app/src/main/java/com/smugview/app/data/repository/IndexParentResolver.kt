package com.smugview.app.data.repository

import com.smugview.app.data.db.CachedAlbum
import com.smugview.app.data.db.CollectionDao

/**
 * Gives every gallery in the flat index a parent folder NodeID, locally (design 3.4, review R-05).
 *
 * `user!albums` has no parent link at all; what it does carry is the containing folder's path
 * (`Uris.Folder`, V4) and the gallery's `UrlPath`. A folder's `WebUri` path equals its `UrlPath` (V5),
 * so a gallery's folder path can be matched against the folder rows already in `cached_nodes`.
 *
 *  - The path comes from the crawl when the gallery was in it (fresh, so it may also correct a stale
 *    parent), else from `urlPath` minus its last segment (only fills a parent that is still null).
 *  - `""` is the site root ([rootNodeId]; the root has no row of its own to match).
 *  - A recent gallery (ILU within 30 days) that still has no parent is the case where a folder is
 *    new and not cached yet: the nearest *matched* ancestor path is relisted (forced, through
 *    [relist]) and the match retried, at most [MAX_DEPTH] rounds and within [budget] folders.
 *  - It never calls `folder/...`: the tree sync already lists every reachable folder (V9).
 */
internal class IndexParentResolver(
    private val dao: CollectionDao,
    private val clock: () -> Long,
    /** Forced relist of one folder; false when it could not be listed (locked, offline). */
    private val relist: suspend (folderId: String) -> Boolean
) {
    class Result(val resolved: Int, val unresolved: Int, val relisted: Set<String>)

    suspend fun resolve(
        nickname: String,
        rootNodeId: String?,
        crawlFolderPaths: Map<String, String?> = emptyMap(),
        budget: Int = SmugMugRepository.MAX_RELIST,
        alreadyRelisted: Set<String> = emptySet()
    ): Result {
        val rows = dao.getAlbumIndex(nickname)
        val parents = HashMap<String, String?>(rows.size * 2)
        val paths = HashMap<String, String?>(rows.size * 2)
        val fresh = HashSet<String>()
        for (row in rows) {
            parents[row.albumKey] = row.parentNodeId
            val crawled = crawlFolderPaths[row.albumKey]
            if (crawled != null) fresh.add(row.albumKey)
            paths[row.albumKey] = crawled ?: folderPathOfUrlPath(row.urlPath)
        }
        val original = HashMap(parents)

        var folders = folderIndex(nickname, rootNodeId)
        match(rows, paths, fresh, folders, parents)

        val relisted = LinkedHashSet<String>()
        val horizon = clock() - RECENT_DAYS * 86_400_000L
        val recent = rows.filter { r ->
            val t = GalleryCrawl.isoMillis(r.imagesLastUpdated)
            t != null && t >= horizon && paths[r.albumKey] != null
        }
        for (round in 0 until MAX_DEPTH) {
            val missing = recent.filter { parents[it.albumKey] == null }
            if (missing.isEmpty()) break
            val targets = LinkedHashSet<String>()
            for (r in missing) {
                nearestMatchedAncestor(paths[r.albumKey]!!, folders)
                    ?.takeIf { it !in relisted && it !in alreadyRelisted }
                    ?.let { targets.add(it) }
            }
            if (targets.isEmpty()) break
            var listedAny = false
            for (id in targets) {
                if (relisted.size >= budget) break
                if (relist(id)) {
                    relisted.add(id)
                    listedAny = true
                }
            }
            if (!listedAny) break
            folders = folderIndex(nickname, rootNodeId)
            match(rows, paths, fresh, folders, parents)
        }

        val changes = parents.filter { (key, parent) -> parent != null && parent != original[key] }
            .mapValues { it.value!! }
        if (changes.isNotEmpty()) dao.applyAlbumParents(changes)
        return Result(changes.size, parents.values.count { it == null }, relisted)
    }

    private fun match(
        rows: List<CachedAlbum>,
        paths: Map<String, String?>,
        fresh: Set<String>,
        folders: Map<String, String>,
        parents: MutableMap<String, String?>
    ) {
        for (row in rows) {
            val path = paths[row.albumKey] ?: continue
            val id = folders[path.lowercase()] ?: continue
            if (parents[row.albumKey] == null || row.albumKey in fresh) parents[row.albumKey] = id
        }
    }

    /** Folder path (lower case, no trailing slash, `""` = the site root) to NodeID. */
    private suspend fun folderIndex(nickname: String, rootNodeId: String?): Map<String, String> {
        val map = HashMap<String, String>()
        for (n in dao.getCachedNodesForNickname(nickname)) {
            if (n.type != "Folder") continue
            val path = pathOfWebUri(n.webUri) ?: continue
            map[path.lowercase()] = n.nodeId
        }
        if (rootNodeId != null) map[""] = rootNodeId
        return map
    }

    private fun nearestMatchedAncestor(folderPath: String, folders: Map<String, String>): String? {
        var p = folderPath
        while (true) {
            folders[p.lowercase()]?.let { return it }
            if (p.isEmpty()) return null
            p = p.substringBeforeLast('/', "")
        }
    }

    companion object {
        const val MAX_DEPTH = 8
        const val RECENT_DAYS = 30L

        /** `https://host/Family/School/` to `/Family/School`; the host alone to `""`. */
        fun pathOfWebUri(webUri: String?): String? {
            if (webUri.isNullOrBlank()) return null
            return try {
                java.net.URI(webUri).path.orEmpty().trimEnd('/')
            } catch (e: Exception) {
                null
            }
        }

        /** `/Family/School/Gallery` to `/Family/School`; a root-level gallery to `""`. */
        fun folderPathOfUrlPath(urlPath: String?): String? {
            val p = urlPath?.trimEnd('/')?.takeIf { it.startsWith("/") } ?: return null
            return p.substringBeforeLast('/', "")
        }
    }
}
