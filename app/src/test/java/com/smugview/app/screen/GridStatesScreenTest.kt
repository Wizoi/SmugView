package com.smugview.app.screen

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.smugview.app.data.repository.FakeSmugMugServer
import com.smugview.app.ui.grid.PhotoGridScreen
import com.smugview.app.ui.text.UserMessages
import com.smugview.app.ui.viewmodel.GalleryFilterType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.UnknownHostException

/**
 * Step 6-4 (design 3.2, R-46/R-47): the gallery grid says which of its states it is in.
 *
 *  - offline, nothing saved: "You're offline" (the old screen: a red "Access Error / Failed to Load" heading);
 *  - a gallery that is really empty: [UserMessages.EMPTY_GALLERY] (the old screen: "No Photos Found / No photos match
 *    your current search or type filter.", as if the user had searched);
 *  - a type filter on a gallery without that type: [UserMessages.FILTER_EMPTY] and "Show all";
 *  - page 2 of a 2-page gallery answers 503: the 100 photos that arrived stay, with a banner and "Load the rest"
 *    (the old screen: the 100 photos and nothing that says 50 are missing).
 *
 * Fixture F: FfHCms (AlbumKey; NodeID LCdk7F) is 150 photos under Family -> School, 100 per page. N74KSK is public.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class GridStatesScreenTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var screens: ScreenRig

    @Before fun setUp() { screens = ScreenRig(compose) }
    @After fun tearDown() = screens.close()

    private fun showGallery(albumKey: String) {
        // The user saved the password of the Family folder (FfHCms sits under it: Family -> School -> gallery).
        screens.rig.passwords.savePassword("2sDN5x", "family-pw")
        screens.viewModel.selectSite(FakeSmugMugServer.SITE_A)
        screens.awaitSiteQuiet()
        screens.setContent {
            PhotoGridScreen(
                albumKey = albumKey,
                albumTitle = "A gallery",
                onNavigateToPhotoDetail = {},
                onBackClick = {},
                onNavigateToFolder = {},
                onNavigateToCastController = {},
                viewModel = screens.viewModel
            )
        }
    }

    private fun shown(text: String, substring: Boolean = false) =
        compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()

    private fun awaitText(text: String, substring: Boolean = false) {
        try {
            screens.waitUntil(message = "\"$text\" on screen") { shown(text, substring) }
        } catch (e: AssertionError) {
            // Say what the album held when it timed out: the state is the diagnosis.
            throw AssertionError("${e.message}; album=${screens.viewModel.albumState.value?.copy(photos = emptyList())}", e)
        }
    }

    @Test fun `offline with nothing saved says the phone is offline, not a red access error`() {
        screens.server.failWith = { target -> if (target.startsWith("album/FfHCms")) UnknownHostException("offline") else null }
        showGallery("FfHCms")

        awaitText(UserMessages.OFFLINE_HEADING)
        compose.onNodeWithText(UserMessages.OFFLINE_HEADING).assertIsDisplayed()
        compose.onNodeWithText(UserMessages.BUTTON_TRY_AGAIN).assertIsDisplayed()
        assertTrue("the old heading is still there", !shown("Access Error / Failed to Load"))
        assertTrue("the old Retry button is still there", !shown("Retry"))
    }

    @Test fun `try again after the network is back loads the gallery`() {
        screens.server.failWith = { target -> if (target.startsWith("album/FfHCms")) UnknownHostException("offline") else null }
        showGallery("FfHCms")
        awaitText(UserMessages.BUTTON_TRY_AGAIN)

        screens.server.failWith = null
        compose.onNodeWithText(UserMessages.BUTTON_TRY_AGAIN).performClick()

        screens.waitUntil(message = "the gallery loaded") { screens.viewModel.albumState.value?.complete == true }
        assertEquals(150, screens.viewModel.albumState.value?.photos?.size)
        assertTrue(!shown(UserMessages.OFFLINE_HEADING))
    }

    @Test fun `a gallery that really is empty says so`() {
        screens.server.imageCounts["N74KSK"] = 0
        showGallery("N74KSK")

        awaitText(UserMessages.EMPTY_GALLERY)
        assertTrue("the old search wording is still there", !shown("No Photos Found") && !shown("No photos match your current search or type filter."))
        assertTrue(!shown(UserMessages.FILTER_EMPTY))
    }

    @Test fun `a type filter that hides every photo says so and Show all brings them back`() {
        showGallery("N74KSK") // 12 photos, none a video
        screens.waitUntil(message = "the gallery loaded") { screens.viewModel.albumState.value?.complete == true }
        screens.viewModel.updateFilterType(GalleryFilterType.VIDEOS)

        awaitText(UserMessages.FILTER_EMPTY)
        assertTrue("an empty gallery is not a filtered-out one", !shown(UserMessages.EMPTY_GALLERY))
        assertTrue("the old button is still there", !shown("Reset Type Filter"))

        compose.onNodeWithText(UserMessages.BUTTON_SHOW_ALL).performClick()
        screens.waitUntil(message = "the filter cleared") { !shown(UserMessages.FILTER_EMPTY) }
        assertEquals(GalleryFilterType.ALL, screens.viewModel.filterType.value)
    }

    @Test fun `page 2 answering 503 keeps the 100 photos, says 100 of 150, and Load the rest completes it`() {
        screens.server.respondWith("start=101", 503, times = 1)
        showGallery("FfHCms")

        val banner = "Showing 100 of 150 photos. SmugMug is having trouble."
        awaitText(banner)
        assertEquals(100, screens.viewModel.albumState.value?.photos?.size)
        assertTrue("the gallery must not look finished", screens.viewModel.albumState.value?.complete == false)
        compose.onNodeWithText(UserMessages.BUTTON_LOAD_THE_REST).assertIsDisplayed()
        assertTrue("the full-screen error replaced the photos", !shown(UserMessages.BUTTON_TRY_AGAIN))

        compose.onNodeWithText(UserMessages.BUTTON_LOAD_THE_REST).performClick()
        screens.waitUntil(message = "all 150 arrived") { screens.viewModel.albumState.value?.photos?.size == 150 }
        screens.waitUntil(message = "the banner went") { !shown(banner) }
        assertTrue(screens.viewModel.albumState.value?.complete == true)
    }

    @Test fun `a public gallery that answers 404 is gone, with Go back and no Try again`() {
        screens.server.respondWith("album/N74KSK!images", 404, times = 5)
        showGallery("N74KSK")

        awaitText("Not found on SmugMug")
        compose.onNodeWithText(UserMessages.BUTTON_GO_BACK).assertIsDisplayed()
        assertTrue(!shown(UserMessages.BUTTON_TRY_AGAIN) && !shown("Access Error / Failed to Load"))
    }
}
