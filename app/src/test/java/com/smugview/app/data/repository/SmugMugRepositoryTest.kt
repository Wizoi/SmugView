package com.smugview.app.data.repository

import com.smugview.app.data.api.*
import com.smugview.app.data.db.*
import com.smugview.app.data.security.FakePasswordStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import okhttp3.ResponseBody
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class SmugMugRepositoryTest {

    private fun mockContext(): android.content.Context {
        val mockContext = org.mockito.Mockito.mock(android.content.Context::class.java)
        val mockPrefs = org.mockito.Mockito.mock(android.content.SharedPreferences::class.java)
        org.mockito.Mockito.`when`(mockContext.getSharedPreferences(org.mockito.Mockito.anyString(), org.mockito.Mockito.anyInt())).thenReturn(mockPrefs)
        return mockContext
    }

    // A fake implementation of CollectionDao for testing repository logic
    class FakeCollectionDao : CollectionDao {
        val nodes = mutableListOf<CachedNode>()
        val searchResults = mutableListOf<SearchResult>()
        
        override suspend fun insertNodes(nodes: List<CachedNode>) {
            this.nodes.addAll(nodes)
        }

        override fun getCachedNodesByParent(parentNodeId: String?): Flow<List<CachedNode>> {
            return flowOf(nodes.filter { it.parentNodeId == parentNodeId })
        }
        
        override suspend fun getNodeById(nodeId: String): CachedNode? {
            return nodes.find { it.nodeId == nodeId }
        }

        override suspend fun getNodeByIdOrKey(idOrKey: String): CachedNode? {
            return nodes.find { it.nodeId == idOrKey || it.getAlbumKey() == idOrKey }
        }

        override suspend fun updateChildCount(nodeId: String, count: Int) {
            // No-op for test
        }
        
        override suspend fun getAlbumsInScope(scopeNodeId: String): List<CachedNode> {
            // Simple recursive traversal simulator for test
            val result = mutableListOf<CachedNode>()
            val queue = ArrayDeque<String>()
            queue.add(scopeNodeId)
            while (queue.isNotEmpty()) {
                val current = queue.removeFirst()
                val children = nodes.filter { it.parentNodeId == current }
                for (child in children) {
                    if (child.type == "Album") {
                        result.add(child)
                    } else if (child.type == "Folder") {
                        queue.add(child.nodeId)
                    }
                }
            }
            return result
        }

        override suspend fun searchNodesGlobal(query: String, type: String, nickname: String): List<CachedNode> {
            return nodes.filter { it.type == type && (it.title.contains(query, true) || it.description?.contains(query, true) == true) && (it.nickname == nickname || it.nickname.isEmpty()) }
        }

        override suspend fun searchNodesInScope(scopeNodeId: String, query: String, type: String): List<CachedNode> {
            val albums = getAlbumsInScope(scopeNodeId)
            return albums.filter { it.type == type && (it.title.contains(query, true) || it.description?.contains(query, true) == true) }
        }

        override suspend fun getAllCachedNodes(): List<CachedNode> = nodes

        val albumIndex = mutableListOf<CachedAlbum>()
        override suspend fun upsertAlbums(albums: List<CachedAlbum>) {
            for (a in albums) {
                albumIndex.removeAll { it.albumKey == a.albumKey }
                albumIndex.add(a)
            }
        }
        override suspend fun getAlbumIndex(nickname: String): List<CachedAlbum> =
            albumIndex.filter { it.nickname == nickname || it.nickname.isEmpty() }.sortedBy { it.sortIndex }
        override suspend fun getLatestAlbumDateModified(nickname: String): String? =
            albumIndex.filter { it.nickname == nickname || it.nickname.isEmpty() }.mapNotNull { it.dateModified }.maxOrNull()
        override suspend fun getAlbumIndexCount(nickname: String): Int =
            albumIndex.count { it.nickname == nickname || it.nickname.isEmpty() }

        override suspend fun getNodesByAlbumUris(albumUris: List<String>): List<CachedNode> {
            return nodes.filter { it.albumUri in albumUris }
        }

        val viewedUpdates = mutableMapOf<String, String>()

        override suspend fun getAllDescendants(nodeId: String): List<CachedNode> {
            val result = mutableListOf<CachedNode>()
            val queue = ArrayDeque<String>()
            queue.add(nodeId)
            while (queue.isNotEmpty()) {
                val current = queue.removeFirst()
                val children = nodes.filter { it.parentNodeId == current }
                for (child in children) {
                    result.add(child)
                    queue.add(child.nodeId)
                }
            }
            return result
        }

        override suspend fun insertViewedUpdates(updates: List<ViewedGalleryUpdate>) {
            for (u in updates) {
                viewedUpdates[u.nodeId] = u.lastViewedDateModified
            }
        }

        override suspend fun clearViewedUpdates() {
            viewedUpdates.clear()
        }

        override fun getNodesWithActiveUpdates(): Flow<List<String>> {
            val active = mutableSetOf<String>()
            val nowMs = System.currentTimeMillis()
            val thirtyDaysAgoMs = nowMs - 30L * 24L * 60L * 60L * 1000L
            
            for (n in nodes) {
                val dm = n.dateModified ?: continue
                val isRecent = try {
                    val instant = java.time.Instant.parse(dm)
                    instant.toEpochMilli() >= thirtyDaysAgoMs
                } catch (e: Exception) {
                    true // Fallback for simple unit test date formats
                }
                if (!isRecent) continue
                
                val lastViewed = viewedUpdates[n.nodeId]
                val isNewUpdate = lastViewed == null || dm > lastViewed
                if (isNewUpdate) {
                    active.add(n.nodeId)
                }
            }
            
            val result = mutableSetOf<String>()
            for (actId in active) {
                var currId: String? = actId
                while (currId != null && currId != "root") {
                    result.add(currId)
                    currId = nodes.find { it.nodeId == currId }?.parentNodeId
                }
            }
            return flowOf(result.toList())
        }

        override suspend fun removeBookmark(collectionId: Long, type: String, itemKey: String) {}
        override suspend fun isBookmarked(collectionId: Long, type: String, itemKey: String): Boolean = false
        override suspend fun isBookmarkedAnywhere(type: String, itemKey: String): Boolean = false

        // Restored stubs
        override suspend fun removeBookmarkGlobally(itemKey: String) {}
        override fun getPagedSearchResultsDesc(query: String, scope: String, type: String): androidx.paging.PagingSource<Int, SearchResult> = throw Exception()
        override fun getPagedSearchResultsAsc(query: String, scope: String, type: String): androidx.paging.PagingSource<Int, SearchResult> = throw Exception()
        override suspend fun createCollection(collection: OfflineCollection): Long = 0L
        override fun getCollectionsForSite(siteNickname: String): Flow<List<OfflineCollection>> = flowOf(emptyList())
        override suspend fun getCollectionById(collectionId: Long): OfflineCollection? = null
        override suspend fun deleteCollection(collectionId: Long) {}
        override suspend fun addPhotoToCollection(photo: CollectionPhoto) {}
        override fun getPhotosInCollection(collectionId: Long): Flow<List<CollectionPhoto>> = flowOf(emptyList())
        override suspend fun getCollectionPhoto(imageKey: String, collectionId: Long): CollectionPhoto? = null
        override suspend fun removePhotoFromCollection(imageKey: String, collectionId: Long) {}
        override suspend fun updateDownloadStatus(imageKey: String, collectionId: Long, filePath: String, downloaded: Boolean) {}
        override suspend fun updateDownloadStatusForAll(imageKey: String, filePath: String?, downloaded: Boolean) {}
        override suspend fun getPendingDownloads(): List<CollectionPhoto> = emptyList()
        override suspend fun renameCollection(collectionId: Long, newName: String) {}
        override suspend fun addBookmark(bookmark: CollectionBookmark): Long = 0L
        override fun getBookmarksForCollection(collectionId: Long): Flow<List<CollectionBookmark>> = flowOf(emptyList())

        override suspend fun getBookmarkByItemKey(itemKey: String): CollectionBookmark? = null
        override suspend fun getCollectionPhotoByKey(imageKey: String): CollectionPhoto? = null
        
        override suspend fun insertSearchQuery(searchHistory: SearchHistory) {}
        override fun getSearchHistory(nickname: String): Flow<List<SearchHistory>> = flowOf(emptyList())
        override suspend fun deleteSearchQuery(query: String, nickname: String) {}
        override suspend fun clearSearchHistory(nickname: String) {}
        override suspend fun clearAllSearchHistory() {}
        override suspend fun updateNodeAccess(nodeId: String, access: String?, passwordHint: String?) {}
        
        override suspend fun insertSearchResults(results: List<SearchResult>) {
            searchResults.addAll(results)
        }
        override suspend fun getSearchResults(query: String, scope: String, type: String): List<SearchResult> {
            return searchResults.filter { it.searchQuery == query && it.searchScope == scope && it.itemType == type }
        }
        override suspend fun deleteSearchResultsForQueryAndType(query: String, scope: String, type: String) {
            searchResults.removeAll { it.searchQuery == query && it.searchScope == scope && it.itemType == type }
        }
        override suspend fun clearAllCachedNodes() {
            nodes.clear()
        }
    }

    private fun createMockApi(interceptor: Interceptor): SmugMugApi {
        val client = OkHttpClient.Builder()
            .addInterceptor(interceptor)
            .build()
            
        val retrofit = Retrofit.Builder()
            .baseUrl("https://api.smugmug.com/api/v2/")
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            
        return retrofit.create(SmugMugApi::class.java)
    }

    @Test
    fun testGetAlbumsInScopeOrchestration() = runBlocking {
        val fakeDao = FakeCollectionDao()
        
        val mockInterceptor = Interceptor { chain ->
            val url = chain.request().url.toString()
            val json = if (url.contains("node/folder1!children")) {
                """
                {
                  "Response": {
                    "Uri": "/api/v2/node/folder1!children",
                    "Locator": "Node",
                    "LocatorType": "Objects",
                    "Node": [
                      {
                        "Uri": "/api/v2/node/folder2",
                        "NodeID": "folder2",
                        "Type": "Folder",
                        "Name": "Subfolder",
                        "DateModified": "2026-07-15T06:07:19+00:00",
                        "Uris": {
                          "ChildNodes": "/api/v2/node/folder2!children"
                        }
                      },
                      {
                        "Uri": "/api/v2/node/album1",
                        "NodeID": "album1",
                        "Type": "Album",
                        "Name": "My Gallery",
                        "DateModified": "2026-07-15T06:07:19+00:00",
                        "Uris": {
                          "Album": "/api/v2/album/album1"
                        }
                      }
                    ]
                  },
                  "Code": 200,
                  "Message": "Ok"
                }
                """.trimIndent()
            } else {
                """
                {
                  "Response": {
                    "Uri": "/api/v2/node/folder2!children",
                    "Locator": "Node",
                    "LocatorType": "Objects",
                    "Node": []
                  },
                  "Code": 200,
                  "Message": "Ok"
                }
                """.trimIndent()
            }
            
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(json.toResponseBody("application/json".toMediaTypeOrNull()))
                .build()
        }
        
        val api = createMockApi(mockInterceptor)
        val repository = SmugMugRepository(api, fakeDao, FakePasswordStore(), mockContext()).apply { maxPagesPerFetch = 2 }

        // 1. Initially database cache is empty
        val initialAlbums = repository.getAlbumsInScope("folder1")
        assertTrue(initialAlbums.isEmpty())

        // 2. Trigger remote fetch for scope folder1
        val fetched = repository.fetchAlbumsInScopeRemote("folder1", "dummy_key")
        
        // Should have found 1 album directly under folder1
        assertEquals(1, fetched.size)
        assertEquals("album1", fetched[0].nodeId)
        
        // 3. Verify they are now cached in the database
        val cachedAlbums = repository.getAlbumsInScope("folder1")
        assertEquals(1, cachedAlbums.size)
        assertEquals("album1", cachedAlbums[0].nodeId)
    }

    @Test
    fun testGetAlbumKeywordsOrchestration() = runBlocking {
        val fakeDao = FakeCollectionDao()
        
        val mockInterceptor = Interceptor { chain ->
            val json = """
            {
              "Response": {
                "Uri": "/api/v2/album/album1",
                "Locator": "Album",
                "LocatorType": "Object",
                "Album": {
                  "AlbumKey": "album1",
                  "Uri": "/api/v2/album/album1"
                }
              },
              "Expansions": {
                "/api/v2/album/album1": {
                  "AlbumKeywords": {
                    "Keywords": ["nature", "sunset"]
                  }
                }
              },
              "Code": 200,
              "Message": "Ok"
            }
            """.trimIndent()
            
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(json.toResponseBody("application/json".toMediaTypeOrNull()))
                .build()
        }
        
        val api = createMockApi(mockInterceptor)
        val repository = SmugMugRepository(api, fakeDao, FakePasswordStore(), mockContext()).apply { maxPagesPerFetch = 2 }

        // Call repository keywords batch method
        val response = repository.getAlbumKeywords(listOf("album1"), "dummy_key")
        assertNotNull(response.expansions)
        
        val keywords = response.expansions?.get("/api/v2/album/album1")?.albumKeywords?.keywords
        assertNotNull(keywords)
        assertEquals(2, keywords?.size)
        assertTrue(keywords!!.contains("nature"))
        assertTrue(keywords.contains("sunset"))
    }

    @Test
    fun testMockVisibilityAnonymousState() = runBlocking {
        val fakeDao = FakeCollectionDao()
        var callCount = 0
        
        val mockInterceptor = Interceptor { chain ->
            callCount++
            val json = if (callCount == 1) {
                // Anonymous: empty expansions
                """
                {
                  "Response": {
                    "Uri": "/api/v2/album/album1",
                    "Locator": "Album",
                    "LocatorType": "Object"
                  },
                  "Expansions": {},
                  "Code": 200,
                  "Message": "Ok"
                }
                """.trimIndent()
            } else {
                // Unlocked: full expansions
                """
                {
                  "Response": {
                    "Uri": "/api/v2/album/album1",
                    "Locator": "Album",
                    "LocatorType": "Object"
                  },
                  "Expansions": {
                    "/api/v2/album/album1": {
                      "AlbumKeywords": {
                        "Keywords": ["nature", "sunset"]
                      }
                    }
                  },
                  "Code": 200,
                  "Message": "Ok"
                }
                """.trimIndent()
            }
            
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(json.toResponseBody("application/json".toMediaTypeOrNull()))
                .build()
        }
        
        val api = createMockApi(mockInterceptor)
        val repository = SmugMugRepository(api, fakeDao, FakePasswordStore(), mockContext()).apply { maxPagesPerFetch = 2 }

        // 1. Simulate anonymous/locked state
        val response1 = repository.getAlbumKeywords(listOf("album1"), "dummy_key")
        assertTrue(response1.expansions == null || response1.expansions!!.isEmpty())

        // 2. Simulate unlocked state
        val response2 = repository.getAlbumKeywords(listOf("album1"), "dummy_key")
        assertNotNull(response2.expansions)
        val keywords = response2.expansions?.get("/api/v2/album/album1")?.albumKeywords?.keywords
        assertNotNull(keywords)
        assertEquals(2, keywords?.size)
    }

    @Test
    fun testSearchImagesOrchestrationAndPaging() = runBlocking {
        val fakeDao = FakeCollectionDao()
        val gson = com.google.gson.Gson()
        
        val mockInterceptor = Interceptor { chain ->
            val url = chain.request().url.toString()
            val images = if (url.contains("start=251")) {
                (251..300).map { i -> mapOf("ImageKey" to "img_$i", "Title" to "Image $i") }
            } else {
                (1..250).map { i -> mapOf("ImageKey" to "img_$i", "Title" to "Image $i") }
            }
            
            val nextField = if (url.contains("start=251")) null else "/api/v2/image!search?start=251"
            
            val payload = mapOf(
                "Response" to mapOf(
                    "Image" to images,
                    "Pages" to mapOf(
                        "Start" to (if (url.contains("start=251")) 251 else 1),
                        "Count" to images.size,
                        "Total" to 300,
                        "Next" to nextField
                    )
                ),
                "Code" to 200,
                "Message" to "Ok"
            )
            
            val json = gson.toJson(payload)
            
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(json.toResponseBody("application/json".toMediaTypeOrNull()))
                .build()
        }
        
        val api = createMockApi(mockInterceptor)
        val repository = SmugMugRepository(api, fakeDao, FakePasswordStore(), mockContext()).apply { maxPagesPerFetch = 2 }

        // Call performBackgroundSearchImages
        repository.performBackgroundSearchImages(
            nickname = "testUser",
            scopeUri = "/api/v2/node/folder1",
            scopeKey = "folder1",
            query = "testQuery",
            apiKey = "dummyKey"
        )

        // Verify they were inserted in search_results table in Fake Dao
        val dbResults = fakeDao.getSearchResults("testQuery", "folder1", "Photo")
        assertEquals(300, dbResults.size)
        assertEquals("img_1", dbResults.first().itemKey)
    }

    @Test
    fun testGetImagesByKeywordRepositoryMapping() = runBlocking {
        val fakeDao = FakeCollectionDao()
        var apiScope: String? = null
        var apiText: String? = null
        
        val mockInterceptor = Interceptor { chain ->
            val url = chain.request().url
            apiScope = url.queryParameter("Scope")
            apiText = url.queryParameter("Text")
            
            val json = """
            {
              "Response": {
                "Image": [
                  {
                    "ImageKey": "res1",
                    "Title": "Result Image"
                  }
                ],
                "Pages": {
                  "Start": 1,
                  "Count": 1,
                  "Total": 1
                }
              },
              "Code": 200,
              "Message": "Ok"
            }
            """.trimIndent()
            
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(json.toResponseBody("application/json".toMediaTypeOrNull()))
                .build()
        }
        
        val api = createMockApi(mockInterceptor)
        val repository = SmugMugRepository(api, fakeDao, FakePasswordStore(), mockContext()).apply { maxPagesPerFetch = 2 }

        val result = repository.getImagesByKeyword(
            scope = "/api/v2/user/testUser",
            keywords = "clara,idzi",
            apiKey = "dummyKey",
            count = 100,
            start = 5
        )

        assertEquals(1, result.size)
        assertEquals("res1", result[0].imageKey)
        assertEquals("/api/v2/user/testUser", apiScope)
        assertEquals("clara idzi", apiText) // Verify comma is replaced with space
    }

    @Test
    fun testSearchResultMappingPreservesKeywords() {
        val originalImage = AlbumImageData(
            imageKey = "test_key",
            title = "Test Title",
            caption = "Test Caption",
            keywords = "tahoma, marching, band",
            keywordArray = listOf("tahoma", "marching", "band")
        )
        val searchResult = originalImage.toSearchResult("query", "scope", 1)
        assertEquals("tahoma, marching, band", searchResult.keywords)
        
        val mappedBack = searchResult.toAlbumImageData()
        assertEquals("tahoma, marching, band", mappedBack.keywordsString)
    }

    @Test
    fun testResolveAndCacheAlbumLineageSafeForRoot() = runBlocking {
        val fakeDao = FakeCollectionDao()
        val mockInterceptor = Interceptor { chain ->
            if (chain.request().url.toString().contains("node/root")) {
                throw RuntimeException("Should not request root node from API")
            }
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(404)
                .message("Not Found")
                .body("".toResponseBody("application/json".toMediaTypeOrNull()))
                .build()
        }
        val api = createMockApi(mockInterceptor)
        val repository = SmugMugRepository(api, fakeDao, FakePasswordStore(), mockContext()).apply { maxPagesPerFetch = 2 }
        
        fakeDao.insertNodes(listOf(
            CachedNode(
                nodeId = "child_node",
                parentNodeId = "root",
                type = "Album",
                title = "Child Node",
                description = null,
                access = "Public",
                passwordHint = null,
                uri = "/api/v2/album/child_node",
                childNodesUri = null,
                albumUri = "/api/v2/album/child_node"
            )
        ))
        
        val lineage = repository.resolveAndCacheAlbumLineage("child_node", "dummyKey")
        assertEquals(0, lineage.size)
    }

    @Test
    fun testActiveUpdatesDetectionBubblingAndClearing() = runBlocking {
        val fakeDao = FakeCollectionDao()
        val mockInterceptor = Interceptor { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(404)
                .message("Not Found")
                .body("".toResponseBody("application/json".toMediaTypeOrNull()))
                .build()
        }
        val api = createMockApi(mockInterceptor)
        val repository = SmugMugRepository(api, fakeDao, FakePasswordStore(), mockContext()).apply { maxPagesPerFetch = 2 }

        // Insert hierarchy:
        // root (parentNodeId = null)
        // -> folder1 (parentNodeId = "root", type = "Folder")
        //    -> album1 (parentNodeId = "folder1", type = "Album")
        val now = java.time.Instant.now()
        val recentDate = now.toString() // within 30 days (current time)

        val nodeFolder = CachedNode(
            nodeId = "folder1",
            parentNodeId = "root",
            type = "Folder",
            title = "Folder 1",
            description = null,
            access = "Public",
            passwordHint = null,
            uri = "/node/folder1",
            childNodesUri = null,
            albumUri = null
        )
        val nodeAlbum = CachedNode(
            nodeId = "album1",
            parentNodeId = "folder1",
            type = "Album",
            title = "Album 1",
            description = null,
            access = "Public",
            passwordHint = null,
            uri = "/node/album1",
            childNodesUri = null,
            albumUri = "/api/v2/album/album1",
            dateModified = recentDate
        )

        fakeDao.insertNodes(listOf(nodeFolder, nodeAlbum))

        // Check active updates: album1 is updated recently and not viewed, folder1 should bubble it up
        val initialUpdates = repository.getNodesWithActiveUpdates().first()
        println("DEBUG initialUpdates: $initialUpdates")
        assertTrue("Album should have active update", initialUpdates.contains("album1"))
        assertTrue("Folder should bubble up update from album", initialUpdates.contains("folder1"))

        // Satisfy / Mark album1 as viewed
        repository.markNodeAsViewed("album1")

        // Active updates should now be empty
        val postViewUpdates = repository.getNodesWithActiveUpdates().first()
        assertFalse("Album should no longer have active update", postViewUpdates.contains("album1"))
        assertFalse("Folder should no longer bubble up update", postViewUpdates.contains("folder1"))

        // Add a new update to album1 (date modified is newer than viewed date)
        val newerDate = now.plus(java.time.Duration.ofHours(2)).toString()
        val nodeAlbumUpdated = nodeAlbum.copy(dateModified = newerDate)
        fakeDao.clearAllCachedNodes()
        fakeDao.insertNodes(listOf(nodeFolder, nodeAlbumUpdated))

        // Active updates should reappear
        val updatedUpdates = repository.getNodesWithActiveUpdates().first()
        assertTrue("Album should show active update again after date changes", updatedUpdates.contains("album1"))
        assertTrue("Folder should bubble up update again", updatedUpdates.contains("folder1"))

        // Long press mark parent folder as viewed, which recursively satisfies album1
        repository.markNodeAsViewed("folder1")
        val finalUpdates = repository.getNodesWithActiveUpdates().first()
        assertFalse("Album should be recursively satisfied", finalUpdates.contains("album1"))
        assertFalse("Folder should be satisfied", finalUpdates.contains("folder1"))
    }

    @Test
    fun testSearchPublicSites() = runBlocking {
        val fakeDao = FakeCollectionDao()
        
        val mockInterceptor = Interceptor { chain ->
            val url = chain.request().url.toString()
            val json = when {
                url.contains("user!search") -> {
                    """
                    {
                      "Response": {
                        "Uri": "/api/v2/user!search",
                        "Locator": "User",
                        "LocatorType": "Objects",
                        "User": [
                          {
                            "NickName": "glenncampbell",
                            "Name": "Glenn Campbell",
                            "WebUri": "https://glenncampbell.smugmug.com",
                            "Uris": {
                              "UserAlbums": "/api/v2/user/glenncampbell!albums"
                            }
                          },
                          {
                            "NickName": "uphs",
                            "Name": "Union Pacific Historical Society",
                            "WebUri": "https://uphs.smugmug.com",
                            "Uris": {
                              "UserAlbums": "/api/v2/user/uphs!albums"
                            }
                          }
                        ]
                      },
                      "Code": 200,
                      "Message": "Ok"
                    }
                    """.trimIndent()
                }
                url.contains("glenncampbell!recentimages") -> {
                    """
                    {
                      "Response": {
                        "Uri": "/api/v2/user/glenncampbell!recentimages",
                        "Locator": "Image",
                        "LocatorType": "Objects",
                        "Image": [
                          {
                            "ImageKey": "img1",
                            "Title": "Sunset",
                            "Caption": "Nice sunset",
                            "ThumbnailUrl": "https://photos.smugmug.com/img1-th.jpg",
                            "WebUri": "https://glenncampbell.smugmug.com/Nature/Sunset/i-img1"
                          },
                          {
                            "ImageKey": "img2",
                            "Title": "Forest",
                            "Caption": "Deep forest",
                            "ThumbnailUrl": "https://photos.smugmug.com/img2-th.jpg",
                            "WebUri": "https://glenncampbell.smugmug.com/Nature/Forest/i-img2"
                          }
                        ]
                      },
                      "Code": 200,
                      "Message": "Ok"
                    }
                    """.trimIndent()
                }
                url.contains("uphs!recentimages") -> {
                    """
                    {
                      "Response": {
                        "Uri": "/api/v2/user/uphs!recentimages",
                        "Locator": "Image",
                        "LocatorType": "Objects",
                        "Image": [
                          {
                            "ImageKey": "img3",
                            "Title": "Depot History",
                            "Caption": "Union Pacific Depot",
                            "ThumbnailUrl": "https://photos.smugmug.com/img3-th.jpg",
                            "WebUri": "https://uphs.smugmug.com/UP-Facilities/1922-UP-Depots/i-img3"
                          }
                        ]
                      },
                      "Code": 200,
                      "Message": "Ok"
                    }
                    """.trimIndent()
                }
                else -> """{"Code": 404, "Message": "Not found"}"""
            }
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(json.toResponseBody("application/json".toMediaTypeOrNull()))
                .build()
        }
        
        val mockApi = createMockApi(mockInterceptor)
        val repo = SmugMugRepository(mockApi, fakeDao, FakePasswordStore(), mockContext()).apply { maxPagesPerFetch = 2 }
        
        val result = repo.searchPublicSites("nature", "fakeApiKey").first()
        val sites = result.getOrThrow()
        assertEquals(2, sites.size)
        
        val glenn = sites.find { it.nickname == "glenncampbell" }
        assertNotNull(glenn)
        assertEquals(2, glenn!!.previewPhotos.size)
        assertEquals("https://glenncampbell.smugmug.com", glenn.webUri)
        
        val uphs = sites.find { it.nickname == "uphs" }
        assertNotNull(uphs)
        assertEquals(1, uphs!!.previewPhotos.size)
        assertEquals("https://uphs.smugmug.com", uphs.webUri)
    }

    @Test
    fun testRepositoryGetNodeCachingPrioritizesSecurityType() = runBlocking {
        val fakeDao = FakeCollectionDao()
        val mockNodeData = com.smugview.app.data.api.NodeData(
            uri = "/api/v2/node/testNodeId",
            nodeId = "testNodeId",
            type = "Folder",
            name = "Test Locked Folder",
            description = null,
            securityType = "Password",
            privacy = "Public",
            passwordHint = "Hint",
            webUri = "https://test.weburi",
            uris = com.smugview.app.data.api.NodeUris(
                parentNode = "/api/v2/node/root"
            ),
            dateModified = null
        )
        
        val mockInterceptor = Interceptor { chain ->
            val request = chain.request()
            val json = if (request.method == "POST") {
                "{}"
            } else {
                com.google.gson.Gson().toJson(com.smugview.app.data.api.SingleNodeResponse(com.smugview.app.data.api.SingleNodePayload(mockNodeData)))
            }
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(json.toResponseBody("application/json".toMediaTypeOrNull()))
                .build()
        }
        
        val api = createMockApi(mockInterceptor)
        val repository = SmugMugRepository(api, fakeDao, FakePasswordStore(), mockContext()).apply { maxPagesPerFetch = 2 }
        
        // Call unlockInheritedPasswordRoot which fetches and caches the node
        repository.unlockInheritedPasswordRoot("testNodeId", "dummy_key", "password")
        
        // Verify the node was saved into the fake database with the correct "Password" access parameter
        // rather than the "Public" privacy parameter.
        val cached = fakeDao.getNodeById("testNodeId")
        assertNotNull(cached)
        assertEquals("Password", cached?.access)
    }
}

