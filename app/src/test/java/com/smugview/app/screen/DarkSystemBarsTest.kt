package com.smugview.app.screen

import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.WindowCompat
import com.smugview.app.ui.theme.DeepDarkBackground
import com.smugview.app.ui.theme.SmugViewTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** V4 / P12: every screen is dark by design, so in the light system theme the status bar still needs light icons on a dark bar. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class DarkSystemBarsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun `the light system theme still gets a dark status bar with light icons`() {
        compose.setContent { SmugViewTheme(darkTheme = false) {} }
        compose.waitForIdle()
        val window = compose.activity.window
        assertEquals(DeepDarkBackground.toArgb(), window.statusBarColor)
        assertFalse(WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars)
    }

    @Test fun `the dark system theme gets the same bar`() {
        compose.setContent { SmugViewTheme(darkTheme = true) {} }
        compose.waitForIdle()
        val window = compose.activity.window
        assertEquals(DeepDarkBackground.toArgb(), window.statusBarColor)
        assertFalse(WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars)
    }
}
