package com.smugview.app.screen

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.smugview.app.data.cast.CastDevice
import com.smugview.app.data.cast.CastSupport
import com.smugview.app.data.cast.CastType
import com.smugview.app.ui.component.DeviceItem
import com.smugview.app.ui.text.UserMessages
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Owner request (2026-10-29): a device that cannot be cast to is marked in the picker (a red circle) and tapping it says why, instead of
 * connecting and failing afterwards. A device that reports it cannot is the only one marked; everything else stays selectable.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class CastDeviceRowScreenTest {
    @get:Rule val compose = createComposeRule()

    private val unsupportedRoku = CastDevice(
        "192.168.1.30", "Living Room Roku", "192.168.1.30", CastType.ROKU,
        support = CastSupport.Unsupported(UserMessages.CAST_ROKU_UNSUPPORTED)
    )
    private val googleTv = CastDevice("route-1", "Bedroom TV", "google-cast-route", CastType.GOOGLE)
    private val picks = mutableListOf<String>()

    private fun show(device: CastDevice) = compose.setContent {
        DeviceItem(device = device, isConnectingGlobal = false, onDeviceSelected = { picks += it.id })
    }

    @Test fun `a device that cannot be cast to has a red circle`() {
        show(unsupportedRoku)
        compose.onNodeWithContentDescription("Cannot be cast to").assertIsDisplayed()
    }

    @Test fun `tapping it says why and does not connect`() {
        show(unsupportedRoku)
        compose.onNodeWithText("Living Room Roku").performClick()
        compose.onNodeWithText(UserMessages.CAST_ROKU_UNSUPPORTED).assertIsDisplayed()
        assertEquals("nothing was picked", emptyList<String>(), picks)
    }

    @Test fun `the reason can be closed`() {
        show(unsupportedRoku)
        compose.onNodeWithText("Living Room Roku").performClick()
        compose.onNodeWithText("OK").performClick()
        compose.onNodeWithText(UserMessages.CAST_ROKU_UNSUPPORTED).assertDoesNotExist()
    }

    @Test fun `a device nothing says against is selectable and has no red circle`() {
        show(googleTv)
        compose.onNodeWithContentDescription("Cannot be cast to").assertDoesNotExist()
        compose.onNodeWithText("Bedroom TV").performClick()
        assertEquals(listOf("route-1"), picks)
    }
}
