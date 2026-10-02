package com.smugview.app.screen

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.smugview.app.data.db.OfflineFile
import com.smugview.app.data.offline.OfflineStore
import com.smugview.app.data.repository.FakeSmugMugServer
import com.smugview.app.ui.browser.CollectionsTabView
import kotlinx.coroutines.runBlocking
import org.junit.After
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
 * Step 6-16 (design 3.13, Q10): unsaving a photo asks first when the copy saved on this phone is used by nothing else, says
 * how big it is and that SmugMug keeps the photo, and deletes it only on "Remove". When another collection still holds the
 * photo nothing is deleted, so nothing is asked and the removal stays one tap. Cancel changes nothing.
 *
 * Texts are literals on purpose: they are design section 5.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class RemoveConfirmScreenTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var screens: ScreenRig
    private val vm get() = screens.viewModel
    private val sql get() = screens.rig.db.openHelper.writableDatabase
    private val dao get() = screens.rig.db.offlineDao()
    private val now = System.currentTimeMillis()
    private val site = FakeSmugMugServer.SITE_A
    private val gallery = "FfHCms"

    private val confirmText =
        "Remove “Sunset” from Favorites? The copy saved on this phone (1 KB) will be deleted. It stays on SmugMug."

    @Before fun setUp() {
        screens = ScreenRig(compose)
        sql.execSQL("INSERT INTO offline_collections (id, name, siteNickname, createdAt) VALUES (1, 'Favorites', '$site', $now)")
        sql.execSQL("INSERT INTO offline_collections (id, name, siteNickname, createdAt) VALUES (2, 'Trip', '$site', $now)")
        vm.selectSite(site)
        screens.awaitSiteQuiet()
    }

    private var onScreen by androidx.compose.runtime.mutableStateOf(true)

    /** The screen leaves before the database closes: a delete invalidates the lists it observes, and a re-query on a closed database fails the test. */
    @After fun tearDown() { onScreen = false; screens.settle(); screens.close() }

    private fun saved(key: String): File = runBlocking {
        val rel = OfflineStore.relPathOf(key, "jpg")
        val file = File(screens.rig.offlineFilesDir, rel).also { it.parentFile!!.mkdirs(); it.writeBytes(ByteArray(1_000) { 7 }) }
        dao.insertFile(
            OfflineFile(
                fileKey = OfflineStore.fileKeyOf(key), imageKey = key, albumKey = gallery, nickname = site, expectedBytes = 1_000,
                title = "Sunset", format = "JPG", state = OfflineStore.DONE, relPath = rel, bytes = 1_000, createdAt = now, updatedAt = now
            )
        )
        file
    }

    private fun imageBookmark(key: String, collection: Int) = sql.execSQL(
        "INSERT INTO collection_bookmarks (collectionId, type, itemKey, title, albumKey, albumTitle, thumbnailUrl, extraData) " +
            "VALUES ($collection, 'Image', '$key', 'Sunset', '$gallery', 'Class photos', NULL, NULL)"
    )

    private fun photoRow(key: String, collection: Int) = sql.execSQL(
        "INSERT INTO collection_photos (imageKey, collectionId, albumKey, title, thumbnailUrl, archivedUri, localFilePath, dateTaken, keywords, isDownloaded) " +
            "VALUES ('$key', $collection, '$gallery', 'Sunset', NULL, NULL, NULL, NULL, NULL, 0)"
    )

    private fun shown(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    private fun awaitText(text: String) = screens.waitUntil(message = "\"$text\" on screen") { shown(text) }

    private fun openFavorites() {
        screens.setContent {
            if (onScreen) CollectionsTabView(viewModel = vm, onNavigateToAlbum = { _, _ -> }, onImageClick = { _, _ -> }, onNavigateToCastController = {})
        }
        awaitText("Favorites")
        compose.onNodeWithText("Favorites").performClick()
    }

    private fun unsave() {
        screens.waitUntil(message = "an Unsave button") { compose.onAllNodesWithContentDescription("Unsave").fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithContentDescription("Unsave")[0].performClick()
    }

    private fun isBookmarked(collection: Long) = runBlocking { vm.isBookmarked(collection, "Image", KEY) }

    /** Red on the old screen: the bookmark went at once and its saved copy with it, with no question. */
    @Test fun `unsaving an image whose only saved copy is this one asks first`() {
        val file = saved(KEY); imageBookmark(KEY, 1)
        openFavorites()
        awaitText("Class photos")
        compose.onNodeWithText("Class photos").performClick()
        awaitText("Image Shortcut")

        unsave()

        awaitText(confirmText)
        assertTrue("nothing removed while the question is open", isBookmarked(1))
        assertTrue(file.isFile)
        compose.onNodeWithText("Cancel").performClick()
        compose.waitForIdle()
        assertFalse("the question closed", shown(confirmText))
        assertTrue("Cancel changes nothing", isBookmarked(1))
        assertTrue(file.isFile)

        unsave()
        awaitText(confirmText)
        compose.onNodeWithText("Remove").performClick()
        screens.waitUntil(message = "the bookmark removed") { !isBookmarked(1) }
        screens.waitUntil(message = "the saved copy deleted") { !file.exists() }
    }

    @Test fun `when another collection still holds the photo nothing is asked and nothing is deleted`() {
        val file = saved(KEY); imageBookmark(KEY, 1); imageBookmark(KEY, 2)
        openFavorites()
        awaitText("Class photos")
        compose.onNodeWithText("Class photos").performClick()
        awaitText("Image Shortcut")

        unsave()

        screens.waitUntil(message = "the bookmark removed") { !isBookmarked(1) }
        assertFalse("no question", shown(confirmText))
        assertTrue("Trip still has it", isBookmarked(2))
        assertTrue("its copy stays", file.isFile)
    }

    @Test fun `an image that was never saved to this phone is removed at once`() {
        imageBookmark(KEY, 1)
        openFavorites()
        awaitText("Class photos")
        compose.onNodeWithText("Class photos").performClick()
        awaitText("Image Shortcut")

        unsave()

        screens.waitUntil(message = "the bookmark removed") { !isBookmarked(1) }
        assertFalse(shown(confirmText))
    }

    /** Red on the old screen: the close button on a saved photo's row removed it, and its copy, at once. */
    @Test fun `removing a saved photo whose only copy is this one asks first`() {
        val file = saved(KEY); photoRow(KEY, 1)
        openFavorites()

        unsave()

        awaitText(confirmText)
        assertTrue("nothing deleted while the question is open", file.isFile)
        compose.onNodeWithText("Remove").performClick()
        screens.waitUntil(message = "the saved copy deleted") { !file.exists() }
    }

    private companion object { const val KEY = "AbC123" }
}
