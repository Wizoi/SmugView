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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlinx.coroutines.runBlocking

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
    private fun openSite(saveFamilyPassword: Boolean = true) {
        if (saveFamilyPassword) screens.rig.passwords.savePassword("2sDN5x", "family-pw")
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

        compose.onNodeWithText(UserMessages.REMOVE_FROM_COLLECTIONS).performClick()
        awaitText("2 photos saved on this phone", substring = true)
        assertTrue("the question names the gallery", shown("New School Year", substring = true))
        assertFalse("the gallery is gone from SmugMug: its photos do not stay there", shown("They stay on SmugMug", substring = true))
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

    // ---- 6-11c: a 404 under a root that is unlocked this launch is "gone", not "locked" ----

    private val family = "2sDN5x"
    private val unlocks get() = screens.rig.repository.unlocks
    private fun familyAccess() = screens.viewModel.passwordAccess.value[family]

    /** The prompt is raised a moment after the load fails (it asks UnlockManager for the root first): wait it out, then say none came. */
    private fun assertNoPasswordPrompt(why: String) {
        val deadline = System.currentTimeMillis() + 2_000
        while (System.currentTimeMillis() < deadline) {
            assertNull(why, screens.viewModel.passwordPromptNode)
            screens.settle()
            Thread.sleep(20) // the prompt is raised from a background launch: give it real time to arrive
        }
    }
    private fun http(code: Int) = retrofit2.HttpException(
        retrofit2.Response.error<Any>(code, okhttp3.ResponseBody.create(null, "{}"))
    )

    /** Fixture F as cached rows: Family (password) -> School -> gallery (NodeID LCdk7F, AlbumKey FfHCms), so the gallery's root is known. */
    private fun cacheTopology() {
        val site = FakeSmugMugServer.SITE_A
        fun row(id: String, parent: String, type: String, title: String, access: String? = null, albumUri: String? = null) = CachedNode(
            nodeId = id, parentNodeId = parent, type = type, title = title, description = null, access = access, passwordHint = null,
            uri = "/api/v2/node/$id", childNodesUri = if (type == "Folder") "/api/v2/node/$id!children" else null, albumUri = albumUri, nickname = site
        )
        runBlocking {
            screens.rig.dao.insertNodes(
                listOf(
                    row(family, "4zqWw", "Folder", "Family", access = "Password"),
                    row("P4BKB", family, "Folder", "School"),
                    row("LCdk7F", "P4BKB", "Album", "New School Year", albumUri = "/api/v2/album/$album")
                )
            )
        }
        val node = runBlocking { screens.rig.repository.getNodeById("LCdk7F") }
        assertEquals("the gallery's NodeID differs from its AlbumKey", album, node?.getAlbumKey())
    }

    @Test fun `a 404 under a Family folder unlocked this launch says gone and raises no password prompt`() {
        openSite()
        cacheTopology()
        screens.waitUntil(message = "Family has a live session") { familyAccess() == com.smugview.app.data.repository.UnlockManager.Access.Session }
        galleryIsDeleted()
        showGallery()

        awaitText("Not found on SmugMug")
        screens.settle()

        assertNoPasswordPrompt("a live session means the 404 is not a lock: no prompt")
        assertFalse("no password dialog beside the GONE view", shown("Cancel"))
        assertEquals("and the saved password is untouched", "family-pw", screens.rig.passwords.getPassword(family))
    }

    @Test fun `a password prompt that was already up for the gallery closes when the 404 arrives under a live session`() {
        openSite()
        cacheTopology()
        screens.waitUntil(message = "Family has a live session") { familyAccess() == com.smugview.app.data.repository.UnlockManager.Access.Session }
        val node = runBlocking { screens.rig.repository.getNodeById("LCdk7F")!! }
        screens.viewModel.requestPassword(node)
        screens.waitUntil(message = "the prompt is up") { screens.viewModel.passwordPromptNode != null }
        galleryIsDeleted()
        showGallery()

        awaitText("Not found on SmugMug")
        screens.waitUntil(message = "the prompt closed") { screens.viewModel.passwordPromptNode == null }
        assertTrue(shown(UserMessages.BUTTON_GO_BACK))
    }

    @Test fun `a 404 with nothing unlocked still raises the password prompt, because that is how a locked folder answers`() {
        openSite(saveFamilyPassword = false)
        cacheTopology()
        screens.rig.passwords.savePassword("unrelated-key", "x") // another saved password must not count as Family's
        assertTrue("no session for Family", familyAccess() != com.smugview.app.data.repository.UnlockManager.Access.Session)
        galleryIsDeleted()
        showGallery()

        screens.waitUntil(message = "the prompt is up") { screens.viewModel.passwordPromptNode != null }
        assertEquals("the prompt asks for Family's password", family, screens.viewModel.passwordPromptNode?.nodeId)
    }

    @Test fun `the session expiring mid-visit brings the prompt back for the next 404`() {
        openSite()
        cacheTopology()
        screens.waitUntil(message = "Family has a live session") { familyAccess() == com.smugview.app.data.repository.UnlockManager.Access.Session }
        galleryIsDeleted()
        showGallery()
        awaitText("Not found on SmugMug")
        assertNoPasswordPrompt("live session: a 404 is gone, no prompt")

        // The session expires: SmugMug rejects the saved password at the next unlock, so the root is no longer live.
        screens.server.unlockCode = 401
        runBlocking { unlocks.reauthorize(album, "test-key") }
        assertTrue("the root is no longer live", familyAccess() != com.smugview.app.data.repository.UnlockManager.Access.Session)
        screens.viewModel.selectAlbum(album, force = true)

        screens.waitUntil(message = "without a live session the 404 prompts again") { screens.viewModel.passwordPromptNode != null }
        assertEquals(family, screens.viewModel.passwordPromptNode?.nodeId)
    }

    @Test fun `a 401 under a live session still raises the prompt, only a 404 is read as gone`() {
        openSite()
        cacheTopology()
        screens.waitUntil(message = "Family has a live session") { familyAccess() == com.smugview.app.data.repository.UnlockManager.Access.Session }
        screens.server.respondWith("album/$album", 401, times = 20)
        showGallery()

        screens.waitUntil(message = "a 401 prompts") { screens.viewModel.passwordPromptNode != null }
        assertEquals(family, screens.viewModel.passwordPromptNode?.nodeId)
    }
}
