package com.smugview.app.screen

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.smugview.app.data.repository.FakeSmugMugServer
import com.smugview.app.ui.text.UserMessages
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
 * Step 6-7 (design 3.5, R-45): the photo viewer says what is wrong instead of spinning.
 *
 *  - offline, a photo that is not saved, in a gallery that was never opened on this phone: the offline heading in the
 *    words of a photo, with "Go back" and "Try again" (the old viewer: a spinner, for ever);
 *  - a gallery that needs a password the phone does not have: the password prompt shows IN the viewer, and dismissing it
 *    goes back (the old viewer: a spinner, with the prompt appearing on no screen);
 *  - a gone gallery goes back only.
 *
 * Fixture F: FfHCms (AlbumKey; NodeID LCdk7F) is 150 photos under the password folder Family (2sDN5x) -> School -> gallery.
 * The viewer is the first destination of the rig's grid <-> viewer NavHost, so the grid never loads the gallery first.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class ViewerStatesScreenTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var screens: ScreenRig

    @Before fun setUp() { screens = ScreenRig(compose) }
    @After fun tearDown() = screens.close()

    private val photo = "FfHCmsi005"
    private val offlineBody = "This photo hasn't been opened on this phone yet, so there's nothing saved to show. Connect to the internet and tap Try again."

    private fun shown(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    private fun awaitText(text: String) = screens.waitUntil(message = "\"$text\" on screen") { shown(text) }

    private fun openViewer(albumKey: String, imageKey: String, savePassword: Boolean) {
        if (savePassword) screens.rig.passwords.savePassword("2sDN5x", "family-pw")
        screens.viewModel.selectSite(FakeSmugMugServer.SITE_A)
        screens.awaitSiteQuiet()
        screens.setGalleryContent(albumKey, startImageKey = imageKey)
    }

    private fun offline() {
        screens.server.failWith = { target -> if (target.startsWith("album/FfHCms")) UnknownHostException("offline") else null }
    }

    @Test fun `offline with the photo not saved says the phone is offline, with Go back and Try again`() {
        offline()
        openViewer("FfHCms", photo, savePassword = true)

        awaitText(offlineBody)
        assertTrue(shown(UserMessages.OFFLINE_HEADING))
        assertTrue(shown(UserMessages.BUTTON_GO_BACK))
        assertTrue(shown(UserMessages.BUTTON_TRY_AGAIN))
    }

    @Test fun `Go back leaves the viewer, and Try again after the network is back loads the gallery`() {
        offline()
        openViewer("FfHCms", photo, savePassword = true)
        awaitText(offlineBody)

        compose.onNodeWithText(UserMessages.BUTTON_GO_BACK).performClick()
        assertEquals(1, screens.backClicks.get())

        screens.server.failWith = null
        compose.onNodeWithText(UserMessages.BUTTON_TRY_AGAIN).performClick()
        screens.waitUntil(message = "the gallery loaded") { screens.viewModel.albumState.value?.complete == true }
        screens.waitUntil(message = "the offline text went") { !shown(offlineBody) }
    }

    @Test fun `a locked gallery with no saved password shows the password prompt in the viewer, and dismissing it goes back`() {
        openViewer("FfHCms", photo, savePassword = false)

        awaitText("Password Required")
        assertEquals("the prompt is for the Family folder", "2sDN5x", screens.viewModel.passwordPromptNode?.nodeId)

        compose.onNodeWithText("Cancel").performClick()
        assertEquals("dismissing the prompt is Back", 1, screens.backClicks.get())
        assertTrue("the prompt is gone", screens.viewModel.passwordPromptNode == null)
    }

    @Test fun `a right password typed in the viewer opens the photo that was asked for`() {
        openViewer("FfHCms", photo, savePassword = false)
        awaitText("Password Required")

        screens.viewModel.submitPassword("family-pw")

        screens.waitUntil(message = "the gallery loaded after the unlock") { screens.viewModel.albumState.value?.complete == true }
        screens.waitUntil(message = "the prompt went") { !shown("Password Required") }
        assertEquals("no Back was needed", 0, screens.backClicks.get())
        screens.waitUntil(message = "the viewer is on the photo it was opened on") { (pagerValue(compose) ?: 0f) > 0f }
    }

    @Test fun `a gallery that answers 404 is gone, with Go back and no Try again`() {
        screens.server.respondWith("album/N74KSK!images", 404, times = 20)
        openViewer("N74KSK", "N74KSKi001", savePassword = false)

        awaitText("Not found on SmugMug")
        assertTrue(shown(UserMessages.BUTTON_GO_BACK))
        assertTrue(!shown(UserMessages.BUTTON_TRY_AGAIN))
        assertTrue("no password prompt over a gallery that is gone: ${screens.viewModel.passwordPromptNode?.nodeId}", screens.viewModel.passwordPromptNode == null && !shown("Password Required"))
    }
}
