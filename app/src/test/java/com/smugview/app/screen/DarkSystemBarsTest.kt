package com.smugview.app.screen

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.smugview.app.ui.theme.DarkSystemBars
import com.smugview.app.ui.theme.SmugViewTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** V4: in the light system theme the photo viewers (black by design) still got a white status bar. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class DarkSystemBarsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun `a viewer that is black by design turns the status bar black, and the bar returns when it closes`() {
        var viewerOpen by mutableStateOf(false)
        compose.setContent { SmugViewTheme(darkTheme = false) { if (viewerOpen) DarkSystemBars() } }
        val window = compose.activity.window

        compose.waitForIdle()
        val lightBar = window.statusBarColor
        assertNotEquals("the light theme's own bar is not black", Color.Black.toArgb(), lightBar)

        viewerOpen = true
        compose.waitForIdle()
        assertEquals(Color.Black.toArgb(), window.statusBarColor)

        viewerOpen = false
        compose.waitForIdle()
        assertEquals(lightBar, window.statusBarColor)
    }
}
