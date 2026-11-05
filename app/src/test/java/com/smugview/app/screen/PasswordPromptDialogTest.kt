package com.smugview.app.screen

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.smugview.app.data.db.CachedNode
import com.smugview.app.ui.browser.PasswordPromptDialog
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A tester on an iPad typed what he was sure was the right password and was told "Incorrect password". SmugMug passwords are case
 * sensitive (checked against the live API: the right password with a capital first letter answers 401, trailing spaces are ignored), and
 * the dots of a hidden field cannot show a stray capital. The dialog now has a Show button.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class PasswordPromptDialogTest {
    @get:Rule val compose = createComposeRule()

    private val node = CachedNode(
        nodeId = "2sDN5x", parentNodeId = null, type = "Folder", title = "Family", description = null,
        access = "Password", passwordHint = null, uri = "/api/v2/node/2sDN5x", childNodesUri = null, albumUri = null
    )

    /** What the field draws: the EditableText semantics hold the transformed text (dots when hidden). */
    private fun drawn(): String =
        compose.onNode(hasSetTextAction()).fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text.orEmpty()

    @Test fun `Show reveals what was typed so a stray capital can be seen`() {
        compose.setContent { PasswordPromptDialog(node = node, error = null, onSubmit = {}, onDismiss = {}) }
        compose.onNode(hasSetTextAction()).performTextInput("Hunter2x")

        assertEquals("the field starts hidden", "•".repeat(8), drawn())

        compose.onNodeWithText("Show").performClick()
        assertEquals("after Show it is plain text", "Hunter2x", drawn())

        compose.onNodeWithText("Hide").performClick()
        assertEquals("hidden again after Hide", "•".repeat(8), drawn())
    }
}
