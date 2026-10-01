package com.smugview.app.scenario

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.smugview.app.data.db.CachedNode
import com.smugview.app.data.repository.FakeSmugMugServer
import com.smugview.app.screen.ScreenRig
import com.smugview.app.ui.grid.PhotoGridScreen
import com.smugview.app.ui.text.UserMessages
import com.smugview.app.ui.viewmodel.BrowserUiState
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
import java.io.File

/**
 * Step 6-11 (design 3.8, Q4 (a), N5): a bookmark is removed by the user and by nothing else. A 404 is also how a locked folder
 * answers (L2), so a bookmarked gallery or folder that answers 404 is NEVER removed on its own. The grid says it is gone and offers
 * "Remove from collections" (with the Phase 5 confirmation when saved photos would go with it).
 *
 * The old code removed the bookmark, its kept-offline rows and (through the garbage pass) its saved files the moment the 404
 * arrived, from `handleAlbumLoadError` (galleries) and `onLoadFailure` (folders).
 *
 * Fixture F: Family (2sDN5x, password) -> School (P4BKB) -> FfHCms (AlbumKey; NodeID LCdk7F). The user saved the Family password,
 * so the 404 is "gone", not "locked". Photos saved on the phone are real files under `filesDir/offline`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class GoneBookmarkScenarioTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var screens: ScreenRig

    private val album = "FfHCms"
    private val trip = 2L
    private val favorites = 1L
    private val now = System.currentTimeMillis()

    @Before fun setUp() { screens = ScreenRig(compose) }
    @After fun tearDown() = screens.close()

    private val sql get() = screens.rig.db.openHelper.writableDatabase

    private fun count(table: String, where: String = "1=1") =
        sql.query("SELECT COUNT(*) FROM $table WHERE $where").use { it.moveToFirst(); it.getInt(0) }

    private fun shown(text: String, substring: Boolean = false) =
        compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()

    private fun awaitText(text: String, substring: Boolean = false) =
        screens.waitUntil(message = "\"$text\" on screen") { shown(text, substring) }

    private fun file(imageKey: String) = File(screens.rig.offlineFilesDir, "offline/$imageKey.jpg")

    /** The site is open, the Family password is saved (so a 404 below it is "gone", not "locked"), and two collections exist. */
    private fun openSite() {
        screens.rig.passwords.savePassword("2sDN5x", "family-pw")
        screens.viewModel.selectSite(FakeSmugMugServer.SITE_A)
        screens.awaitSiteQuiet()
        sql.execSQL("INSERT INTO offline_collections (id, name, siteNickname, createdAt) VALUES ($favorites, 'Favorites', '${FakeSmugMugServer.SITE_A}', $now)")
        sql.execSQL("INSERT INTO offline_collections (id, name, siteNickname, createdAt) VALUES ($trip, 'Trip', '${FakeSmugMugServer.SITE_A}', $now)")
    }

    private fun bookmark(collection: Long, type: String, key: String, title: String) =
        sql.execSQL(
            "INSERT INTO collection_bookmarks (collectionId, type, itemKey, title, albumKey, albumTitle, thumbnailUrl) " +
                "VALUES ($collection, '$type', '$key', '$title', '${if (type == "Album") key else ""}', '${if (type == "Album") title else ""}', NULL)"
        )

    /** The gallery is kept offline in Trip: its row, its listing, and a saved file for each of [imageKeys]. */
    private fun keepOffline(vararg imageKeys: String) {
        sql.execSQL(
            "INSERT INTO offline_galleries (collectionId, albumKey, nickname, title, state, retryable, wifiOnly) " +
                "VALUES ($trip, '$album', '${FakeSmugMugServer.SITE_A}', 'New School Year', 'LISTED', 0, 1)"
        )
        imageKeys.forEachIndexed { i, key ->
            file(key).apply { parentFile!!.mkdirs(); writeBytes(ByteArray(2_000_000) { 7 }) }
            sql.execSQL(
                "INSERT INTO offline_files (fileKey, imageKey, albumKey, nickname, state, relPath, bytes, createdAt, updatedAt) " +
                    "VALUES ('$key/orig', '$key', '$album', '${FakeSmugMugServer.SITE_A}', 'DONE', 'offline/$key.jpg', 2000000, $now, $now)"
            )
            sql.execSQL("INSERT INTO offline_gallery_items (collectionId, albumKey, imageKey, sortIndex) VALUES ($trip, '$album', '$key', $i)")
        }
    }

    /** The gallery was deleted on SmugMug: its own record and its image list both answer 404. */
    private fun galleryIsDeleted() = screens.server.respondWith("album/$album", 404, times = 20)

    /**
     * `handleAlbumLoadError` still raises the password prompt on any 404 whose node it can place (not changed by 6-11: owner
     * decision, see design 14), so the prompt is on screen beside the GONE view. The user closes it (its Cancel also goes back; the
     * test's back is a no-op) before using the button, so the dialogs do not share a "Cancel".
     */
    private fun closeThePasswordPromptIfAny() {
        val deadline = System.currentTimeMillis() + 1_500
        while (screens.viewModel.passwordPromptNode == null && System.currentTimeMillis() < deadline) screens.waitUntil(message = "idle") { true }
        screens.viewModel.dismissPasswordPrompt()
        screens.settle()
    }

    private fun showGallery() {
        screens.setContent {
            PhotoGridScreen(
                albumKey = album,
                albumTitle = "New School Year",
                onNavigateToPhotoDetail = {},
                onBackClick = {},
                onNavigateToFolder = {},
                onNavigateToCastController = {},
                viewModel = screens.viewModel
            )
        }
    }

    @Test fun `a bookmarked gallery kept offline that answers 404 keeps its bookmark, its rows and its files, and the screen says it is gone`() {
        openSite()
        bookmark(trip, "Album", album, "New School Year")
        keepOffline("Xk3Lq2m", "Yb9Rt4n")
        galleryIsDeleted()
        showGallery()

        awaitText("Not found on SmugMug")
        screens.settle()

        assertEquals("the bookmark must stay", 1, count("collection_bookmarks", "type = 'Album' AND itemKey = '$album'"))
        assertEquals("the kept-offline row must stay", 1, count("offline_galleries", "albumKey = '$album'"))
        assertEquals(2, count("offline_gallery_items", "albumKey = '$album'"))
        assertEquals(2, count("offline_files", "state = 'DONE'"))
        assertTrue("the saved photos must stay on the phone", file("Xk3Lq2m").isFile && file("Yb9Rt4n").isFile)
        compose.onNodeWithText(UserMessages.REMOVE_FROM_COLLECTIONS).assertIsDisplayed()
        compose.onNodeWithText(UserMessages.BUTTON_GO_BACK).assertIsDisplayed()
    }

    @Test fun `Remove from collections asks first because saved photos would go, Cancel keeps everything, Delete removes it all but a photo another collection holds`() {
        openSite()
        bookmark(trip, "Album", album, "New School Year")
        keepOffline("Xk3Lq2m", "Yb9Rt4n", "Zc5Wv8p")
        // Zc5Wv8p is also saved by hand into Favorites: that copy is not this gallery's to delete.
        sql.execSQL(
            "INSERT INTO collection_photos (imageKey, collectionId, albumKey, title, thumbnailUrl, archivedUri, localFilePath, dateTaken, keywords, isDownloaded) " +
                "VALUES ('Zc5Wv8p', $favorites, '$album', 'IMG_Zc5Wv8p', NULL, NULL, NULL, NULL, NULL, 0)"
        )
        galleryIsDeleted()
        showGallery()
        awaitText(UserMessages.REMOVE_FROM_COLLECTIONS)
        closeThePasswordPromptIfAny()

        compose.onNodeWithText(UserMessages.REMOVE_FROM_COLLECTIONS).performClick()
        awaitText("2 photos saved on this phone", substring = true)
        assertTrue("the question names the gallery", shown("New School Year", substring = true))
        compose.onNodeWithText("Cancel").performClick()
        screens.settle()
        assertFalse(shown("2 photos saved on this phone", substring = true))
        assertEquals("Cancel removes nothing", 1, count("collection_bookmarks", "type = 'Album' AND itemKey = '$album'"))
        assertTrue(file("Xk3Lq2m").isFile)

        compose.onNodeWithText(UserMessages.REMOVE_FROM_COLLECTIONS).performClick()
        awaitText("2 photos saved on this phone", substring = true)
        compose.onNodeWithText("Delete").performClick()
        screens.waitUntil(message = "the bookmark is gone") { count("collection_bookmarks", "itemKey = '$album'") == 0 }
        screens.waitUntil(message = "the unshared files are gone") { !file("Xk3Lq2m").exists() && !file("Yb9Rt4n").exists() }

        assertEquals(0, count("offline_galleries", "albumKey = '$album'"))
        assertEquals(0, count("offline_gallery_items", "albumKey = '$album'"))
        assertTrue("a photo another collection holds stays", file("Zc5Wv8p").isFile)
        assertEquals(1, count("offline_files", "imageKey = 'Zc5Wv8p'"))
        screens.waitUntil(message = "the button went with the bookmark") { !shown(UserMessages.REMOVE_FROM_COLLECTIONS) }
        compose.onNodeWithText("Not found on SmugMug").assertIsDisplayed()
    }

    @Test fun `a bookmarked gallery with nothing saved is removed by the button with no question`() {
        openSite()
        bookmark(trip, "Album", album, "New School Year")
        bookmark(favorites, "Album", album, "New School Year")
        galleryIsDeleted()
        showGallery()
        awaitText(UserMessages.REMOVE_FROM_COLLECTIONS)
        closeThePasswordPromptIfAny()
        assertEquals("both bookmarks survived the 404", 2, count("collection_bookmarks", "itemKey = '$album'"))

        compose.onNodeWithText(UserMessages.REMOVE_FROM_COLLECTIONS).performClick()

        screens.waitUntil(message = "every bookmark of it is gone") { count("collection_bookmarks", "itemKey = '$album'") == 0 }
        assertFalse("nothing was saved, so nothing is asked", shown("Delete"))
    }

    @Test fun `a gone gallery that nobody bookmarked offers no Remove from collections`() {
        openSite()
        galleryIsDeleted()
        showGallery()

        awaitText("Not found on SmugMug")
        screens.settle()

        assertFalse(shown(UserMessages.REMOVE_FROM_COLLECTIONS))
        compose.onNodeWithText(UserMessages.BUTTON_GO_BACK).assertIsDisplayed()
    }

    @Test fun `a bookmarked folder that answers 404 and has no cached row keeps its bookmark`() {
        openSite()
        bookmark(trip, "Folder", "ZzGone1", "Old trips")
        screens.server.respondWith("node/ZzGone1", 404, times = 20)
        assertEquals("the folder has no cached row", 0, count("cached_nodes", "nodeId = 'ZzGone1'"))

        screens.viewModel.navigateToChildFolder(
            CachedNode(
                nodeId = "ZzGone1", parentNodeId = null, type = "Folder", title = "Old trips", description = null, access = null,
                passwordHint = null, uri = "/api/v2/node/ZzGone1", childNodesUri = "/api/v2/node/ZzGone1!children", albumUri = null
            )
        )
        screens.waitUntil(message = "the folder load failed") { screens.viewModel.browserState.value is BrowserUiState.Error }
        screens.settle()

        assertEquals("a 404 never removes a bookmark", 1, count("collection_bookmarks", "type = 'Folder' AND itemKey = 'ZzGone1'"))
    }
}
