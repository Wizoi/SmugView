package com.smugview.app.ui.viewmodel

import android.app.Application
import android.content.SharedPreferences
import androidx.work.WorkManager
import com.smugview.app.data.api.*
import com.smugview.app.data.db.*
import com.smugview.app.data.repository.SmugMugRepository
import com.smugview.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito

@OptIn(ExperimentalCoroutinesApi::class)
class SmugViewModelTest {

    private val testScheduler = TestCoroutineScheduler()
    private val testDispatcher = UnconfinedTestDispatcher(testScheduler)
    
    private lateinit var mockApp: Application
    private lateinit var mockPrefs: SharedPreferences
    private lateinit var mockWorkManager: WorkManager
    private lateinit var mockRepository: SmugMugRepository
    private lateinit var viewModel: SmugViewModel

    @Before
    fun setUp() {
        com.smugview.app.data.repository.SmugMugRepository.isTesting = true
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
        
        Mockito.`when`(mockRepository.isAlbumsCacheLoaded)
            .thenReturn(kotlinx.coroutines.flow.MutableStateFlow(true))
            
        Mockito.`when`(mockRepository.albumsCache)
            .thenReturn(kotlinx.coroutines.flow.MutableStateFlow(emptyList()))
            
        Mockito.`when`(mockRepository.getSearchHistory())
            .thenReturn(kotlinx.coroutines.flow.MutableStateFlow(emptyList()))
            
        Mockito.`when`(mockRepository.getLocalCollections(Mockito.anyString()))
            .thenReturn(flowOf(emptyList()))
            
        kotlinx.coroutines.runBlocking {
            Mockito.`when`(mockRepository.getAllCachedNodes())
                .thenReturn(emptyList())
        }
        
        viewModel = SmugViewModel(mockApp, mockRepository, mockWorkManager)
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
            
        Mockito.`when`(mockRepository.getSearchResultNodes("Sunset", "site", "Folder"))
            .thenReturn(emptyList())
            
        Mockito.`when`(mockRepository.getSearchResultPhotos("Sunset", "site"))
            .thenReturn(emptyList())
            
        Mockito.`when`(mockRepository.hasSearchResultInDb("Sunset", "site"))
            .thenReturn(false)
            
        Mockito.`when`(mockRepository.searchNodesRemote("/api/v2/node/4zqWw", "site", "Sunset", BuildConfig.SMUGMUG_API_KEY, null))
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
        Mockito.verify(mockRepository).insertSearchQuery("Sunset")
    }
    
    @Test
    fun testLiveApiMvysoSearch() = runTest {
        val okHttpClient = okhttp3.OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request().newBuilder()
                .header("Accept", "application/json")
                .build()
            chain.proceed(request)
        }.build()
        val retrofit = retrofit2.Retrofit.Builder()
            .baseUrl("https://api.smugmug.com/api/v2/")
            .client(okHttpClient)
            .addConverterFactory(retrofit2.converter.gson.GsonConverterFactory.create())
            .build()
            
        val api = retrofit.create(com.smugview.app.data.api.SmugMugApi::class.java)
        
        try {
            // Test image!search with user scope
            val res2 = api.searchImages("***REMOVED_SMUGMUG_API_KEY***", "/api/v2/user/cmac", "MVYSO", "DateTaken", "Descending", 10, 1)
            println("image!search MVYSO Count: ${res2.response.images?.size ?: 0}")
            if (res2.response.images?.isNotEmpty() == true) {
                println("First image: ${res2.response.images!!.first().title}")
            }
        } catch (e: Exception) {
            println("image!search failed: ${e.message}")
        }
    }
    
    @Test
    fun testLiveApiMvysoBruteForce() = kotlinx.coroutines.runBlocking {
        val okHttpClient = okhttp3.OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request().newBuilder()
                .header("Accept", "application/json")
                .build()
            chain.proceed(request)
        }.build()
        val retrofit = retrofit2.Retrofit.Builder()
            .baseUrl("https://api.smugmug.com/api/v2/")
            .client(okHttpClient)
            .addConverterFactory(retrofit2.converter.gson.GsonConverterFactory.create())
            .build()
            
        val api = retrofit.create(com.smugview.app.data.api.SmugMugApi::class.java)
        
        try {
            val albums = api.getUserAlbums("cmac", "***REMOVED_SMUGMUG_API_KEY***", 200)
            println("Found ${albums.response.albums?.size} albums.")
            for (album in (albums.response.albums ?: emptyList()).take(2)) {
                try {
                    val isExactMatch = album.name.contains("MVYSO", ignoreCase = true)
                    val textToSearch = if (isExactMatch) null else "MVYSO"
                    val res = api.searchImages("***REMOVED_SMUGMUG_API_KEY***", album.uri, textToSearch, "DateTaken", "Descending", 10, 1)
                    if (res.response.images?.isNotEmpty() == true) {
                        println("FOUND images in ALBUM: ${album.name} (Uri: ${album.uri}) - ${res.response.images!!.size} images. (Text used: $textToSearch)")
                        println("First image: ${res.response.images!!.first().title}")
                    }
                } catch (e: Exception) {
                    // ignore failures for locked albums
                }
                kotlinx.coroutines.delay(200) // Pace it
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
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
            
        // scopeKey for global search is now 'site', not the root node ID
        Mockito.`when`(mockRepository.getSearchResultPhotos("Sunset", "site"))
            .thenReturn(mockPhotos)
        
        // Cache uses getLong with '_ts' suffix; return a recent timestamp (1 minute ago) to simulate a cache hit
        val recentTimestamp = System.currentTimeMillis() - 60_000L
        Mockito.`when`(mockPrefs.getLong("site_Sunset_ts", 0L))
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
            "site",
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
            
        Mockito.`when`(mockRepository.getSearchResultNodes("Sunset", "site", "Folder"))
            .thenReturn(emptyList())
            
        // scopeKey for global search is now 'site'
        Mockito.`when`(mockRepository.getSearchResultPhotos("Sunset", "site"))
            .thenReturn(emptyList())
        
        // Cache uses getLong with '_ts' suffix; return 0 to simulate a cache miss
        Mockito.`when`(mockPrefs.getLong("site_Sunset_ts", 0L))
            .thenReturn(0L)
            
        Mockito.`when`(mockRepository.searchNodesRemote("/api/v2/node/4zqWw", "site", "Sunset", BuildConfig.SMUGMUG_API_KEY, null))
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
            "site",
            "Sunset",
            BuildConfig.SMUGMUG_API_KEY,
            null
        )
    }

    @Test
    fun testKeywordIntersection() = runTest {
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
        
        Mockito.`when`(mockRepository.getImagesByKeyword("/api/v2/user/testUser", "clara", BuildConfig.SMUGMUG_API_KEY))
            .thenReturn(claraImages)
            
        Mockito.`when`(mockRepository.getImagesByKeyword("/api/v2/user/testUser", "clara,laurel", BuildConfig.SMUGMUG_API_KEY))
            .thenReturn(claraLaurelImages)

        // Start collecting tagFilteredPhotos to keep the WhileSubscribed flow active
        val collectJob = launch {
            viewModel.tagFilteredPhotos.collect {}
        }

        // 1. Select "clara" keyword
        viewModel.selectTag("clara")
        advanceUntilIdle()
        
        // Check results
        val results1 = viewModel.tagFilteredPhotos.value
        assertEquals(3, results1.size)
        assertTrue(results1.any { it.imageKey == "img1" })
        assertTrue(results1.any { it.imageKey == "img2" })
        assertTrue(results1.any { it.imageKey == "img3" })

        // 2. Select "laurel" keyword as well
        viewModel.selectTag("laurel")
        advanceUntilIdle()
        
        // Check results: only the photo with BOTH keywords should remain
        val results2 = viewModel.tagFilteredPhotos.value
        assertEquals(1, results2.size)
        assertEquals("img2", results2.first().imageKey)

        collectJob.cancel()
    }
}


// Add extension to expose activeNickname setter for unit test purposes
fun SmugViewModel.setActiveNicknameForTest(nickname: String) {
    val activeNicknameField = SmugViewModel::class.java.getDeclaredField("_activeNickname")
    activeNicknameField.isAccessible = true
    val stateFlow = activeNicknameField.get(this) as kotlinx.coroutines.flow.MutableStateFlow<String?>
    stateFlow.value = nickname
}
