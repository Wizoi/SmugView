package com.smugview.app.scenario

import com.smugview.app.ui.viewmodel.SearchScope
import com.smugview.app.ui.viewmodel.TagSearchController
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

/**
 * Phase 4 step 4-5 (design 3.3, P6, R-24): `image!search` serves at most 10,000 results and answers an empty
 * `500 text/html` for any window that ends past it (`start + count - 1 > 10,000`). The Tags loader assumed a
 * page of 500 and stopped at `start > 9501`, but the server clamps a page to 100, so a 10,000-result keyword
 * stopped at 9,600. Both search paths must now page by `start` to exactly the window, then stop quietly.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class SearchWindowTest {
    private lateinit var rig: ScenarioRig
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @Before fun setUp() {
        rig = ScenarioRig()
        rig.server.imageSearchTotal = 25_000 // the live API reports at most 10,000
    }

    @After fun tearDown() {
        scope.coroutineContext[Job]?.cancel()
        rig.close()
    }

    private fun searchRequests() = synchronized(rig.server.requestLog) {
        rig.server.requestLog.filter { it.url.encodedPath.endsWith("image!search") }
    }

    private fun searchWindows() = searchRequests().map {
        val start = it.url.queryParameter("start")!!.toInt()
        val count = minOf(it.url.queryParameter("count")!!.toInt(), 100) // the server clamps a page to 100
        start to start + count - 1
    }

    @Test fun `the Tags loader reads all 10,000 results and never asks past the window`() {
        val tagScope = SearchScope("Folder: School", "P4BKB", "/api/v2/node/P4BKB")
        val controller = TagSearchController(
            repository = rig.repository, apiKey = "test-key", viewModelScope = scope, siteScope = { scope },
            sharedPrefs = rig.app.getSharedPreferences("smugview_prefs", android.content.Context.MODE_PRIVATE),
            searchScope = MutableStateFlow(tagScope), isViewingDetail = MutableStateFlow(false),
            activeNickname = MutableStateFlow("idzifamily"), getUnlockedPassword = { null }
        )
        controller.triggerTagScopeScan(tagScope, clearSelected = true)
        awaitUntil("tags scanned") { !controller.isScanningTags.value && controller.allScopeTags.value.isNotEmpty() }
        controller.selectTag(controller.allScopeTags.value.keys.first())
        awaitUntil("all results loaded", timeoutMs = 90_000) {
            !controller.isLoadingPhotos.value && controller.allScopePhotos.value.isNotEmpty()
        }

        assertEquals(10_000, controller.allScopePhotos.value.size)
        assertEquals("the total shown is what was reachable", 10_000, controller.keywordPhotosTotal.value)
        val past = searchWindows().filter { it.second > 10_000 }
        assertTrue("requests past the 10,000 window: $past", past.isEmpty())
    }

    @Test fun `the photo search stops at the window with no request past it`() = runBlocking {
        rig.repository.performBackgroundSearchImages("idzifamily", "/api/v2/node/4zqWw", "scope-key", "kentridge", "test-key")

        val stored = rig.dao.getSearchResults("kentridge", "scope-key", "Photo")
        assertEquals(10_000, stored.size)
        val past = searchWindows().filter { it.second > 10_000 }
        assertTrue("requests past the 10,000 window: $past", past.isEmpty())
    }
}
