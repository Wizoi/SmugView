package com.smugview.app.ui.viewmodel

import android.app.Application
import android.content.SharedPreferences
import androidx.work.WorkManager
import com.smugview.app.data.api.*
import com.smugview.app.data.db.*
import com.smugview.app.data.repository.SmugMugRepository
import com.smugview.app.data.cast.CastManager
import com.smugview.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito
import androidx.paging.PagingData

@OptIn(ExperimentalCoroutinesApi::class)
class SmugViewModelTest {


    private val testScheduler = TestCoroutineScheduler()
    private val testDispatcher = UnconfinedTestDispatcher(testScheduler)
    
    private lateinit var mockApp: Application
    private lateinit var mockPrefs: SharedPreferences
    private lateinit var mockWorkManager: WorkManager
    private lateinit var mockRepository: SmugMugRepository
    private lateinit var mockCastManager: CastManager
    private lateinit var fakePasswordStore: com.smugview.app.data.security.FakePasswordStore
    private lateinit var viewModel: SmugViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        
        mockApp = Mockito.mock(Application::class.java)
        mockPrefs = Mockito.mock(SharedPreferences::class.java)
        val mockEditor = Mockito.mock(SharedPreferences.Editor::class.java)
        
        Mockito.`when`(mockApp.getSharedPreferences(Mockito.anyString(), Mockito.anyInt())).thenReturn(mockPrefs)
        Mockito.`when`(mockPrefs.getString(Mockito.anyString(), Mockito.anyString())).thenAnswer { invocation -> invocation.arguments[1] as String }
        Mockito.`when`(mockPrefs.getString(Mockito.anyString(), Mockito.isNull())).thenReturn(null)
        Mockito.`when`(mockPrefs.getBoolean(Mockito.anyString(), Mockito.anyBoolean())).thenAnswer { invocation -> invocation.arguments[1] as Boolean }
        Mockito.`when`(mockPrefs.getLong(Mockito.anyString(), Mockito.anyLong())).thenAnswer { invocation -> invocation.arguments[1] as Long }
        Mockito.`when`(mockPrefs.edit()).thenReturn(mockEditor)
        Mockito.`when`(mockEditor.putString(Mockito.anyString(), Mockito.anyString())).thenReturn(mockEditor)
        Mockito.`when`(mockEditor.putBoolean(Mockito.anyString(), Mockito.anyBoolean())).thenReturn(mockEditor)
        Mockito.`when`(mockEditor.putLong(Mockito.anyString(), Mockito.anyLong())).thenReturn(mockEditor)
        
        mockWorkManager = Mockito.mock(WorkManager::class.java)
        mockRepository = Mockito.mock(SmugMugRepository::class.java)
        // Cap the VM's "follow next-url" pagination loops at 2 pages in tests (replaces the old
        // SmugMugRepository.isTesting static). Without this a mock returns 0 and loops never run.
        Mockito.`when`(mockRepository.maxPagesPerFetch).thenReturn(2)
        mockCastManager = Mockito.mock(CastManager::class.java)
        
        Mockito.`when`(mockCastManager.discoveredDevices).thenReturn(MutableStateFlow(emptyList()))
        Mockito.`when`(mockCastManager.activeDevice).thenReturn(MutableStateFlow(null))
        Mockito.`when`(mockCastManager.isCasting).thenReturn(MutableStateFlow(false))
        Mockito.`when`(mockCastManager.currentImageUri).thenReturn(MutableStateFlow(null))
        Mockito.`when`(mockCastManager.slideshowInterval).thenReturn(MutableStateFlow(5))
        Mockito.`when`(mockCastManager.isSlideshowPlaying).thenReturn(MutableStateFlow(false))
        
        Mockito.`when`(mockRepository.isAlbumsCacheLoaded)
            .thenReturn(kotlinx.coroutines.flow.MutableStateFlow(true))

        Mockito.`when`(mockRepository.isIndexingSubtree)
            .thenReturn(kotlinx.coroutines.flow.MutableStateFlow(false))

        Mockito.`when`(mockRepository.albumsCache)
            .thenReturn(kotlinx.coroutines.flow.MutableStateFlow(emptyList()))
            
        Mockito.`when`(mockRepository.getSearchHistory())
            .thenReturn(kotlinx.coroutines.flow.MutableStateFlow(emptyList()))
            
        Mockito.`when`(mockRepository.getNodesWithActiveUpdates())
            .thenReturn(flowOf(emptyList()))
            
        Mockito.`when`(mockRepository.getLocalCollections(Mockito.anyString()))
            .thenReturn(flowOf(emptyList()))
            
        kotlinx.coroutines.runBlocking {
            Mockito.`when`(mockRepository.getAllCachedNodes())
                .thenReturn(emptyList())
            Mockito.`when`(mockRepository.hasSearchPhotosInDb(Mockito.anyString(), Mockito.anyString()))
                .thenReturn(true)
            Mockito.`when`(mockRepository.getImagesByKeywordPage(
                Mockito.nullable(String::class.java),
                Mockito.anyString(),
                Mockito.anyString(),
                Mockito.anyInt(),
                Mockito.anyInt(),
                Mockito.nullable(String::class.java)
            )).thenAnswer { invocation ->
                val scope = invocation.arguments[0] as? String
                val keywords = invocation.arguments[1] as String
                val apiKey = invocation.arguments[2] as String
                val count = invocation.arguments[3] as Int
                val start = invocation.arguments[4] as Int
                val nextUrl = invocation.arguments[5] as? String

                if (nextUrl == null) {
                    val list = kotlinx.coroutines.runBlocking {
                        mockRepository.getImagesByKeyword(scope, keywords, apiKey, count, start)
                    }
                    Triple(list, null, list.size)
                } else {
                    Triple(emptyList<AlbumImageData>(), null, 0)
                }
            }
        }
        
        fakePasswordStore = com.smugview.app.data.security.FakePasswordStore()
        viewModel = SmugViewModel(mockApp, mockRepository, mockWorkManager, mockCastManager, fakePasswordStore, testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun testPerformSearchWithEmptyCache() = runTest {
        viewModel.setActiveNicknameForTest("testUser")
        
        val mockPhotos = listOf(
            AlbumImageData(imageKey = "img555", title = "Sunset Shore")
        )
        
        Mockito.`when`(mockRepository.getUserRootNodeId(Mockito.anyString(), Mockito.anyString()))
            .thenReturn(flowOf(Result.success("4zqWw")))
            
        Mockito.`when`(mockRepository.getSearchResultNodes("Sunset", "site:testUser", "Folder"))
            .thenReturn(emptyList())
            
        Mockito.`when`(mockRepository.getSearchResultPhotos("Sunset", "site:testUser"))
            .thenReturn(emptyList())
            
        Mockito.`when`(mockRepository.hasSearchResultInDb("Sunset", "site:testUser"))
            .thenReturn(false)
        Mockito.`when`(mockRepository.hasSearchPhotosInDb("Sunset", "site:testUser"))
            .thenReturn(false)
            
        Mockito.`when`(mockRepository.searchNodesRemote("/api/v2/node/4zqWw", "site:testUser", "Sunset", BuildConfig.SMUGMUG_API_KEY, null))
            .thenReturn(flowOf(Result.success(emptyList())))
            
        // Mock getPagedSearchPhotos to return a dummy PagingSource
        val mockPagingSource = object : androidx.paging.PagingSource<Int, SearchResult>() {
            override fun getRefreshKey(state: androidx.paging.PagingState<Int, SearchResult>): Int? = null
            override suspend fun load(params: LoadParams<Int>): LoadResult<Int, SearchResult> {
                return LoadResult.Page(data = emptyList(), prevKey = null, nextKey = null)
            }
        }
        Mockito.`when`(mockRepository.getPagedSearchPhotos(Mockito.anyString(), Mockito.anyString(), Mockito.anyString()))
            .thenReturn(mockPagingSource)
        
        // Perform search
        viewModel.performSearch("Sunset")
        
        // Advance scheduler to run coroutines
        advanceUntilIdle()
        
        // Verify ViewModel has loaded results successfully
        val state = viewModel.searchState.value
        assertTrue("Expected Success state but was: $state", state is SearchUiState.Success)
        
        // Verify insertSearchQuery was called
        Mockito.verify(mockRepository).insertSearchQuery("Sunset", "testUser")
    }
    
    // NOTE: The former `testLiveApiMvysoSearch` / `testLiveApiMvysoBruteForce` tests were
    // removed. They hit the live SmugMug API over the network (flaky, non-deterministic,
    // CI-hostile), asserted nothing, and embedded a real API key in source. Live-API probing
    // belongs in a manually-run scratch tool, never in the unit suite.

    /**
     * Regression test for: searching right after unlocking a password-protected folder could read
     * repository.albumsCache.value mid-write from the background SmugMugRepository
     * .unlockAndIndexSubtree walk, matching only whatever galleries it had reached so far, then
     * cache that incomplete snapshot as "fully searched" for 24h. performSearch must wait for
     * repository.isIndexingSubtree to clear before reading the gallery cache.
     */
    @Test
    fun testPerformSearchWaitsForInProgressSubtreeIndexing() = runTest {
        viewModel.setActiveNicknameForTest("testUser")

        val indexingFlow = MutableStateFlow(true)
        val albumsFlow = MutableStateFlow<List<CachedNode>>(emptyList())
        Mockito.`when`(mockRepository.isIndexingSubtree).thenReturn(indexingFlow)
        Mockito.`when`(mockRepository.albumsCache).thenReturn(albumsFlow)

        Mockito.`when`(mockRepository.getUserRootNodeId(Mockito.anyString(), Mockito.anyString()))
            .thenReturn(flowOf(Result.success("4zqWw")))
        Mockito.`when`(mockRepository.getSearchResultNodes("Family", "site:testUser", "Folder"))
            .thenReturn(emptyList())
        Mockito.`when`(mockRepository.hasSearchPhotosInDb("Family", "site:testUser")).thenReturn(false)
        Mockito.`when`(
            mockRepository.searchNodesRemote(
                Mockito.anyString(), Mockito.anyString(), Mockito.anyString(),
                Mockito.anyString(), Mockito.nullable(String::class.java)
            )
        ).thenReturn(flowOf(Result.success(emptyList())))
        val mockPagingSource = object : androidx.paging.PagingSource<Int, SearchResult>() {
            override fun getRefreshKey(state: androidx.paging.PagingState<Int, SearchResult>): Int? = null
            override suspend fun load(params: LoadParams<Int>): LoadResult<Int, SearchResult> =
                LoadResult.Page(data = emptyList(), prevKey = null, nextKey = null)
        }
        Mockito.`when`(mockRepository.getPagedSearchPhotos(Mockito.anyString(), Mockito.anyString(), Mockito.anyString()))
            .thenReturn(mockPagingSource)

        viewModel.performSearch("Family")
        advanceUntilIdle()

        // Still indexing: performSearch must be parked waiting, not settled on the (as-yet
        // incomplete) cache snapshot.
        assertEquals(SearchUiState.Loading, viewModel.searchState.value)

        // Background indexing finishes, and the gallery it was walking toward lands in the cache.
        albumsFlow.value = listOf(
            CachedNode(
                nodeId = "album1", parentNodeId = "root", type = "Album", title = "Family Reunion",
                description = null, access = "Public", passwordHint = null,
                uri = "/api/v2/node/album1", childNodesUri = null, albumUri = "/api/v2/album/album1"
            )
        )
        indexingFlow.value = false
        advanceUntilIdle()

        val state = viewModel.searchState.value
        assertTrue("Expected Success state but was: $state", state is SearchUiState.Success)
        val galleries = (state as SearchUiState.Success).galleries
        assertEquals(1, galleries.size)
        assertEquals("Family Reunion", galleries.first().title)
    }

    @Test
    fun testPerformSearchCacheHitSkipsRemote() = runTest {
        viewModel.setActiveNicknameForTest("testUser")
        
        val mockPhotos = listOf(
            AlbumImageData(imageKey = "img555", title = "Sunset Cached")
        )
        
        Mockito.`when`(mockRepository.getUserRootNodeId(Mockito.anyString(), Mockito.anyString()))
            .thenReturn(flowOf(Result.success("4zqWw")))
        
        Mockito.`when`(mockRepository.getSearchResultNodes(Mockito.anyString(), Mockito.anyString(), Mockito.anyString()))
            .thenReturn(emptyList())
            
        // scopeKey for global search is now 'site:testUser'
        Mockito.`when`(mockRepository.getSearchResultPhotos("Sunset", "site:testUser"))
            .thenReturn(mockPhotos)
        
        // Cache uses getLong with '_ts' suffix; return a recent timestamp (1 minute ago) to simulate a cache hit
        val recentTimestamp = System.currentTimeMillis() - 60_000L
        Mockito.`when`(mockPrefs.getLong("site:testUser_Sunset_ts", 0L))
            .thenReturn(recentTimestamp)

        // Mock getPagedSearchPhotos to return a dummy PagingSource
        val mockPagingSource = object : androidx.paging.PagingSource<Int, SearchResult>() {
            override fun getRefreshKey(state: androidx.paging.PagingState<Int, SearchResult>): Int? = null
            override suspend fun load(params: LoadParams<Int>): LoadResult<Int, SearchResult> {
                return LoadResult.Page(data = emptyList(), prevKey = null, nextKey = null)
            }
        }
        Mockito.`when`(mockRepository.getPagedSearchPhotos(Mockito.anyString(), Mockito.anyString(), Mockito.anyString()))
            .thenReturn(mockPagingSource)
        
        // Perform search (forceRefresh = false)
        viewModel.performSearch("Sunset", forceRefresh = false)
        
        // Advance scheduler to run coroutines
        advanceUntilIdle()
        
        // Verify searchState matches DB cache immediately
        val state = viewModel.searchState.value
        assertTrue("Expected Success state but was: $state", state is SearchUiState.Success)
        
        Mockito.verify(mockRepository, Mockito.never()).performBackgroundSearchImages(
            "testUser",
            null,
            "site:testUser",
            "Sunset",
            BuildConfig.SMUGMUG_API_KEY,
            null
        )
    }

    @Test
    fun testPerformSearchNoCacheRequiresRemote() = runTest {
        viewModel.setActiveNicknameForTest("testUser")
        
        val mockPhotos = listOf(
            AlbumImageData(imageKey = "img555", title = "Sunset Shore")
        )
        
        Mockito.`when`(mockRepository.getUserRootNodeId(Mockito.anyString(), Mockito.anyString()))
            .thenReturn(flowOf(Result.success("4zqWw")))
            
        Mockito.`when`(mockRepository.getSearchResultNodes("Sunset", "site:testUser", "Folder"))
            .thenReturn(emptyList())
            
        // scopeKey for global search is now 'site:testUser'
        Mockito.`when`(mockRepository.getSearchResultPhotos("Sunset", "site:testUser"))
            .thenReturn(emptyList())
        
        // Cache uses getLong with '_ts' suffix; return 0 to simulate a cache miss
        Mockito.`when`(mockPrefs.getLong("site:testUser_Sunset_ts", 0L))
            .thenReturn(0L)
            
        Mockito.`when`(mockRepository.searchNodesRemote("/api/v2/node/4zqWw", "site:testUser", "Sunset", BuildConfig.SMUGMUG_API_KEY, null))
            .thenReturn(flowOf(Result.success(emptyList())))
            
        // Mock getPagedSearchPhotos to return a dummy PagingSource
        val mockPagingSource = object : androidx.paging.PagingSource<Int, SearchResult>() {
            override fun getRefreshKey(state: androidx.paging.PagingState<Int, SearchResult>): Int? = null
            override suspend fun load(params: LoadParams<Int>): LoadResult<Int, SearchResult> {
                return LoadResult.Page(data = emptyList(), prevKey = null, nextKey = null)
            }
        }
        Mockito.`when`(mockRepository.getPagedSearchPhotos(Mockito.anyString(), Mockito.anyString(), Mockito.anyString()))
            .thenReturn(mockPagingSource)
        
        // Perform search (forceRefresh = false but no cache-hit in SharedPreferences)
        viewModel.performSearch("Sunset", forceRefresh = false)
        
        // Advance scheduler to run coroutines
        advanceUntilIdle()
        
        // Verify searchState successfully gets remote results
        val state = viewModel.searchState.value
        assertTrue("Expected Success state but was: $state", state is SearchUiState.Success)
        
        Mockito.verify(mockRepository).performBackgroundSearchImages(
            "testUser",
            "/api/v2/node/4zqWw",
            "site:testUser",
            "Sunset",
            BuildConfig.SMUGMUG_API_KEY,
            null
        )
    }

    @Test
    fun testKeywordIntersection() = kotlinx.coroutines.runBlocking {
        viewModel.setActiveNicknameForTest("testUser")
        
        val claraImages = listOf(
            AlbumImageData(imageKey = "img1", title = "Clara Portrait", keywords = "clara, family"),
            AlbumImageData(imageKey = "img2", title = "Clara and Laurel", keywords = "clara, laurel, holiday"),
            AlbumImageData(imageKey = "img3", title = "Clara solo", keywords = "clara, portrait")
        )
        
        val claraLaurelImages = listOf(
            AlbumImageData(imageKey = "img1", title = "Clara Portrait", keywords = "clara, family"),
            AlbumImageData(imageKey = "img2", title = "Clara and Laurel", keywords = "clara, laurel, holiday"),
            AlbumImageData(imageKey = "img3", title = "Clara solo", keywords = "clara, portrait"),
            AlbumImageData(imageKey = "img4", title = "Laurel solo", keywords = "laurel, sports")
        )
        
        Mockito.`when`(mockRepository.getUserRootNodeId("testUser", BuildConfig.SMUGMUG_API_KEY))
            .thenReturn(flowOf(Result.success("4zqWw")))

        Mockito.`when`(mockRepository.getImagesByKeyword("/api/v2/node/4zqWw", "clara", BuildConfig.SMUGMUG_API_KEY, 500, 1))
            .thenReturn(claraImages)
            
        Mockito.`when`(mockRepository.getImagesByKeyword("/api/v2/node/4zqWw", "clara,laurel", BuildConfig.SMUGMUG_API_KEY, 500, 1))
            .thenReturn(claraLaurelImages)

        // Start collecting tagFilteredPhotos to keep the WhileSubscribed flow active
        val collectJob = launch {
            viewModel.tagFilteredPhotos.collect {}
        }

        // 1. Select "clara" keyword
        viewModel.selectTag("clara")
        
        var results1 = emptyList<AlbumImageData>()
        kotlinx.coroutines.withTimeout(3000) {
            while (results1.size != 3) {
                kotlinx.coroutines.delay(20)
                results1 = viewModel.tagFilteredPhotos.value
            }
        }
        
        assertEquals(3, results1.size)
        assertTrue(results1.any { it.imageKey == "img1" })
        assertTrue(results1.any { it.imageKey == "img2" })
        assertTrue(results1.any { it.imageKey == "img3" })

        // 2. Select "laurel" keyword as well
        viewModel.selectTag("laurel")
        
        var results2 = emptyList<AlbumImageData>()
        kotlinx.coroutines.withTimeout(3000) {
            while (results2.size != 1) {
                kotlinx.coroutines.delay(20)
                results2 = viewModel.tagFilteredPhotos.value
            }
        }
        
        collectJob.cancel()
    }

    @Test
    fun testPhotoGallerySortingAndFiltering() = runTest {
        val photos = listOf(
            AlbumImageData(imageKey = "img1", title = "A Video", format = "MP4", date = "2026-07-01"),
            AlbumImageData(imageKey = "img2", title = "Latest JPG", format = "JPG", date = "2026-07-09"),
            AlbumImageData(imageKey = "img3", title = "Middle JPG", format = "JPG", date = "2026-07-05")
        )
        viewModel.setRawPhotosForTest(photos)

        suspend fun collectPagingData(flow: Flow<PagingData<AlbumImageData>>): List<AlbumImageData> {
            val differ = androidx.paging.AsyncPagingDataDiffer(
                diffCallback = object : androidx.recyclerview.widget.DiffUtil.ItemCallback<AlbumImageData>() {
                    override fun areItemsTheSame(oldItem: AlbumImageData, newItem: AlbumImageData) = oldItem.imageKey == newItem.imageKey
                    override fun areContentsTheSame(oldItem: AlbumImageData, newItem: AlbumImageData) = oldItem == newItem
                },
                updateCallback = object : androidx.recyclerview.widget.ListUpdateCallback {
                    override fun onInserted(position: Int, count: Int) {}
                    override fun onRemoved(position: Int, count: Int) {}
                    override fun onMoved(fromPosition: Int, toPosition: Int) {}
                    override fun onChanged(position: Int, count: Int, payload: Any?) {}
                },
                mainDispatcher = kotlinx.coroutines.Dispatchers.Unconfined,
                workerDispatcher = kotlinx.coroutines.Dispatchers.Unconfined
            )
            val job = launch {
                flow.collect { differ.submitData(it) }
            }
            kotlinx.coroutines.yield()
            job.cancel()
            return differ.snapshot().items
        }

        // 1. Default Sort & Filter
        val defaultList = collectPagingData(viewModel.photosFlow)
        assertEquals(3, defaultList.size)

        // 2. Sort by date_asc (Older to Newer)
        viewModel.updateSort("date_asc")
        val ascList = collectPagingData(viewModel.photosFlow)
        assertEquals(3, ascList.size)
        assertEquals("img1", ascList[0].imageKey) // 2026-07-01
        assertEquals("img3", ascList[1].imageKey) // 2026-07-05
        assertEquals("img2", ascList[2].imageKey) // 2026-07-09

        // 3. Sort by date_desc (Newer to Older)
        viewModel.updateSort("date_desc")
        val descList = collectPagingData(viewModel.photosFlow)
        assertEquals(3, descList.size)
        assertEquals("img2", descList[0].imageKey) // 2026-07-09
        assertEquals("img3", descList[1].imageKey) // 2026-07-05
        assertEquals("img1", descList[2].imageKey) // 2026-07-01

        // 4. Filter IMAGES (Filters out MP4)
        viewModel.updateFilterType(GalleryFilterType.IMAGES)
        val imagesList = collectPagingData(viewModel.photosFlow)
        assertEquals(2, imagesList.size)
        assertTrue(imagesList.any { it.imageKey == "img2" })
        assertTrue(imagesList.any { it.imageKey == "img3" })
        assertFalse(imagesList.any { it.imageKey == "img1" })

        // 5. Filter VIDEOS (Only returns MP4)
        viewModel.updateFilterType(GalleryFilterType.VIDEOS)
        val videosList = collectPagingData(viewModel.photosFlow)
        assertEquals(1, videosList.size)
        assertEquals("img1", videosList.first().imageKey)
    }

    @Test
    fun testFolderNavigationAndStateMapping() = runTest {
        viewModel.setActiveNicknameForTest("testUser")
        
        val childNode = createTestNode(nodeId = "folder1", parentNodeId = "root", type = "Folder", title = "Subfolder 1")
        val grandChildren = listOf(
            createTestNode(nodeId = "album1", parentNodeId = "folder1", type = "Album", title = "Album 1")
        )
        
        Mockito.`when`(mockRepository.getNodeChildren("folder1", BuildConfig.SMUGMUG_API_KEY, false, null))
            .thenReturn(flowOf(Result.success(grandChildren)))
            
        viewModel.navigateToChildFolder(childNode)
        advanceUntilIdle()
        
        assertEquals("folder1", viewModel.currentFolderId)
        assertEquals(1, viewModel.folderNavigationStack.size)
        assertEquals("folder1", viewModel.folderNavigationStack.first().nodeId)
        
        val state = viewModel.browserState.value
        assertTrue(state is BrowserUiState.Success)
        val successNodes = (state as BrowserUiState.Success).nodes
        assertEquals(1, successNodes.size)
        assertEquals("album1", successNodes[0].nodeId)
    }

    @Test
    fun testSearchScreenDataMapping() = runTest {
        viewModel.setActiveNicknameForTest("testUser")
        
        val matchedFolders = listOf(
            createTestNode(nodeId = "folderSunset", type = "Folder", title = "Sunset Folder")
        )
        val matchedGalleries = listOf(
            createTestNode(nodeId = "albumSunset", type = "Album", title = "Sunset Album")
        )
        
        Mockito.`when`(mockRepository.getUserRootNodeId(Mockito.anyString(), Mockito.anyString()))
            .thenReturn(flowOf(Result.success("4zqWw")))
        
        Mockito.`when`(mockPrefs.getLong("site:testUser_Sunset_ts", 0L))
            .thenReturn(System.currentTimeMillis() - 60_000L)
            
        Mockito.`when`(mockRepository.getSearchResultNodes("Sunset", "site:testUser", "Folder"))
            .thenReturn(matchedFolders)
            
        Mockito.`when`(mockRepository.albumsCache)
            .thenReturn(kotlinx.coroutines.flow.MutableStateFlow(matchedGalleries))
            
        val mockPagingSource = object : androidx.paging.PagingSource<Int, SearchResult>() {
            override fun getRefreshKey(state: androidx.paging.PagingState<Int, SearchResult>): Int? = null
            override suspend fun load(params: LoadParams<Int>): LoadResult<Int, SearchResult> {
                return LoadResult.Page(data = emptyList(), prevKey = null, nextKey = null)
            }
        }
        Mockito.`when`(mockRepository.getPagedSearchPhotos(Mockito.anyString(), Mockito.anyString(), Mockito.anyString()))
            .thenReturn(mockPagingSource)

        viewModel.performSearch("Sunset")
        advanceUntilIdle()
        
        val state = viewModel.searchState.value
        assertTrue(state is SearchUiState.Success)
        val successState = state as SearchUiState.Success
        assertEquals(1, successState.folders.size)
        assertEquals("folderSunset", successState.folders.first().nodeId)
        assertEquals(1, successState.galleries.size)
        assertEquals("albumSunset", successState.galleries.first().nodeId)
        assertTrue(successState.photos.isEmpty()) // Replaced by Pager in Success payload
    }

    @Test
    fun testGetImageDetailsWithPasswordFallbackAndImageAlbum() = runTest {
        val testImageKey = "imgUnlocked"
        val testAlbumKey = "albumKey1"
        
        val albumNode = CachedNode(
            nodeId = testAlbumKey,
            parentNodeId = "root",
            type = "Album",
            title = "Test Album",
            description = null,
            access = "Password",
            passwordHint = null,
            uri = "/api/v2/node/$testAlbumKey",
            childNodesUri = null,
            albumUri = "/api/v2/album/$testAlbumKey",
            webUri = "https://gallery.idzifamily.com/Family/School/2026-06-13--Laurel-Graduation-Day"
        )
        
        Mockito.`when`(mockRepository.getCachedNodesForActiveSite())
            .thenReturn(listOf(albumNode))
        Mockito.`when`(mockRepository.getNodeByIdOrKey(testAlbumKey))
            .thenReturn(albumNode)

        // The password saved for THIS album is what must be used. (Previously the production code
        // brute-forced every saved password against the endpoint; that replay was removed, so the
        // credential now has to resolve to this node.)
        fakePasswordStore.savePassword(testAlbumKey, "gallery")

        Mockito.`when`(mockRepository.unlockAlbum(testAlbumKey, BuildConfig.SMUGMUG_API_KEY, "gallery"))
            .thenReturn(true)
            
        val anonymousImg = AlbumImageData(
            imageKey = testImageKey,
            title = "Locked Title",
            thumbnailUrl = "https://photos.smugmug.com/Family/School/2026-06-13--Laurel-Graduation-Day/i-imgUnlocked/0/Th/th.jpg",
            uris = null
        )
        
        val unlockedImg = AlbumImageData(
            imageKey = testImageKey,
            title = "Unlocked Title",
            thumbnailUrl = "https://photos.smugmug.com/Family/School/2026-06-13--Laurel-Graduation-Day/i-imgUnlocked/0/Th/th.jpg",
            uris = AlbumImageUris(imageAlbum = "/api/v2/album/$testAlbumKey")
        )
        
        Mockito.`when`(mockRepository.getImage(testImageKey, BuildConfig.SMUGMUG_API_KEY, null))
            .thenReturn(flowOf(Result.success(anonymousImg)))
            
        Mockito.`when`(mockRepository.getImage(testImageKey, BuildConfig.SMUGMUG_API_KEY, "gallery"))
            .thenReturn(flowOf(Result.success(unlockedImg)))
            
        val flow = viewModel.getImageDetails(testImageKey)
        val result = flow.first { it != null }
        
        assertNotNull(result)
        assertTrue(result!!.isSuccess)
        val img = result.getOrNull()
        assertNotNull(img)
        assertEquals("Unlocked Title", img?.title)
        assertEquals("/api/v2/album/$testAlbumKey", img?.uris?.album)
    }

    /**
     * Security regression test: a password saved for an UNRELATED album must never be replayed
     * against a different locked album. Previously getImageDetails looped over every saved
     * password and tried each one, which cross-contaminated credentials and could trip API rate
     * limiting.
     */
    @Test
    fun testGetImageDetailsDoesNotReplayUnrelatedPasswords() = runTest {
        val testImageKey = "imgLocked"
        val testAlbumKey = "albumKey1"

        val albumNode = CachedNode(
            nodeId = testAlbumKey,
            parentNodeId = "root",
            type = "Album",
            title = "Test Album",
            description = null,
            access = "Password",
            passwordHint = null,
            uri = "/api/v2/node/$testAlbumKey",
            childNodesUri = null,
            albumUri = "/api/v2/album/$testAlbumKey",
            webUri = "https://gallery.idzifamily.com/Family/School/2026-06-13--Laurel-Graduation-Day"
        )

        Mockito.`when`(mockRepository.getCachedNodesForActiveSite()).thenReturn(listOf(albumNode))
        Mockito.`when`(mockRepository.getNodeByIdOrKey(testAlbumKey)).thenReturn(albumNode)

        // A password belonging to a completely different album.
        fakePasswordStore.savePassword("some_other_album", "gallery")

        // If the code were to (incorrectly) replay it, this stub would unlock the album.
        Mockito.`when`(mockRepository.unlockAlbum(testAlbumKey, BuildConfig.SMUGMUG_API_KEY, "gallery"))
            .thenReturn(true)

        val anonymousImg = AlbumImageData(
            imageKey = testImageKey,
            title = "Locked Title",
            thumbnailUrl = "https://photos.smugmug.com/Family/School/2026-06-13--Laurel-Graduation-Day/i-$testImageKey/0/Th/th.jpg",
            uris = null
        )
        Mockito.`when`(mockRepository.getImage(testImageKey, BuildConfig.SMUGMUG_API_KEY, null))
            .thenReturn(flowOf(Result.success(anonymousImg)))

        val result = viewModel.getImageDetails(testImageKey).first { it != null }
        assertEquals("Locked Title", result?.getOrNull()?.title)

        // The unrelated credential must never have been tried against this album.
        Mockito.verify(mockRepository, Mockito.never())
            .unlockAlbum(testAlbumKey, BuildConfig.SMUGMUG_API_KEY, "gallery")
    }

    @Test
    fun testObserveSelectedTagsToLoadImagesInjectsRedactedKeywords() = runTest {
        viewModel.setActiveNicknameForTest("testUser")
        
        val redactedImage = AlbumImageData(
            imageKey = "img_redacted",
            title = "Redacted Caption",
            keywords = "",
            keywordArray = emptyList()
        )
        
        Mockito.`when`(mockRepository.getUserRootNodeId("testUser", BuildConfig.SMUGMUG_API_KEY))
            .thenReturn(flowOf(Result.success("4zqWw")))
            
        Mockito.`when`(mockRepository.getImagesByKeyword("/api/v2/node/4zqWw", "dancing", BuildConfig.SMUGMUG_API_KEY, 500, 1))
            .thenReturn(listOf(redactedImage))
            
        val collectJob = launch {
            viewModel.tagFilteredPhotos.collect {}
        }
        
        viewModel.selectTag("dancing")
        
        var results = emptyList<AlbumImageData>()
        kotlinx.coroutines.withTimeout(3000) {
            while (results.isEmpty()) {
                kotlinx.coroutines.delay(20)
                results = viewModel.tagFilteredPhotos.value
            }
        }
        
        collectJob.cancel()
        
        assertEquals(1, results.size)
        assertEquals("img_redacted", results[0].imageKey)
        assertEquals("dancing", results[0].keywordsString)
    }

    @Test
    fun testSelectAlbumMarksAsViewed() = runBlocking {
        val albumKey = "album1"
        val mockAlbum = com.smugview.app.data.api.AlbumDetails(
            uri = "/api/v2/album/$albumKey",
            albumKey = albumKey,
            nodeId = "node_album1",
            name = "Test Album"
        )
        
        val cachedNode = CachedNode(
            nodeId = "node_album1",
            parentNodeId = "folder1",
            type = "Album",
            title = "Test Album",
            description = null,
            access = "Public",
            passwordHint = null,
            uri = "/node/album1",
            childNodesUri = null,
            albumUri = "/api/v2/album/$albumKey",
            dateModified = "2026-07-15T06:07:19+00:00"
        )
        
        Mockito.`when`(mockRepository.getNodeByIdOrKey(albumKey))
            .thenReturn(cachedNode)
            
        // NOTE: the `password` params are nullable and are null in this scenario. Mockito's
        // anyString() does NOT match null, so these stubs previously missed and returned null,
        // leaking an NPE out of selectAlbum's coroutine into the *next* test. Use nullable().
        Mockito.`when`(mockRepository.resolveAndCacheAlbumLineage(Mockito.anyString(), Mockito.anyString(), Mockito.nullable(String::class.java)))
            .thenReturn(emptyList())

        Mockito.`when`(mockRepository.getAlbum(Mockito.anyString(), Mockito.anyString(), Mockito.nullable(String::class.java)))
            .thenReturn(mockAlbum)

        Mockito.`when`(mockRepository.getAlbumImagesPage(Mockito.anyString(), Mockito.anyString(), Mockito.nullable(String::class.java)))
            .thenReturn(com.smugview.app.data.api.AlbumImagesResponse(
                response = com.smugview.app.data.api.AlbumImagesPayload(
                    images = emptyList()
                )
            ))

        viewModel.selectAlbum(albumKey, "apiKey")
        
        Mockito.verify(mockRepository, Mockito.timeout(3000).atLeastOnce())
            .markNodeAsViewed("node_album1")
    }

    @Test
    fun testLoadActiveSiteDetailsSuccess() = runTest {
        val testNickname = "testUser"
        val mockAlbums = com.smugview.app.data.api.UserAlbumsResponse(
            response = com.smugview.app.data.api.UserAlbumsPayload(
                albums = listOf(
                    com.smugview.app.data.api.AlbumDetails(
                        uri = "/api/v2/album/galleryKey",
                        name = "Test Gallery",
                        albumKey = "galleryKey",
                        nodeId = "nodeId",
                        uris = com.smugview.app.data.api.NodeUris(
                            highlightImage = "/api/v2/image/highlight"
                        ),
                        dateModified = "2026-07-16T12:00:00Z",
                        imageCount = 4500
                    )
                )
            ),
            expansions = mapOf(
                "/api/v2/image/highlight" to com.smugview.app.data.api.ExpansionContainer(
                    image = com.smugview.app.data.api.ExpansionImage(
                        thumbnailUrl = "https://thumb.url"
                    )
                )
            )
        )
        
        val mockRecentImagesResponse = com.smugview.app.data.api.ImageSearchResponse(
            response = com.smugview.app.data.api.ImageSearchPayload(
                images = listOf(
                    com.smugview.app.data.api.AlbumImageData(
                        imageKey = "recentImgKey",
                        thumbnailUrl = "https://recent.url"
                    )
                ),
                pages = com.smugview.app.data.api.PagesData(
                    start = 1,
                    count = 1,
                    total = 4500
                )
            )
        )
        
        val mockTopKeywords = com.smugview.app.data.api.TopKeywordsResponse(
            response = com.smugview.app.data.api.TopKeywordsPayload(
                userTopKeywords = com.smugview.app.data.api.UserTopKeywordsContainer(
                    keywords = listOf("nature", "sunset")
                )
            )
        )
        
        kotlinx.coroutines.runBlocking {
            Mockito.`when`(mockRepository.getUserAlbumsResponse(Mockito.anyString(), Mockito.anyString(), Mockito.nullable(String::class.java)))
                .thenReturn(mockAlbums)
            Mockito.`when`(mockRepository.getUserRecentImagesResponse(Mockito.anyString(), Mockito.anyString(), Mockito.anyInt(), Mockito.nullable(String::class.java)))
                .thenReturn(mockRecentImagesResponse)
            Mockito.`when`(mockRepository.getUserTopKeywords(Mockito.anyString(), Mockito.anyString(), Mockito.nullable(String::class.java), Mockito.nullable(String::class.java)))
                .thenReturn(mockTopKeywords)
        }
            
        val loadDetailsMethod = SmugViewModel::class.java.getDeclaredMethod("loadActiveSiteDetails", String::class.java)
        loadDetailsMethod.isAccessible = true
        loadDetailsMethod.invoke(viewModel, testNickname)
        
        advanceUntilIdle()
        
        assertEquals(1, viewModel.activeSiteRecentImages.value.size)
        assertEquals("recentImgKey", viewModel.activeSiteRecentImages.value.first().imageKey)
        
        assertEquals(1, viewModel.activeSiteAlbums.value.size)
        assertEquals("galleryKey", viewModel.activeSiteAlbums.value.first().albumKey)
        assertEquals("https://thumb.url", viewModel.activeSiteAlbums.value.first().coverUrl)
        
        assertEquals(2, viewModel.activeSiteTopKeywords.value.size)
        assertEquals("nature", viewModel.activeSiteTopKeywords.value.first())

        assertEquals(4500, viewModel.activeSiteTotalPhotos.value)
        assertEquals(null, viewModel.activeSiteTotalGalleries.value) // mockAlbums response has null pages, so it defaults to size 1 after mapped
    }

    @Test
    fun testSelectSiteLoadsActiveSiteDetails() = runTest {
        val testNickname = "selectTestUser"
        val mockUser = com.smugview.app.data.api.UserData(
            nickName = testNickname,
            name = "Select Test",
            webUri = "https://select.test",
            uris = com.smugview.app.data.api.UserUris(
                node = "/api/v2/node/selectRootId"
            )
        )
        
        val mockAlbums = com.smugview.app.data.api.UserAlbumsResponse(
            response = com.smugview.app.data.api.UserAlbumsPayload(
                albums = listOf(
                    com.smugview.app.data.api.AlbumDetails(
                        uri = "/api/v2/album/selectGalleryKey",
                        name = "Select Test Gallery",
                        albumKey = "selectGalleryKey",
                        nodeId = "selectNodeId",
                        uris = com.smugview.app.data.api.NodeUris(
                            highlightImage = "/api/v2/image/selectHighlight"
                        ),
                        dateModified = "2026-07-16T12:00:00Z"
                    )
                )
            ),
            expansions = emptyMap()
        )
        
        val mockRecentImagesResponse = com.smugview.app.data.api.ImageSearchResponse(
            response = com.smugview.app.data.api.ImageSearchPayload(
                images = emptyList()
            )
        )
        
        val mockTopKeywords = com.smugview.app.data.api.TopKeywordsResponse(
            response = com.smugview.app.data.api.TopKeywordsPayload(
                userTopKeywords = com.smugview.app.data.api.UserTopKeywordsContainer(
                    keywords = emptyList()
                )
            )
        )
        
        kotlinx.coroutines.runBlocking {
            Mockito.`when`(mockRepository.parseNodeIdFromUri(Mockito.anyString()))
                .thenReturn("selectRootId")
            Mockito.`when`(mockRepository.getUserProfile(Mockito.anyString(), Mockito.anyString(), Mockito.nullable(String::class.java)))
                .thenReturn(flowOf(Result.success(mockUser)))
            Mockito.`when`(mockRepository.getUserAlbumsResponse(Mockito.anyString(), Mockito.anyString(), Mockito.nullable(String::class.java)))
                .thenReturn(mockAlbums)
            Mockito.`when`(mockRepository.getUserRecentImagesResponse(Mockito.anyString(), Mockito.anyString(), Mockito.anyInt(), Mockito.nullable(String::class.java)))
                .thenReturn(mockRecentImagesResponse)
            Mockito.`when`(mockRepository.getUserTopKeywords(Mockito.anyString(), Mockito.anyString(), Mockito.nullable(String::class.java), Mockito.nullable(String::class.java)))
                .thenReturn(mockTopKeywords)
            Mockito.`when`(mockRepository.getNodeChildren(Mockito.anyString(), Mockito.anyString(), Mockito.anyBoolean(), Mockito.nullable(String::class.java), Mockito.nullable(String::class.java)))
                .thenReturn(flowOf(Result.success(emptyList())))
        }
        
        viewModel.selectSite(testNickname)
        advanceUntilIdle()
        
        assertEquals(1, viewModel.activeSiteAlbums.value.size)
        assertEquals("selectGalleryKey", viewModel.activeSiteAlbums.value.first().albumKey)
    }

    @Test
    fun testTreeSyncSkipsLockedNodes() = runTest {
        val testNickname = "syncTestUser"
        val mockUser = com.smugview.app.data.api.UserData(
            nickName = testNickname,
            name = "Sync Test",
            webUri = "https://sync.test",
            uris = com.smugview.app.data.api.UserUris(
                node = "/api/v2/node/syncRootId"
            )
        )
        
        val lockedNode = CachedNode(
            nodeId = "lockedNodeId",
            parentNodeId = "syncRootId",
            type = "Folder",
            title = "Locked Folder",
            description = null,
            access = "Password",
            passwordHint = "hint",
            uri = "/api/v2/node/lockedNodeId",
            childNodesUri = null,
            albumUri = null
        )
        
        var wasLockedNodeCalled = false
        
        kotlinx.coroutines.runBlocking {
            Mockito.`when`(mockRepository.parseNodeIdFromUri(Mockito.anyString()))
                .thenReturn("syncRootId")
            Mockito.`when`(mockRepository.getUserProfile(Mockito.anyString(), Mockito.anyString(), Mockito.nullable(String::class.java)))
                .thenReturn(flowOf(Result.success(mockUser)))
            Mockito.`when`(mockRepository.getUserAlbumsResponse(Mockito.anyString(), Mockito.anyString(), Mockito.nullable(String::class.java)))
                .thenReturn(com.smugview.app.data.api.UserAlbumsResponse(com.smugview.app.data.api.UserAlbumsPayload(emptyList()), emptyMap()))
            Mockito.`when`(mockRepository.getUserRecentImagesResponse(Mockito.anyString(), Mockito.anyString(), Mockito.anyInt(), Mockito.nullable(String::class.java)))
                .thenReturn(com.smugview.app.data.api.ImageSearchResponse(com.smugview.app.data.api.ImageSearchPayload(emptyList())))
            Mockito.`when`(mockRepository.getUserTopKeywords(Mockito.anyString(), Mockito.anyString(), Mockito.nullable(String::class.java), Mockito.nullable(String::class.java)))
                .thenReturn(com.smugview.app.data.api.TopKeywordsResponse(com.smugview.app.data.api.TopKeywordsPayload(com.smugview.app.data.api.UserTopKeywordsContainer(emptyList()))))
            
            Mockito.`when`<Flow<Result<List<CachedNode>>>>(
                mockRepository.getNodeChildren(
                    Mockito.anyString(),
                    Mockito.anyString(),
                    Mockito.anyBoolean(),
                    Mockito.nullable(String::class.java),
                    Mockito.nullable(String::class.java)
                )
            ).thenAnswer { invocation ->
                val nodeId = invocation.arguments[0] as String
                if (nodeId == "syncRootId") {
                    flowOf(Result.success(listOf(lockedNode)))
                } else if (nodeId == "lockedNodeId") {
                    wasLockedNodeCalled = true
                    flowOf<Result<List<CachedNode>>>(Result.failure(Exception("Should not crawl locked node")))
                } else {
                    flowOf(Result.success(emptyList()))
                }
            }
                
            Mockito.`when`(mockRepository.getNodeById("lockedNodeId")).thenReturn(lockedNode)
        }
        
        viewModel.selectSite(testNickname)
        advanceUntilIdle()
        
        assertFalse("Should not crawl lockedNodeId", wasLockedNodeCalled)
    }

    @Test
    fun testHandleAlbumLoadErrorFallbackToInMemoryAlbums() = runTest {
        val lockedAlbumKey = "inMemLockedKey"
        val mockAlbums = com.smugview.app.data.api.UserAlbumsResponse(
            response = com.smugview.app.data.api.UserAlbumsPayload(
                albums = listOf(
                    com.smugview.app.data.api.AlbumDetails(
                        uri = "/api/v2/album/$lockedAlbumKey",
                        name = "In Memory Locked Gallery",
                        albumKey = lockedAlbumKey,
                        nodeId = "inMemNodeId",
                        securityType = "Password",
                        passwordHint = "Some hint",
                        uris = com.smugview.app.data.api.NodeUris(
                            highlightImage = null
                        ),
                        dateModified = "2026-07-16T12:00:00Z"
                    )
                )
            ),
            expansions = emptyMap()
        )
        
        val testNickname = "fallbackTestUser"
        val mockUser = com.smugview.app.data.api.UserData(
            nickName = testNickname,
            name = "Fallback Test",
            webUri = "https://fallback.test",
            uris = com.smugview.app.data.api.UserUris(
                node = "/api/v2/node/fallbackRootId"
            )
        )
        
        kotlinx.coroutines.runBlocking {
            Mockito.`when`(mockRepository.parseNodeIdFromUri(Mockito.anyString()))
                .thenReturn("fallbackRootId")
            Mockito.`when`(mockRepository.getUserProfile(Mockito.anyString(), Mockito.anyString(), Mockito.nullable(String::class.java)))
                .thenReturn(flowOf(Result.success(mockUser)))
            Mockito.`when`(mockRepository.getUserAlbumsResponse(Mockito.anyString(), Mockito.anyString(), Mockito.nullable(String::class.java)))
                .thenReturn(mockAlbums)
            Mockito.`when`(mockRepository.getUserRecentImagesResponse(Mockito.anyString(), Mockito.anyString(), Mockito.anyInt(), Mockito.nullable(String::class.java)))
                .thenReturn(com.smugview.app.data.api.ImageSearchResponse(com.smugview.app.data.api.ImageSearchPayload(emptyList())))
            Mockito.`when`(mockRepository.getUserTopKeywords(Mockito.anyString(), Mockito.anyString(), Mockito.nullable(String::class.java), Mockito.nullable(String::class.java)))
                .thenReturn(com.smugview.app.data.api.TopKeywordsResponse(com.smugview.app.data.api.TopKeywordsPayload(com.smugview.app.data.api.UserTopKeywordsContainer(emptyList()))))
            Mockito.`when`<Flow<Result<List<CachedNode>>>>(
                mockRepository.getNodeChildren(
                    Mockito.anyString(),
                    Mockito.anyString(),
                    Mockito.anyBoolean(),
                    Mockito.nullable(String::class.java),
                    Mockito.nullable(String::class.java)
                )
            ).thenReturn(flowOf(Result.success(emptyList())))
                
            Mockito.`when`(mockRepository.getNodeById(lockedAlbumKey)).thenReturn(null)
            Mockito.`when`(mockRepository.getNodeByIdOrKey(lockedAlbumKey)).thenReturn(null)
        }
        
        viewModel.selectSite(testNickname)
        advanceUntilIdle()
        
        kotlinx.coroutines.runBlocking {
            Mockito.`when`<com.smugview.app.data.api.AlbumDetails?>(
                mockRepository.getAlbum(
                    Mockito.anyString(),
                    Mockito.anyString(),
                    Mockito.nullable(String::class.java)
                )
            ).thenThrow(retrofit2.HttpException(retrofit2.Response.error<Any>(401, okhttp3.ResponseBody.create(null, ""))))

            Mockito.`when`<com.smugview.app.data.api.AlbumImagesResponse>(
                mockRepository.getAlbumImagesPage(
                    Mockito.anyString(),
                    Mockito.anyString(),
                    Mockito.nullable(String::class.java)
                )
            ).thenThrow(retrofit2.HttpException(retrofit2.Response.error<Any>(401, okhttp3.ResponseBody.create(null, ""))))
        }
        
        viewModel.selectAlbum(lockedAlbumKey)
        advanceUntilIdle()
        
        val promptNode = viewModel.passwordPromptNode
        assertNotNull("Password prompt node should not be null", promptNode)
        assertEquals(lockedAlbumKey, promptNode?.nodeId)
        assertEquals("Password", promptNode?.access)
        assertEquals("Some hint", promptNode?.passwordHint)
        assertEquals("In Memory Locked Gallery", promptNode?.title)
    }
}



// Add extension to expose activeNickname setter for unit test purposes
fun SmugViewModel.setActiveNicknameForTest(nickname: String) {
    val activeNicknameField = SmugViewModel::class.java.getDeclaredField("_activeNickname")
    activeNicknameField.isAccessible = true
    val stateFlow = activeNicknameField.get(this) as kotlinx.coroutines.flow.MutableStateFlow<String?>
    stateFlow.value = nickname
}

fun SmugViewModel.setRawPhotosForTest(photos: List<AlbumImageData>) {
    val rawPhotosField = SmugViewModel::class.java.getDeclaredField("_rawPhotos")
    rawPhotosField.isAccessible = true
    val stateFlow = rawPhotosField.get(this) as kotlinx.coroutines.flow.MutableStateFlow<List<AlbumImageData>>
    stateFlow.value = photos
}

fun createTestNode(
    nodeId: String,
    parentNodeId: String? = null,
    type: String = "Folder",
    title: String = "Test Node",
    uri: String = "/api/v2/node/$nodeId"
): CachedNode {
    return CachedNode(
        nodeId = nodeId,
        parentNodeId = parentNodeId,
        type = type,
        title = title,
        description = null,
        access = "Public",
        passwordHint = null,
        uri = uri,
        childNodesUri = null,
        albumUri = null
    )
}
