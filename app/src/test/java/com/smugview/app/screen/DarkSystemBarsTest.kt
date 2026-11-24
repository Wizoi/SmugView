package com.smugview.app.screen

import androidx.activity.ComponentActivity
import android.graphics.Color
import com.smugview.app.enableDarkEdgeToEdge
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.WindowCompat
import com.smugview.app.ui.theme.SmugViewTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * V4 / P12: every screen is dark by design, so in the light system theme the status bar still needs light icons. Edge-to-edge (Play, 2026-11-24):
 * the bar is transparent and the app draws behind it; `Window.setStatusBarColor` is deprecated and ignored from Android 15, so the theme must not set it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class DarkSystemBarsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun `the light system theme still gets a dark status bar with light icons`() {
        compose.activity.enableDarkEdgeToEdge()
        compose.setContent { SmugViewTheme(darkTheme = false) {} }
        compose.waitForIdle()
        val window = compose.activity.window
        assertEquals("the bar stays transparent", Color.TRANSPARENT, window.statusBarColor)
        assertFalse(WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars)
        assertFalse(WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightNavigationBars)
    }

    @Test fun `the dark system theme gets the same bar`() {
        compose.activity.enableDarkEdgeToEdge()
        compose.setContent { SmugViewTheme(darkTheme = true) {} }
        compose.waitForIdle()
        val window = compose.activity.window
        assertEquals("the bar stays transparent", Color.TRANSPARENT, window.statusBarColor)
        assertFalse(WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars)
        assertFalse(WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightNavigationBars)
    }
}
