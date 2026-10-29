package com.smugview.app.screen

import androidx.compose.ui.test.junit4.createComposeRule
import com.smugview.app.data.repository.FakeSmugMugServer
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Owner report (2026-10-29): the gallery page's header showed the feature (highlight) image instead of the gallery's cover image.
 * The header's picture is the first image request a Coil call makes for the banner; the rig records every model.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class GalleryHeaderCoverScreenTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var screens: ScreenRig

    @Before fun setUp() { screens = ScreenRig(compose) }
    @After fun tearDown() = screens.close()

    @Test fun `the gallery page's header asks for the gallery's cover picture`() {
        screens.viewModel.selectSite(FakeSmugMugServer.SITE_A)
        screens.awaitSiteQuiet()
        screens.setGalleryContent("N74KSK")
        screens.waitUntil(message = "the header requested the gallery's cover picture") {
            screens.imageRequests.any { it.contains("cover-sXQz4G") }
        }
        assertTrue("the cover is requested at the header size", screens.imageRequests.any { it.contains("/L/cover-sXQz4G-L.jpg") })
    }
}
