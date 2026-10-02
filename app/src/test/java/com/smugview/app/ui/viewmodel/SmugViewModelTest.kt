package com.smugview.app.ui.viewmodel

import android.app.Application
import android.content.SharedPreferences
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
    private lateinit var mockOffline: com.smugview.app.data.offline.OfflineCollections
    private lateinit var mockOfflineReader: com.smugview.app.data.offline.OfflineReader
    private lateinit var mockRepository: SmugMugRepository
    /** First-page tag-search results the mocked `getImagesByKeywordPage` answers, keyed `scope|keywords|count|start`. */
    private val keywordPages = mutableMapOf<String, List<AlbumImageData>>()
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
        
        mockOffline = Mockito.mock(com.smugview.app.data.offline.OfflineCollections::class.java)
        mockOfflineReader = Mockito.mock(com.smugview.app.data.offline.OfflineReader::class.java)
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

        Mockito.`when`(mockRepository.albumIndex)
            .thenReturn(kotlinx.coroutines.flow.MutableStateFlow(SmugMugRepository.AlbumIndexSnapshot("", emptyList())))
            
        // Home totals from the index: none yet, so the page-1 numbers stand (design 3.6)
        Mockito.`when`(mockRepository.siteTotals(Mockito.anyString()))
            .thenReturn(flowOf(null))

        Mockito.`when`(mockRepository.getSearchHistory())
            .thenReturn(kotlinx.coroutines.flow.MutableStateFlow(emptyList()))
            
        Mockito.`when`(mockRepository.getNodesWithActiveUpdates(Mockito.anyString()))
            .thenReturn(flowOf(emptyList()))
            
        Mockito.`when`(mockRepository.getLocalCollections(Mockito.anyString()))
            .thenReturn(flowOf(emptyList()))
            
        kotlinx.coroutines.runBlocking {
            Mockito.`when`(mockRepository.lineageOf(Mockito.anyString(), Mockito.anyString()))
                .thenReturn(emptyList())
            Mockito.`when`(mockRepository.getAllCachedNodes())
                .thenReturn(emptyList())
            Mockito.`when`(mockRepository.hasSearchPhotosInDb(Mockito.anyString(), Mockito.anyString()))
                .thenReturn(true)
            Mockito.`when`(mockRepository.getImagesByKeywordPage(
                Mockito.nullable(String::class.java),
                Mockito.anyString(),
                Mockito.anyString(),
                Mockito.anyInt(),
                Mockito.anyInt()
            )).thenAnswer { invocation ->
                val scope = invocation.arguments[0] as? String
                val keywords = invocation.arguments[1] as String
                val count = invocation.arguments[3] as Int
                val start = invocation.arguments[4] as Int
                val list = keywordPages["$scope|$keywords|$count|$start"] ?: emptyList()
                com.smugview.app.data.api.ImageSearchResponse(
                    com.smugview.app.data.api.ImageSearchPayload(list, com.smugview.app.data.api.PagesData(start, list.size, list.size))
                )
            }
        }
        
        fakePasswordStore = com.smugview.app.data.security.FakePasswordStore()
        Mockito.`when`(mockRepository.unlocks)
            .thenReturn(com.smugview.app.data.repository.UnlockManager(mockRepository, fakePasswordStore))
        viewModel = SmugViewModel(mockApp, mockRepository, mockOffline, mockOfflineReader, mockCastManager, fakePasswordStore, testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** Keeps what the launch-unlock run reported, for assertions. */
    private class RecordingReporter : com.smugview.app.diag.SyncReporter {
        val runs = java.util.concurrent.CopyOnWriteArrayList<com.smugview.app.diag.SyncRun>()
        @Volatile var finished = false
        override fun begin(kind: com.smugview.app.diag.SyncKind, nickname: String, actionId: String) =
            com.smugview.app.diag.SyncRun(actionId, kind, nickname, System.currentTimeMillis()).also { runs.add(it) }
        override fun finish(run: com.smugview.app.diag.SyncRun) {
            if (run.stop == null) run.stop = com.smugview.app.diag.StopReason.Completed
            finished = true
        }
        override fun recordUnlock(a: com.smugview.app.diag.UnlockAttempt) {}
        override fun recent(n: Int) = runs.toList().takeLast(n)
        override fun hasOpenRun() = runs.isNotEmpty() && !finished
    }

    // Phase 2 step 2-7 (design 3.2, 3.8): the ViewModel no longer unlocks at launch. The unlock moved
    // into the repository's site sync (SessionUnlockTest) so it runs BEFORE the crawl; the old VM path
    // skipped every saved key as "already unlocked" (R-22) and so never created a session (findings #16).
    @Test
    fun init_doesNotUnlockAnything_theSiteSyncOwnsTheLaunchUnlock() {
        val reporter = RecordingReporter()
        val store = com.smugview.app.data.security.FakePasswordStore(
            mapOf("2sDN5x" to "fake-pw-1", "LCdk7F" to "fake-pw-1")
        )
        SmugViewModel(mockApp, mockRepository, mockOffline, mockOfflineReader, mockCastManager, store, testDispatcher, reporter)
        Thread.sleep(300) // the old init launched the unlock on Dispatchers.IO

        assertTrue("no LaunchUnlock run from the ViewModel", reporter.runs.isEmpty())
        runBlocking {
            Mockito.verify(mockRepository, Mockito.never()).unlockNodeResult(Mockito.anyString(), Mockito.anyString(), Mockito.anyString())
            Mockito.verify(mockRepository, Mockito.never()).unlockAlbumResult(Mockito.anyString(), Mockito.anyString(), Mockito.anyString())
        }
    }

    @Test
    fun navigatingIntoFolder_doesNotMarkItsGalleriesViewed() = runTest {
        // v0.7.6 regression: navigateToChildFolder called markNodeAsViewed(folder), which marks
        // the folder AND EVERY GALLERY BENEATH IT as viewed — so merely browsing into "School"
        // silently cleared the "new" dots of galleries the user never opened. Only opening a
        // gallery (or the explicit long-press "Mark as Viewed") may clear a dot.
        Mockito.`when`(
            mockRepository.getNodeChildren(
                Mockito.anyString(),
                Mockito.anyString(), Mockito.anyString(), Mockito.anyBoolean(),
                Mockito.nullable(String::class.java), Mockito.nullable(String::class.java)
            )
        ).thenReturn(flowOf(Result.success(emptyList())))
        val school = CachedNode(
            nodeId = "school", parentNodeId = "family", type = "Folder", title = "School",
            description = null, access = "None", passwordHint = null,
            uri = "/api/v2/node/school", childNodesUri = null, albumUri = null
        )

        viewModel.navigateToChildFolder(school)
        advanceUntilIdle()

        Mockito.verify(mockRepository, Mockito.never()).markNodeAsViewed(Mockito.anyString())
    }

    // Phase 2 step 2-10 (design 3.5): the dot set is per site, by NodeID. A Home screen for one site
    // must not show another site's dots (the old query read every cached node).
    @Test
    fun activeUpdateNodeIds_followsTheActiveSiteOnly_andIsEmptyBeforeOneIsChosen() = runTest {
        Mockito.`when`(mockRepository.getNodesWithActiveUpdates("idzifamily"))
            .thenReturn(flowOf(listOf("LCdk7F", "P4BKB", "2sDN5x", "4zqWw")))
        Mockito.`when`(mockRepository.getNodesWithActiveUpdates("someoneelse"))
            .thenReturn(flowOf(emptyList()))
        val job = launch(testDispatcher) { viewModel.activeUpdateNodeIds.collect {} }

        assertEquals("no active site yet: nothing lit", emptySet<String>(), viewModel.activeUpdateNodeIds.value)
        Mockito.verify(mockRepository, Mockito.never()).getNodesWithActiveUpdates(Mockito.anyString())

        viewModel.setActiveNicknameForTest("idzifamily")
        assertEquals(setOf("LCdk7F", "P4BKB", "2sDN5x", "4zqWw"), viewModel.activeUpdateNodeIds.value)

        viewModel.setActiveNicknameForTest("someoneelse")
        assertEquals("switching site switches the dot set", emptySet<String>(), viewModel.activeUpdateNodeIds.value)
        job.cancel()
    }

    // Phase 2 step 2-11 (findings #5): Home compared AlbumKey with a set of NodeIDs, so a Featured
    // gallery never got its dot (AlbumKey FfHCms != NodeID LCdk7F).
    @Test
    fun homeFeaturedGallery_isDottedByNodeId_notByAlbumKey() = runTest {
        val albums = com.smugview.app.data.api.UserAlbumsResponse(
            response = com.smugview.app.data.api.UserAlbumsPayload(
                albums = listOf(
                    com.smugview.app.data.api.AlbumDetails(
                        uri = "/api/v2/album/FfHCms", albumKey = "FfHCms", nodeId = "LCdk7F", name = "New School Year",
                        dateModified = "2026-09-28T12:00:00+00:00", imageCount = 12
                    )
                )
            )
        )
        kotlinx.coroutines.runBlocking {
            Mockito.`when`(mockRepository.getUserAlbumsResponse(Mockito.anyString(), Mockito.anyString()))
                .thenReturn(albums)
            Mockito.`when`(mockRepository.getUserRecentImagesResponse(Mockito.anyString(), Mockito.anyString(), Mockito.anyInt()))
                .thenReturn(com.smugview.app.data.api.ImageSearchResponse(
                    response = com.smugview.app.data.api.ImageSearchPayload(images = emptyList())
                ))
            Mockito.`when`(mockRepository.getUserTopKeywords(Mockito.anyString(), Mockito.anyString(), Mockito.nullable(String::class.java)))
                .thenReturn(com.smugview.app.data.api.TopKeywordsResponse(
                    response = com.smugview.app.data.api.TopKeywordsPayload(
                        userTopKeywords = com.smugview.app.data.api.UserTopKeywordsContainer(keywords = emptyList())
                    )
                ))
        }
        val load = SmugViewModel::class.java.getDeclaredMethod("loadActiveSiteDetails", String::class.java)
        load.isAccessible = true
        load.invoke(viewModel, "idzifamily")
        advanceUntilIdle()

        val item = viewModel.activeSiteAlbums.value.single()
        assertEquals("FfHCms", item.albumKey)
        assertEquals("the hub item carries the gallery's NodeID", "LCdk7F", item.nodeId)
        assertTrue("dotted when the dot set holds the NodeID", item.hasActiveUpdate(setOf("LCdk7F", "P4BKB", "2sDN5x")))
        assertFalse("the AlbumKey is not a node", item.hasActiveUpdate(setOf("FfHCms")))
        assertFalse("an unrelated set lights nothing", item.hasActiveUpdate(setOf("sXQz4G")))
    }

    // The Home tab lock icon compared the AlbumKey with a set of NodeIDs (findings #5, same mix as the dot):
    // a gallery unlocked through its folder (NodeID in the set, AlbumKey not) still showed a closed lock.
    @Test fun hubItem_underAnUnlockedFolder_showsAnOpenLock() {
        val item = com.smugview.app.ui.viewmodel.HubAlbumItem(
            albumKey = "FfHCms", title = "New School Year", coverUrl = null, imageCount = 3,
            dateModified = null, access = "Password", nodeId = "LCdk7F"
        )
        assertTrue("the NodeID is unlocked through its folder", item.isUnlocked(setOf("2sDN5x", "P4BKB", "LCdk7F")))
        assertTrue("a directly saved AlbumKey also counts", item.isUnlocked(setOf("FfHCms")))
        assertFalse("an unrelated set leaves it locked", item.isUnlocked(setOf("sXQz4G")))
    }

    // R-03: a self-parented cached row (SmugMug's ParentNode is the node's own !parent link) made the
    // inherited-password walk spin forever, which froze opening any gallery under that row.
    @Test(timeout = 15_000)
    fun getUnlockedPassword_withSelfParentedRow_terminatesWithNull() = runTest {
        val loop = CachedNode(
            nodeId = "loop", parentNodeId = "loop", type = "Folder", title = "Loop",
            description = null, access = "None", passwordHint = null,
            uri = "/api/v2/node/loop", childNodesUri = null, albumUri = null
        )
        Mockito.`when`(mockRepository.getNodeById("loop")).thenReturn(loop)

        assertNull(viewModel.getUnlockedPassword("loop"))
    }

    @Test
    fun testPerformSearchWithEmptyCache() = runTest {
        viewModel.setActiveNicknameForTest("testUser")
        
        val mockPhotos = listOf(
            AlbumImageData(imageKey = "img555", title = "Sunset Shore")
        )
        
            
        Mockito.`when`(mockRepository.getSearchResultNodes("Sunset", "site:testUser", "Folder"))
            .thenReturn(emptyList())
            
        Mockito.`when`(mockRepository.getSearchResultPhotos("Sunset", "site:testUser"))
            .thenReturn(emptyList())
            
        Mockito.`when`(mockRepository.hasSearchResultInDb("Sunset", "site:testUser"))
            .thenReturn(false)
        Mockito.`when`(mockRepository.hasSearchPhotosInDb("Sunset", "site:testUser"))
            .thenReturn(false)
            
        Mockito.`when`(mockRepository.searchNodesRemote("testUser", "/api/v2/user/testUser", "site:testUser", "Sunset", BuildConfig.SMUGMUG_API_KEY))
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
     *
     * Also covers the follow-up: that wait must NOT hold up photo search, which hits a live API
     * and never reads albumsCache — the UI (SearchTabView.kt) gates its whole results shell behind
     * SearchUiState.Success, so performSearch flips to Success (photos wired up immediately,
     * galleries/folders initially empty) right away instead of sitting in Loading until the
     * gallery-cache wait clears.
     *
     * And the second follow-up: with galleries/folders empty AND visible (Success, not Loading),
     * isGalleriesFoldersLoading must be true so the UI can distinguish "still loading" from "found
     * nothing" — verified live to otherwise look identical to a broken/empty search for as long as
     * the background indexing walk runs (up to 18+ seconds observed on a real site).
     */
    @Test
    fun testPerformSearchWaitsForInProgressSubtreeIndexing() = runTest {
        viewModel.setActiveNicknameForTest("testUser")

        val indexingFlow = MutableStateFlow(true)
        var albumsNow = emptyList<CachedNode>()
        Mockito.`when`(mockRepository.isIndexingSubtree).thenReturn(indexingFlow)
        Mockito.`when`(mockRepository.albumsCacheFor("testUser")).thenAnswer { albumsNow }

        Mockito.`when`(mockRepository.getUserRootNodeId(Mockito.anyString(), Mockito.anyString()))
            .thenReturn(flowOf(Result.success("4zqWw")))
        Mockito.`when`(mockRepository.getSearchResultNodes("Family", "site:testUser", "Folder"))
            .thenReturn(emptyList())
        Mockito.`when`(mockRepository.hasSearchPhotosInDb("Family", "site:testUser")).thenReturn(false)
        Mockito.`when`(
            mockRepository.searchNodesRemote(
                Mockito.anyString(), Mockito.anyString(), Mockito.anyString(), Mockito.anyString(),
                Mockito.anyString()
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

        // Still indexing: galleries must not show the (as-yet incomplete) cache snapshot — but
        // photo search must NOT be blocked by it either. The UI only renders once searchState is
        // Success (see SearchTabView.kt), so this has to be Success-with-empty-galleries, not
        // Loading, or the Photos tab would never even mount while indexing is in progress.
        val stateWhileIndexing = viewModel.searchState.value
        assertTrue(
            "Expected Success (with galleries not yet populated) but was: $stateWhileIndexing — " +
                "photo search must not be blocked behind the gallery-cache wait",
            stateWhileIndexing is SearchUiState.Success
        )
        assertEquals(0, (stateWhileIndexing as SearchUiState.Success).galleries.size)
        assertTrue(
            "isGalleriesFoldersLoading must be true so the UI shows a loading state, not '(0)'",
            viewModel.isGalleriesFoldersLoading.value
        )

        // Background indexing finishes, and the gallery it was walking toward lands in the cache.
        albumsNow = listOf(
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
        assertFalse(
            "isGalleriesFoldersLoading must clear once galleries/folders actually resolved",
            viewModel.isGalleriesFoldersLoading.value
        )
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
            BuildConfig.SMUGMUG_API_KEY
        )
    }

    @Test
    fun testPerformSearchNoCacheRequiresRemote() = runTest {
        viewModel.setActiveNicknameForTest("testUser")
        
        val mockPhotos = listOf(
            AlbumImageData(imageKey = "img555", title = "Sunset Shore")
        )
        
            
        Mockito.`when`(mockRepository.getSearchResultNodes("Sunset", "site:testUser", "Folder"))
            .thenReturn(emptyList())
            
        // scopeKey for global search is now 'site:testUser'
        Mockito.`when`(mockRepository.getSearchResultPhotos("Sunset", "site:testUser"))
            .thenReturn(emptyList())
        
        // Cache uses getLong with '_ts' suffix; return 0 to simulate a cache miss
        Mockito.`when`(mockPrefs.getLong("site:testUser_Sunset_ts", 0L))
            .thenReturn(0L)
            
        Mockito.`when`(mockRepository.searchNodesRemote("testUser", "/api/v2/user/testUser", "site:testUser", "Sunset", BuildConfig.SMUGMUG_API_KEY))
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
            "/api/v2/user/testUser",
            "site:testUser",
            "Sunset",
            BuildConfig.SMUGMUG_API_KEY
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

        keywordPages["/api/v2/node/4zqWw|clara|100|1"] = claraImages
            
        keywordPages["/api/v2/node/4zqWw|clara,laurel|100|1"] = claraLaurelImages

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
        
        Mockito.`when`(mockRepository.getNodeChildren("testUser", "folder1", BuildConfig.SMUGMUG_API_KEY, false, null))
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
    fun uncachedFolderOffline_showsTheOfflineMessage_notHttp504() = runTest {
        viewModel.setActiveNicknameForTest("testUser")
        val childNode = createTestNode(nodeId = "folder1", parentNodeId = "root", type = "Folder", title = "Subfolder 1")
        // What Retrofit throws for OkHttp's synthetic 504: no network response, no cache response.
        val req = okhttp3.Request.Builder().url("https://api.smugmug.com/api/v2/node/folder1!children").build()
        val raw = okhttp3.Response.Builder().request(req).protocol(okhttp3.Protocol.HTTP_1_1)
            .code(504).message("Unsatisfiable Request (only-if-cached)").build()
        val synthetic = retrofit2.HttpException(
            retrofit2.Response.error<Any>(okhttp3.ResponseBody.create(null, ""), raw)
        )
        Mockito.`when`(mockRepository.getNodeChildren("testUser", "folder1", BuildConfig.SMUGMUG_API_KEY, false, null))
            .thenReturn(flowOf(Result.failure(synthetic)))

        viewModel.navigateToChildFolder(childNode)
        advanceUntilIdle()

        val state = viewModel.browserState.value
        assertTrue(state.toString(), state is BrowserUiState.Error)
        val problem = (state as BrowserUiState.Error).problem
        assertEquals(com.smugview.app.ui.text.Problem.OfflineNothingSaved(com.smugview.app.ui.text.Subject.Folder), problem)
        assertEquals(
            "This folder hasn't been opened on this phone yet, so there's nothing saved to show. " +
                "Connect to the internet and tap Try again.",
            com.smugview.app.ui.text.UserMessages.body(problem)
        )
        assertEquals("You're offline", com.smugview.app.ui.text.UserMessages.heading(problem))
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
            
        Mockito.`when`(mockRepository.albumsCacheFor("testUser"))
            .thenReturn(matchedGalleries)
            
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
        
        Mockito.`when`(mockRepository.getCachedNodesForSite(Mockito.anyString()))
            .thenReturn(listOf(albumNode))
        Mockito.`when`(mockRepository.getNodeByIdOrKey(testAlbumKey))
            .thenReturn(albumNode)

        // The password saved for THIS album is what must be used. (Previously the production code
        // brute-forced every saved password against the endpoint; that replay was removed, so the
        // credential now has to resolve to this node.)
        fakePasswordStore.savePassword(testAlbumKey, "gallery")

        // The unlock goes through repository.unlocks (UnlockManager over the repository's UnlockIo).
        Mockito.`when`(mockRepository.getNodeById(testAlbumKey)).thenReturn(albumNode)
        // The session cookie is the only thing that unlocks (R-23): the server answers the same URL
        // differently before and after `!unlock`, so the fake flips on the unlock call, not on a password.
        val sessionOpen = java.util.concurrent.atomic.AtomicBoolean(false)
        Mockito.`when`(mockRepository.unlockAlbumResult(testAlbumKey, BuildConfig.SMUGMUG_API_KEY, "gallery"))
            .thenAnswer {
                sessionOpen.set(true)
                com.smugview.app.data.repository.SmugMugRepository.UnlockResult.Success
            }

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
        
        Mockito.`when`(mockRepository.getImage(testImageKey, BuildConfig.SMUGMUG_API_KEY))
            .thenAnswer { flowOf(Result.success(if (sessionOpen.get()) unlockedImg else anonymousImg)) }
            
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

        Mockito.`when`(mockRepository.getCachedNodesForSite(Mockito.anyString())).thenReturn(listOf(albumNode))
        Mockito.`when`(mockRepository.getNodeByIdOrKey(testAlbumKey)).thenReturn(albumNode)

        // A password belonging to a completely different album.
        fakePasswordStore.savePassword("some_other_album", "gallery")

        // If the code were to (incorrectly) replay it, this stub would unlock the album.
        Mockito.`when`(mockRepository.unlockAlbumResult(testAlbumKey, BuildConfig.SMUGMUG_API_KEY, "gallery"))
            .thenReturn(com.smugview.app.data.repository.SmugMugRepository.UnlockResult.Success)

        val anonymousImg = AlbumImageData(
            imageKey = testImageKey,
            title = "Locked Title",
            thumbnailUrl = "https://photos.smugmug.com/Family/School/2026-06-13--Laurel-Graduation-Day/i-$testImageKey/0/Th/th.jpg",
            uris = null
        )
        Mockito.`when`(mockRepository.getImage(testImageKey, BuildConfig.SMUGMUG_API_KEY))
            .thenReturn(flowOf(Result.success(anonymousImg)))

        val result = viewModel.getImageDetails(testImageKey).first { it != null }
        assertEquals("Locked Title", result?.getOrNull()?.title)

        // The unrelated credential must never have been tried against this album.
        Mockito.verify(mockRepository, Mockito.never())
            .unlockAlbumResult(testAlbumKey, BuildConfig.SMUGMUG_API_KEY, "gallery")
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
            
        keywordPages["/api/v2/node/4zqWw|dancing|100|1"] = listOf(redactedImage)
            
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
    fun testSelectAlbumMarksAsViewedOncePageOneIsShown() = runBlocking {
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

        Mockito.`when`(mockRepository.getAlbum(Mockito.anyString(), Mockito.anyString(), Mockito.nullable(String::class.java), Mockito.nullable(String::class.java)))
            .thenReturn(mockAlbum)

        Mockito.`when`(mockRepository.isGalleryLit(Mockito.anyString(), Mockito.anyString())).thenReturn(false)

        Mockito.`when`(mockRepository.getAlbumImagesPage(Mockito.anyString(), Mockito.anyString(), Mockito.nullable(String::class.java), Mockito.anyInt(), Mockito.nullable(String::class.java)))
            .thenReturn(com.smugview.app.data.api.AlbumImagesResponse(
                response = com.smugview.app.data.api.AlbumImagesPayload(
                    images = emptyList()
                )
            ))

        viewModel.selectAlbum(albumKey, "apiKey")
        
        Mockito.verify(mockRepository, Mockito.timeout(3000).atLeastOnce())
            .markGalleryViewed(mockAlbum)
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
            Mockito.`when`(mockRepository.getUserAlbumsResponse(Mockito.anyString(), Mockito.anyString()))
                .thenReturn(mockAlbums)
            Mockito.`when`(mockRepository.getUserRecentImagesResponse(Mockito.anyString(), Mockito.anyString(), Mockito.anyInt()))
                .thenReturn(mockRecentImagesResponse)
            Mockito.`when`(mockRepository.getUserTopKeywords(Mockito.anyString(), Mockito.anyString(), Mockito.nullable(String::class.java)))
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
            Mockito.`when`(mockRepository.getUserAlbumsResponse(Mockito.anyString(), Mockito.anyString()))
                .thenReturn(mockAlbums)
            Mockito.`when`(mockRepository.getUserRecentImagesResponse(Mockito.anyString(), Mockito.anyString(), Mockito.anyInt()))
                .thenReturn(mockRecentImagesResponse)
            Mockito.`when`(mockRepository.getUserTopKeywords(Mockito.anyString(), Mockito.anyString(), Mockito.nullable(String::class.java)))
                .thenReturn(mockTopKeywords)
            Mockito.`when`(mockRepository.getNodeChildren(Mockito.anyString(), Mockito.anyString(), Mockito.anyString(), Mockito.anyBoolean(), Mockito.nullable(String::class.java), Mockito.nullable(String::class.java)))
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
            Mockito.`when`(mockRepository.getUserAlbumsResponse(Mockito.anyString(), Mockito.anyString()))
                .thenReturn(com.smugview.app.data.api.UserAlbumsResponse(com.smugview.app.data.api.UserAlbumsPayload(emptyList()), emptyMap()))
            Mockito.`when`(mockRepository.getUserRecentImagesResponse(Mockito.anyString(), Mockito.anyString(), Mockito.anyInt()))
                .thenReturn(com.smugview.app.data.api.ImageSearchResponse(com.smugview.app.data.api.ImageSearchPayload(emptyList())))
            Mockito.`when`(mockRepository.getUserTopKeywords(Mockito.anyString(), Mockito.anyString(), Mockito.nullable(String::class.java)))
                .thenReturn(com.smugview.app.data.api.TopKeywordsResponse(com.smugview.app.data.api.TopKeywordsPayload(com.smugview.app.data.api.UserTopKeywordsContainer(emptyList()))))
            
            Mockito.`when`<Flow<Result<List<CachedNode>>>>(
                mockRepository.getNodeChildren(
                    Mockito.anyString(),
                    Mockito.anyString(),
                    Mockito.anyString(),
                    Mockito.anyBoolean(),
                    Mockito.nullable(String::class.java),
                    Mockito.nullable(String::class.java)
                )
            ).thenAnswer { invocation ->
                val nodeId = invocation.arguments[1] as String
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
            Mockito.`when`(mockRepository.getUserAlbumsResponse(Mockito.anyString(), Mockito.anyString()))
                .thenReturn(mockAlbums)
            Mockito.`when`(mockRepository.getUserRecentImagesResponse(Mockito.anyString(), Mockito.anyString(), Mockito.anyInt()))
                .thenReturn(com.smugview.app.data.api.ImageSearchResponse(com.smugview.app.data.api.ImageSearchPayload(emptyList())))
            Mockito.`when`(mockRepository.getUserTopKeywords(Mockito.anyString(), Mockito.anyString(), Mockito.nullable(String::class.java)))
                .thenReturn(com.smugview.app.data.api.TopKeywordsResponse(com.smugview.app.data.api.TopKeywordsPayload(com.smugview.app.data.api.UserTopKeywordsContainer(emptyList()))))
            Mockito.`when`<Flow<Result<List<CachedNode>>>>(
                mockRepository.getNodeChildren(
                    Mockito.anyString(),
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
                    Mockito.nullable(String::class.java),
                    Mockito.nullable(String::class.java)
                )
            ).thenThrow(retrofit2.HttpException(retrofit2.Response.error<Any>(401, okhttp3.ResponseBody.create(null, ""))))

            Mockito.`when`(mockRepository.isGalleryLit(Mockito.anyString(), Mockito.anyString())).thenReturn(false)

            Mockito.`when`<com.smugview.app.data.api.AlbumImagesResponse>(
                mockRepository.getAlbumImagesPage(
                    Mockito.anyString(),
                    Mockito.anyString(),
                    Mockito.nullable(String::class.java),
                    Mockito.anyInt(),
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
    // The gallery photos live in the AlbumLoader now; an inline (Unconfined) run publishes them synchronously.
    albumLoader.select("test_album", scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)) { run ->
        run.update { it.copy(photos = photos, complete = true) }
    }
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
