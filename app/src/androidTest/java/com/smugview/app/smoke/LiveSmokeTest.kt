package com.smugview.app.smoke

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.getOrNull
import com.smugview.app.MainActivity
import com.smugview.app.share.ShareContent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Step 6-21 (Q11 (a)): J1, J3 and J6 as a smoke over the real stack and the live SmugMug API, read-only, and no password is ever
 * typed. It runs against the debug app already installed on emulator-5554 with its saved site, so it is started with
 * `adb shell am instrument -w com.smugview.app.test/androidx.test.runner.AndroidJUnitRunner` after `installDebug installDebugAndroidTest`;
 * `connectedDebugAndroidTest` would uninstall the app afterwards and with it the saved site. The data is the owner's own site, so
 * only things that don't change are asserted: a word's search tabs, the locked Family folder's prompt, and the shape of links.
 */
@OptIn(ExperimentalTestApi::class)
class LiveSmokeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private fun shown(text: String, substring: Boolean = false) =
        compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()

    private fun awaitText(text: String, substring: Boolean = false, timeoutMs: Long = 30_000) =
        compose.waitUntil(timeoutMs) { shown(text, substring) }

    private fun awaitDescribed(description: String, timeoutMs: Long = 30_000) =
        compose.waitUntil(timeoutMs) { compose.onAllNodesWithContentDescription(description).fetchSemanticsNodes().isNotEmpty() }

    private fun tapTab(label: String) {
        awaitText(label)
        compose.onAllNodesWithText(label).onFirst().performClick()
    }

    private fun search(word: String) {
        val field = compose.onNode(hasSetTextAction())
        field.performTextReplacement(word)
        field.performImeAction()
    }

    @Test fun j1_theLockedFamilyFolderAsksForItsPasswordAndCancelOpensNothing() {
        awaitText("Family")
        compose.onAllNodesWithText("Family").onFirst().performClick()
        awaitText("Password Required")
        compose.onNodeWithText("Cancel").performClick()
        compose.waitUntil(10_000) { !shown("Password Required") }
        assertTrue("back on the folder list", shown("Family"))
    }

    @Test fun j3_searchAWordThenAWordNobodyHas() {
        tapTab("Search")
        compose.waitUntil(30_000) { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
        search("Landscapes")
        awaitText("Galleries (", substring = true)
        search("zzqx")
        awaitText("matches “zzqx”", substring = true)
        assertFalse("no raw exception text", shown("Exception", substring = true))
    }

    @Test fun j6_aGalleryLinkAndAPhotoLinkAreWebPages() {
        tapTab("Search")
        compose.waitUntil(30_000) { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
        search("Landscapes")
        awaitText("Galleries (", substring = true)
        compose.onAllNodesWithText("Galleries (", substring = true).onFirst().performClick()
        awaitDescribed("Landscapes")
        compose.onAllNodesWithContentDescription("Landscapes").onFirst().performClick()
        awaitDescribed("Share Album")
        compose.onNodeWithContentDescription("Share Album").performClick()
        val galleryLink = sharedLink()
        assertWebPage(galleryLink)
        compose.onNodeWithText("Done").performClick()
        compose.waitUntil(10_000) { !shown(com.smugview.app.ui.text.UserMessages.SHARE_CAPTION) }

        // A photo tile: a described clickable well below the top bar.
        val tile = SemanticsMatcher("a photo tile") {
            it.config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull()?.isNotBlank() == true &&
                it.config.getOrNull(SemanticsActions.OnClick) != null && it.boundsInRoot.top > 300f && it.boundsInRoot.height > 200f
        }
        compose.waitUntil(30_000) { compose.onAllNodes(tile).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(tile).onFirst().performClick()
        Thread.sleep(2_500)
        if (compose.onAllNodesWithContentDescription("Share Link").fetchSemanticsNodes().isEmpty()) {
            compose.onRoot().performClick()
            Thread.sleep(800)
        }
        awaitDescribed("Share Link")
        compose.onNodeWithContentDescription("Share Link").performClick()
        val photoLink = sharedLink()
        assertWebPage(photoLink)
        assertTrue("a photo page ends with /i-{key}: $photoLink", Regex(".*/i-[^/]+$").matches(photoLink))
        assertTrue("under its gallery: $photoLink vs $galleryLink", photoLink.startsWith(galleryLink.trimEnd('/')))
    }

    private fun sharedLink(): String {
        awaitText(com.smugview.app.ui.text.UserMessages.SHARE_CAPTION)
        val node = compose.onAllNodes(hasText("https://", substring = true)).fetchSemanticsNodes().first()
        return node.config.getOrNull(SemanticsProperties.Text)!!.first().text
    }

    private fun assertWebPage(url: String) {
        assertTrue("a web page address: $url", ShareContent.isShareable(url))
        assertFalse("no CDN address: $url", url.contains("photos.smugmug.com"))
    }
}
