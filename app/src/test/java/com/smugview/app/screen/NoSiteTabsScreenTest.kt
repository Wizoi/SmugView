package com.smugview.app.screen

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.junit4.createComposeRule
import com.smugview.app.ui.browser.BrowserScreen
import com.smugview.app.ui.viewmodel.BrowserTab
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * App Store review 2026-11-20 (1.0.1 build 19, guideline 2.1(a)): Home, Search, Tags and Collections "are unresponsive". With no site chosen those
 * four tabs were `enabled = false`: dimmed, and a tap did nothing. A tap must now say what to do, and leave the Hub showing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE, qualifiers = "w360dp-h2400dp")
class NoSiteTabsScreenTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var screens: ScreenRig
    private val vm get() = screens.viewModel
    private var onScreen by androidx.compose.runtime.mutableStateOf(true)
    private val message = "Search for a SmugMug site first, or pick a featured photographer."

    @Before fun setUp() {
        screens = ScreenRig(compose)
        vm.setActiveTab(BrowserTab.Hub)
    }

    @After fun tearDown() { onScreen = false; screens.settle(); screens.close() }

    private fun shown(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    @Test fun `with no site chosen a tap on each site tab explains what to do and stays on the Hub`() {
        screens.setContent {
            if (onScreen) BrowserScreen(
                onNavigateToAlbum = { _, _ -> }, onNavigateToExplorer = {}, onNavigateToSearchPhotoDetail = { _, _ -> },
                onNavigateToPhotoDetail = { _, _ -> }, onNavigateToKeywordImages = {}, onNavigateToCastController = {}, viewModel = vm
            )
        }
        assertNull("premise: no site is chosen", vm.activeNickname.value?.takeIf { it.isNotEmpty() })
        for (tab in listOf("Home", "Search", "Tags", "Collections")) {
            compose.mainClock.advanceTimeBy(6_000)
            screens.waitUntil(message = "the previous message to clear") { !shown(message) }
            val node = compose.onNode(hasText(tab).and(hasClickAction()))
            node.assertIsEnabled()
            node.performClick()
            screens.waitUntil(message = "the message after tapping $tab") { shown(message) }
            assertEquals("tapping $tab with no site", BrowserTab.Hub, vm.activeTab.value)
            screens.settle()
        }
    }
}
