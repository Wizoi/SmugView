package com.smugview.app.screen

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import com.smugview.app.data.repository.FakeSmugMugServer
import com.smugview.app.ui.browser.FoldersTabView
import com.smugview.app.ui.viewmodel.BrowserUiState
import org.junit.After
import com.smugview.app.ui.component.avatarModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Step 6-2 (N4, design 3.7): the profile avatar used to request `secure.smugmug.com/users/{nick}-avatar.jpg`
 * and `{nick}.smugmug.com/bioimage`, both 404 on 5 of 5 live sites (L1). It now shows the BioImage the profile
 * call already carries (its path-bearing `/Th/` thumbnail resized to `/S/`), else the first letter, and never
 * asks for those two hosts.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class ProfileAvatarTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var screens: ScreenRig

    @Before fun setUp() { screens = ScreenRig(compose) }
    @After fun tearDown() = screens.close()

    /** Renders the Folders tab (its header shows the avatar at the site root) and waits for the profile and the folders. */
    private fun renderFoldersHeader(nickname: String) {
        screens.viewModel.selectSite(nickname)
        screens.setContent {
            val state by screens.viewModel.browserState.collectAsState()
            FoldersTabView(
                uiState = state,
                currentFolderId = screens.viewModel.currentFolderId,
                onNavigateToAlbum = { _, _ -> },
                viewModel = screens.viewModel
            )
        }
        screens.awaitSiteQuiet()
        screens.waitUntil { screens.viewModel.activeUserProfile.value != null }
        compose.waitForIdle()
    }

    private fun isDeadAvatarHost(url: String) =
        url.contains("://secure.smugmug.com/") || url.endsWith(".smugmug.com/bioimage")

    @Test fun `a site with a BioImage shows its thumbnail resized to S and never asks the dead avatar hosts`() {
        renderFoldersHeader(FakeSmugMugServer.SITE_A)
        // the new code asks the BioImage URL; the old code asks a dead host first (and, answered, never falls through)
        screens.waitUntil(message = "the avatar request") {
            screens.imageRequests.any { "/i-hQ6nTzR/" in it || isDeadAvatarHost(it) }
        }
        val requests = screens.imageRequests.toList()
        assertTrue("no request to secure.smugmug.com or {nick}.smugmug.com/bioimage: $requests", requests.none(::isDeadAvatarHost))
        assertEquals(
            "the BioImage thumbnail, /Th/ -> /S/ and the -Th suffix -> -S",
            "https://photos.smugmug.com/Family/Holidays/2014-12-25-Christmas/i-hQ6nTzR/0/S/Dragon-S.jpg",
            requests.first { "/i-hQ6nTzR/" in it }
        )
    }

    @Test fun `a site with no BioImage shows the first letter and requests no avatar at all`() {
        renderFoldersHeader(FakeSmugMugServer.SITE_B)
        // Coil answers on its own threads: wait for either the first request (old code asks the dead host) or the letter.
        screens.waitUntil(message = "a request or the letter") {
            screens.imageRequests.isNotEmpty() || compose.onAllNodesWithText("S").fetchSemanticsNodes().isNotEmpty()
        }
        val requests = screens.imageRequests.toList()
        assertTrue("no request to secure.smugmug.com or {nick}.smugmug.com/bioimage: $requests", requests.none(::isDeadAvatarHost))
        // the site's name is "siteb": the avatar is its initial letter, "S"
        compose.onAllNodesWithText("S")[0].assertIsDisplayed()
    }

    @Test fun `avatarModel resizes the BioImage thumbnail to S including the file name suffix`() {
        assertEquals(
            "https://photos.smugmug.com/Family/Holidays/2014-12-25-Christmas/i-wKMwXWK/0/S/2014-12%20-%20Christmas%20-%201-S.jpg",
            avatarModel("https://photos.smugmug.com/Family/Holidays/2014-12-25-Christmas/i-wKMwXWK/0/Th/2014-12%20-%20Christmas%20-%201-Th.jpg")
        )
    }

    @Test fun `avatarModel is null, so the letter shows, when the site has no usable BioImage`() {
        assertNull(avatarModel(null))
        assertNull(avatarModel(""))
        assertNull(avatarModel("   "))
    }
}
