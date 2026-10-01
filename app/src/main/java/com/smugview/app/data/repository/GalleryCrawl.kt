package com.smugview.app.data.repository

import com.smugview.app.data.api.AlbumDetails
import com.smugview.app.data.api.SmugMugApi
import com.smugview.app.data.db.CachedAlbum
import com.smugview.app.data.db.CollectionDao
import kotlinx.coroutines.delay

/**
 * The gallery crawl (design 3.3): read EVERY page of `user!albums`, then write all or nothing.
 *
 * `user!albums` is not sorted by any date it returns, so no early stop is safe (findings #14), and
 * `Pages.NextPage` drops `_expand` and `_verbosity` (V3), so the page URLs are built here from the
 * typed call (`start = 1, 101, ...`) and every page carries the same query. Pages accumulate in
 * memory; any failure throws before [write] runs, so a partial listing never reaches the index.
 */
internal class GalleryCrawl(
    private val api: SmugMugApi,
    private val dao: CollectionDao,
    private val pageDelayMs: Long = 100L
) {
    /** One listed gallery plus what the listing expanded for it. */
    class Crawled(
        val album: AlbumDetails,
        val highlightUrl: String?,
        /** `Uris.Folder` minus `/api/v2/folder/user/{nick}` (`""` = the site root), for the resolver (2-9). */
        val folderPath: String?
    )

    class Fetched(
        val albums: List<Crawled>,
        val pages: Int,
        val nullLastUpdated: Int,
        val passwordSecurity: Int
    ) {
        /** AlbumKey to its folder path (`Uris.Folder`), for the resolver (design 3.4). */
        val folderPaths: Map<String, String?> by lazy { albums.associate { it.album.albumKey to it.folderPath } }
    }

    class Written(val changed: List<CachedAlbum>, val pruned: Int, val pruneSkipped: Int)

    /** Fetches every page. Throws on any failure (IO, HTTP, parse); [onPage] reports progress. */
    suspend fun fetchAll(
        nickname: String,
        apiKey: String,
        onPage: (pages: Int, seen: Int, nullLastUpdated: Int, passwordSecurity: Int) -> Unit = { _, _, _, _ -> }
    ): Fetched {
        val all = ArrayList<Crawled>()
        var start = 1
        var pages = 0
        var nullLastUpdated = 0
        var passwordSecurity = 0
        while (true) {
            val response = api.getUserAlbums(nickname, apiKey, count = PAGE_SIZE, start = start, cacheControl = "no-cache")
            val albums = response.response.albums ?: emptyList()
            val expansions = response.expansions
            pages++
            for (album in albums) {
                val highlightUrl = album.uris?.highlightImage?.let { expansions?.get(it)?.image?.thumbnailUrl }
                    ?.replace("/Th/", "/M/")?.replace("/th/", "/m/")
                    ?.replace("-Th.", "-M.")?.replace("-th.", "-m.")
                all.add(Crawled(album, highlightUrl, folderPathOf(album.uris?.folder, nickname)))
            }
            nullLastUpdated += albums.count { it.dateModified == null }
            passwordSecurity += albums.count { it.securityType == "Password" }
            onPage(pages, all.size, nullLastUpdated, passwordSecurity)

            val total = response.response.pages?.total
            val done = albums.isEmpty() ||
                (total != null && start + PAGE_SIZE > total) ||
                (total == null && albums.size < PAGE_SIZE)
            if (done) break
            if (pages >= MAX_PAGES) throw IllegalStateException("PageCap")
            start += PAGE_SIZE
            if (pageDelayMs > 0) delay(pageDelayMs)
        }
        return Fetched(all, pages, nullLastUpdated, passwordSecurity)
    }

    /**
     * Writes the crawl in one transaction. Per gallery: `imagesLastUpdated = MAX(existing, crawled)`
     * (it never moves back), `dateModified = LastUpdated`, `parentNodeId` kept (the resolver owns it).
     * When [prune] is set, index rows of [nickname] the listing did not contain are deleted, unless
     * that is more than `max(20, 5%)` of the index: then the prune is skipped and reported
     * (an expired session lists only the anonymous galleries, and must not read as a mass delete).
     * [Written.changed] is the galleries new to the index or whose ILU moved forward.
     */
    suspend fun write(nickname: String, crawled: List<Crawled>, prune: Boolean): Written {
        val existing = dao.getAlbumIndex(nickname).associateBy { it.albumKey }
        val seen = HashSet<String>(crawled.size * 2)
        val upserts = ArrayList<CachedAlbum>(crawled.size)
        val changed = ArrayList<CachedAlbum>()
        for ((index, c) in crawled.withIndex()) {
            val a = c.album
            val old = existing[a.albumKey]
            seen.add(a.albumKey)
            val ilu = maxIso(old?.imagesLastUpdated, a.imagesLastUpdated)
            val row = CachedAlbum(
                albumKey = a.albumKey,
                nodeId = a.nodeId ?: old?.nodeId ?: a.albumKey,
                name = a.name,
                securityType = a.securityType,
                passwordHint = a.passwordHint,
                uri = a.uri,
                webUri = a.webUri,
                urlPath = a.urlPath,
                imageCount = a.imageCount,
                dateModified = a.dateModified,
                galleryStyle = a.galleryStyle,
                highlightImageUrl = c.highlightUrl ?: old?.highlightImageUrl,
                sortIndex = index,
                nickname = nickname,
                parentNodeId = old?.parentNodeId,
                imagesLastUpdated = ilu
            )
            upserts.add(row)
            val moved = old?.imagesLastUpdated != null && isoMillis(ilu) != null && isoMillis(old.imagesLastUpdated) != null &&
                isoMillis(ilu)!! > isoMillis(old.imagesLastUpdated)!!
            if (old == null || moved) changed.add(row)
        }

        var pruneKeys = emptyList<String>()
        var pruneSkipped = 0
        if (prune) {
            val own = existing.values.filter { it.nickname == nickname }
            val unseen = own.filter { it.albumKey !in seen }.map { it.albumKey }
            if (unseen.size > maxOf(PRUNE_FLOOR, own.size * PRUNE_PERCENT / 100)) pruneSkipped = unseen.size
            else pruneKeys = unseen
        }
        dao.applyCrawl(upserts, pruneKeys)
        return Written(changed, pruneKeys.size, pruneSkipped)
    }

    companion object {
        const val PAGE_SIZE = 100
        const val MAX_PAGES = 200
        const val PRUNE_FLOOR = 20
        const val PRUNE_PERCENT = 5

        fun folderPathOf(folderUri: String?, nickname: String): String? {
            val prefix = "/api/v2/folder/user/$nickname"
            return folderUri?.takeIf { it.startsWith(prefix, ignoreCase = true) }?.substring(prefix.length)?.trimEnd('/')
        }

        fun isoMillis(s: String?): Long? = s?.let {
            try { java.time.OffsetDateTime.parse(it).toInstant().toEpochMilli() } catch (e: Exception) { null }
        }

        /** The later of two ISO-8601 instants; a value that does not parse loses to one that does. */
        fun maxIso(a: String?, b: String?): String? {
            if (a == null) return b
            if (b == null) return a
            val ma = isoMillis(a)
            val mb = isoMillis(b)
            return when {
                ma != null && mb != null -> if (mb > ma) b else a
                ma != null -> a
                mb != null -> b
                else -> if (b > a) b else a
            }
        }
    }
}
