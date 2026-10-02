package com.smugview.app.share

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import com.smugview.app.ui.component.QrShareDialog
import com.smugview.app.ui.detail.shareProblem
import com.smugview.app.ui.text.Problem
import com.smugview.app.ui.text.UserMessages
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Step 6-14 (design 3.10): the share dialog never shows a blank spinner. With no web page for the photo (blank, not
 * https, or a CDN address) it says so and turns its buttons off; with one it shows the page, and Copy and Share link
 * are on. A failed picture share names its real cause.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class QrShareDialogTest {
    @get:Rule val compose = createComposeRule()

    private val isSpinner = SemanticsMatcher("is a progress indicator") {
        it.config.contains(SemanticsProperties.ProgressBarRangeInfo)
    }

    private fun spinners() = compose.onAllNodes(isSpinner).fetchSemanticsNodes()

    /** Red on the old dialog: a blank URL left the white box on a spinner for ever and the buttons live. */
    @Test fun `a photo with no web page says so, shows no spinner and turns Copy and Share link off`() {
        compose.setContent { QrShareDialog(title = "Photo", url = "", onDismissRequest = {}) }
        compose.waitForIdle()

        compose.onNodeWithText(UserMessages.SHARE_NO_LINK).assertIsDisplayed()
        assertTrue("no spinner", spinners().isEmpty())
        compose.onNodeWithText("Copy").assertIsNotEnabled()
        compose.onNodeWithText(UserMessages.SHARE_LINK).assertIsNotEnabled()
    }

    /** Red on the old dialog: it showed a QR for the CDN original, which is a file, not a page. */
    @Test fun `a CDN original address is not a link`() {
        compose.setContent {
            QrShareDialog(title = "Photo", url = "https://photos.smugmug.com/Family/i-abc/0/D/x-D.jpg", onDismissRequest = {})
        }
        compose.waitForIdle()

        compose.onNodeWithText(UserMessages.SHARE_NO_LINK).assertIsDisplayed()
        compose.onNodeWithText("Copy").assertIsNotEnabled()
    }

    @Test fun `a web page shows its caption and address and the buttons are on`() {
        val url = "https://site.smugmug.com/Family/Holidays/n-abc/i-XyZ"
        compose.setContent { QrShareDialog(title = "Photo", url = url, onDismissRequest = {}) }
        compose.waitForIdle()

        compose.onNodeWithText(UserMessages.SHARE_CAPTION).assertIsDisplayed()
        compose.onNodeWithText(url).assertIsDisplayed()
        compose.onNodeWithText("Copy").assertIsEnabled()
        compose.onNodeWithText(UserMessages.SHARE_LINK).assertIsEnabled()
        assertTrue("no 'no link' text", compose.onAllNodesWithText(UserMessages.SHARE_NO_LINK).fetchSemanticsNodes().isEmpty())
    }

    @Test fun `Share picture and its small print appear only when the caller offers it`() {
        val url = "https://site.smugmug.com/Family/n-abc/i-XyZ"
        compose.setContent { QrShareDialog(title = "Photo", url = url, onDismissRequest = {}, onSharePicture = {}) }
        compose.waitForIdle()

        compose.onNodeWithText(UserMessages.SHARE_PICTURE).fetchSemanticsNode()
        compose.onNodeWithText(UserMessages.SHARE_STRIPPED).fetchSemanticsNode()
    }

    @Test fun `a status from SmugMug is never reported as offline`() {
        assertTrue(shareProblem(ShareFiles.HttpFailure(429)) is Problem.RateLimited)
        assertTrue(shareProblem(ShareFiles.HttpFailure(503)) is Problem.SmugMugTrouble)
        assertTrue(shareProblem(ShareFiles.HttpFailure(404)) is Problem.Gone)
        assertTrue(shareProblem(ShareFiles.NotAPictureException()) is Problem.Unexpected)
        assertTrue(shareProblem(java.net.UnknownHostException("x")) is Problem.OfflineNothingSaved)
    }
}
