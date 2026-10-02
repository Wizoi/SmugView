package com.smugview.app.screen

import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import com.smugview.app.data.offline.OfflineNetworkRule
import com.smugview.app.data.offline.OfflineStore
import com.smugview.app.ui.browser.NetworkSettingDialog
import com.smugview.app.ui.theme.SmugViewTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Step 6-21 (emulator, light system theme): the network setting dialog's hint lines were hard-coded white on the dialog's
 * own (light) surface and could not be read. Every line must be readable on the surface in both themes (WCAG 4.5:1).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class NetworkSettingThemeTest {
    @get:Rule val compose = createComposeRule()

    private val lines = listOf(
        "On Wi-Fi only",
        "Kept galleries wait for Wi-Fi. A Wi-Fi network marked as metered, like a phone hotspot, counts as mobile data.",
        "On Wi-Fi or mobile data",
        "Photos you save one at a time always download right away, on any connection (a few MB each)."
    )

    private fun contrast(a: Color, b: Color): Double {
        val l1 = a.luminance().toDouble()
        val l2 = b.luminance().toDouble()
        return (maxOf(l1, l2) + 0.05) / (minOf(l1, l2) + 0.05)
    }

    private fun assertReadable(dark: Boolean) {
        var surface = Color.Unspecified
        compose.setContent {
            SmugViewTheme(darkTheme = dark) {
                surface = AlertDialogDefaults.containerColor
                NetworkSettingDialog(
                    rule = OfflineNetworkRule.WIFI_ONLY,
                    waiting = OfflineStore.GalleryWaiting(0L, false),
                    onChoose = {}, onClose = {}
                )
            }
        }
        compose.waitForIdle()
        for (line in lines) {
            val node = compose.onNodeWithText(line, substring = true, useUnmergedTree = true).fetchSemanticsNode()
            val results = mutableListOf<TextLayoutResult>()
            node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
            val color = results.first().layoutInput.style.color
            val ratio = contrast(color.compositeOver(surface), surface)
            assertTrue("\"${line.take(30)}\" on the ${if (dark) "dark" else "light"} dialog: contrast $ratio < 4.5 ($color on $surface)", ratio >= 4.5)
        }
    }

    @Test fun `every line of the network dialog is readable in the light theme`() = assertReadable(dark = false)

    @Test fun `every line of the network dialog is readable in the dark theme`() = assertReadable(dark = true)
}
