package com.smugview.app.data.repository

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import com.smugview.app.data.api.AlbumImageData
import com.smugview.app.data.api.AlbumDetails
import com.smugview.app.data.api.AlbumImagesResponse
import com.smugview.app.data.api.AlbumKeywordsResponse
import com.smugview.app.data.api.isVideo
import com.smugview.app.data.api.ImageSearchResponse
import com.smugview.app.data.api.ExifData
import com.smugview.app.data.api.SmugMugApi
import com.smugview.app.data.api.NodeData
import com.smugview.app.data.db.CachedNode
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
import okhttp3.ResponseBody.Companion.toResponseBody
import kotlinx.coroutines.CancellationException
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
    @ApplicationContext private val context: Context
) {
    companion object {
        var isTesting = false
    }

    private val nodeLocks = ConcurrentHashMap<String, Mutex>()

    private val _albumsCache = kotlinx.coroutines.flow.MutableStateFlow<List<CachedNode>>(emptyList())
    val albumsCache: kotlinx.coroutines.flow.StateFlow<List<CachedNode>> = _albumsCache

    private val _isAlbumsCacheLoaded = kotlinx.coroutines.flow.MutableStateFlow(false)
    val isAlbumsCacheLoaded: kotlinx.coroutines.flow.StateFlow<Boolean> = _isAlbumsCacheLoaded
    // Helper to parse NodeID from Uri path
    fun parseNodeIdFromUri(uri: String): String {
        return uri.substringAfterLast("/").substringBefore("!")
    }

    fun getUserProfile(nickname: String, apiKey: String, ignoreErrors: String? = null): Flow<Result<com.smugview.app.data.api.UserData>> = flow {
        try {
            val userResponse = api.getUserProfile(nickname, apiKey, ignoreErrors = ignoreErrors)
            val bioImageKey = userResponse.expansions?.values?.firstOrNull { it.bioImage != null }?.bioImage?.imageKey
            val enrichedUser = userResponse.response.user.copy(bioImageKey = bioImageKey)
            emit(Result.success(enrichedUser))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emit(Result.failure(e))
        }
    }.flowOn(Dispatchers.IO)

    // Resolves nickname to root node ID
    fun getUserRootNodeId(nickname: String, apiKey: String): Flow<Result<String>> = flow {
        try {
            val response = api.getUserProfile(nickname, apiKey)
            val nodeUri = response.response.user.uris.node
            val nodeId = parseNodeIdFromUri(nodeUri)
            emit(Result.success(nodeId))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emit(Result.failure(e))
        }
    }.flowOn(Dispatchers.IO)

    // Fetches children nodes (with offline cache boundary)
    suspend fun clearEntireCache() {
        dao.clearAllCachedNodes()
        dao.clearSearchHistory()
    }

    fun getNodeChildren(
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
                val allApiNodes = mutableListOf<com.smugview.app.data.api.NodeData>()
                val allExpansions = mutableMapOf<String, com.smugview.app.data.api.ExpansionContainer>()

                val response = try {
                    if (nodeId.startsWith("virtual:")) {
                        com.smugview.app.data.api.NodeListResponse(
                            com.smugview.app.data.api.NodeListPayload(emptyList())
                        )
                    } else {
                        api.getNodeChildren(nodeId, apiKey, password, count = 100, ignoreErrors = ignoreErrors)
                    }
                } catch (e: Exception) {
                    if (!password.isNullOrEmpty() && (e is retrofit2.HttpException && (e.code() == 401 || e.code() == 404))) {
                        val unlocked = unlockInheritedPasswordRoot(nodeId, apiKey, password)
                        if (unlocked) {
                            api.getNodeChildren(nodeId, apiKey, password, count = 100, ignoreErrors = ignoreErrors)
                        } else {
                            throw e
                        }
                    } else {
                        // For real nodes, 401 or 404 without a password often means it's password protected or private.
                        // Throw the error so the UI can catch it and prompt for a password.
                        throw e
                    }
                }
                response.response.nodes?.let { allApiNodes.addAll(it) }
                response.expansions?.let { allExpansions.putAll(it) }

                var nextUrl = response.response.pages?.next
                var pageNum = 1
                while (nextUrl != null && !nodeId.startsWith("virtual:")) {
                    pageNum++
                    if (com.smugview.app.BuildConfig.DEBUG) {
                        android.util.Log.d("SmugMugRepository", "getNodeChildren: fetching page $pageNum for nodeId=$nodeId via nextUrl=$nextUrl")
                    }
                    kotlinx.coroutines.delay(100)
                    val overriddenUrl = overrideUrlCount(nextUrl, 100)
                    val nextResponse = api.getNodeChildrenByUri(overriddenUrl, apiKey, password, ignoreErrors = ignoreErrors)
                    nextResponse.response.nodes?.let { allApiNodes.addAll(it) }
                    nextResponse.expansions?.let { allExpansions.putAll(it) }
                    nextUrl = nextResponse.response.pages?.next
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
                        webUri = node.webUri
                    )
                }

                // Save to database
                dao.insertNodes(dbNodes)
                dao.updateChildCount(nodeId, dbNodes.size)
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

    // Fetch album details
    suspend fun getAlbum(
        albumKey: String,
        apiKey: String,
        password: String? = null
    ): AlbumDetails? {
        return try {
            try {
                api.getAlbum(albumKey, apiKey, password).response.album
            } catch (e: Exception) {
                if (!password.isNullOrEmpty() && (e is retrofit2.HttpException && (e.code() == 401 || e.code() == 404))) {
                    val unlocked = unlockInheritedPasswordRoot(albumKey, apiKey, password)
                    if (unlocked) {
                        api.getAlbum(albumKey, apiKey, password).response.album
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
    }

    private fun overrideUrlCount(url: String, newCount: Int = 500): String {
        val countOverridden = if (url.contains("count=")) {
            url.replace(Regex("count=\\d+"), "count=$newCount")
        } else {
            val separator = if (url.contains("?")) "&" else "?"
            "$url${separator}count=$newCount"
        }
        return if (countOverridden.contains("_verbosity=")) {
            countOverridden.replace(Regex("_verbosity=\\d+"), "_verbosity=1")
        } else {
            val separator = if (countOverridden.contains("?")) "&" else "?"
            "$countOverridden${separator}_verbosity=1"
        }
    }

    private fun overrideUrlCountAndStart(url: String, newCount: Int = 500, newStart: Int): String {
        val countOverridden = if (url.contains("count=")) {
            url.replace(Regex("count=\\d+"), "count=$newCount")
        } else {
            val separator = if (url.contains("?")) "&" else "?"
            "$url${separator}count=$newCount"
        }
        val startOverridden = if (countOverridden.contains("start=")) {
            countOverridden.replace(Regex("start=\\d+"), "start=$newStart")
        } else {
            val separator = if (countOverridden.contains("?")) "&" else "?"
            "$countOverridden${separator}start=$newStart"
        }
        return if (startOverridden.contains("_verbosity=")) {
            startOverridden.replace(Regex("_verbosity=\\d+"), "_verbosity=1")
        } else {
            val separator = if (startOverridden.contains("?")) "&" else "?"
            "$startOverridden${separator}_verbosity=1"
        }
    }

    suspend fun getAlbumImagesPage(
        albumKey: String,
        apiKey: String,
        password: String? = null
    ): AlbumImagesResponse {
        return try {
            val response = api.getAlbumImages(albumKey, apiKey, password)
            val images = response.response.images
            
            // If the response is successful but images is null or empty, and we have a password, try unlocking parent root
            if ((images == null || images.isEmpty()) && !password.isNullOrEmpty()) {
                val unlocked = unlockInheritedPasswordRoot(albumKey, apiKey, password)
                if (unlocked) {
                    val retryResponse = api.getAlbumImages(albumKey, apiKey, password)
                    if (retryResponse.response.images != null && retryResponse.response.images.isNotEmpty()) {
                        return retryResponse
                    }
                }
            }
            
            if (response.response.images == null) {
                throw retrofit2.HttpException(retrofit2.Response.error<Any>(401, "".toResponseBody(null)))
            }
            response
        } catch (e: Exception) {
            if (!password.isNullOrEmpty() && (e is retrofit2.HttpException && (e.code() == 401 || e.code() == 404))) {
                val unlocked = unlockInheritedPasswordRoot(albumKey, apiKey, password)
                if (unlocked) {
                    val retryResponse = api.getAlbumImages(albumKey, apiKey, password)
                    if (retryResponse.response.images == null) {
                        throw e
                    }
                    retryResponse
                } else {
                    throw e
                }
            } else {
                throw e
            }
        }
    }

    suspend fun getAlbumImagesPageByUri(
        nextUrl: String,
        apiKey: String,
        password: String? = null
    ): AlbumImagesResponse {
        val overriddenUrl = overrideUrlCount(nextUrl, 500)
        val absoluteUrl = if (overriddenUrl.startsWith("http")) overriddenUrl else "https://api.smugmug.com$overriddenUrl"
        return api.getAlbumImagesByUri(absoluteUrl, apiKey, password)
    }

    suspend fun getUserAlbumsByUri(url: String, apiKey: String): com.smugview.app.data.api.UserAlbumsResponse {
        return api.getUserAlbumsByUri(url, apiKey)
    }

    suspend fun getAlbumKeywords(albumKeys: List<String>, apiKey: String, password: String? = null): com.smugview.app.data.api.AlbumKeywordsResponse {
        val keysString = albumKeys.joinToString(",")
        return api.getAlbumKeywords(keysString, apiKey, password)
    }

    suspend fun getUserTopKeywords(nickname: String, apiKey: String, nodeId: String? = null): com.smugview.app.data.api.TopKeywordsResponse {
        return api.getUserTopKeywords(nickname, apiKey, nodeId)
    }

    suspend fun getImagesByKeyword(
        scope: String?,
        keywords: String,
        apiKey: String,
        count: Int = 500,
        start: Int = 1
    ): List<AlbumImageData> {
        val spaceSeparatedText = keywords.replace(",", " ")
        val allImages = mutableListOf<AlbumImageData>()
        try {
            var response = api.getImagesByKeyword(
                apiKey = apiKey,
                scope = scope,
                text = spaceSeparatedText,
                count = count,
                start = start
            )
            response.response.images?.let { allImages.addAll(elements = it) }
            
            var nextUrl = response.response.pages?.next
            var pageCount = 1
            var nextStart = start + (response.response.images?.size ?: 0)
            while (nextUrl != null && (!isTesting || pageCount < 2)) {
                val overriddenUrl = overrideUrlCountAndStart(nextUrl, 500, nextStart)
                val absoluteUrl = if (overriddenUrl.startsWith(prefix = "http")) overriddenUrl else "https://api.smugmug.com$overriddenUrl"
                response = api.searchImagesByUri(
                    url = absoluteUrl,
                    apiKey = apiKey
                )
                val pageImages = response.response.images ?: emptyList()
                pageImages.let { allImages.addAll(elements = it) }
                nextUrl = response.response.pages?.next
                nextStart += pageImages.size
                pageCount++
                kotlinx.coroutines.delay(100)
            }
        } catch (e: Exception) {
            android.util.Log.e("SmugMugRepository", "Failed to getImagesByKeyword", e)
        }
        return allImages
    }

    suspend fun updateImageMetadata(imageKey: String, apiKey: String, keywords: String): Boolean {
        val body = com.smugview.app.data.api.UpdateImageMetadataRequest(keywords)
        val response = api.updateImageMetadata(imageKey, apiKey, body)
        return response.isSuccessful
    }

    // Fetch all album images recursively following pagination links
    suspend fun getAllAlbumImages(
        albumKey: String,
        apiKey: String,
        password: String? = null
    ): List<AlbumImageData> {
        val allImages = mutableListOf<AlbumImageData>()
        try {
            var response = try {
                val res = api.getAlbumImages(albumKey, apiKey, password)
                if (res.response.images == null) {
                    throw retrofit2.HttpException(retrofit2.Response.error<Any>(401, "".toResponseBody(null)))
                }
                res
            } catch (e: Exception) {
                if (!password.isNullOrEmpty() && (e is retrofit2.HttpException && (e.code() == 401 || e.code() == 404))) {
                    val unlocked = unlockAlbum(albumKey, apiKey, password)
                    if (unlocked) {
                        val retryRes = api.getAlbumImages(albumKey, apiKey, password)
                        if (retryRes.response.images == null) {
                            throw e
                        }
                        retryRes
                    } else {
                        throw e
                    }
                } else {
                    throw e
                }
            }
            response.response.images?.let { allImages.addAll(it) }
            var nextUrl = response.response.pages?.next
            var pageCount = 1
            while (nextUrl != null && (!isTesting || pageCount < 2)) {
                // Ensure nextUrl is relative to Retrofit's base URL if required, or absolute
                // SmugMug nextUri starts with /api/v2/... Retrofit @Url supports relative/absolute paths.
                val overriddenUrl = overrideUrlCount(nextUrl, 500)
                val absoluteUrl = if (overriddenUrl.startsWith("http")) overriddenUrl else "https://api.smugmug.com$overriddenUrl"
                response = api.getAlbumImagesByUri(absoluteUrl, apiKey, password)
                response.response.images?.let { allImages.addAll(it) }
                nextUrl = response.response.pages?.next
                pageCount++
                kotlinx.coroutines.delay(100)
            }
        } catch (e: Exception) {
            // Propagate or log
            throw e
        }
        return allImages
    }

    // Paging flow for album photos
    fun getAlbumImages(
        albumKey: String,
        apiKey: String,
        password: String? = null
    ): Flow<PagingData<AlbumImageData>> {
        return Pager(
            config = PagingConfig(
                pageSize = 20,
                enablePlaceholders = false
            ),
            pagingSourceFactory = { PhotoPagingSource(api, apiKey, albumKey, password) }
        ).flow
    }


    suspend fun syncAllUserAlbums(nickname: String, apiKey: String, rootNodeId: String) {
        try {
            val response = api.getUserAlbums(nickname, apiKey)
            val albums = response.response.albums ?: emptyList()
            
            val nodesToInsert = mutableMapOf<String, CachedNode>()
            
            albums.forEach { album ->
                val pathStr = album.urlPath ?: ""
                val pathParts = pathStr.split("/").filter { it.isNotEmpty() }
                
                var parentId = rootNodeId
                var currentPath = ""
                
                // Construct virtual folder nodes for all parts EXCEPT the last one
                for (i in 0 until pathParts.size - 1) {
                    val part = pathParts[i]
                    currentPath += "/" + part
                    val folderNodeId = "virtual:$currentPath"
                    
                    if (!nodesToInsert.containsKey(folderNodeId)) {
                        nodesToInsert[folderNodeId] = CachedNode(
                            nodeId = folderNodeId,
                            parentNodeId = parentId,
                            type = "Folder",
                            title = part,
                            description = null,
                            access = "Public",
                            passwordHint = null,
                            uri = folderNodeId,
                            childNodesUri = null,
                            albumUri = null,
                            highlightImageUrl = null,
                            childCount = null,
                            sortIndex = 0,
                            webUri = null
                        )
                    }
                    parentId = folderNodeId
                }
                
                val actualNodeId = album.nodeId ?: album.albumKey
                val existing = dao.getNodeById(actualNodeId)
                val albumNode = CachedNode(
                    nodeId = actualNodeId,
                    parentNodeId = existing?.parentNodeId?.takeIf { it != "root" && !it.startsWith("virtual:") } ?: parentId,
                    type = "Album",
                    title = album.name,
                    description = existing?.description,
                    access = album.securityType ?: existing?.access ?: "Public",
                    passwordHint = album.passwordHint ?: existing?.passwordHint,
                    uri = album.uri,
                    childNodesUri = existing?.childNodesUri,
                    albumUri = album.uri,
                    highlightImageUrl = existing?.highlightImageUrl,
                    childCount = album.imageCount ?: existing?.childCount,
                    sortIndex = existing?.sortIndex ?: 0,
                    webUri = album.webUri ?: existing?.webUri
                )
                nodesToInsert[actualNodeId] = albumNode
            }
            
            if (nodesToInsert.isNotEmpty()) {
                dao.insertNodes(nodesToInsert.values.toList())
            }
        } catch (e: Exception) {
            android.util.Log.e("SmugMugRepository", "API syncAllUserAlbums failed", e)
        }
    }

    suspend fun buildInMemoryGalleryCache(nickname: String, apiKey: String) {
        if (com.smugview.app.BuildConfig.DEBUG) {
            android.util.Log.d("SmugMugRepository", "buildInMemoryGalleryCache starting for user=$nickname")
        }
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            _isAlbumsCacheLoaded.value = false
            val allAlbums = mutableListOf<CachedNode>()
            try {
                var response = api.getUserAlbums(nickname, apiKey)
                
                while (true) {
                    val albums = response.response.albums ?: emptyList()
                    if (com.smugview.app.BuildConfig.DEBUG) {
                        android.util.Log.d("SmugMugRepository", "buildInMemoryGalleryCache page fetched ${albums.size} albums")
                    }
                    val expansions = response.expansions
                    
                    val parsedNodes = albums.mapIndexed { index, album ->
                        val highlightUri = album.uris?.highlightImage
                        val highlightUrl = if (highlightUri != null) {
                            val expansion = expansions?.get(highlightUri)
                            val thumb = expansion?.image?.thumbnailUrl
                            thumb?.replace("/Th/", "/M/")?.replace("/th/", "/m/")?.replace("-Th.", "-M.")?.replace("-th.", "-m.")
                        } else null
                        
                        val actualNodeId = album.nodeId ?: album.albumKey
                        CachedNode(
                            nodeId = actualNodeId,
                            parentNodeId = "root",
                            type = "Album",
                            title = album.name,
                            description = null,
                            access = album.securityType ?: "Public",
                            passwordHint = album.passwordHint,
                            uri = album.uri,
                            childNodesUri = null,
                            albumUri = album.uri,
                            highlightImageUrl = highlightUrl,
                            childCount = album.imageCount,
                            sortIndex = allAlbums.size + index,
                            webUri = album.webUri
                        )
                    }
                    allAlbums.addAll(parsedNodes)
                    
                    val nextUrl = response.response.pages?.next
                    if (nextUrl == null) break
                    kotlinx.coroutines.delay(100)
                    val overriddenUrl = overrideUrlCount(nextUrl, 100)
                    response = api.getUserAlbumsByUri(overriddenUrl, apiKey)
                }
                _albumsCache.value = allAlbums
                if (com.smugview.app.BuildConfig.DEBUG) {
                    android.util.Log.d("SmugMugRepository", "buildInMemoryGalleryCache completed: total cached albums = ${allAlbums.size}")
                }
                Unit
            } catch (e: Exception) {
                android.util.Log.e("SmugMugRepository", "Failed to build in-memory gallery cache", e)
            } finally {
                _isAlbumsCacheLoaded.value = true
            }
        }
    }

    fun getSavedPasswordForNode(node: CachedNode): String? {
        val passwordPrefs = context.getSharedPreferences("smugview_passwords", Context.MODE_PRIVATE)
        val albumKey = node.getAlbumKey()
        var saved = passwordPrefs.getString(albumKey, null)
        if (!saved.isNullOrEmpty()) return saved
        saved = passwordPrefs.getString(node.nodeId, null)
        if (!saved.isNullOrEmpty()) return saved
        saved = passwordPrefs.getString(node.parentNodeId, null)
        if (!saved.isNullOrEmpty()) return saved
        return null
    }

    suspend fun performBackgroundSearchImages(
        nickname: String,
        scopeUri: String?,
        scopeKey: String,
        query: String,
        apiKey: String,
        password: String? = null
    ) {
        if (com.smugview.app.BuildConfig.DEBUG) {
            android.util.Log.d("SmugMugRepository", "performBackgroundSearchImages starting: user=$nickname, scopeUri=$scopeUri, scopeKey=$scopeKey, query=$query")
        }
        try {
            dao.deleteSearchResultsForQueryAndType(query = query, scope = scopeKey, type = "Photo")

            val targetScope = scopeUri ?: "/api/v2/node/4zqWw"
            if (com.smugview.app.BuildConfig.DEBUG) {
                android.util.Log.d("SmugMugRepository", "performBackgroundSearchImages calling searchImages with scope=$targetScope")
            }
            val response = api.searchImages(
                apiKey = apiKey,
                scope = targetScope,
                text = query,
                start = 1,
                count = 500
            )

            var insertedCount = 0
            val insertedKeys = mutableSetOf<String>()

            var nextUrl = response.response.pages?.next
            val pageImages = response.response.images ?: emptyList()
            if (com.smugview.app.BuildConfig.DEBUG) {
                android.util.Log.d("SmugMugRepository", "performBackgroundSearchImages page 1 returned ${pageImages.size} images")
            }
            val pageExpansions = response.expansions
            if (pageExpansions != null) {
                pageImages.forEach { img ->
                    if (img.isVideo) {
                        val largestVideoUri = img.uris?.largestVideo
                        if (largestVideoUri != null) {
                            img.videoUrl = pageExpansions[largestVideoUri]?.largestVideo?.url
                        }
                    }
                }
            }
            
            val filteredPageImages = pageImages.filter { img -> insertedKeys.add(img.imageKey) }
            if (filteredPageImages.isNotEmpty()) {
                dao.insertSearchResults(
                    results = filteredPageImages.mapIndexed { index, img ->
                        img.toSearchResult(query = query, scope = scopeKey, index = insertedCount + index)
                    }
                )
                insertedCount += filteredPageImages.size
            }

            var pageCount = 1
            var nextStart = 1 + pageImages.size
            while (nextUrl != null && (!isTesting || pageCount < 2)) {
                val overriddenUrl = overrideUrlCountAndStart(nextUrl, 500, nextStart)
                val absoluteUrl = if (overriddenUrl.startsWith("http")) overriddenUrl else "https://api.smugmug.com$overriddenUrl"
                if (com.smugview.app.BuildConfig.DEBUG) {
                    android.util.Log.d("SmugMugRepository", "performBackgroundSearchImages calling searchImagesUserByUri: page ${pageCount + 1}")
                }
                val nextPageResponse = api.searchImagesUserByUri(
                    url = absoluteUrl,
                    apiKey = apiKey,
                    password = password
                )
                val nextPageImages = nextPageResponse.response.images ?: emptyList()
                if (com.smugview.app.BuildConfig.DEBUG) {
                    android.util.Log.d("SmugMugRepository", "performBackgroundSearchImages page ${pageCount + 1} returned ${nextPageImages.size} images")
                }
                val nextPageExpansions = nextPageResponse.expansions
                if (nextPageExpansions != null) {
                    nextPageImages.forEach { img ->
                        if (img.isVideo) {
                            val largestVideoUri = img.uris?.largestVideo
                            if (largestVideoUri != null) {
                                img.videoUrl = nextPageExpansions[largestVideoUri]?.largestVideo?.url
                            }
                        }
                    }
                }
                
                val filteredNextPageImages = nextPageImages.filter { img -> insertedKeys.add(img.imageKey) }
                if (filteredNextPageImages.isNotEmpty()) {
                    dao.insertSearchResults(
                        results = filteredNextPageImages.mapIndexed { index, img ->
                            img.toSearchResult(query = query, scope = scopeKey, index = insertedCount + index)
                        }
                    )
                    insertedCount += filteredNextPageImages.size
                }
                nextUrl = nextPageResponse.response.pages?.next
                nextStart += nextPageImages.size
                pageCount++
                kotlinx.coroutines.delay(100)
            }
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

    suspend fun searchFolders(scopeNodeId: String?, query: String): List<CachedNode> {
        return if (scopeNodeId != null) {
            dao.searchNodesInScope(scopeNodeId, query, "Folder")
        } else {
            dao.searchNodesGlobal(query, "Folder")
        }
    }

    suspend fun searchGalleries(scopeNodeId: String?, query: String): List<CachedNode> {
        return if (scopeNodeId != null) {
            dao.searchNodesInScope(scopeNodeId, query, "Album")
        } else {
            dao.searchNodesGlobal(query, "Album")
        }
    }

    suspend fun getAlbumsInScope(scopeNodeId: String?): List<CachedNode> {
        return if (scopeNodeId != null) {
            dao.getAlbumsInScope(scopeNodeId)
        } else {
            dao.getAllCachedNodes().filter { it.type == "Album" }
        }
    }

    suspend fun fetchAlbumsInScopeRemote(scopeNodeId: String, apiKey: String, password: String? = null): List<CachedNode> {
        val albums = mutableListOf<CachedNode>()
        val queue = ArrayDeque<Pair<String, String?>>() // (nodeId, password for this node)
        queue.add(Pair(scopeNodeId, password))

        val passwordPrefs = context.getSharedPreferences("smugview_passwords", Context.MODE_PRIVATE)
        
        var foldersScanned = 0
        val maxFolders = 30 // Avoid rate-limit blocks by scanning up to 30 folders
        
        while (queue.isNotEmpty() && foldersScanned < maxFolders) {
            val (currentNodeId, nodePassword) = queue.removeFirst()
            foldersScanned++
            try {
                val response = api.getNodeChildren(currentNodeId, apiKey, nodePassword, ignoreErrors = "true")
                val nodes = response.response.nodes ?: emptyList<NodeData>()
                val dbNodes = nodes.mapIndexed { index, node ->
                    val highlightUri = node.uris.highlightImage
                    val highlightUrl = if (highlightUri != null) {
                        response.expansions?.get(highlightUri)?.image?.thumbnailUrl
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
                        webUri = node.webUri
                    )
                }
                
                if (dbNodes.isNotEmpty()) {
                    dao.insertNodes(dbNodes)
                }
                
                for (node in dbNodes) {
                    if (node.type == "Folder") {
                        // Resolve per-node password: check by nodeId, then fall back to parent password
                        val childPassword = passwordPrefs.getString(node.nodeId, null) ?: nodePassword
                        queue.add(Pair(node.nodeId, childPassword))
                    } else if (node.type == "Album") {
                        albums.add(node)
                    }
                }
            } catch (e: Exception) {
                // Ignore and proceed to next node
            }
        }
        return albums
    }

    suspend fun getAllCachedNodes(): List<CachedNode> {
        val dbNodes = dao.getAllCachedNodes()
        val memNodes = albumsCache.value
        return (dbNodes + memNodes).distinctBy { it.nodeId }
    }

    suspend fun getNodesByAlbumUris(albumUris: List<String>): List<CachedNode> {
        if (albumUris.isEmpty()) return emptyList()
        return dao.getNodesByAlbumUris(albumUris)
    }


    // Verifies password correctness for an album
    suspend fun verifyAlbumPassword(albumKey: String, apiKey: String, password: String?): Boolean {
        if (password.isNullOrEmpty()) return false
        return unlockAlbum(albumKey, apiKey, password)
    }

    suspend fun unlockNode(nodeId: String, apiKey: String, password: String): Boolean {
        return try {
            val response = api.unlockNode(nodeId, apiKey, password, "true")
            response.isSuccessful
        } catch (e: Exception) {
            false
        }
    }

    suspend fun unlockAlbum(albumKey: String, apiKey: String, password: String): Boolean {
        return try {
            val response = api.unlockAlbum(albumKey, apiKey, password, "true")
            response.isSuccessful
        } catch (e: Exception) {
            false
        }
    }

    // Fetches EXIF details
    fun getImageExif(imageKey: String, apiKey: String, password: String?): Flow<Result<ExifData>> = flow {
        try {
            val response = api.getImageExif(imageKey, apiKey, password)
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
        apiKey: String,
        password: String? = null
    ): Flow<Result<AlbumImageData>> = flow {
        try {
            val response = api.getImage(imageKey, apiKey, password)
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

    suspend fun fetchNodeFromApi(nodeId: String, apiKey: String, password: String? = null): CachedNode? {
        if (nodeId == "root") return null
        return try {
            val response = api.getNode(nodeId = nodeId, apiKey = apiKey)
            val node = response.response.node
            val parentNodeId = node.uris.parentNode?.substringAfterLast("/")
            val cachedNode = CachedNode(
                nodeId = node.nodeId,
                parentNodeId = parentNodeId,
                type = node.type,
                title = node.name ?: "Untitled",
                description = node.description,
                access = node.securityType,
                passwordHint = node.passwordHint,
                uri = node.uri ?: "",
                childNodesUri = node.uris.childNodes,
                albumUri = node.uris.album,
                highlightImageUrl = null,
                sortIndex = 0,
                webUri = node.webUri
            )
            dao.insertNodes(listOf(cachedNode))
            cachedNode
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    suspend fun resolveAndCacheAlbumLineage(albumKey: String, apiKey: String, password: String? = null): List<CachedNode> {
        val cachedNode = dao.getNodeById(albumKey) ?: run {
            val allNodes = dao.getAllCachedNodes()
            allNodes.find { it.nodeId == albumKey || it.getAlbumKey() == albumKey }
        }
        var currentNode = cachedNode ?: run {
            val albumDetails = getAlbum(albumKey, apiKey, password)
            val nId = albumDetails?.nodeId
            if (albumDetails != null && !nId.isNullOrEmpty()) {
                fetchNodeFromApi(nId, apiKey, password)
            } else {
                null
            }
        }

        val parents = mutableListOf<CachedNode>()
        var parentId = currentNode?.parentNodeId
        val visitedIds = mutableSetOf<String>()
        if (!parentId.isNullOrEmpty()) {
            visitedIds.add(parentId)
        }
        while (!parentId.isNullOrEmpty() && parentId != "root") {
            var parentNode = dao.getNodeById(parentId)
            if (parentNode == null) {
                parentNode = fetchNodeFromApi(parentId, apiKey, password)
            }
            if (parentNode != null) {
                if (parentNode.parentNodeId != "root" && !parentNode.parentNodeId.isNullOrEmpty()) {
                    parents.add(0, parentNode)
                }
                val nextParentId = parentNode.parentNodeId
                if (nextParentId == parentId || visitedIds.contains(nextParentId)) {
                    break
                }
                parentId = nextParentId
                if (!parentId.isNullOrEmpty()) {
                    visitedIds.add(parentId)
                }
            } else {
                break
            }
        }
        return parents
    }

    // --- Offline Local Collections Room Interface ---

    suspend fun getNodeById(nodeId: String): CachedNode? = dao.getNodeById(nodeId)

    suspend fun insertNodes(nodes: List<CachedNode>) {
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
        dao.insertNodes(safeNodes)
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

    suspend fun insertSearchQuery(query: String) {
        if (query.isNotBlank()) {
            dao.insertSearchQuery(SearchHistory(query.trim()))
        }
    }

    fun getSearchHistory(): Flow<List<SearchHistory>> {
        return dao.getSearchHistory()
    }

    suspend fun clearSearchHistory() {
        dao.clearSearchHistory()
    }

    suspend fun deleteSearchQuery(query: String) {
        dao.deleteSearchQuery(query)
    }

    suspend fun getUserAlbums(nickname: String, apiKey: String): List<AlbumDetails> {
        return api.getUserAlbums(nickname, apiKey).response.albums ?: emptyList()
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

    suspend fun resolvePasswordRootNodeId(nodeId: String, apiKey: String): String {
        var currentId = nodeId
        while (true) {
            try {
                val nodeResponse = api.getNode(currentId, apiKey, ignoreErrors = "true")
                val nodeData = nodeResponse.response.node
                if (nodeData.privacy == "Password") {
                    return currentId
                }
                val parentUri = nodeData.uris.parentNode
                if (parentUri != null && nodeData.privacy == "Inherited") {
                    val parentId = parentUri.substringAfterLast("/").substringBefore("!")
                    if (parentId.isNotEmpty() && parentId != currentId) {
                        currentId = parentId
                        continue
                    }
                }
                break
            } catch (e: Exception) {
                break
            }
        }
        return currentId
    }

    suspend fun unlockInheritedPasswordRoot(idOrKey: String, apiKey: String, password: String): Boolean {
        val node = dao.getNodeByIdOrKey(idOrKey)
        val nodeId = node?.nodeId ?: idOrKey
        val rootNodeId = try {
            resolvePasswordRootNodeId(nodeId, apiKey)
        } catch (e: Exception) {
            nodeId
        }
        
        val rootNode = dao.getNodeById(rootNodeId) ?: try {
            val apiNode = getNode(rootNodeId, apiKey, ignoreErrors = "true")
            val cn = CachedNode(
                nodeId = apiNode.nodeId,
                parentNodeId = apiNode.uris.parentNode?.substringAfterLast("/")?.substringBefore("!") ?: "root",
                type = apiNode.type,
                title = apiNode.name ?: "Folder",
                description = apiNode.description,
                access = apiNode.privacy ?: apiNode.securityType ?: "Public",
                passwordHint = apiNode.passwordHint,
                uri = apiNode.uri,
                childNodesUri = apiNode.uris.childNodes,
                albumUri = apiNode.uris.album,
                highlightImageUrl = null,
                childCount = null,
                sortIndex = 0,
                webUri = apiNode.webUri
            )
            dao.insertNodes(listOf(cn))
            cn
        } catch (e: Exception) {
            null
        }
        
        return if (rootNode != null) {
            if (rootNode.type == "Folder") {
                unlockNode(rootNode.nodeId, apiKey, password)
            } else {
                val albumKey = rootNode.getAlbumKey()
                if (albumKey.isNotEmpty()) {
                    unlockAlbum(albumKey, apiKey, password)
                } else {
                    unlockNode(rootNode.nodeId, apiKey, password)
                }
            }
        } else {
            unlockNode(idOrKey, apiKey, password) || unlockAlbum(idOrKey, apiKey, password)
        }
    }

    suspend fun getAllDescendants(nodeId: String): List<CachedNode> {
        return dao.getAllDescendants(nodeId)
    }

    fun searchNodesRemote(
        scopeUri: String,
        scopeKey: String,
        query: String,
        apiKey: String,
        password: String? = null
    ): Flow<Result<List<CachedNode>>> = flow {
        try {
            android.util.Log.d("SmugMugRepository", "searchNodesRemote starting: scopeUri=$scopeUri, scopeKey=$scopeKey, query=$query")
            val response = api.searchNodes(apiKey, scopeUri, query, password)
            val apiNodes = response.response.nodes ?: emptyList()
            android.util.Log.d("SmugMugRepository", "searchNodesRemote API returned ${apiNodes.size} nodes")
            val dbNodes = apiNodes.mapIndexed { index, node ->
                val highlightUri = node.uris.highlightImage
                val highlightUrl = if (highlightUri != null) {
                    val expansion = response.expansions?.get(highlightUri)
                    val thumb = expansion?.image?.thumbnailUrl
                    thumb?.replace("/Th/", "/M/")?.replace("/th/", "/m/")?.replace("-Th.", "-M.")?.replace("-th.", "-m.")
                } else null

                val existing = dao.getNodeById(node.nodeId)
                val parentId = existing?.parentNodeId?.takeIf { it != "search_result" }
                    ?: node.uris.parentNode?.substringAfterLast("/")?.substringBefore("!")
                    ?: "search_result"

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
                    webUri = node.webUri
                )
            }
            dao.deleteSearchResultsForQueryAndType(query, scopeKey, "Folder")
            dao.deleteSearchResultsForQueryAndType(query, scopeKey, "Album")
            android.util.Log.d("SmugMugRepository", "searchNodesRemote deleted old search results for query=$query, scopeKey=$scopeKey")
            if (dbNodes.isNotEmpty()) {
                dao.insertNodes(dbNodes)
                android.util.Log.d("SmugMugRepository", "searchNodesRemote inserted ${dbNodes.size} nodes into cached_nodes")
                val searchResults = dbNodes.mapIndexed { index, node ->
                    node.toSearchResult(query, scopeKey, index)
                }
                dao.insertSearchResults(searchResults)
                android.util.Log.d("SmugMugRepository", "searchNodesRemote inserted ${searchResults.size} search results into search_results")
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
        
        // Also fetch local DB matches for nodes matching this scope and query
        // isGlobal: scopeKey is "site" (generic sentinel) or blank — do NOT hardcode site-specific root node IDs.
        val isGlobal = scope == "site" || scope.isBlank()
        val localResults = if (isGlobal) {
            dao.searchNodesGlobal(query, type)
        } else {
            dao.searchNodesInScope(scope, query, type)
        }
        
        val combined = (remoteResults + localResults).distinctBy { it.nodeId }
        android.util.Log.d("SmugMugRepository", "getSearchResultNodes combined: query=$query, scope=$scope, type=$type, remoteSize=${remoteResults.size}, localSize=${localResults.size}, combinedSize=${combined.size}")
        return combined
    }

    suspend fun getSearchResultPhotos(query: String, scope: String): List<AlbumImageData> {
        val results = dao.getSearchResults(query, scope, "Photo")
        android.util.Log.d("SmugMugRepository", "getSearchResultPhotos DB: query=$query, scope=$scope, resultsSize=${results.size}")
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
