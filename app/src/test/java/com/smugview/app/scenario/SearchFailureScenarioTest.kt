package com.smugview.app.scenario

import com.smugview.app.ui.text.Problem
import com.smugview.app.ui.text.Subject
import com.smugview.app.ui.viewmodel.SearchController
import com.smugview.app.ui.viewmodel.SearchScope
import com.smugview.app.ui.viewmodel.SearchUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.UnknownHostException
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Phase 6 step 6-6 (design 3.4, N1, R-47 Search): a photo search that fails (a 5xx, a 429, no network) used to
 * throw out of `backgroundSearchJob`, which has no catch, into a scope with no exception handler: an uncaught
 * exception on the main thread, so the app closed. The fix catches it and says so (`photosProblem`); the
 * galleries and folders, which come from the index on this phone, stay on screen.
 *
 * The data is the real shape: password folder Family -> School -> gallery (AlbumKey FfHCms, NodeID LCdk7F) and a
 * public gallery under Kentridge, so the index has a gallery that matches "Public".
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class SearchFailureScenarioTest {
    private lateinit var rig: ScenarioRig
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val uncaught = CopyOnWriteArrayList<Throwable>()
    private var previousHandler: Thread.UncaughtExceptionHandler? = null

    @Before fun setUp() {
        rig = ScenarioRig()
        previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, e -> uncaught += e }
    }

    @After fun tearDown() {
        Thread.setDefaultUncaughtExceptionHandler(previousHandler)
        scope.coroutineContext[Job]?.cancel()
        rig.close()
    }

    private fun controller(nickname: String = "idzifamily"): SearchController = SearchController(
        repository = rig.repository, apiKey = "test-key", scope = scope, siteScope = { scope },
        sharedPrefs = rig.app.getSharedPreferences("smugview_prefs", android.content.Context.MODE_PRIVATE),
        searchStatusPrefs = rig.app.getSharedPreferences("smugview_search_status", android.content.Context.MODE_PRIVATE),
        searchScope = MutableStateFlow(SearchScope("Entire site")), activeNickname = MutableStateFlow(nickname),
        getUnlockedPassword = { null }
    )

    private fun imageSearches() = synchronized(rig.server.requestLog) { rig.server.requestLog.count { it.url.encodedPath.endsWith("image!search") } }

    /** Waits until the photo search was sent, finished, and had time to hand an exception to a handler. */
    private fun settle(c: SearchController) {
        awaitUntil("the photo search is sent") { imageSearches() > 0 }
        awaitUntil("the photo search is no longer loading") { !c.isSearchPhotosLoading.value }
        Thread.sleep(300)
    }

    @Test fun `a photo search that answers 503 does not crash the app`() = runBlocking {
        rig.repository.buildInMemoryGalleryCache("idzifamily", "k")
        rig.server.respondWith("image!search", 503, times = 100)
        val c = controller()

        c.performSearch("Public")
        settle(c)

        assertEquals("nothing may reach the thread's uncaught handler: $uncaught", emptyList<Throwable>(), uncaught.toList())
    }

    @Test fun `a photo search with no network does not crash the app`() = runBlocking {
        rig.repository.buildInMemoryGalleryCache("idzifamily", "k")
        rig.server.failWith = { target -> if (target.contains("image!search")) UnknownHostException("api.smugmug.com") else null }
        val c = controller()

        c.performSearch("Public")
        settle(c)

        assertEquals("nothing may reach the thread's uncaught handler: $uncaught", emptyList<Throwable>(), uncaught.toList())
        assertTrue("galleries from the index still show", (c.searchState.value as? SearchUiState.Success)?.galleries?.any { it.title == "Public Gallery" } == true)
    }

    private fun problemOf(c: SearchController) = (c.searchState.value as? SearchUiState.Success)?.photosProblem

    @Test fun `a 503 on the photo search is named, the galleries from the index stay, and the next search asks again`() = runBlocking {
        rig.repository.buildInMemoryGalleryCache("idzifamily", "k")
        rig.server.respondWith("image!search", 503, times = 1)
        val c = controller()

        c.performSearch("Public")
        settle(c)

        assertEquals(Problem.SmugMugTrouble(Subject.Search, 503), problemOf(c))
        val state = c.searchState.value as SearchUiState.Success
        assertEquals(listOf("Public Gallery"), state.galleries.map { it.title })
        val searchPrefs = rig.app.getSharedPreferences("smugview_search_status", android.content.Context.MODE_PRIVATE)
        assertTrue("a failed search is not marked 'fully searched' for 24 hours: ${searchPrefs.all}", searchPrefs.all.isEmpty())

        // The server is healthy again: the same query is sent again and the problem is gone.
        val before = imageSearches()
        c.performSearch("Public")
        awaitUntil("the photo search is sent again") { imageSearches() > before }
        awaitUntil("the photo search finished") { !c.isSearchPhotosLoading.value }
        awaitUntil("the problem is cleared") { problemOf(c) == null && c.searchState.value is SearchUiState.Success }
    }

    @Test fun `no network is offline, a 429 is rate limited`() = runBlocking {
        rig.repository.buildInMemoryGalleryCache("idzifamily", "k")
        rig.server.failWith = { target -> if (target.contains("image!search")) UnknownHostException("api.smugmug.com") else null }
        val c = controller()

        c.performSearch("Public")
        settle(c)
        assertEquals(Problem.OfflineNothingSaved(Subject.Search), problemOf(c))

        rig.server.failWith = null
        rig.server.respond429("image!search", times = 100)
        c.performSearch("Public kentridge")
        awaitUntil("rate limited") { problemOf(c) == Problem.RateLimited(Subject.Search) }
    }

    @Test fun `a search with no site says so with a problem, not a message`() = runBlocking {
        val c = controller(nickname = "")

        c.performSearch("Public")
        awaitUntil("an error state") { c.searchState.value is SearchUiState.Error }

        assertTrue((c.searchState.value as SearchUiState.Error).problem is Problem.Unexpected)
    }
}
