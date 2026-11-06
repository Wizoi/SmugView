package com.smugview.app.screen

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.smugview.app.share.ShareSizes
import com.smugview.app.ui.detail.ShareSizePrompt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** "Share picture: which size?" (owner, 2026-11-06): Mobile or Original, asked every time. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class ShareSizePromptTest {
    @get:Rule val compose = createComposeRule()

    private var chosen = ""

    private fun show(loading: Boolean, choices: ShareSizes.Choices) {
        compose.setContent {
            ShareSizePrompt(loading, choices, onMobile = { chosen = "mobile" }, onOriginal = { chosen = "original" }, onDismiss = { chosen = "cancel" })
        }
    }

    private fun jpeg(bytes: Long?) = ShareSizes.choices(isVideo = false, format = "JPG", archivedSize = bytes, hasOriginalSource = true)

    @Test fun `both sizes are offered, the original with its size, and each choice says which was picked`() {
        show(false, jpeg(4_200_000L))
        compose.onNodeWithText("Mobile size").assertIsEnabled()
        compose.onNodeWithText("Original size · 4.2 MB").assertIsEnabled()
        compose.onNodeWithText("Camera details and location are removed.").assertExists()

        compose.onNodeWithText("Original size · 4.2 MB").performClick()
        assertEquals("original", chosen)
        compose.onNodeWithText("Mobile size").performClick()
        assertEquals("mobile", chosen)
        compose.onNodeWithText("Cancel").performClick()
        assertEquals("cancel", chosen)
    }

    @Test fun `a non JPEG original is offered with the warning that camera details stay in`() {
        show(false, ShareSizes.choices(isVideo = false, format = "PNG", archivedSize = 3_000_000L, hasOriginalSource = true))
        compose.onNodeWithText("Original size · 3.0 MB").assertIsEnabled()
        compose.onNodeWithText("Not a JPEG, so camera details and location stay in the file.").assertExists()
    }

    @Test fun `a very large original is offered with a large file warning`() {
        show(false, jpeg(60_000_000L))
        compose.onNodeWithText("Original size · 60.0 MB").assertIsEnabled()
        assertTrue(compose.onAllNodesWithText("A large file", substring = true).fetchSemanticsNodes().isNotEmpty())
    }

    @Test fun `while the details load the original waits and Mobile is already available`() {
        show(true, jpeg(null))
        compose.onNodeWithText("Mobile size").assertIsEnabled()
        compose.onNodeWithText("Original size").assertIsNotEnabled()
        compose.onNodeWithText("Checking the original…").assertExists()
    }

    @Test fun `a video has no original`() {
        show(false, ShareSizes.choices(isVideo = true, format = "MP4", archivedSize = 90_000_000L, hasOriginalSource = true))
        compose.onNodeWithText("Original size").assertIsNotEnabled()
        compose.onNodeWithText("The original isn't available right now.").assertExists()
    }
}
