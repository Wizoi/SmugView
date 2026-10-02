package com.smugview.app.screen

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import com.smugview.app.data.repository.FakeSmugMugServer
import com.smugview.app.ui.grid.PhotoGridScreen
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Finding #26: opening a gallery from a Collections shortcut showed the previous gallery's parent in the breadcrumb.
 *
 * The grid builds its crumb list in a `remember` keyed on the stack's SIZE and the gallery title. The Folders stack is replaced a
 * moment after the screen opens (the lineage read is a request), so when the new gallery's parent sits at the same depth as the old
 * one's the key does not change and the old parent stays. Real topology: the "Kentridge" folder's gallery, then the "Meets"
 * folder's gallery, both one folder under the site root.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class BreadcrumbScreenTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var screens: ScreenRig

    @Before fun setUp() { screens = ScreenRig(compose) }
    @After fun tearDown() { screens.close() }

    private fun shown(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    @Test fun `a gallery opened after another shows its own parent folder in the breadcrumb`() {
        screens.viewModel.selectSite(FakeSmugMugServer.SITE_A)
        screens.awaitSiteQuiet()
        var albumKey by mutableStateOf("N74KSK")
        var albumTitle by mutableStateOf("Public Gallery")
        screens.setContent {
            PhotoGridScreen(
                albumKey = albumKey,
                albumTitle = albumTitle,
                onNavigateToPhotoDetail = {},
                onBackClick = {},
                onNavigateToFolder = {},
                onNavigateToCastController = {},
                viewModel = screens.viewModel
            )
        }
        screens.waitUntil(message = "the breadcrumb of the first gallery names its folder") { shown("Kentridge") }

        compose.runOnUiThread {
            albumKey = FakeSmugMugServer.BIG_GALLERY_ALBUM
            albumTitle = "Meets gallery"
        }
        screens.waitUntil(message = "the breadcrumb of the second gallery names its own folder") { shown("Meets") }
        assertFalse("the first gallery's folder is still in the breadcrumb", shown("Kentridge"))
    }
}
