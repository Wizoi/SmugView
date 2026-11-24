package com.smugview.app.screen

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.smugview.app.data.cast.CastDevice
import com.smugview.app.data.cast.CastType
import com.smugview.app.enableDarkEdgeToEdge
import com.smugview.app.ui.component.CastControllerScreen
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Play pre-launch report (2026-11-24): edge-to-edge is forced from Android 15, so a screen that is not a Scaffold must keep its own content
 * out from under the status and navigation bars. The cast controller is a plain Column with its own back button at the top.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE, qualifiers = "w360dp-h800dp")
class EdgeToEdgeInsetsScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun `the cast controller starts below the status bar and ends above the navigation bar`() {
        compose.activity.enableDarkEdgeToEdge()
        compose.setContent {
            CastControllerScreen(
                activeDevice = CastDevice("route-1", "Bedroom TV", "google-cast-route", CastType.GOOGLE),
                albumTitle = "Class photos", currentImageUri = null, isSlideshowPlaying = false, slideshowInterval = 5,
                volume = 0.5f, isMuted = false, onPlayPauseToggle = {}, onNextClick = {}, onPrevClick = {}, onIntervalChange = {},
                onVolumeChange = {}, onToggleMute = {}, onStopCasting = {}, onBackClick = {}
            )
        }
        val statusBar = 96
        val navBar = 144
        compose.runOnUiThread {
            val insets = WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, statusBar, 0, 0))
                .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, navBar))
                .setVisible(WindowInsetsCompat.Type.statusBars(), true)
                .setVisible(WindowInsetsCompat.Type.navigationBars(), true)
                .build()
            ViewCompat.dispatchApplyWindowInsets(compose.activity.window.decorView, insets)
        }
        compose.waitForIdle()

        val back = compose.onNodeWithContentDescription("Back to gallery").fetchSemanticsNode().boundsInRoot
        val rootHeight = compose.activity.window.decorView.height
        assertTrue("the back button's top (${back.top}px) is under the ${statusBar}px status bar", back.top >= statusBar)
        assertTrue("the screen's bottom edge would still reach the navigation bar (root ${rootHeight}px)", back.bottom <= rootHeight - navBar)
    }
}
