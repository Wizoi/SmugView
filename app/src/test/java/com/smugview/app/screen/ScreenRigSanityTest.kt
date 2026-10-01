package com.smugview.app.screen

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import com.smugview.app.ui.browser.FoldersTabView
import com.smugview.app.ui.viewmodel.BrowserUiState
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Step 6-0: a Compose screen renders over the real view model and the fake server (green on the old code). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class ScreenRigSanityTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var screens: ScreenRig

    @Before fun setUp() { screens = ScreenRig(compose) }
    @After fun tearDown() = screens.close()

    @Test fun `the Folders tab of site A shows the Family folder`() {
        screens.viewModel.selectSite("idzifamily")
        screens.setContent {
            val state by screens.viewModel.browserState.collectAsState()
            FoldersTabView(
                uiState = state,
                currentFolderId = screens.viewModel.currentFolderId,
                onNavigateToAlbum = { _, _ -> },
                viewModel = screens.viewModel
            )
        }
        screens.waitUntil { screens.viewModel.browserState.value is BrowserUiState.Success }
        compose.onNodeWithText("Family").assertIsDisplayed()
    }
}
