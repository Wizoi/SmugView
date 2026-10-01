package com.smugview.app.scenario

import com.smugview.app.ui.viewmodel.SearchController
import com.smugview.app.ui.viewmodel.SearchScope
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
 * Phase 4 step 4-10 (design 3.6, P8, R-33): a search over the whole site with no node scope used to look up
 * the site root, and when that failed fell back to idzifamily's own root (`/api/v2/node/4zqWw`), so site B
 * searched site A, or, for the folder search, did not run at all. A user URI is a scope that needs no lookup
 * and can never be another site's (live: `Scope=/api/v2/user/idzifamily` = the node-root scope, 284 = 284
 * photos and 4 = 4 nodes).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class SearchScopeFallbackTest {
    private lateinit var rig: ScenarioRig
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @Before fun setUp() { rig = ScenarioRig() }

    @After fun tearDown() {
        scope.coroutineContext[Job]?.cancel()
        rig.close()
    }

    private fun scopeOf(path: String): List<String?> = synchronized(rig.server.requestLog) {
        rig.server.requestLog.filter { it.url.encodedPath.endsWith(path) }.map { it.url.queryParameter("Scope") }
    }

    private fun controllerFor(nickname: String): SearchController {
        val prefs = rig.app.getSharedPreferences("smugview_prefs", android.content.Context.MODE_PRIVATE)
        return SearchController(
            repository = rig.repository, apiKey = "test-key", scope = scope, siteScope = { scope },
            sharedPrefs = prefs, searchStatusPrefs = rig.app.getSharedPreferences("smugview_search_status", android.content.Context.MODE_PRIVATE),
            searchScope = MutableStateFlow(SearchScope("Entire site")), activeNickname = MutableStateFlow(nickname),
            getUnlockedPassword = { null }
        )
    }

    @Test fun `site B searches its own user when its profile cannot be read`() = runBlocking {
        // Site B's gallery index is already loaded (the folder search waits for it), but its profile is unreachable.
        rig.repository.buildInMemoryGalleryCache("siteb", "k")
        rig.server.respondWith("user/siteb?", 503, times = 100)
        val controller = controllerFor("siteb")

        controller.performSearch("kentridge")

        awaitUntil("the photo search is sent") { scopeOf("image!search").isNotEmpty() }
        awaitUntil("the folder search is sent (it was skipped when the root did not resolve)") { scopeOf("node!search").isNotEmpty() }
        assertTrue("every photo search is scoped to site B: ${scopeOf("image!search")}", scopeOf("image!search").all { it == "/api/v2/user/siteb" })
        assertEquals(listOf<String?>("/api/v2/user/siteb"), scopeOf("node!search").distinct())
    }

    @Test fun `the repository fallback is the site's own user, never another site's root`() = runBlocking {
        rig.repository.performBackgroundSearchImages("siteb", null, "site:siteb", "kentridge", "test-key")

        assertEquals(listOf<String?>("/api/v2/user/siteb"), scopeOf("image!search").distinct())
    }
}
