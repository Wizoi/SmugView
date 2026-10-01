package com.smugview.app.data.repository
import com.smugview.app.util.SmugLog

import com.smugview.app.data.api.AlbumImageData
import com.smugview.app.data.api.AlbumDetails
import com.smugview.app.data.api.AlbumImagesResponse
import com.smugview.app.data.api.canonicalImageKey
import com.smugview.app.data.api.isVideo
import com.smugview.app.data.api.ImageSearchResponse
import com.smugview.app.data.api.ExifData
import com.smugview.app.data.api.SmugMugApi
import com.smugview.app.data.api.NodeData
import com.smugview.app.data.api.Page
import com.smugview.app.data.api.Pager
import com.smugview.app.data.api.toPage
import com.smugview.app.data.api.ParentNodeData
import com.smugview.app.data.api.UserSearchResponse
import com.smugview.app.data.api.UserData
import com.smugview.app.data.db.CachedNode
import com.smugview.app.data.db.CachedAlbum
import com.smugview.app.data.db.ViewedGalleryUpdate
import com.smugview.app.data.db.CollectionDao
import com.smugview.app.data.db.CollectionPhoto
import com.smugview.app.data.db.CollectionBookmark
import com.smugview.app.data.db.OfflineCollection
import com.smugview.app.data.db.SearchHistory
import com.smugview.app.data.db.SearchResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import okhttp3.ResponseBody.Companion.toResponseBody
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext

@Singleton
class SmugMugRepository @Inject constructor(
    private val api: SmugMugApi,
    private val dao: CollectionDao,
    private val passwordStore: com.smugview.app.data.security.PasswordStore,
    @ApplicationContext private val context: Context,
    private val syncReporter: com.smugview.app.diag.SyncReporter = com.smugview.app.diag.SyncReporter.NOOP,
    private val syncState: SyncStateStore = InMemorySyncStateStore()
) : UnlockIo {
    /**
     * The one owner of unlock sessions per password root (design phase-3 3.4). A singleton like the
     * cookie jar it describes; the repository is its [UnlockIo].
     */
    val unlocks: UnlockManager by lazy { UnlockManager(io = this, store = passwordStore) }

    /**
     * Upper bound on how many pages the "follow next-url" pagination loops will fetch.
     * Unbounded in production; tests set a small value so they don't page through a fake's whole
     * dataset. Replaces the former `companion object { var isTesting }` static, which shipped a
     * test-only branch in production control flow.
     */
    var maxPagesPerFetch: Int = Int.MAX_VALUE

    private val nodeLocks = ConcurrentHashMap<String, Mutex>()

    // Root node IDs are stable per site; memoize to avoid re-fetching the user profile
    // on every search / tag-scan that resolves an "Entire Site" scope. Cleared by clearEntireCache().
    private val rootNodeIdCache = ConcurrentHashMap<String, String>()

    /** Stamps [nickname], the site of the work that issued the write, onto nodes before persisting them (R-10). */
    private suspend fun insertNodesScoped(nickname: String, nodes: List<CachedNode>) {
        val scoped = if (nickname.isEmpty()) {
            nodes
        } else {
            nodes.map { if (it.nickname == nickname) it else it.copy(nickname = nickname) }
        }
        dao.insertNodes(scoped)
    }

    /** A listing result for [parentId]: replaces that parent's cached children (R-07), stamped with [nickname]. */
    private suspend fun replaceChildrenScoped(nickname: String, parentId: String, nodes: List<CachedNode>) {
        val scoped = if (nickname.isEmpty()) nodes else nodes.map { if (it.nickname == nickname) it else it.copy(nickname = nickname) }
        dao.replaceChildren(parentId, scoped)
    }

    /**
     * The in-memory gallery index of ONE site: the snapshot carries its nickname, so a reader asking
     * for another site's index gets nothing instead of the previous site's galleries (design 3.1).
     */
    data class AlbumIndexSnapshot(val nickname: String, val nodes: List<CachedNode>)

    private val _albumIndex = kotlinx.coroutines.flow.MutableStateFlow(AlbumIndexSnapshot("", emptyList()))
    val albumIndex: kotlinx.coroutines.flow.StateFlow<AlbumIndexSnapshot> = _albumIndex

    /** The in-memory gallery index of [nickname]; empty when the held snapshot belongs to another site. */
    fun albumsCacheFor(nickname: String): List<CachedNode> =
        _albumIndex.value.let { if (it.nickname == nickname) it.nodes else emptyList() }

    private val _isAlbumsCacheLoaded = kotlinx.coroutines.flow.MutableStateFlow(false)
    val isAlbumsCacheLoaded: kotlinx.coroutines.flow.StateFlow<Boolean> = _isAlbumsCacheLoaded

    /**
     * True while any [unlockAndIndexSubtree] walk is actively writing to [albumIndex] /
     * `cached_nodes` in the background. A counter (not a plain boolean) because more than one
     * unlock can be in flight — the flag should only drop once the LAST one finishes, not the
     * first.
     *
     * Exists so a foreground read that depends on the cache being complete (e.g. gallery search
     * matching `albumsCacheFor(nickname)` right after the user unlocks a folder) can wait for the
     * background writer to finish instead of racing it and silently caching/showing incomplete
     * results — see the "search reads incomplete data mid-background-sync" fix in AGENTS.md.
     */
    private val activeSubtreeIndexJobs = java.util.concurrent.atomic.AtomicInteger(0)
    private val _isIndexingSubtree = kotlinx.coroutines.flow.MutableStateFlow(false)
    val isIndexingSubtree: kotlinx.coroutines.flow.StateFlow<Boolean> = _isIndexingSubtree

    // Helper to parse NodeID from Uri path
    fun parseNodeIdFromUri(uri: String): String {
        return uri.substringAfterLast("/").substringBefore("!")
    }

    fun getUserProfile(nickname: String, apiKey: String, ignoreErrors: String? = null): Flow<Result<com.smugview.app.data.api.UserData>> = flow {
        if (com.smugview.app.BuildConfig.DEBUG) {
            android.util.Log.d("SmugMugRepository", "getUserProfile called: nickname=$nickname, ignoreErrors=$ignoreErrors")
        }
        try {
            val userResponse = api.getUserProfile(nickname, apiKey, ignoreErrors = ignoreErrors)
            val bioImageKey = userResponse.expansions?.values?.firstOrNull { it.bioImage != null }?.bioImage?.imageKey
            val enrichedUser = userResponse.response.user.copy(bioImageKey = bioImageKey)
            if (com.smugview.app.BuildConfig.DEBUG) {
                android.util.Log.d("SmugMugRepository", "getUserProfile success: nickname=$nickname")
            }
            emit(Result.success(enrichedUser))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (com.smugview.app.BuildConfig.DEBUG) {
                android.util.Log.e("SmugMugRepository", "getUserProfile failed: nickname=$nickname", e)
            }
            emit(Result.failure(e))
        }
    }.flowOn(Dispatchers.IO)

    // Resolves nickname to root node ID
    fun getUserRootNodeId(nickname: String, apiKey: String): Flow<Result<String>> = flow {
        if (com.smugview.app.BuildConfig.DEBUG) {
            android.util.Log.d("SmugMugRepository", "getUserRootNodeId called: nickname=$nickname")
        }
        rootNodeIdCache[nickname]?.let {
            emit(Result.success(it))
            return@flow
        }
        try {
            val response = api.getUserProfile(nickname, apiKey)
            val nodeUri = response.response.user.uris.node
            val nodeId = parseNodeIdFromUri(nodeUri)
            rootNodeIdCache[nickname] = nodeId
            if (com.smugview.app.BuildConfig.DEBUG) {
                android.util.Log.d("SmugMugRepository", "getUserRootNodeId success: nickname=$nickname, nodeId=$nodeId")
            }
            emit(Result.success(nodeId))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (com.smugview.app.BuildConfig.DEBUG) {
                android.util.Log.e("SmugMugRepository", "getUserRootNodeId failed: nickname=$nickname", e)
            }
            emit(Result.failure(e))
        }
    }.flowOn(Dispatchers.IO)

    // Fetches children nodes (with offline cache boundary)
    suspend fun clearEntireCache() {
        rootNodeIdCache.clear()
        dao.clearAllCachedNodes()
        dao.clearAllSearchHistory()
    }

    fun getNodeChildren(
        nickname: String,
        nodeId: String,
        apiKey: String,
        forceRefresh: Boolean = false,
        password: String? = null,
        ignoreErrors: String? = null
    ): Flow<Result<List<CachedNode>>> = flow<Result<List<CachedNode>>> {
        if (com.smugview.app.BuildConfig.DEBUG) {
            android.util.Log.d("SmugMugRepository", "getNodeChildren started: nodeId=$nodeId, forceRefresh=$forceRefresh, hasPassword=${!password.isNullOrEmpty()}")
        }
        // First emit Room cached value
        val cached = dao.getCachedNodesByParent(nodeId).first()
        if (cached.isNotEmpty() && !forceRefresh) {
            if (com.smugview.app.BuildConfig.DEBUG) {
                android.util.Log.d("SmugMugRepository", "getNodeChildren cached hit: found ${cached.size} children for nodeId=$nodeId")
            }
            emit(Result.success(cached))
            return@flow
        }

        val lock = nodeLocks.getOrPut(nodeId) { Mutex() }
        lock.withLock {
            // Re-check cache after acquiring the lock in case another coroutine populated it
            val cachedPostLock = dao.getCachedNodesByParent(nodeId).first()
            if (cachedPostLock.isNotEmpty() && !forceRefresh) {
                if (com.smugview.app.BuildConfig.DEBUG) {
                    android.util.Log.d("SmugMugRepository", "getNodeChildren cached hit post-lock: found ${cachedPostLock.size} children for nodeId=$nodeId")
                }
                emit(Result.success(cachedPostLock))
                return@withLock
            }
            if (com.smugview.app.BuildConfig.DEBUG) {
                android.util.Log.d("SmugMugRepository", "getNodeChildren cache miss: fetching from remote API for nodeId=$nodeId")
            }

            try {
                val dbNodes = fetchAndStoreChildren(nickname, nodeId, apiKey, forceRefresh, password, ignoreErrors)
                emit(Result.success(dbNodes))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // If API fails but we have cache, don't fail, otherwise emit error
                val postLockCache = dao.getCachedNodesByParent(nodeId).first()
                if (postLockCache.isNotEmpty()) {
                    emit(Result.success(postLockCache))
                } else {
                    emit(Result.failure(e))
                }
            }
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Fetches every page of [nodeId]'s children from the API and writes them (replacing the parent's
     * listing, R-07). Throws on any failure; callers decide whether to fall back to the cache.
     * [forceRefresh] adds `Cache-Control: no-cache` so a forced listing reaches the server (R-35).
     */
    private suspend fun fetchAndStoreChildren(
        nickname: String,
        nodeId: String,
        apiKey: String,
        forceRefresh: Boolean,
        password: String?,
        ignoreErrors: String?
    ): List<CachedNode> {
        val cacheControl = if (forceRefresh) "no-cache" else null
        val allApiNodes = mutableListOf<com.smugview.app.data.api.NodeData>()
        val allExpansions = mutableMapOf<String, com.smugview.app.data.api.ExpansionContainer>()

        // Every page is the same typed call with `start`, never a followed `NextPage`: that link drops
        // `_expand`, so rows 101+ used to come back without covers (design 3.2, R-27). A failure on any
        // page throws and nothing is written (the caller keeps the cache).
        if (!nodeId.startsWith("virtual:")) {
            Pager.each(
                pageSize = 100,
                fetch = { start, count ->
                    val response = if (start == 1) {
                        try {
                            api.getNodeChildren(nodeId, apiKey, count = count, start = start, ignoreErrors = ignoreErrors, cacheControl = cacheControl)
                        } catch (e: Exception) {
                            if (!password.isNullOrEmpty() && (e is retrofit2.HttpException && (e.code() == 401 || e.code() == 404))) {
                                val unlocked = unlocks.reauthorize(nodeId, apiKey, password) == UnlockResult.Success
                                if (unlocked) {
                                    api.getNodeChildren(nodeId, apiKey, count = count, start = start, ignoreErrors = ignoreErrors, cacheControl = cacheControl)
                                } else {
                                    throw e
                                }
                            } else {
                                // For real nodes, 401 or 404 without a password often means it's password protected or private.
                                // Throw the error so the UI can catch it and prompt for a password.
                                throw e
                            }
                        }
                    } else {
                        if (com.smugview.app.BuildConfig.DEBUG) {
                            android.util.Log.d("SmugMugRepository", "getNodeChildren: fetching start=$start for nodeId=$nodeId")
                        }
                        api.getNodeChildren(nodeId, apiKey, count = count, start = start, ignoreErrors = ignoreErrors, cacheControl = cacheControl)
                    }
                    response.expansions?.let { allExpansions.putAll(it) }
                    response.toPage(start)
                },
                onPage = { page -> allApiNodes.addAll(page.items); true }
            )
        }

        if (com.smugview.app.BuildConfig.DEBUG) {
            android.util.Log.d("SmugMugRepository", "getNodeChildren: completed fetching all pages for nodeId=$nodeId. Total children fetched = ${allApiNodes.size}")
        }
        
        val dbNodes = allApiNodes.mapIndexed { index, node ->
            val highlightUri = node.uris.highlightImage
            val highlightUrl = if (highlightUri != null) {
                val expansion = allExpansions[highlightUri]
                val thumb = expansion?.image?.thumbnailUrl
                thumb?.replace("/Th/", "/M/")?.replace("/th/", "/m/")?.replace("-Th.", "-M.")?.replace("-th.", "-m.")
            } else null

            CachedNode(
                nodeId = node.nodeId,
                parentNodeId = nodeId,
                type = node.type,
                title = node.name ?: "Untitled",
                description = node.description,
                access = node.securityType,
                passwordHint = node.passwordHint,
                uri = node.uri,
                childNodesUri = node.uris.childNodes,
                albumUri = node.uris.album,
                highlightImageUrl = highlightUrl,
                sortIndex = index,
                webUri = node.webUri,
                dateModified = node.dateModified
            )
        }

        // Save to database. The listing is the truth for this parent: children it no longer has go (R-07).
        if (nodeId.startsWith("virtual:")) insertNodesScoped(nickname, dbNodes) else replaceChildrenScoped(nickname, nodeId, dbNodes)
        dao.updateChildCount(nodeId, dbNodes.size)
        // Any Album-type children (e.g. galleries revealed by unlocking a password-protected
        // parent folder) also need to land in the flat gallery index, since search matches
        // galleries exclusively against it (see buildInMemoryGalleryCache) — otherwise a
        // freshly-unlocked folder's galleries are cached here but stay invisible to search.
        mergeAlbumsIntoIndex(nickname, dbNodes)
        return dbNodes
    }

    // Fetch album details
    suspend fun getAlbum(
        albumKey: String,
        apiKey: String,
        password: String? = null,
        cacheControl: String? = null
    ): AlbumDetails? {
        val album = try {
            try {
                api.getAlbum(albumKey, apiKey, ignoreErrors = "true", cacheControl = cacheControl).response.album
            } catch (e: Exception) {
                if (!password.isNullOrEmpty() && (e is retrofit2.HttpException && (e.code() == 401 || e.code() == 404))) {
                    val unlocked = unlocks.reauthorize(albumKey, apiKey, password) == UnlockResult.Success
                    if (unlocked) {
                        api.getAlbum(albumKey, apiKey, ignoreErrors = "true", cacheControl = cacheControl).response.album
                    } else {
                        throw e
                    }
                } else {
                    throw e
                }
            }
        } catch (e: Exception) {
            null
        }
        // Fresh truth beats a crawl up to 15 minutes old (design 3.5): move the index's
        // ImagesLastUpdated forward (never back) so the "viewed" mark written next matches it.
        album?.imagesLastUpdated?.let { ilu ->
            try {
                dao.raiseAlbumImagesLastUpdated(album.albumKey, ilu)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SmugLog.w("SmugMugRepository", "index ILU raise failed: ${e.javaClass.simpleName}")
            }
        }
        return album
    }

    /**
     * One page of a gallery, from [start] (design 3.2): every page is this same typed call, so `_expand`
     * and the filters ride on every page (never a followed `Pages.NextPage`, R-27). Page 1 carries the
     * password logic (reauthorize on an empty or refused first answer); later pages are plain calls.
     */
    suspend fun getAlbumImagesPage(
        albumKey: String,
        apiKey: String,
        password: String? = null,
        start: Int = 1,
        cacheControl: String? = null
    ): AlbumImagesResponse {
        // Later pages are not retried through the unlock path: the first page already proved the access.
        if (start > 1) return api.getAlbumImages(albumKey, apiKey, start = start, cacheControl = cacheControl)
        return firstImagesPage(albumKey, apiKey, password, cacheControl)
    }

    /**
     * Page 1 of a gallery with the password logic (design 3.4): a refused answer (401/404) or an empty one
     * reauthorizes the password root once when a password is saved, and asks again. A page that is still
     * empty is "locked" only when the album itself says so (`ResponseLevel == "Password"`): the `!images`
     * of a locked gallery is a 200 with no photos, the same as a gallery that has none (R-32).
     *
     * @throws AlbumLockedException the gallery is locked for this caller
     */
    private suspend fun firstImagesPage(
        albumKey: String,
        apiKey: String,
        password: String?,
        cacheControl: String?
    ): AlbumImagesResponse {
        var first = try {
            api.getAlbumImages(albumKey, apiKey, start = 1, ignoreErrors = "true", cacheControl = cacheControl)
        } catch (e: Exception) {
            if (!password.isNullOrEmpty() && (e is retrofit2.HttpException && (e.code() == 401 || e.code() == 404))) {
                if (unlocks.reauthorize(albumKey, apiKey, password) == UnlockResult.Success) {
                    api.getAlbumImages(albumKey, apiKey, start = 1, ignoreErrors = "true", cacheControl = cacheControl)
                } else {
                    throw e
                }
            } else {
                throw e
            }
        }
        if (!first.hasNoListing()) return first

        var pending: TransientReason? = null
        if (!password.isNullOrEmpty()) {
            val outcome = unlocks.reauthorizeOutcome(albumKey, apiKey, password)
            when (outcome.result) {
                UnlockResult.Success -> {
                    first = api.getAlbumImages(albumKey, apiKey, start = 1, ignoreErrors = "true", cacheControl = cacheControl)
                    if (!first.hasNoListing()) return first
                }
                // A reason that is not known (the lineage was unreadable) reads as busy: the album was
                // answering a moment ago, so "offline" would be the bolder claim.
                UnlockResult.Transient -> pending = outcome.reason ?: TransientReason.Busy
                UnlockResult.Rejected -> {}
            }
        }
        // Still nothing: a gallery with no photos, or a locked one. Only the album can say which. A failure
        // to read it (offline, uncached) propagates: an empty grid would claim the gallery is empty.
        val album = api.getAlbum(albumKey, apiKey, ignoreErrors = "true", cacheControl = cacheControl).response.album
        if (album.isLocked) throw AlbumLockedException(albumKey, pending)
        return first
    }

    private fun AlbumImagesResponse.hasNoListing(): Boolean =
        response.images.isNullOrEmpty() && (response.pages?.total ?: 0) == 0

    /** Gives every video of [images] its playable URL from this page's own `LargestVideo` expansions. */
    private fun applyVideoUrls(images: List<AlbumImageData>, expansions: Map<String, com.smugview.app.data.api.ExpansionContainer>?) {
        if (expansions == null) return
        images.forEach { img ->
            if (img.isVideo) {
                val largestVideoUri = img.uris?.largestVideo
                if (largestVideoUri != null) img.videoUrl = expansions[largestVideoUri]?.largestVideo?.url
            }
        }
    }

    suspend fun getUserTopKeywords(
        nickname: String,
        apiKey: String,
        nodeId: String? = null
    ): com.smugview.app.data.api.TopKeywordsResponse {
        // user!topkeywords is scoped by NodeURI; it silently ignores NodeID (Phase 4, Q6, R-28).
        return api.getUserTopKeywords(nickname, apiKey, nodeUri = nodeId?.let { "/api/v2/node/$it" })
    }

    suspend fun getUserRecentImagesResponse(
        nickname: String,
        apiKey: String,
        count: Int = 10
    ): com.smugview.app.data.api.ImageSearchResponse {
        return api.getUserRecentImages(nickname, apiKey, count = count)
    }

    /**
     * One page of the keyword search from [start] (Tags tab). The same typed call for every page, never
     * a followed `NextPage`, so a caller resumes from an `Int` (design 3.2). The server clamps [count]
     * to 100 and answers an empty 500 when `start + count - 1 > 10,000` ([Pager.SEARCH_WINDOW]), so the
     * caller trims with the window. A failure throws; the caller keeps its resume point.
     */
    suspend fun getImagesByKeywordPage(
        scope: String?,
        keywords: String,
        apiKey: String,
        count: Int = SEARCH_PAGE,
        start: Int = 1
    ): ImageSearchResponse = api.getImagesByKeyword(
        apiKey = apiKey,
        scope = scope,
        text = keywords.replace(",", " "),
        count = count,
        start = start
    )

    suspend fun updateImageMetadata(imageKey: String, apiKey: String, keywords: String): Boolean {
        val body = com.smugview.app.data.api.UpdateImageMetadataRequest(keywords)
        val response = api.updateImageMetadata(imageKey, apiKey, body)
        return response.isSuccessful
    }

    /**
     * Every image of a gallery (Cast, Collections downloads). Page 1 keeps its password logic; every
     * page after it is the same typed call with `start` through [Pager], so `_expand=LargestVideo`
     * rides on all of them and each video gets its `videoUrl` (R-27). At most [maxPagesPerFetch] pages.
     */
    suspend fun getAllAlbumImages(
        albumKey: String,
        apiKey: String,
        password: String? = null
    ): List<AlbumImageData> {
        val allImages = mutableListOf<AlbumImageData>()
        val first = firstImagesPage(albumKey, apiKey, password, cacheControl = null)
        applyVideoUrls(first.response.images ?: emptyList(), first.expansions)
        first.response.images?.let { allImages.addAll(it) }
        val next = first.toPage(1).nextStart()
        if (next != null && maxPagesPerFetch > 1) {
            var fetched = 1
            Pager.each(
                first = next,
                pageSize = ALBUM_IMAGES_PAGE,
                fetch = { start, count ->
                    val res = api.getAlbumImages(albumKey, apiKey, count = count, start = start)
                    applyVideoUrls(res.response.images ?: emptyList(), res.expansions)
                    res.toPage(start)
                },
                onPage = { page -> allImages.addAll(page.items); ++fetched < maxPagesPerFetch }
            )
        }
        return allImages
    }

    /**
     * The gallery crawl (design 3.3, findings #14/#15/#16). Publishes the persisted album index at
     * once, then (at most once per [CRAWL_GATE_MS], and only after a crawl that fetched every page)
     * reads EVERY page of `user!albums`, because the listing is not sorted by any date it returns and
     * no early stop is safe. Pages accumulate in memory and one transaction writes them, so a failed
     * crawl changes nothing and does not stamp the gate. Index rows the listing no longer holds are
     * pruned only after a complete crawl ([unlock] says every password root unlocked: otherwise their
     * galleries are merely invisible) and never when that would drop more than `max(20, 5%)` of the
     * index. Folders that already have a cached listing and hold new or changed galleries are then
     * relisted (forced, at most [MAX_RELIST]); their ids are returned so the caller can reload the one
     * on screen. Only gallery *metadata* is synced; contents load on demand.
     *
     * Then [IndexParentResolver] gives every gallery a parent folder from the folder paths (design
     * 3.4); a recent gallery in a folder that is not cached yet relists its nearest cached ancestor.
     * Resolver relists and changed-parent relists share one [MAX_RELIST] budget.
     *
     * [unlock] is the summary of this launch's unlock ([runSiteSync]); null (unknown) never prunes.
     * [rootNodeId] is the site root, the parent of root-level galleries.
     */
    suspend fun buildInMemoryGalleryCache(
        nickname: String,
        apiKey: String,
        unlock: UnlockSummary? = null,
        rootNodeId: String? = null,
        ignoreGate: Boolean = false
    ): Set<String> = crawlMutex.withLock {
        crawlLocked(nickname, apiKey, unlock, rootNodeId, ignoreGate)
    }

    /** One crawl at a time: a runtime-unlock crawl must wait for, then redo, a crawl that began without the session. */
    private val crawlMutex = Mutex()

    /** The [UnlockManager.sessionEpoch] each site's latest crawl started under: a resync for that epoch or older is redundant. */
    private val coveredEpoch = java.util.concurrent.ConcurrentHashMap<String, Int>()

    private suspend fun crawlLocked(
        nickname: String,
        apiKey: String,
        unlock: UnlockSummary?,
        rootNodeId: String?,
        ignoreGate: Boolean
    ): Set<String> {
        if (com.smugview.app.BuildConfig.DEBUG) {
            android.util.Log.d("SmugMugRepository", "buildInMemoryGalleryCache starting for user=$nickname")
        }
        // The session state this crawl starts under (design 3.6). A root that becomes Session while the
        // crawl is in flight is invisible to the pages it already fetched, so its galleries would look
        // deleted: such a crawl may not prune (below), and the resync that follows the unlock redoes it.
        val startEpoch = unlocks.sessionEpoch.value
        coveredEpoch[nickname] = startEpoch
        val actionId = com.smugview.app.diag.DiagContext.newActionId("sync")
        return kotlinx.coroutines.withContext(
            kotlinx.coroutines.Dispatchers.IO + com.smugview.app.diag.DiagContext.element(actionId)
        ) {
            val run = syncReporter.begin(com.smugview.app.diag.SyncKind.GallerySync, nickname, actionId)
            run?.rootNodeId = rootNodeId
            var complete = false
            var pruned = 0
            var pruneSkipped = 0
            var relistedCount = 0
            var parentsResolved = 0
            var unresolved = 0
            try {
                // 1. Instant: publish the persisted index (if any) so UI/search can proceed.
                val persisted = dao.getAlbumIndex(nickname)
                val isFirstSync = persisted.isEmpty()
                run?.isFirstSync = isFirstSync
                run?.persistedCount = persisted.size
                if (!isFirstSync) {
                    _albumIndex.value = AlbumIndexSnapshot(nickname, persisted.map { it.toCachedNode() })
                    _isAlbumsCacheLoaded.value = true
                } else {
                    _isAlbumsCacheLoaded.value = false
                }

                // 2. Gate: a crawl that fetched every page less than 15 minutes ago is fresh enough.
                val gateKey = "lastFullCrawlAt.$nickname"
                val lastCrawl = syncState.getLong(gateKey)
                val ageMs = clock() - lastCrawl
                if (!ignoreGate && lastCrawl > 0 && ageMs in 0 until CRAWL_GATE_MS) {
                    run?.stop = com.smugview.app.diag.StopReason.Completed
                    run?.notes = "gated ageMin=${ageMs / 60_000}"
                    return@withContext emptySet<String>()
                }

                // 3. Every page, all or nothing.
                val crawl = GalleryCrawl(api, dao)
                val fetched = crawl.fetchAll(nickname, apiKey) { pages, seen, nullLastUpdated, passwordSecurity ->
                    run?.let { r ->
                        r.pagesFetched = pages
                        r.albumsSeen = seen
                        r.albumsNullLastUpdated = nullLastUpdated
                        r.albumsPasswordSecurity = passwordSecurity
                    }
                }
                val sessionUnchanged = unlocks.sessionEpoch.value == startEpoch
                val written = crawl.write(nickname, fetched.albums, prune = unlock?.allSucceeded == true && sessionUnchanged)
                complete = true
                pruned = written.pruned
                pruneSkipped = written.pruneSkipped
                syncState.putLong(gateKey, clock())
                run?.stop = com.smugview.app.diag.StopReason.NoNextPage
                run?.changedCount = written.changed.size
                _albumIndex.value = AlbumIndexSnapshot(nickname, dao.getAlbumIndex(nickname).map { it.toCachedNode() })
                if (com.smugview.app.BuildConfig.DEBUG) {
                    android.util.Log.d("SmugMugRepository", "album index crawled: ${written.changed.size} new/changed, total=${_albumIndex.value.nodes.size}")
                }

                // 4. Parents from the folder paths (relisting the nearest cached ancestor of a recent
                // gallery whose folder is new).
                val resolved = IndexParentResolver(dao, clock) { id -> relistOne(nickname, id, apiKey) }
                    .resolve(nickname, rootNodeId, fetched.folderPaths, MAX_RELIST)
                parentsResolved = resolved.resolved
                unresolved = resolved.unresolved
                if (resolved.resolved > 0 || resolved.relisted.isNotEmpty()) {
                    _albumIndex.value = AlbumIndexSnapshot(nickname, dao.getAlbumIndex(nickname).map { it.toCachedNode() })
                }

                // 5. A new or changed gallery means its parent folder's cached listing (the Folders
                // tab) may be stale: relist the folders that already have one.
                val parentOf = dao.getAlbumIndex(nickname).associate { it.albumKey to it.parentNodeId }
                val relisted = LinkedHashSet<String>(resolved.relisted)
                relisted += relistFolders(
                    nickname,
                    written.changed.mapNotNull { parentOf[it.albumKey] }.distinct()
                        .filter { it !in relisted },
                    apiKey, MAX_RELIST - relisted.size
                )
                relistedCount = relisted.size
                run?.invalidatedParents = relisted.size
                relisted
            } catch (e: CancellationException) {
                // Recorded, then rethrown (design 3.1): a cancelled crawl stops its caller too, so a site
                // switch really ends the site's sync. No gate stamp is written: the gate counts from a
                // completed crawl only, so switching straight back runs the crawl again.
                run?.stop = com.smugview.app.diag.StopReason.Cancelled
                throw e
            } catch (e: Exception) {
                SmugLog.e("SmugMugRepository", "Failed to sync gallery cache", e)
                run?.stop = com.smugview.app.diag.StopReason.Error
                run?.stopDetail = e.javaClass.simpleName +
                    ((e as? retrofit2.HttpException)?.let { " ${it.code()}" } ?: "")
                emptySet()
            } finally {
                _isAlbumsCacheLoaded.value = true
                if (run != null) {
                    if (run.notes == null) {
                        run.notes = "complete=$complete pruned=$pruned pruneSkipped=$pruneSkipped parentsResolved=$parentsResolved unresolved=$unresolved relisted=$relistedCount"
                    }
                    syncReporter.finish(run)
                }
            }
        }
    }

    /**
     * Forced relist of up to [limit] of [folderIds] that already have a cached listing (a folder
     * never opened has nothing stale to refresh). A folder that cannot be listed (locked, offline) is
     * skipped. Returns the folders actually relisted.
     */
    private suspend fun relistFolders(nickname: String, folderIds: List<String>, apiKey: String, limit: Int = MAX_RELIST): Set<String> {
        val relisted = LinkedHashSet<String>()
        for (id in folderIds) {
            if (relisted.size >= limit) break
            if (dao.getCachedNodesByParent(id).first().isEmpty()) continue
            if (relistOne(nickname, id, apiKey)) relisted.add(id)
        }
        return relisted
    }

    /** One forced listing of [id] (under its node lock); false when it failed. Pauses afterwards. */
    private suspend fun relistOne(nickname: String, id: String, apiKey: String): Boolean {
        var ok = false
        try {
            nodeLocks.getOrPut(id) { Mutex() }.withLock { fetchAndStoreChildren(nickname, id, apiKey, true, null, "true") }
            ok = true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SmugLog.w("sync", "relist skip $id: ${e.javaClass.simpleName}")
        }
        if (treeSyncDelayMs > 0) kotlinx.coroutines.delay(treeSyncDelayMs)
        return ok
    }

    companion object {
        /** A crawl that fetched every page this recently is not repeated (design 3.3). */
        const val CRAWL_GATE_MS = 15 * 60_000L

        /** Most folders one crawl relists. */
        const val MAX_RELIST = 30

        /** What the app asks of `album!images` per page (the server's cap is 500). */
        const val ALBUM_IMAGES_PAGE = 500

        /** What the app asks of `image!search` per page (the server's cap is 100, P13). */
        const val SEARCH_PAGE = 100
    }

    /** Wall clock for the crawl gate; tests move it. */
    internal var clock: () -> Long = System::currentTimeMillis

    /** Pause between unlock calls in [unlockSavedRoots]; tests set it to 0. */
    internal var unlockDelayMs: Long = 400L

    /**
     * What [unlockSavedRoots] did. [allSucceeded] is true only when every distinct root unlocked:
     * the crawl may prune index rows only then (design 3.3), because a root that failed to unlock
     * hides its galleries from the listing and they would look deleted.
     */
    data class UnlockSummary(
        val roots: Int, val ok: Int, val rejected: Int, val transient: Int,
        /** What happened to each root, by the root's NodeID (or the bare saved key when its node is unknown). */
        val results: Map<String, UnlockResult> = emptyMap()
    ) {
        val allSucceeded: Boolean get() = rejected == 0 && transient == 0

        companion object { val NONE = UnlockSummary(0, 0, 0, 0) }
    }

    /** A password root to unlock: a folder/album node, or a bare saved key whose node is unknown. */
    private data class UnlockTarget(
        val dedupeKey: String,
        val node: CachedNode?,
        val bareKey: String?,
        val password: String,
        /** The bare key may be an AlbumKey, so try `album!unlock` if `node!unlock` did not succeed. */
        val albumFallback: Boolean = false
    )

    /**
     * Session unlock at launch (design 3.2). A saved password is not a session: the cookie jar is
     * empty at every launch, and without the `!unlock` cookie password-protected galleries are
     * invisible to `user!albums` (findings #16). So every saved key is mapped to the root that holds
     * its password (offline from the cached rows when possible, else one `!parents` call), and each
     * distinct root is unlocked once, sequentially. There is no "already unlocked" skip. A rejected
     * or transient answer never deletes a saved password: launch cannot ask the user, and the prompt
     * path owns deletion. Recorded as a LaunchUnlock run.
     */
    suspend fun unlockSavedRoots(nickname: String, apiKey: String): UnlockSummary {
        val actionId = com.smugview.app.diag.DiagContext.newActionId("unlock")
        return kotlinx.coroutines.withContext(
            Dispatchers.IO + com.smugview.app.diag.DiagContext.element(actionId)
        ) {
            val run = syncReporter.begin(com.smugview.app.diag.SyncKind.LaunchUnlock, nickname, actionId)
            var summary = UnlockSummary.NONE
            try {
                val saved = passwordStore.all().filterValues { it.isNotEmpty() }
                run?.savedKeys = saved.size
                val targets = LinkedHashMap<String, UnlockTarget>()
                for ((key, password) in saved) {
                    val target = findUnlockTarget(key, password, apiKey)
                    targets.putIfAbsent(target.dedupeKey, target)
                }
                var ok = 0
                var rejected = 0
                var transient = 0
                var first = true
                val results = LinkedHashMap<String, UnlockResult>()
                for (t in targets.values) {
                    if (!first && unlockDelayMs > 0) kotlinx.coroutines.delay(unlockDelayMs)
                    first = false
                    val result = unlockTarget(t, apiKey)
                    when (result) {
                        UnlockResult.Success -> ok++
                        UnlockResult.Rejected -> rejected++
                        UnlockResult.Transient -> transient++
                    }
                    (t.node?.nodeId ?: t.bareKey)?.let { results[it] = result }
                }
                summary = UnlockSummary(targets.size, ok, rejected, transient, results)
                unlocks.recordLaunch(summary)
                run?.stop = com.smugview.app.diag.StopReason.Completed
            } catch (e: CancellationException) {
                run?.stop = com.smugview.app.diag.StopReason.Cancelled
                throw e
            } catch (e: Exception) {
                run?.stop = com.smugview.app.diag.StopReason.Error
                run?.stopDetail = e.javaClass.simpleName
            } finally {
                run?.notes = "roots=${summary.roots} ok=${summary.ok} rejected=${summary.rejected} transient=${summary.transient}"
                run?.let { syncReporter.finish(it) }
            }
            summary
        }
    }

    /** The password root of a saved [key]: cached rows first (depth <= 32, cycle-safe), else `!parents`. */
    private suspend fun findUnlockTarget(key: String, password: String, apiKey: String): UnlockTarget {
        val row: CachedNode? = dao.getNodeByIdOrKey(key)
            ?: dao.getAlbumNodeIdByKey(key)?.let { dao.getNodeById(it) }
        if (row != null) {
            val seen = mutableSetOf<String>()
            var current: CachedNode? = row
            var depth = 0
            while (current != null && depth < 32 && seen.add(current.nodeId)) {
                if (current.access == "Password") return targetFor(current, password)
                val parent = current.parentNodeId
                // Chain complete and no Password row above: the saved key is its own root.
                if (parent == null || parent == "root") return targetFor(row, password)
                current = dao.getNodeById(parent)
                depth++
            }
            // A cached link is missing: fall through to the one-request answer.
        }
        return when (val r = resolvePasswordRootNodeId(key, apiKey)) {
            is RootResolution.Resolved -> {
                val rootRow = dao.getNodeById(r.nodeId)
                val rootPassword = passwordStore.getPassword(r.nodeId)?.takeIf { it.isNotEmpty() } ?: password
                when {
                    rootRow != null -> targetFor(rootRow, rootPassword)
                    // The key's own node is the root: it may be a gallery, so allow the album route.
                    r.nodeId == key -> UnlockTarget("node:${r.nodeId}", null, key, rootPassword, albumFallback = true)
                    else -> UnlockTarget("node:${r.nodeId}", null, r.nodeId, rootPassword)
                }
            }
            RootResolution.NotProtected, RootResolution.Unknown ->
                if (row != null) targetFor(row, password) else UnlockTarget("node:$key", null, key, password, albumFallback = true)
        }
    }

    private fun targetFor(node: CachedNode, password: String): UnlockTarget {
        val own = passwordStore.getPassword(node.nodeId)?.takeIf { it.isNotEmpty() }
            ?: node.getAlbumKey().takeIf { node.type != "Folder" }
                ?.let { passwordStore.getPassword(it) }?.takeIf { it.isNotEmpty() }
        val dedupe = if (node.type == "Folder") "node:${node.nodeId}" else "album:${node.getAlbumKey()}"
        return UnlockTarget(dedupe, node, null, own ?: password)
    }

    private suspend fun unlockTarget(t: UnlockTarget, apiKey: String): UnlockResult {
        val node = t.node
        if (node != null) {
            return if (node.type == "Folder") {
                unlockNodeResult(node.nodeId, apiKey, t.password)
            } else {
                val albumKey = node.getAlbumKey()
                if (albumKey.isNotEmpty() && albumKey != node.nodeId) unlockAlbumResult(albumKey, apiKey, t.password)
                else unlockNodeResult(node.nodeId, apiKey, t.password)
            }
        }
        val key = t.bareKey ?: return UnlockResult.Transient
        val asNode = unlockNodeResult(key, apiKey, t.password)
        if (asNode == UnlockResult.Success || !t.albumFallback) return asNode
        // A bare key may be an AlbumKey: try it as one. Success wins; otherwise a transient answer stands.
        val asAlbum = unlockAlbumResult(key, apiKey, t.password)
        return if (asAlbum == UnlockResult.Success) asAlbum else if (asNode == UnlockResult.Transient) asNode else asAlbum
    }

    /**
     * One site sync, in the order that makes the data right (design 3.8): unlock the saved password
     * roots (so the session cookie exists), then the gallery crawl, then the folder tree. Returns the
     * folders whose listing changed so the caller can reload one that is on screen.
     */
    suspend fun runSiteSync(nickname: String, rootNodeId: String, apiKey: String): Set<String> {
        // The crawl mutex is held across the unlock AND the crawl: the unlock bumps the session epoch,
        // and a resync triggered by that bump must queue behind this crawl (which covers it), not run
        // first and leave this one gated and unable to prune.
        val invalidated = crawlMutex.withLock {
            val unlock = unlockSavedRoots(nickname, apiKey)
            crawlLocked(nickname, apiKey, unlock, rootNodeId, false)
        }
        return afterCrawl(nickname, rootNodeId, apiKey, invalidated)
    }

    /**
     * Crawl and tree walk again after the user unlocked a password folder or gallery while the app was
     * running: the new session cookie makes galleries visible to `user!albums` that the last crawl
     * could not see, so the 15-minute gate does not apply. [UnlockSummary] is unknown here, so nothing
     * is pruned. A no-op (no run, no tree walk) when the latest crawl already started under the current
     * session epoch: the launch unlock bumps the epoch before the launch crawl, which covers it.
     */
    suspend fun resyncAfterUnlock(nickname: String, rootNodeId: String, apiKey: String): Set<String> {
        val invalidated = crawlMutex.withLock {
            if (unlocks.sessionEpoch.value <= (coveredEpoch[nickname] ?: -1)) return emptySet()
            crawlLocked(nickname, apiKey, null, rootNodeId, true)
        }
        return afterCrawl(nickname, rootNodeId, apiKey, invalidated)
    }

    private suspend fun afterCrawl(nickname: String, rootNodeId: String, apiKey: String, invalidated: Set<String>): Set<String> {
        syncFolderTree(nickname, rootNodeId, apiKey)
        // The tree walk may have cached folders the crawl could not match (design 3.4: again after the tree sync).
        val again = IndexParentResolver(dao, clock) { id -> relistOne(nickname, id, apiKey) }
            .resolve(nickname, rootNodeId, emptyMap(), MAX_RELIST, invalidated)
        if (again.resolved > 0 || again.relisted.isNotEmpty()) {
            _albumIndex.value = AlbumIndexSnapshot(nickname, dao.getAlbumIndex(nickname).map { it.toCachedNode() })
        }
        return invalidated + again.relisted
    }

    /** Pause between folder listings in [syncFolderTree]; tests set it to 0. */
    internal var treeSyncDelayMs: Long = 200L

    /**
     * Result of one [syncFolderTree] walk. [complete] is true only when every reachable folder was
     * listed (locked folders with no session are skipped, not failures).
     */
    data class TreeSyncResult(val forced: Boolean, val complete: Boolean, val listed: Int, val skippedLocked: Int)

    /**
     * Walks the folder tree under [rootNodeId] (BFS), listing every reachable folder so `cached_nodes`
     * has its real parent links (design 3.6). The first walk on a v16 database for a site is *forced*
     * (`Cache-Control: no-cache`, `replaceChildren`): listings written by earlier versions can hold
     * wrong rows we cannot find locally (R-04). The flag `treeRepairDone.<nick>` is set only when the
     * walk finishes without error, so an offline or killed run is retried on the next launch. Later
     * walks are cache-first and only fetch what is not cached yet.
     *
     * A 401/403 means the folder is locked and we hold no session for it: skipped, not an error. Any
     * other failure aborts the walk (nothing is hammered offline) and leaves the flag unset.
     * Recorded as a [com.smugview.app.diag.SyncKind.FolderTreeSync] run.
     */
    suspend fun syncFolderTree(nickname: String, rootNodeId: String, apiKey: String): TreeSyncResult {
        val flagKey = "treeRepairDone.$nickname"
        val forced = !syncState.getBoolean(flagKey)
        val actionId = com.smugview.app.diag.DiagContext.newActionId("tree")
        return kotlinx.coroutines.withContext(
            Dispatchers.IO + com.smugview.app.diag.DiagContext.element(actionId)
        ) {
            val run = syncReporter.begin(com.smugview.app.diag.SyncKind.FolderTreeSync, nickname, actionId)
            var listed = 0
            var skippedLocked = 0
            var complete = false
            var failure: String? = null
            try {
                val visited = mutableSetOf<String>()
                val queue = ArrayDeque<String>()
                queue.addLast(rootNodeId)
                var aborted = false
                while (queue.isNotEmpty() && !aborted) {
                    val id = queue.removeFirst()
                    if (!visited.add(id)) continue
                    try {
                        val children: List<CachedNode> = if (forced) {
                            val lock = nodeLocks.getOrPut(id) { Mutex() }
                            lock.withLock { fetchAndStoreChildren(nickname, id, apiKey, true, null, "true") }
                        } else {
                            getNodeChildren(nickname, id, apiKey, forceRefresh = false, password = null, ignoreErrors = "true")
                                .first().getOrThrow()
                        }
                        listed++
                        children.filter { it.type == "Folder" }.forEach { queue.addLast(it.nodeId) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        val code = (e as? retrofit2.HttpException)?.code()
                        if (code == 401 || code == 403) {
                            skippedLocked++
                        } else {
                            failure = e.javaClass.simpleName + (code?.let { " $it" } ?: "")
                            aborted = true
                        }
                    }
                    if (!aborted && queue.isNotEmpty() && treeSyncDelayMs > 0) kotlinx.coroutines.delay(treeSyncDelayMs)
                }
                complete = !aborted
                if (complete && forced) syncState.putBoolean(flagKey, true)
                run?.stop = if (complete) com.smugview.app.diag.StopReason.Completed else com.smugview.app.diag.StopReason.Error
                run?.stopDetail = failure
            } catch (e: CancellationException) {
                run?.stop = com.smugview.app.diag.StopReason.Cancelled
                throw e
            } finally {
                run?.notes = "forced=$forced complete=$complete listed=$listed skippedLocked=$skippedLocked"
                run?.let { syncReporter.finish(it) }
            }
            TreeSyncResult(forced, complete, listed, skippedLocked)
        }
    }

    /**
     * Walks a just-unlocked folder's entire subtree (BFS, same password) so every nested gallery —
     * not just the folder's direct children — gets merged into the flat search index via
     * [getNodeChildren]'s own [mergeAlbumsIntoIndex] call. Without this, unlocking a folder whose
     * galleries live under sub-folders (e.g. locked "Family" -> "School" -> the actual gallery)
     * only indexes the direct children, leaving search still blind to anything nested deeper even
     * though the folder itself now shows as unlocked. A sub-folder that turns out to need a
     * different password is skipped, not treated as fatal to the rest of the walk. [maxNodes] caps
     * the crawl so a pathologically large hierarchy can't turn one password entry into an unbounded
     * background fetch.
     */
    suspend fun unlockAndIndexSubtree(nickname: String, rootNodeId: String, apiKey: String, password: String, maxNodes: Int = 300) {
        val actionId = com.smugview.app.diag.DiagContext.newActionId("subtree")
        kotlinx.coroutines.withContext(com.smugview.app.diag.DiagContext.element(actionId)) {
            val run = syncReporter.begin(com.smugview.app.diag.SyncKind.SubtreeIndex, nickname, actionId)
            var nodesFetched = 0
            var skipped = 0
            if (activeSubtreeIndexJobs.incrementAndGet() == 1) {
                _isIndexingSubtree.value = true
            }
            try {
                val visited = mutableSetOf(rootNodeId)
                val queue = mutableListOf(rootNodeId)

                while (queue.isNotEmpty() && nodesFetched < maxNodes) {
                    val currentNodeId = queue.removeAt(0)
                    nodesFetched++
                    try {
                        getNodeChildren(nickname, currentNodeId, apiKey, forceRefresh = true, password = password, ignoreErrors = "true")
                            .first()
                            .onSuccess { children ->
                                for (child in children) {
                                    if (child.type == "Folder" && visited.add(child.nodeId)) {
                                        queue.add(child.nodeId)
                                    }
                                }
                            }
                            .onFailure {
                                skipped++
                                SmugLog.w("sync", "subtree skip $currentNodeId: ${it.javaClass.simpleName}")
                            }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // Skip this sub-folder (wrong password / other failure) and keep walking the rest.
                        skipped++
                        SmugLog.w("sync", "subtree skip $currentNodeId: ${e.javaClass.simpleName}")
                    }
                    kotlinx.coroutines.delay(200)
                }
                run?.stop = com.smugview.app.diag.StopReason.Completed
            } catch (e: CancellationException) {
                run?.stop = com.smugview.app.diag.StopReason.Cancelled
                throw e
            } finally {
                if (activeSubtreeIndexJobs.decrementAndGet() == 0) {
                    _isIndexingSubtree.value = false
                }
                run?.let {
                    it.notes = "nodes=$nodesFetched skipped=$skipped"
                    syncReporter.finish(it)
                }
            }
        }
    }

    /**
     * Upserts any Album-type nodes into the flat gallery index ([CachedAlbum] / [albumIndex]) so
     * they're searchable, and refreshes the in-memory cache. Existing metadata we don't have from a
     * node-children fetch (urlPath, galleryStyle) is preserved from the prior index entry if present.
     */
    private suspend fun mergeAlbumsIntoIndex(nickname: String, nodes: List<CachedNode>) {
        val albums = nodes.filter { it.type == "Album" }
        if (albums.isEmpty()) return
        val existingByKey = dao.getAlbumIndex(nickname).associateBy { it.albumKey }
        val toUpsert = albums.map { node ->
            val albumKey = node.getAlbumKey()
            val existing = existingByKey[albumKey]
            CachedAlbum(
                albumKey = albumKey,
                nodeId = node.nodeId,
                name = node.title,
                securityType = node.access ?: existing?.securityType,
                passwordHint = node.passwordHint ?: existing?.passwordHint,
                uri = node.albumUri ?: node.uri,
                webUri = node.webUri ?: existing?.webUri,
                urlPath = existing?.urlPath,
                imageCount = node.childCount ?: existing?.imageCount,
                // A node's DateModified is not the album's LastUpdated (findings #15): it is never written
                // here, so the index date stays the crawl's.
                dateModified = existing?.dateModified,
                galleryStyle = existing?.galleryStyle,
                highlightImageUrl = node.highlightImageUrl ?: existing?.highlightImageUrl,
                sortIndex = existing?.sortIndex ?: node.sortIndex,
                nickname = nickname,
                parentNodeId = node.parentNodeId ?: existing?.parentNodeId,
                imagesLastUpdated = existing?.imagesLastUpdated
            )
        }
        dao.upsertAlbums(toUpsert)
        _albumIndex.value = AlbumIndexSnapshot(nickname, dao.getAlbumIndex(nickname).map { it.toCachedNode() })
    }

    suspend fun performBackgroundSearchImages(
        nickname: String,
        scopeUri: String?,
        scopeKey: String,
        query: String,
        apiKey: String
    ) {
        if (com.smugview.app.BuildConfig.DEBUG) {
            android.util.Log.d("SmugMugRepository", "performBackgroundSearchImages starting: user=$nickname, scopeUri=$scopeUri, scopeKey=$scopeKey, query=$query")
        }
        try {
            dao.deleteSearchResultsForQueryAndType(query = query, scope = scopeKey, type = "Photo")

            // No node scope: the site's own user, which needs no lookup and is never another site's root (R-33).
            val targetScope = scopeUri ?: "/api/v2/user/$nickname"
            if (com.smugview.app.BuildConfig.DEBUG) {
                android.util.Log.d("SmugMugRepository", "performBackgroundSearchImages calling searchImages with scope=$targetScope")
            }

            var insertedCount = 0
            val insertedKeys = mutableSetOf<String>()
            var fetched = 0

            // Every page is the same typed call with `start` (never a followed `NextPage`), trimmed to the
            // search backend's 10,000-result window, which it answers with an empty 500 (design 3.2, P6).
            Pager.each(
                pageSize = SEARCH_PAGE,
                window = Pager.SEARCH_WINDOW,
                fetch = { start, count ->
                    val response = api.searchImages(apiKey = apiKey, scope = targetScope, text = query, start = start, count = count)
                    val pageImages = response.response.images ?: emptyList()
                    if (com.smugview.app.BuildConfig.DEBUG) {
                        android.util.Log.d("SmugMugRepository", "performBackgroundSearchImages page ${fetched + 1} returned ${pageImages.size} images")
                    }
                    applyVideoUrls(pageImages, response.expansions)
                    response.toPage(start)
                },
                onPage = { page ->
                    val fresh = page.items.filter { img -> insertedKeys.add(img.imageKey) }
                    if (fresh.isNotEmpty()) {
                        dao.insertSearchResults(
                            results = fresh.mapIndexed { index, img ->
                                img.toSearchResult(query = query, scope = scopeKey, index = insertedCount + index)
                            }
                        )
                        insertedCount += fresh.size
                    }
                    ++fetched < maxPagesPerFetch
                }
            )
            if (com.smugview.app.BuildConfig.DEBUG) {
                android.util.Log.d("SmugMugRepository", "performBackgroundSearchImages completed: total inserted = $insertedCount")
            }
        } catch (e: Exception) {
            android.util.Log.e("SmugMugRepository", "Failed performBackgroundSearchImages", e)
            throw e
        }
    }

    fun getPagedSearchPhotos(query: String, scopeKey: String, order: String): androidx.paging.PagingSource<Int, SearchResult> {
        return if (order == "Descending") {
            dao.getPagedSearchResultsDesc(query, scopeKey, "Photo")
        } else {
            dao.getPagedSearchResultsAsc(query, scopeKey, "Photo")
        }
    }

    suspend fun searchFolders(scopeNodeId: String?, query: String, nickname: String = ""): List<CachedNode> {
        return if (scopeNodeId != null) {
            dao.searchNodesInScope(scopeNodeId, query, "Folder")
        } else {
            dao.searchNodesGlobal(query, "Folder", nickname)
        }
    }

    suspend fun searchGalleries(scopeNodeId: String?, query: String, nickname: String = ""): List<CachedNode> {
        return if (scopeNodeId != null) {
            dao.searchNodesInScope(scopeNodeId, query, "Album")
        } else {
            dao.searchNodesGlobal(query, "Album", nickname)
        }
    }

    suspend fun getAlbumsInScope(scopeNodeId: String?): List<CachedNode> {
        return if (scopeNodeId != null) {
            dao.getAlbumsInScope(scopeNodeId)
        } else {
            dao.getAllCachedNodes().filter { it.type == "Album" }
        }
    }

    suspend fun fetchAlbumsInScopeRemote(nickname: String, scopeNodeId: String, apiKey: String, password: String? = null): List<CachedNode> {
        val albums = mutableListOf<CachedNode>()
        val queue = ArrayDeque<Pair<String, String?>>() // (nodeId, password for this node)
        queue.add(Pair(scopeNodeId, password))

        var foldersScanned = 0
        val maxFolders = 30 // Avoid rate-limit blocks by scanning up to 30 folders
        
        while (queue.isNotEmpty() && foldersScanned < maxFolders) {
            val (currentNodeId, nodePassword) = queue.removeFirst()
            foldersScanned++
            
            val savedPassword = passwordStore.getPassword(currentNodeId)
            val effectivePassword = nodePassword ?: savedPassword
            
            val cachedNode = dao.getNodeById(currentNodeId)
            // Cache-only (no apiKey): a password root with neither session nor saved password is skipped.
            val isLocked = cachedNode != null && unlocks.needsPassword(cachedNode)
            val hasPw = !effectivePassword.isNullOrEmpty()
            if (isLocked && !hasPw) {
                if (com.smugview.app.BuildConfig.DEBUG) {
                    android.util.Log.d("SmugMugRepository", "fetchAlbumsInScopeRemote: skipping locked node $currentNodeId without password")
                }
                continue
            }
            
            try {
                // Every page (a folder can hold more than one), each a typed call: a gallery on page 2 used
                // to be missed here (design 3.2, R-27).
                val nodes = mutableListOf<NodeData>()
                val expansions = mutableMapOf<String, com.smugview.app.data.api.ExpansionContainer>()
                Pager.each(
                    pageSize = 100,
                    fetch = { start, count ->
                        val response = api.getNodeChildren(currentNodeId, apiKey, count = count, start = start, ignoreErrors = "true")
                        response.expansions?.let { expansions.putAll(it) }
                        response.toPage(start)
                    },
                    onPage = { page -> nodes.addAll(page.items); true }
                )
                val dbNodes = nodes.mapIndexed { index, node ->
                    val highlightUri = node.uris.highlightImage
                    val highlightUrl = if (highlightUri != null) {
                        expansions[highlightUri]?.image?.thumbnailUrl
                    } else null
                    
                    CachedNode(
                        nodeId = node.nodeId,
                        parentNodeId = currentNodeId,
                        type = node.type,
                        title = node.name ?: "Untitled",
                        description = node.description,
                        access = node.securityType ?: "Public",
                        passwordHint = node.passwordHint,
                        uri = node.uri,
                        childNodesUri = node.uris.childNodes,
                        albumUri = node.uris.album,
                        highlightImageUrl = highlightUrl,
                        sortIndex = index,
                        webUri = node.webUri,
                        dateModified = node.dateModified
                    )
                }
                
                if (dbNodes.isNotEmpty()) {
                    insertNodesScoped(nickname, dbNodes)
                }
                
                for (node in dbNodes) {
                    if (node.type == "Folder") {
                        // Resolve per-node password: check by nodeId, then fall back to parent password
                        val childPassword = passwordStore.getPassword(node.nodeId) ?: nodePassword
                        queue.add(Pair(node.nodeId, childPassword))
                    } else if (node.type == "Album") {
                        albums.add(node)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Ignore and proceed to next node
            }
        }
        return albums
    }

    suspend fun getAllCachedNodes(): List<CachedNode> {
        val dbNodes = dao.getAllCachedNodes()
        val memNodes = _albumIndex.value.nodes
        return (dbNodes + memNodes).distinctBy { it.nodeId }
    }

    /**
     * Same as [getAllCachedNodes] but scoped to [nickname]'s site via [dao.getCachedNodesForNickname]
     * (uses the existing nickname index) instead of every node cached across every site the user
     * has ever browsed. Prefer this for in-memory scan fallbacks (webUri path matching, etc.) —
     * see the "getAllCachedNodes() full-table scan" rule in AGENTS.md.
     */
    suspend fun getCachedNodesForSite(nickname: String): List<CachedNode> {
        val dbNodes = dao.getCachedNodesForNickname(nickname)
        val memNodes = albumsCacheFor(nickname)
        return (dbNodes + memNodes).distinctBy { it.nodeId }
    }

    suspend fun getNodesByAlbumUris(albumUris: List<String>): List<CachedNode> {
        if (albumUris.isEmpty()) return emptyList()
        return dao.getNodesByAlbumUris(albumUris)
    }


    /** Outcome of an unlock attempt. Only [Rejected] (HTTP 401/403) proves the password is wrong;
     *  offline, 429, 5xx and OkHttp's synthetic 504 are [Transient] and must never delete a saved password. */
    enum class UnlockResult { Success, Rejected, Transient }

    private fun unlockResultOf(code: Int): UnlockResult = when {
        code in 200..299 -> UnlockResult.Success
        code == 401 || code == 403 -> UnlockResult.Rejected
        else -> UnlockResult.Transient
    }

    override suspend fun unlockNodeResult(nodeId: String, apiKey: String, password: String): UnlockResult =
        timedUnlock(nodeId, "node") { api.unlockNode(nodeId, apiKey, password, "true").code() }

    override suspend fun unlockAlbumResult(albumKey: String, apiKey: String, password: String): UnlockResult =
        timedUnlock(albumKey, "album") { api.unlockAlbum(albumKey, apiKey, password, "true").code() }

    /**
     * Runs one unlock call and reports it to the [syncReporter] (target, route, outcome, HTTP code or
     * exception class, duration, action id). Every unlock path (launch, prompt, lineage, tag scan)
     * goes through [unlockNodeResult] / [unlockAlbumResult], so all are covered. The password is never
     * passed here, so it cannot reach the report or the log.
     */
    private suspend fun timedUnlock(
        target: String,
        via: String,
        call: suspend () -> Int
    ): UnlockResult {
        val start = System.nanoTime()
        var httpCode: Int? = null
        var result = UnlockResult.Transient
        var exception: String? = null
        try {
            httpCode = call()
            result = unlockResultOf(httpCode)
            if (result == UnlockResult.Transient && (httpCode == 429 || httpCode in 500..599)) {
                currentCoroutineContext()[TransientNote]?.reason = TransientReason.Busy
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            exception = e.javaClass.simpleName
            if (e is java.io.IOException) currentCoroutineContext()[TransientNote]?.reason = TransientReason.Offline
            SmugLog.d("SmugMugRepository") { "unlock exception: via=$via target=$target ${e.javaClass.simpleName}" }
        }
        syncReporter.recordUnlock(
            com.smugview.app.diag.UnlockAttempt(
                target = target, via = via, result = result.name,
                httpCode = httpCode, exception = exception,
                ms = (System.nanoTime() - start) / 1_000_000,
                actionId = com.smugview.app.diag.DiagContext.currentActionId()
            )
        )
        return result
    }

    // Fetches EXIF details
    fun getImageExif(imageKey: String, apiKey: String): Flow<Result<ExifData>> = flow {
        try {
            val response = api.getImageExif(canonicalImageKey(imageKey), apiKey)
            emit(Result.success(response.response.exif))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emit(Result.failure(e))
        }
    }.flowOn(Dispatchers.IO)

    // Fetches single image details
    fun getImage(
        imageKey: String,
        apiKey: String
    ): Flow<Result<AlbumImageData>> = flow {
        try {
            val response = api.getImage(canonicalImageKey(imageKey), apiKey)
            val img = response.response.image
            if (img.isVideo) {
                val largestVideoUri = img.uris?.largestVideo
                val expansions = response.expansions
                if (largestVideoUri != null && expansions != null) {
                    img.videoUrl = expansions[largestVideoUri]?.largestVideo?.url
                }
            }
            emit(Result.success(img))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emit(Result.failure(e))
        }
    }.flowOn(Dispatchers.IO)

    // Fetches the real generated sizes/urls/dimensions for a single image, used by pinch-to-zoom
    fun getImageSizeDetails(
        uri: String,
        apiKey: String
    ): Flow<Result<com.smugview.app.data.api.ImageSizeDetailsPayload>> = flow {
        try {
            val absoluteUrl = if (uri.startsWith("http")) uri else "https://api.smugmug.com$uri"
            val response = api.getImageSizeDetailsByUri(absoluteUrl, apiKey)
            emit(Result.success(response.response.details))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emit(Result.failure(e))
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Self-first lineage of [idOrKey] (a NodeID or an AlbumKey): the node, each ancestor, then the
     * site root. Read from `node/{id}!parents`; when that can't be read (offline, 5xx) it falls back to
     * the cached rows. In memory only: it never inserts or replaces a row (R-04), because a parent
     * parsed from `Uris.ParentNode` was the node itself (R-01) and only a listing may place a row.
     */
    suspend fun lineageOf(idOrKey: String, apiKey: String): List<CachedNode> {
        val chain = try {
            readLineage(idOrKey, apiKey)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        if (chain.isNullOrEmpty()) return cachedLineage(idOrKey)
        return chain.mapIndexed { i, n ->
            val parentId = chain.getOrNull(i + 1)?.nodeId
            (dao.getNodeById(n.nodeId) ?: CachedNode(
                nodeId = n.nodeId,
                parentNodeId = parentId,
                type = n.type ?: "Folder",
                title = n.name ?: "Untitled",
                description = null,
                access = n.securityType,
                passwordHint = null,
                uri = n.uri ?: "",
                childNodesUri = null,
                albumUri = null,
                webUri = n.webUri
            )).copy(parentNodeId = parentId)
        }
    }

    /** Read-only walk of the cached rows (self first); stops at the root, an unknown parent or a cycle. */
    private suspend fun cachedLineage(idOrKey: String): List<CachedNode> {
        val out = mutableListOf<CachedNode>()
        val visited = HashSet<String>()
        var current = dao.getNodeByIdOrKey(idOrKey)
        while (current != null && visited.add(current.nodeId)) {
            out += current
            val parentId = current.parentNodeId
            if (parentId.isNullOrEmpty() || parentId == "root" || parentId == "search_result") break
            current = dao.getNodeById(parentId)
        }
        return out
    }

    /**
     * The breadcrumb for a gallery: its ancestors, root-most first, without the gallery itself and
     * without the site root. Nothing is written (see [lineageOf]). [password] is unused: `!parents`
     * is anonymous-readable.
     */
    suspend fun resolveAndCacheAlbumLineage(albumKey: String, apiKey: String, password: String? = null): List<CachedNode> {
        if (com.smugview.app.BuildConfig.DEBUG) {
            android.util.Log.d("SmugMugRepository", "resolveAndCacheAlbumLineage called: albumKey=$albumKey, hasPassword=${password != null}")
        }
        val parents = lineageOf(albumKey, apiKey).drop(1).dropLast(1).reversed()
        if (com.smugview.app.BuildConfig.DEBUG) {
            android.util.Log.d("SmugMugRepository", "resolveAndCacheAlbumLineage success: albumKey=$albumKey, resolvedLineageSize=${parents.size}")
        }
        return parents
    }

    // --- Offline Local Collections Room Interface ---

    override suspend fun getNodeById(nodeId: String): CachedNode? = dao.getNodeById(nodeId)
    override suspend fun getNodeByIdOrKey(idOrKey: String): CachedNode? = dao.getNodeByIdOrKey(idOrKey)

    suspend fun insertNodes(nickname: String, nodes: List<CachedNode>) {
        val safeNodes = nodes.map { node ->
            val existing = dao.getNodeById(node.nodeId)
            if (existing != null && 
                !existing.parentNodeId.isNullOrEmpty() && 
                existing.parentNodeId != "search_result" && 
                node.parentNodeId == "search_result"
            ) {
                node.copy(
                    parentNodeId = existing.parentNodeId,
                    sortIndex = existing.sortIndex
                )
            } else {
                node
            }
        }
        insertNodesScoped(nickname, safeNodes)
    }

    suspend fun getBookmarkByItemKey(itemKey: String): CollectionBookmark? = dao.getBookmarkByItemKey(itemKey)

    suspend fun getCollectionPhotoByKey(imageKey: String): CollectionPhoto? = dao.getCollectionPhotoByKey(imageKey)

    fun getLocalCollections(siteNickname: String): Flow<List<OfflineCollection>> = dao.getCollectionsForSite(siteNickname)

    suspend fun createLocalCollection(name: String, siteNickname: String): Long {
        return dao.createCollection(OfflineCollection(name = name, siteNickname = siteNickname))
    }

    suspend fun deleteLocalCollection(collectionId: Long) {
        dao.deleteCollection(collectionId)
    }

    suspend fun getCollectionById(collectionId: Long): OfflineCollection? {
        return dao.getCollectionById(collectionId)
    }

    fun getPhotosInCollection(collectionId: Long): Flow<List<CollectionPhoto>> {
        return dao.getPhotosInCollection(collectionId)
    }

    suspend fun addPhotoToCollection(photo: CollectionPhoto) {
        dao.addPhotoToCollection(photo)
    }

    suspend fun removePhotoFromCollection(imageKey: String, collectionId: Long) {
        dao.removePhotoFromCollection(imageKey, collectionId)
    }

    suspend fun updateDownloadStatus(imageKey: String, collectionId: Long, localPath: String, isDownloaded: Boolean) {
        dao.updateDownloadStatus(imageKey, collectionId, localPath, isDownloaded)
    }

    suspend fun updateDownloadStatusForAll(imageKey: String, filePath: String?, downloaded: Boolean) {
        dao.updateDownloadStatusForAll(imageKey, filePath, downloaded)
    }

    suspend fun getPendingDownloads(): List<CollectionPhoto> {
        return dao.getPendingDownloads()
    }

    fun getBookmarksForCollection(collectionId: Long): Flow<List<CollectionBookmark>> {
        return dao.getBookmarksForCollection(collectionId)
    }

    suspend fun addBookmark(bookmark: CollectionBookmark): Long {
        return dao.addBookmark(bookmark)
    }

    suspend fun removeBookmark(collectionId: Long, type: String, itemKey: String) {
        dao.removeBookmark(collectionId, type, itemKey)
    }

    suspend fun removeBookmarkGlobally(itemKey: String) {
        dao.removeBookmarkGlobally(itemKey)
    }

    suspend fun isBookmarked(collectionId: Long, type: String, itemKey: String): Boolean {
        return dao.isBookmarked(collectionId, type, itemKey)
    }

    suspend fun isBookmarkedAnywhere(type: String, itemKey: String): Boolean {
        return dao.isBookmarkedAnywhere(type, itemKey)
    }

    suspend fun renameLocalCollection(collectionId: Long, newName: String) {
        dao.renameCollection(collectionId, newName)
    }

    suspend fun insertSearchQuery(query: String, nickname: String = "") {
        if (query.isNotBlank()) {
            dao.insertSearchQuery(SearchHistory(query = query.trim(), nickname = nickname))
        }
    }

    fun getSearchHistory(nickname: String = ""): Flow<List<SearchHistory>> {
        return dao.getSearchHistory(nickname)
    }

    /**
     * Home's totals from the gallery index (design 3.6, Q3): null while the site's index is empty (first
     * launch, before the first crawl), so the caller keeps its page-1 numbers; then every crawl updates it.
     */
    fun siteTotals(nickname: String): Flow<com.smugview.app.data.db.SiteTotals?> =
        dao.siteTotals(nickname).map { if (it.galleries == 0) null else it }

    /** The index's gallery for a UrlPath (a photo's WebUri has no key), or null. Matches like SQLite's LOWER (A-Z). */
    suspend fun getAlbumByUrlPath(nickname: String, urlPath: String): CachedAlbum? {
        val path = urlPath.trim('/').map { if (it in 'A'..'Z') it + 32 else it }.joinToString("")
        return dao.getAlbumByUrlPath(nickname, path)
    }

    suspend fun clearSearchHistory(nickname: String = "") {
        if (nickname.isNotEmpty()) {
            dao.clearSearchHistory(nickname)
        } else {
            dao.clearAllSearchHistory()
        }
    }

    suspend fun deleteSearchQuery(query: String, nickname: String = "") {
        dao.deleteSearchQuery(query, nickname)
    }

    // Pre-flight API call: fetches only SecurityType + PasswordHint for a given album key.
    // Used by checkAndNavigateToAlbum when the cached_nodes DB has no access info.
    suspend fun getAlbumSecurityInfo(albumKey: String, apiKey: String): AlbumSecurityInfo? {
        return try {
            val response = api.getAlbum(
                albumKey = albumKey,
                apiKey = apiKey,
                filter = "SecurityType,PasswordHint",
                filterUri = "",
                verbosity = 1,
                ignoreErrors = "true"
            )
            AlbumSecurityInfo(
                securityType = response.response.album.securityType,
                passwordHint = response.response.album.passwordHint
            )
        } catch (e: Exception) {
            android.util.Log.w("SmugMugRepository", "getAlbumSecurityInfo failed for albumKey=$albumKey", e)
            null
        }
    }

    // Caches security info resolved from the pre-flight API call back into cached_nodes.
    suspend fun updateNodeAccess(nodeId: String, access: String?, passwordHint: String?) {
        dao.updateNodeAccess(nodeId, access, passwordHint)
    }

    suspend fun getUserAlbums(nickname: String, apiKey: String): List<AlbumDetails> {
        return api.getUserAlbums(nickname, apiKey).response.albums ?: emptyList()
    }

    suspend fun getUserAlbumsResponse(nickname: String, apiKey: String): com.smugview.app.data.api.UserAlbumsResponse {
        return api.getUserAlbums(nickname, apiKey)
    }

    suspend fun getUserAlbumsPreview(nickname: String, apiKey: String): List<com.smugview.app.data.api.AlbumPreview> {
        try {
            val response = api.getUserAlbums(nickname, apiKey)
            val albums = response.response.albums ?: emptyList()
            val expansions = response.expansions
            return albums.map { album ->
                val highlightUri = album.uris?.highlightImage
                val highlightUrl = if (highlightUri != null) {
                    val expansion = expansions?.get(highlightUri)
                    val thumb = expansion?.image?.thumbnailUrl
                    thumb?.replace("/Th/", "/M/")?.replace("/th/", "/m/")?.replace("-Th.", "-M.")?.replace("-th.", "-m.")
                } else null
                com.smugview.app.data.api.AlbumPreview(
                    title = album.name,
                    thumbnailUrl = highlightUrl
                )
            }
        } catch (e: Exception) {
            return emptyList()
        }
    }

    suspend fun getNode(nodeId: String, apiKey: String, ignoreErrors: String? = null): com.smugview.app.data.api.NodeData {
        return api.getNode(nodeId, apiKey, ignoreErrors = ignoreErrors).response.node
    }

    /**
     * Fetches the site root node's own HighlightImage — the site's actual configured header/cover
     * image — and caches it as a self-parented [CachedNode] (parentNodeId = null) so it's available
     * offline. Unlike [getNodeChildren], which only resolves highlight images for a folder's
     * *children*, the homepage banner needs the root folder's own highlight image.
     */
    suspend fun refreshSiteHeaderNode(nickname: String, rootNodeId: String, apiKey: String, ignoreErrors: String? = null): CachedNode? {
        return try {
            val response = api.getNode(rootNodeId, apiKey, expand = "HighlightImage", ignoreErrors = ignoreErrors)
            val node = response.response.node
            val highlightUri = node.uris.highlightImage
            val highlightUrl = if (highlightUri != null) {
                val thumb = response.expansions?.get(highlightUri)?.image?.thumbnailUrl
                thumb?.replace("/Th/", "/M/")?.replace("/th/", "/m/")?.replace("-Th.", "-M.")?.replace("-th.", "-m.")
            } else null

            val existing = dao.getNodeById(rootNodeId)
            val cachedNode = CachedNode(
                nodeId = node.nodeId,
                parentNodeId = null,
                type = node.type,
                title = node.name ?: existing?.title ?: "Home",
                description = node.description,
                access = node.securityType,
                passwordHint = node.passwordHint,
                uri = node.uri,
                childNodesUri = node.uris.childNodes,
                albumUri = node.uris.album,
                highlightImageUrl = highlightUrl ?: existing?.highlightImageUrl,
                childCount = existing?.childCount,
                sortIndex = existing?.sortIndex ?: 0,
                webUri = node.webUri,
                dateModified = node.dateModified
            )
            insertNodes(nickname, listOf(cachedNode))
            cachedNode
        } catch (e: Exception) {
            // Offline or the call failed — fall back to whatever we already have cached, if anything.
            dao.getNodeById(rootNodeId)
        }
    }

    /**
     * Which node holds the password that protects [nodeId] (a NodeID or an AlbumKey)?
     * Reads `node/{id}!parents` (self first, anonymous-readable) and returns the nearest ancestor
     * whose own SecurityType is Password. SmugMug never sends "Inherited" (findings #19), so the old
     * walk-while-Inherited stopped at the first sub-folder. [RootResolution.Unknown] means the lineage
     * could not be read; callers must not unlock or delete anything on Unknown.
     */
    override suspend fun resolvePasswordRootNodeId(nodeId: String, apiKey: String): RootResolution {
        return try {
            val chain = readLineage(nodeId, apiKey)
            if (chain.isNullOrEmpty()) return RootResolution.Unknown
            val root = chain.firstOrNull { it.securityType == "Password" }
            if (root != null) RootResolution.Resolved(root.nodeId) else RootResolution.NotProtected
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            RootResolution.Unknown
        }
    }

    /**
     * `node/{id}!parents` for a NodeID or an AlbumKey: self first, then each ancestor, then the site
     * root. An AlbumKey is mapped to its NodeID via the cached row or the index row; an AlbumKey that
     * is in neither is asked of `album/{key}` after the first 404. Throws on any other failure.
     */
    private suspend fun readLineage(idOrKey: String, apiKey: String): List<ParentNodeData>? {
        val mappedId = dao.getNodeByIdOrKey(idOrKey)?.nodeId
            ?: dao.getAlbumNodeIdByKey(idOrKey)
            ?: idOrKey
        suspend fun parentsOf(id: String): List<ParentNodeData>? =
            api.getNodeParents(id, apiKey, ignoreErrors = "true").response.nodes
        return try {
            parentsOf(mappedId)
        } catch (e: retrofit2.HttpException) {
            if (e.code() != 404) throw e
            val realId = api.getAlbum(mappedId, apiKey, ignoreErrors = "true").response.album.nodeId
            if (realId.isNullOrEmpty() || realId == mappedId) null else parentsOf(realId)
        }
    }

    /** The node as the API describes it, in memory only: nothing here knows the real parent (R-01), so no row is placed. */
    override suspend fun fetchNode(nodeId: String, apiKey: String): CachedNode? = try {
        val apiNode = getNode(nodeId, apiKey, ignoreErrors = "true")
        CachedNode(
            nodeId = apiNode.nodeId,
            parentNodeId = null,
            type = apiNode.type,
            title = apiNode.name ?: "Folder",
            description = apiNode.description,
            access = apiNode.securityType ?: apiNode.privacy ?: "Public",
            passwordHint = apiNode.passwordHint,
            uri = apiNode.uri,
            childNodesUri = apiNode.uris.childNodes,
            albumUri = apiNode.uris.album,
            highlightImageUrl = null,
            childCount = null,
            sortIndex = 0,
            webUri = apiNode.webUri,
            dateModified = apiNode.dateModified
        )
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    suspend fun getAllDescendants(nodeId: String): List<CachedNode> {
        return dao.getAllDescendants(nodeId)
    }

    fun searchNodesRemote(
        nickname: String,
        scopeUri: String,
        scopeKey: String,
        query: String,
        apiKey: String
    ): Flow<Result<List<CachedNode>>> = flow {
        try {
            SmugLog.d("SmugMugRepository") { "searchNodesRemote starting: scopeUri=$scopeUri, scopeKey=$scopeKey, query=$query" }
            val response = api.searchNodes(apiKey, scopeUri, query)
            val apiNodes = response.response.nodes ?: emptyList()
            val existingIds = HashSet<String>()
            SmugLog.d("SmugMugRepository") { "searchNodesRemote API returned ${apiNodes.size} nodes" }
            val dbNodes = apiNodes.mapIndexed { index, node ->
                val highlightUri = node.uris.highlightImage
                val highlightUrl = if (highlightUri != null) {
                    val expansion = response.expansions?.get(highlightUri)
                    val thumb = expansion?.image?.thumbnailUrl
                    thumb?.replace("/Th/", "/M/")?.replace("/th/", "/m/")?.replace("-Th.", "-M.")?.replace("-th.", "-m.")
                } else null

                // A listing owns where a node lives (R-04): a hit keeps the row it already has, and an
                // unknown one is parked under "search_result" (Uris.ParentNode is its own !parent, R-01).
                val existing = dao.getNodeById(node.nodeId)
                if (existing != null) existingIds.add(node.nodeId)
                val parentId = existing?.parentNodeId ?: "search_result"

                CachedNode(
                    nodeId = node.nodeId,
                    parentNodeId = parentId,
                    type = node.type,
                    title = node.name ?: "Untitled",
                    description = node.description,
                    access = node.securityType,
                    passwordHint = node.passwordHint,
                    uri = node.uri,
                    childNodesUri = node.uris.childNodes,
                    albumUri = node.uris.album,
                    highlightImageUrl = highlightUrl,
                    sortIndex = index,
                    webUri = node.webUri,
                    dateModified = node.dateModified
                )
            }
            dao.deleteSearchResultsForQueryAndType(query, scopeKey, "Folder")
            dao.deleteSearchResultsForQueryAndType(query, scopeKey, "Album")
            SmugLog.d("SmugMugRepository") { "searchNodesRemote deleted old search results for query=$query, scopeKey=$scopeKey" }
            if (dbNodes.isNotEmpty()) {
                // Only rows that don't exist yet: a hit never overwrites a listed row's sortIndex, title or parent.
                val fresh = dbNodes.filter { it.nodeId !in existingIds }
                if (fresh.isNotEmpty()) insertNodesScoped(nickname, fresh)
                SmugLog.d("SmugMugRepository") { "searchNodesRemote inserted ${fresh.size} new nodes into cached_nodes" }
                val searchResults = dbNodes.mapIndexed { index, node ->
                    node.toSearchResult(query, scopeKey, index)
                }
                dao.insertSearchResults(searchResults)
                SmugLog.d("SmugMugRepository") { "searchNodesRemote inserted ${searchResults.size} search results into search_results" }
            }
            emit(Result.success(dbNodes))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.e("SmugMugRepository", "searchNodesRemote error: ", e)
            emit(Result.failure(e))
        }
    }.flowOn(Dispatchers.IO)

    suspend fun insertSearchResults(results: List<SearchResult>) {
        dao.insertSearchResults(results)
    }

    suspend fun getSearchResultNodes(query: String, scope: String, type: String): List<CachedNode> {
        val remoteResults = dao.getSearchResults(query, scope, type).map { result ->
            val cached = dao.getNodeById(result.itemKey)
            CachedNode(
                nodeId = result.itemKey,
                parentNodeId = cached?.parentNodeId ?: "search_result",
                type = result.itemType,
                title = result.title,
                description = result.description,
                access = result.access,
                passwordHint = result.passwordHint,
                uri = result.uri ?: "",
                childNodesUri = result.childNodesUri,
                albumUri = result.albumUri,
                highlightImageUrl = result.thumbnailUrl,
                sortIndex = result.sortIndex,
                webUri = result.webUri
            )
        }
        
        // Also fetch local DB matches for nodes matching this scope and query.
        // isGlobal: scope is blank, the legacy "site" sentinel, or the new "site:<nickname>" pattern.
        val isGlobal = scope == "site" || scope.isBlank() || scope.startsWith("site:")
        val nickname = if (scope.startsWith("site:")) scope.removePrefix("site:") else ""
        val localResults = if (isGlobal) {
            dao.searchNodesGlobal(query, type, nickname)
        } else {
            dao.searchNodesInScope(scope, query, type)
        }
        
        val combined = (remoteResults + localResults).distinctBy { it.nodeId }
        SmugLog.d("SmugMugRepository") { "getSearchResultNodes combined: query=$query, scope=$scope, type=$type, remoteSize=${remoteResults.size}, localSize=${localResults.size}, combinedSize=${combined.size}" }
        return combined
    }

    suspend fun getSearchResultPhotos(query: String, scope: String): List<AlbumImageData> {
        val results = dao.getSearchResults(query, scope, "Photo")
        SmugLog.d("SmugMugRepository") { "getSearchResultPhotos DB: query=$query, scope=$scope, resultsSize=${results.size}" }
        return results.map { it.toAlbumImageData() }
    }

    suspend fun deleteSearchResultsForQueryAndType(query: String, scope: String, type: String) {
        dao.deleteSearchResultsForQueryAndType(query, scope, type)
    }

    suspend fun hasSearchResultInDb(query: String, scope: String): Boolean {
        return dao.getSearchResults(query, scope, "Folder").isNotEmpty() ||
               dao.getSearchResults(query, scope, "Album").isNotEmpty() ||
               dao.getSearchResults(query, scope, "Photo").isNotEmpty()
    }

    suspend fun hasSearchPhotosInDb(query: String, scope: String): Boolean {
        return dao.getSearchResults(query, scope, "Photo").isNotEmpty()
    }

    suspend fun getHighestPasswordProtectedParent(nodeId: String): CachedNode? {
        var current: CachedNode? = dao.getNodeById(nodeId)
        var highestPasswordNode: CachedNode? = null
        
        while (current != null) {
            if (current.access == "Password") {
                highestPasswordNode = current
            }
            val parentId = current.parentNodeId
            if (parentId == null || parentId == current.nodeId || parentId == "root" || parentId == "search_result") {
                break
            }
            current = dao.getNodeById(parentId)
        }
        return highestPasswordNode
    }

    /** NodeIDs that carry a dot on [nickname]'s site: one DAO query owns it (design 3.5). */
    fun getNodesWithActiveUpdates(nickname: String): Flow<List<String>> {
        return dao.getNodesWithActiveUpdates(nickname)
    }

    /**
     * Is the gallery [albumKey] (an AlbumKey, or a NodeID) lit right now? Answered by the SAME query that
     * draws the dot ([getNodesWithActiveUpdates]), so there is one owner of "is something new here?".
     * A gallery that is lit opens from the network (step 4-6: the dot says the cached copy is behind).
     * Any failure reads as "not lit": the cache policy still never reuses a pre-unlock response.
     */
    suspend fun isGalleryLit(albumKey: String, nickname: String): Boolean {
        if (nickname.isEmpty()) return false
        return try {
            val nodeId = dao.getAlbumByKey(albumKey)?.nodeId
            val lit = dao.getNodesWithActiveUpdates(nickname).first()
            lit.contains(albumKey) || (nodeId != null && lit.contains(nodeId))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Marks a gallery, or every gallery below a folder, as viewed: the viewed mark becomes the index
     * `ImagesLastUpdated` (the same date the dot compares), not a node DateModified (design 3.5).
     */
    suspend fun markNodeAsViewed(nodeId: String) {
        dao.markViewedAtOrBelow(nodeId)
    }

    fun searchPublicSites(query: String, apiKey: String): Flow<Result<List<DiscoveredSite>>> = flow {
        try {
            val userSearchResponse = api.searchUsers(apiKey, query)
            val matchedUsers = userSearchResponse.response.users ?: emptyList()
            
            // Limit to top 6 portfolios to avoid excessive parallel queries
            val topUsers = matchedUsers.take(6)
            
            val discovered = coroutineScope {
                topUsers.map { user ->
                    async {
                        val photos = try {
                            val imagesResponse = api.getUserRecentImages(user.nickName, apiKey, count = 4)
                            imagesResponse.response.images ?: emptyList()
                        } catch (e: Exception) {
                            emptyList()
                        }
                        
                        DiscoveredSite(
                            nickname = user.nickName,
                            webUri = user.webUri ?: "https://${user.nickName}.smugmug.com",
                            previewPhotos = photos
                        )
                    }
                }.awaitAll()
            }
            
            emit(Result.success(discovered))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emit(Result.failure(e))
        }
    }.flowOn(Dispatchers.IO)
}

fun SearchResult.toCachedNode(): CachedNode {
    return CachedNode(
        nodeId = itemKey,
        parentNodeId = "search_result",
        type = itemType,
        title = title,
        description = description,
        access = access,
        passwordHint = passwordHint,
        uri = uri ?: "",
        childNodesUri = childNodesUri,
        albumUri = albumUri,
        highlightImageUrl = thumbnailUrl,
        sortIndex = sortIndex,
        webUri = webUri
    )
}

fun CachedNode.toSearchResult(query: String, scope: String, index: Int): SearchResult {
    return SearchResult(
        searchQuery = query,
        searchScope = scope,
        itemKey = nodeId,
        itemType = type,
        title = title,
        description = description,
        thumbnailUrl = highlightImageUrl,
        access = access,
        passwordHint = passwordHint,
        uri = uri,
        childNodesUri = childNodesUri,
        albumUri = albumUri,
        webUri = webUri,
        sortIndex = index
    )
}

fun SearchResult.toAlbumImageData(): AlbumImageData {
    return AlbumImageData(
        imageKey = itemKey,
        title = title,
        caption = description,
        thumbnailUrl = thumbnailUrl,
        archivedUri = archivedUri,
        date = date,
        dateTime = date,
        fileName = fileName,
        keywords = keywords,
        webUri = webUri,
        originalWidth = originalWidth,
        originalHeight = originalHeight,
        originalSize = originalSize,
        format = format,
        uris = albumUri?.let { com.smugview.app.data.api.AlbumImageUris(album = it, imageAlbum = it) },
        videoUrl = videoUrl
    )
}

fun AlbumImageData.toSearchResult(query: String, scope: String, index: Int): SearchResult {
    return SearchResult(
        searchQuery = query,
        searchScope = scope,
        itemKey = imageKey,
        itemType = "Photo",
        title = title ?: "",
        description = caption,
        thumbnailUrl = thumbnailUrl,
        archivedUri = archivedUri,
        date = date,
        format = format,
        originalWidth = originalWidth,
        originalHeight = originalHeight,
        fileName = fileName,
        originalSize = originalSize,
        keywords = keywordsString,
        videoUrl = videoUrl,
        webUri = webUri,
        albumUri = uris?.imageAlbum ?: uris?.album,
        sortIndex = index
    )
}

data class DiscoveredSite(
    val nickname: String,
    val webUri: String?,
    val previewPhotos: List<com.smugview.app.data.api.AlbumImageData>
)

fun extractNicknameFromWebUri(webUri: String?): String? {
    if (webUri == null) return null
    try {
        val host = java.net.URI(webUri).host ?: return null
        if (host.endsWith(".smugmug.com")) {
            val sub = host.substringBefore(".smugmug.com")
            if (sub.isNotEmpty() && sub != "www" && sub != "api") {
                return sub
            }
        }
    } catch (e: Exception) {
        try {
            val withoutScheme = webUri.substringAfter("://")
            val host = withoutScheme.substringBefore("/")
            if (host.endsWith(".smugmug.com")) {
                val sub = host.substringBefore(".smugmug.com")
                if (sub.isNotEmpty() && sub != "www" && sub != "api") {
                    return sub
                }
            }
        } catch (e2: Exception) {
            // ignore
        }
    }
    return null
}

data class AlbumSecurityInfo(
    val securityType: String?,
    val passwordHint: String?
)


