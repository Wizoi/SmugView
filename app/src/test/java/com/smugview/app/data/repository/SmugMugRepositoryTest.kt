package com.smugview.app.data.repository

import com.smugview.app.data.api.*
import com.smugview.app.data.db.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import okhttp3.ResponseBody

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

        override suspend fun searchNodesGlobal(query: String, type: String): List<CachedNode> {
            return nodes.filter { it.type == type && (it.title.contains(query, true) || it.description?.contains(query, true) == true) }
        }

        override suspend fun searchNodesInScope(scopeNodeId: String, query: String, type: String): List<CachedNode> {
            val albums = getAlbumsInScope(scopeNodeId)
            return albums.filter { it.type == type && (it.title.contains(query, true) || it.description?.contains(query, true) == true) }
        }

        override suspend fun getAllCachedNodes(): List<CachedNode> = nodes

        override suspend fun getNodesByAlbumUris(albumUris: List<String>): List<CachedNode> {
            return nodes.filter { it.albumUri in albumUris }
        }

        override suspend fun getAllDescendants(nodeId: String): List<CachedNode> = emptyList()
        override suspend fun removeBookmarkGlobally(itemKey: String) {}
        override fun getPagedSearchResultsDesc(query: String, scope: String, type: String): androidx.paging.PagingSource<Int, SearchResult> = throw Exception()
        override fun getPagedSearchResultsAsc(query: String, scope: String, type: String): androidx.paging.PagingSource<Int, SearchResult> = throw Exception()

        // Dummy implementations for required interface methods
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
        override suspend fun removeBookmark(collectionId: Long, type: String, itemKey: String) {}
        override suspend fun isBookmarked(collectionId: Long, type: String, itemKey: String): Boolean = false
        override suspend fun isBookmarkedAnywhere(type: String, itemKey: String): Boolean = false

        override suspend fun getBookmarkByItemKey(itemKey: String): CollectionBookmark? = null
        override suspend fun getCollectionPhotoByKey(imageKey: String): CollectionPhoto? = null
        
        override suspend fun insertSearchQuery(searchHistory: SearchHistory) {}
        override fun getSearchHistory(): Flow<List<SearchHistory>> = flowOf(emptyList())
        override suspend fun deleteSearchQuery(query: String) {}
        override suspend fun clearSearchHistory() {}
        
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

    // A fake implementation of SmugMugApi for unit testing
    class FakeSmugMugApi : SmugMugApi {
        var mockChildNodesResponse: NodeListResponse? = null
        var mockAlbumKeywordsResponse: AlbumKeywordsResponse? = null
        
        override suspend fun getNodeChildren(nodeId: String, apiKey: String, password: String?, filter: String, verbosity: Int, ignoreErrors: String?): NodeListResponse {
            return if (nodeId == "folder1") {
                mockChildNodesResponse ?: throw Exception("Mock not configured")
            } else {
                NodeListResponse(response = NodeListPayload(nodes = emptyList()))
            }
        }
        
        override suspend fun getAlbumKeywords(albumKeys: String, apiKey: String, password: String?, expand: String, filter: String, filterUri: String, verbosity: Int): AlbumKeywordsResponse {
            return mockAlbumKeywordsResponse ?: throw Exception("Mock not configured")
        }

        // Stub other required methods
        override suspend fun getUserProfile(nickname: String, apiKey: String, expand: String, verbosity: Int): UserResponse = throw Exception()
        override suspend fun getUserBioImage(nickname: String, apiKey: String, verbosity: Int): BioImageResponse = throw Exception()
        override suspend fun getNode(nodeId: String, apiKey: String, verbosity: Int, ignoreErrors: String?): SingleNodeResponse = throw Exception()
        override suspend fun getUserTopKeywords(nickname: String, apiKey: String, nodeId: String?, verbosity: Int): TopKeywordsResponse = throw Exception()
        override suspend fun getAlbum(albumKey: String, apiKey: String, password: String?, verbosity: Int, ignoreErrors: String?): AlbumResponse = throw Exception()
        override suspend fun getAlbumImages(albumKey: String, apiKey: String, password: String?, count: Int, expand: String, filter: String, verbosity: Int, ignoreErrors: String?): AlbumImagesResponse = throw Exception()
        override suspend fun getAlbumImagesByUri(url: String, apiKey: String, password: String?, ignoreErrors: String?): AlbumImagesResponse = throw Exception()
        override suspend fun getImageExif(imageKey: String, apiKey: String, password: String?, verbosity: Int): ExifResponse = throw Exception()
        override suspend fun getImage(imageKey: String, apiKey: String, password: String?, expand: String, filter: String, verbosity: Int): ImageResponse = throw Exception()
        
        var searchImagesMock: ((start: Int) -> ImageSearchResponse)? = null
        
        override suspend fun searchImages(
            apiKey: String,
            scope: String?,
            text: String?,
            sortMethod: String?,
            sortDirection: String?,
            count: Int,
            start: Int,
            filter: String,
            filterUri: String,
            expand: String?,
            verbosity: Int
        ): ImageSearchResponse {
            return searchImagesMock?.invoke(start) ?: throw Exception("Mock not configured")
        }
        var searchImagesByUriMock: ((url: String) -> ImageSearchResponse)? = null
        
        override suspend fun searchImagesByUri(url: String, apiKey: String): ImageSearchResponse {
            return searchImagesByUriMock?.invoke(url) ?: throw Exception("Mock not configured")
        }
        
        override suspend fun searchImagesUser(nickname: String, apiKey: String, text: String, scope: String?, password: String?, count: Int, start: Int, expand: String?, filter: String, filterUri: String, verbosity: Int): ImageSearchResponse = throw Exception()
        override suspend fun searchImagesUserByUri(url: String, apiKey: String, password: String?): ImageSearchResponse = throw Exception()
        override suspend fun searchNodes(apiKey: String, scope: String, text: String, password: String?, expand: String, filter: String, verbosity: Int): NodeListResponse = throw Exception()
        
        override suspend fun unlockNode(nodeId: String, apiKey: String, password: String, ignoreErrors: String?): retrofit2.Response<ResponseBody> = throw Exception()
        override suspend fun unlockAlbum(albumKey: String, apiKey: String, password: String, ignoreErrors: String?): retrofit2.Response<ResponseBody> = throw Exception()
        override suspend fun getUserAlbums(nickname: String, apiKey: String, count: Int, expand: String, filter: String, verbosity: Int): UserAlbumsResponse = throw Exception()
        override suspend fun getUserAlbumsByUri(url: String, apiKey: String): UserAlbumsResponse = throw Exception()
        var getImagesByKeywordMock: ((apiKey: String, scope: String?, text: String?, count: Int, start: Int) -> ImageSearchResponse)? = null
        override suspend fun getImagesByKeyword(apiKey: String, scope: String?, text: String?, count: Int, start: Int, filter: String, filterUri: String, verbosity: Int): ImageSearchResponse {
            return getImagesByKeywordMock?.invoke(apiKey, scope, text, count, start) ?: throw Exception("Mock not configured")
        }
        override suspend fun updateImageMetadata(imageKey: String, apiKey: String, body: UpdateImageMetadataRequest): retrofit2.Response<ResponseBody> = throw Exception()
    }

    @Test
    fun testGetAlbumsInScopeOrchestration() = runBlocking {
        val fakeDao = FakeCollectionDao()
        val fakeApi = FakeSmugMugApi()
        val repository = SmugMugRepository(fakeApi, fakeDao, mockContext())

        // 1. Initially database cache is empty
        val initialAlbums = repository.getAlbumsInScope("folder1")
        assertTrue(initialAlbums.isEmpty())

        // 2. Set up mock API children response: folder1 contains folder2 (Folder) and album1 (Album)
        fakeApi.mockChildNodesResponse = NodeListResponse(
            response = NodeListPayload(
                nodes = listOf(
                    NodeData(
                        uri = "/api/v2/node/folder2",
                        nodeId = "folder2",
                        type = "Folder",
                        name = "Subfolder",
                        uris = NodeUris(childNodes = "/api/v2/node/folder2!children")
                    ),
                    NodeData(
                        uri = "/api/v2/node/album1",
                        nodeId = "album1",
                        type = "Album",
                        name = "My Gallery",
                        uris = NodeUris(album = "/api/v2/album/album1")
                    )
                )
            )
        )

        // 3. Trigger remote fetch for scope folder1
        val fetched = repository.fetchAlbumsInScopeRemote("folder1", "dummy_key")
        
        // Should have found 1 album directly under folder1
        assertEquals(1, fetched.size)
        assertEquals("album1", fetched[0].nodeId)
        
        // 4. Verify they are now cached in the database
        val cachedAlbums = repository.getAlbumsInScope("folder1")
        assertEquals(1, cachedAlbums.size)
        assertEquals("album1", cachedAlbums[0].nodeId)
    }

    @Test
    fun testGetAlbumKeywordsOrchestration() = runBlocking {
        val fakeDao = FakeCollectionDao()
        val fakeApi = FakeSmugMugApi()
        val repository = SmugMugRepository(fakeApi, fakeDao, mockContext())

        // Setup mock API keywords response
        fakeApi.mockAlbumKeywordsResponse = AlbumKeywordsResponse(
            expansions = mapOf(
                "/api/v2/album/album1" to AlbumExpansionContainer(
                    albumKeywords = AlbumKeywordsContainer(keywords = listOf("nature", "sunset"))
                )
            )
        )

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
    fun testSearchImagesOrchestrationAndPaging() = runBlocking {
        val fakeDao = FakeCollectionDao()
        val fakeApi = FakeSmugMugApi()
        val repository = SmugMugRepository(fakeApi, fakeDao, mockContext())

        // Mock 1st page: 250 images, total = 300
        val page1Images = (1..250).map { i ->
            AlbumImageData(imageKey = "img_$i", title = "Image $i")
        }
        
        // Mock 2nd page: 50 images
        val page2Images = (251..300).map { i ->
            AlbumImageData(imageKey = "img_$i", title = "Image $i")
        }

        fakeApi.searchImagesMock = { start ->
            if (start == 1) {
                ImageSearchResponse(
                    response = ImageSearchPayload(
                        images = page1Images,
                        pages = PagesData(start = 1, count = 250, total = 300, nextField = "/api/v2/image!search?start=251")
                    )
                )
            } else {
                throw IllegalArgumentException("Unexpected start offset: $start")
            }
        }

        fakeApi.searchImagesByUriMock = { url ->
            if (url.contains("start=251")) {
                ImageSearchResponse(
                    response = ImageSearchPayload(
                        images = page2Images,
                        pages = PagesData(start = 251, count = 50, total = 300)
                    )
                )
            } else {
                ImageSearchResponse(response = ImageSearchPayload(images = emptyList()))
            }
        }

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
        val fakeApi = FakeSmugMugApi()
        val repository = SmugMugRepository(fakeApi, fakeDao, mockContext())

        var apiScope: String? = null
        var apiText: String? = null
        var apiCount = 0
        var apiStart = 0

        fakeApi.getImagesByKeywordMock = { apiKey, scope, text, count, start ->
            apiScope = scope
            apiText = text
            apiCount = count
            apiStart = start
            ImageSearchResponse(
                response = ImageSearchPayload(
                    images = listOf(AlbumImageData(imageKey = "res1", title = "Result Image")),
                    pages = PagesData(start = 1, count = 1, total = 1)
                )
            )
        }

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
        assertEquals(100, apiCount)
        assertEquals(5, apiStart)
    }
}
