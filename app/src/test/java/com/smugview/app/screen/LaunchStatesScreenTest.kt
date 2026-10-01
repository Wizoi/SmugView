package com.smugview.app.screen

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.smugview.app.ui.browser.FoldersTabView
import com.smugview.app.ui.browser.HomeTabView
import com.smugview.app.ui.text.UserMessages
import com.smugview.app.ui.viewmodel.BrowserUiState
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
 * Step 6-5 (design 3.3, N2, R-47 Home): what the Folders tab and Home put on screen when the site did not load.
 *
 *  - Folders, offline launch with nothing saved: "You're offline", the site's name in the sentence, and Try again
 *    (the old screen: a spinner for good; and a Retry that did nothing while no folder was open);
 *  - Home, the galleries request failed: "You're offline. Galleries show here when you're back online."
 *    (the old screen: "No public galleries found.", as if the site had none).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class LaunchStatesScreenTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var screens: ScreenRig

    @Before fun setUp() { screens = ScreenRig(compose) }
    @After fun tearDown() = screens.close()

    private fun shown(text: String, substring: Boolean = false) =
        compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()

    private fun awaitText(text: String, substring: Boolean = false) =
        screens.waitUntil(message = "\"$text\" on screen") { shown(text, substring) }

    private fun showFolders() = screens.setContent {
        val state by screens.viewModel.browserState.collectAsState()
        FoldersTabView(
            uiState = state,
            currentFolderId = screens.viewModel.currentFolderId,
            onNavigateToAlbum = { _, _ -> },
            viewModel = screens.viewModel
        )
    }

    @Test fun `Folders offline at launch says offline, names the site, and Try again opens it`() {
        screens.server.failWith = { UnknownHostException("offline") }
        screens.viewModel.selectSite("idzifamily")
        showFolders()

        awaitText(UserMessages.OFFLINE_HEADING)
        compose.onNodeWithText("Idzifamily hasn't been opened on this phone recently", substring = true).assertIsDisplayed()
        compose.onNodeWithText(UserMessages.BUTTON_TRY_AGAIN).assertIsDisplayed()
        assertTrue("the old Retry button is still there", !shown("Retry"))

        screens.server.failWith = null
        compose.onNodeWithText(UserMessages.BUTTON_TRY_AGAIN).performClick()

        screens.waitUntil(timeoutMs = 10_000, message = "the root listing") { screens.viewModel.browserState.value is BrowserUiState.Success }
        assertTrue(!shown(UserMessages.OFFLINE_HEADING))
    }

    @Test fun `Home with the galleries request failed says offline, never that the site has no galleries`() {
        screens.server.failWith = { target -> if (target.contains("!albums")) UnknownHostException("offline") else null }
        screens.viewModel.selectSite("idzifamily")
        screens.setContent {
            HomeTabView(
                viewModel = screens.viewModel,
                onNavigateToAlbum = { _, _ -> },
                onNavigateToPhotoDetail = { _, _ -> },
                onNavigateToKeywordImages = {}
            )
        }

        awaitText(UserMessages.HOME_OFFLINE)
        assertTrue("the old claim is still there", !shown("No public galleries found.") && !shown(UserMessages.HOME_EMPTY))
    }
}
