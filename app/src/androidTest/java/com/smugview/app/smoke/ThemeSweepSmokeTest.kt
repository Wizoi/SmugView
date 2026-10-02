package com.smugview.app.smoke

import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.graphics.toPixelMap
import androidx.core.view.WindowCompat
import androidx.test.platform.app.InstrumentationRegistry
import com.smugview.app.MainActivity
import com.smugview.app.ui.theme.DeepDarkBackground
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Retro v7: every main tab, in the light and the dark system theme, keeps the dark status bar with light icons and a dark screen
 * (finding #25 was seen on the emulator and parked). Same way of running as LiveSmokeTest: `am instrument` against the installed debug app.
 */
class ThemeSweepSmokeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private fun shell(command: String) {
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command).close()
    }

    @After fun restoreSystemTheme() = shell("cmd uimode night auto")

    private fun shown(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    private fun assertDarkScreen(where: String) {
        val window = compose.activity.window
        assertEquals("status bar colour on $where", DeepDarkBackground.toArgb(), window.statusBarColor)
        assertFalse("status bar icons must be light on $where", WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars)
        val pixels = compose.onRoot().captureToImage().toPixelMap()
        val samples = (0 until 20).map { i -> pixels[pixels.width / 10, (pixels.height - 1) * (i + 1) / 21] }
        val dark = samples.count { it.red + it.green + it.blue < 1.5f }
        assertTrue("the left edge of $where is mostly dark ($dark of ${samples.size} samples)", dark >= samples.size * 3 / 4)
    }

    private fun sweep(night: String) {
        shell("cmd uimode night $night")
        compose.waitForIdle()
        for (label in listOf("Search", "Tags", "Collections")) {
            compose.waitUntil(30_000) { shown(label) }
            compose.onAllNodesWithText(label).onFirst().performClick()
            compose.waitForIdle()
            Thread.sleep(1_500)
            assertDarkScreen("$label tab, night=$night")
        }
    }

    @Test fun lightSystemTheme() = sweep("no")

    @Test fun darkSystemTheme() = sweep("yes")
}
