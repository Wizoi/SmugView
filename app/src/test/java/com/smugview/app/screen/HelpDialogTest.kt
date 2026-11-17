package com.smugview.app.screen

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.smugview.app.ui.WelcomeHelp
import com.smugview.app.ui.component.HelpDialog
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The Help entry (owner, 2026-11-16): short tips, a way to send feedback, the privacy policy, and Done. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class HelpDialogTest {
    @get:Rule val compose = createComposeRule()

    private val opened = mutableListOf<String>()
    private var dismissed = 0

    private fun show() {
        val handler = object : UriHandler { override fun openUri(uri: String) { opened += uri } }
        compose.setContent {
            CompositionLocalProvider(LocalUriHandler provides handler) { HelpDialog(onDismiss = { dismissed++ }) }
        }
    }

    @Test fun `it shows the tips`() {
        show()
        compose.onNodeWithText("How to use SmugView").assertExists()
        compose.onNodeWithText("Pick a public site", substring = true).assertExists()
        compose.onNodeWithText("Search and Tags", substring = true).assertExists()
        compose.onNodeWithText("password", substring = true).assertExists()
    }

    @Test fun `send feedback opens the support page`() {
        show()
        compose.onNodeWithText("Send feedback or get help").performScrollTo().performClick()
        assertEquals(listOf(WelcomeHelp.SUPPORT_URL), opened)
    }

    @Test fun `the privacy policy link opens the policy`() {
        show()
        compose.onNodeWithText("Privacy policy").performScrollTo().performClick() // below the tips: the dialog scrolls
        assertEquals(listOf(WelcomeHelp.PRIVACY_URL), opened)
    }

    @Test fun `done closes it`() {
        show()
        compose.onNodeWithText("Done").performClick()
        assertEquals(1, dismissed)
    }
}
