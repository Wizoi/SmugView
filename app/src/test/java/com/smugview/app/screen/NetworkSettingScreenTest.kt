package com.smugview.app.screen

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.smugview.app.data.db.OfflineFile
import com.smugview.app.data.offline.OfflineNetworkRule
import com.smugview.app.data.offline.OfflineStore
import com.smugview.app.data.repository.FakeSmugMugServer
import com.smugview.app.ui.browser.BrowserScreen
import com.smugview.app.ui.viewmodel.BrowserTab
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Step 6-N2 (addendum 2.2, N10): one network setting for kept galleries. The Collections top bar has a settings button; its
 * dialog stores the choice the moment a row is tapped; a kept gallery that waits for Wi-Fi offers "Change network setting" and
 * no longer has a per-gallery "Use mobile data" control.
 *
 * Texts are literals on purpose: they are addendum section 5.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class NetworkSettingScreenTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var screens: ScreenRig
    private val vm get() = screens.viewModel
    private val sql get() = screens.rig.db.openHelper.writableDatabase
    private val settings get() = screens.rig.settings
    private val now = System.currentTimeMillis()
    private val site = FakeSmugMugServer.SITE_A
    private val gallery = "FfHCms"

    @Before fun setUp() {
        screens = ScreenRig(compose)
        sql.execSQL("INSERT INTO offline_collections (id, name, siteNickname, createdAt) VALUES (1, 'Favorites', '$site', $now)")
        sql.execSQL(
            "INSERT INTO collection_bookmarks (collectionId, type, itemKey, title, albumKey, albumTitle, thumbnailUrl, extraData) " +
                "VALUES (1, 'Album', '$gallery', 'Class photos', '$gallery', 'Class photos', NULL, NULL)"
        )
        sql.execSQL(
            "INSERT INTO offline_galleries (collectionId, albumKey, nickname, title, state, retryable) " +
                "VALUES (1, '$gallery', '$site', 'Class photos', 'LISTED', 0)"
        )
        listOf("Hk42gZp", "n83tQ3s").forEachIndexed { i, key ->
            sql.execSQL("INSERT INTO offline_gallery_items (collectionId, albumKey, imageKey, sortIndex) VALUES (1, '$gallery', '$key', $i)")
            runBlocking {
                screens.rig.db.offlineDao().insertFile(
                    OfflineFile(
                        fileKey = OfflineStore.fileKeyOf(key), imageKey = key, albumKey = gallery, nickname = site, expectedBytes = 2L shl 20,
                        title = "IMG_$key", format = "JPG", state = OfflineStore.PENDING, createdAt = now, updatedAt = now
                    )
                )
            }
        }
        vm.selectSite(site)
        screens.awaitSiteQuiet()
        vm.setActiveTab(BrowserTab.Collections)
    }

    private var onScreen by androidx.compose.runtime.mutableStateOf(true)

    @After fun tearDown() { onScreen = false; screens.settle(); screens.close() }

    private fun shown(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    private fun awaitText(text: String) = screens.waitUntil(message = "\"$text\" on screen") { shown(text) }

    private fun showBrowser() {
        screens.setContent {
            if (onScreen) BrowserScreen(
                onNavigateToAlbum = { _, _ -> }, onNavigateToExplorer = {}, onNavigateToSearchPhotoDetail = { _, _ -> },
                onNavigateToPhotoDetail = { _, _ -> }, onNavigateToKeywordImages = {}, onNavigateToCastController = {}, viewModel = vm
            )
        }
    }

    private fun openDialog() {
        showBrowser()
        screens.waitUntil(message = "the settings button") {
            compose.onAllNodesWithContentDescription("Saving for offline settings").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("Saving for offline settings").performClick()
        awaitText("Saving galleries for offline")
    }

    @Test fun `the Collections top bar opens the network setting, and a choice is stored at once`() {
        openDialog()
        assertEquals(OfflineNetworkRule.WIFI_ONLY, settings.rule.value)
        assertTrue(shown("On Wi-Fi only"))
        assertTrue(shown("On Wi-Fi or mobile data"))
        assertTrue(shown("Photos you save one at a time always download right away, on any connection (a few MB each)."))
        awaitText("Kept galleries also save on mobile data. A gallery is often 100 MB or more; 4 MB is waiting now.")

        compose.onNodeWithText("On Wi-Fi or mobile data").performClick()
        screens.waitUntil(message = "the rule stored") { settings.rule.value == OfflineNetworkRule.WIFI_AND_MOBILE }

        compose.onNodeWithText("On Wi-Fi only").performClick()
        screens.waitUntil(message = "the rule stored again") { settings.rule.value == OfflineNetworkRule.WIFI_ONLY }

        compose.onNodeWithText("Close").performClick()
        compose.waitForIdle()
        assertFalse("Close closed the dialog", shown("Saving galleries for offline"))
    }

    @Test fun `a kept gallery that waits for Wi-Fi offers the setting and no per-gallery mobile data control`() {
        showBrowser()
        awaitText("Favorites")
        compose.onNodeWithText("Favorites").performClick()
        awaitText("Class photos")
        awaitText("Waiting for Wi-Fi")
        assertFalse("the per-gallery control is gone", shown("Use mobile data too"))
        assertFalse(shown("Use mobile data (4 MB)"))

        compose.onNodeWithText("Change network setting").performScrollTo().performClick()

        awaitText("Saving galleries for offline")
    }
}
