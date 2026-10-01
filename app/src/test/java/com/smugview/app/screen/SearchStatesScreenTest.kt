package com.smugview.app.screen

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.smugview.app.data.repository.FakeSmugMugServer
import com.smugview.app.ui.browser.SearchTabView
import com.smugview.app.ui.viewmodel.SearchUiState
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.UnknownHostException

/**
 * Step 6-6 (design 3.4, R-47 Search, N1): the Search tab names what happened.
 *
 *  - no photo, gallery or folder matches: "Nothing on {site} matches "{query}"." (the old screen: "Enter a word or
 *    phrase to search", as if nothing had been typed);
 *  - the photo search failed but the index has a hit: the failure is said above the results, the galleries are
 *    there (the old screen: the app closed);
 *  - the photo search failed and nothing else matches: the cause, with Try again;
 *  - while photos load the tab says "Photos (...)" instead of "Photos (0)".
 *
 * The texts are literals on purpose: they are design section 5, and a typo in UserMessages must fail here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class SearchStatesScreenTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var screens: ScreenRig

    @Before fun setUp() { screens = ScreenRig(compose) }
    @After fun tearDown() = screens.close()

    private fun openSearch() {
        screens.viewModel.selectSite(FakeSmugMugServer.SITE_A)
        screens.awaitSiteQuiet()
        screens.setContent { SearchTabView(viewModel = screens.viewModel, onImageClick = { _, _ -> }, onNavigateToAlbum = { _, _ -> }) }
    }

    private fun shown(text: String, substring: Boolean = false) =
        compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()

    private fun awaitText(text: String, substring: Boolean = false) {
        try {
            screens.waitUntil(message = "\"$text\" on screen") { shown(text, substring) }
        } catch (e: AssertionError) {
            throw AssertionError("${e.message}; search=${screens.viewModel.searchState.value}", e)
        }
    }

    private fun siteName() = screens.viewModel.activeUserProfile.value?.name?.takeIf { it.isNotBlank() }
        ?: screens.viewModel.activeNickname.value!!.replaceFirstChar { it.uppercase() }

    @Test fun `a search with no match says what it looked for`() {
        screens.server.imageSearchTotal = 0
        openSearch()

        screens.viewModel.performSearch("zzqx")

        awaitText("Nothing on ${siteName()} matches “zzqx”.")
        assertTrue("the old zero-results wording is still there", !shown("Enter a word or phrase to search"))
    }

    @Test fun `a failed photo search says so above the galleries from this phone and nothing crashes`() {
        screens.server.respondWith("image!search", 503, times = 100)
        openSearch()

        screens.viewModel.performSearch("Public")

        awaitText("Couldn't search photos. SmugMug is having trouble. Galleries and folders below come from this phone.")
        compose.onNodeWithText("Galleries (1)").assertIsDisplayed()
        compose.onNodeWithText("Try again").assertIsDisplayed()
    }

    @Test fun `offline with no match says offline, and Try again searches again`() {
        screens.server.failWith = { target -> if (target.contains("image!search")) UnknownHostException("offline") else null }
        openSearch()
        screens.viewModel.performSearch("zzqx")
        awaitText("You're offline")
        compose.onNodeWithText("You went offline.").assertIsDisplayed()
        assertTrue(!shown("Nothing on ${siteName()} matches “zzqx”."))

        screens.server.failWith = null
        screens.server.imageSearchTotal = 0
        compose.onNodeWithText("Try again").performClick()

        awaitText("Nothing on ${siteName()} matches “zzqx”.")
        assertTrue(!shown("You're offline"))
    }

    @Test fun `the photos tab shows a pending mark while photos load, not a zero`() {
        val gate = screens.server.hold("image!search")
        openSearch()

        screens.viewModel.performSearch("Public")
        awaitText("Galleries (1)")
        try {
            assertTrue("Photos (…) while loading", shown("Photos (…)"))
            assertTrue("Photos (0) reads as no photos", !shown("Photos (0)"))
        } finally {
            gate.release()
        }
        screens.waitUntil(message = "the photo search finished") { !screens.viewModel.isSearchPhotosLoading.value }
        assertTrue(screens.viewModel.searchState.value is SearchUiState.Success)
    }
}
