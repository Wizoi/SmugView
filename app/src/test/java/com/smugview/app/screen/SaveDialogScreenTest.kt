package com.smugview.app.screen

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import com.smugview.app.data.api.AlbumImageData
import com.smugview.app.data.db.CachedAlbum
import com.smugview.app.data.db.CollectionBookmark
import com.smugview.app.data.repository.FakeSmugMugServer
import com.smugview.app.ui.component.AddToCollectionsDialog
import kotlinx.coroutines.flow.first
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
 * Step 6-16 (design 3.13, Q9, R-44): the Save dialog writes nothing when it opens. "Save" writes the ticked set (and removes
 * what was bookmarked and is now unticked), "Cancel" writes nothing. A search result, which has no album link, is saved with the
 * key of its gallery, found from its picture's address.
 *
 * Texts are literals on purpose: they are design section 5.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class SaveDialogScreenTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var screens: ScreenRig
    private val vm get() = screens.viewModel
    private val sql get() = screens.rig.db.openHelper.writableDatabase
    private val now = System.currentTimeMillis()
    private var dismissed = false

    @Before fun setUp() {
        screens = ScreenRig(compose)
        sql.execSQL("INSERT INTO offline_collections (id, name, siteNickname, createdAt) VALUES (1, 'Favorites', '${FakeSmugMugServer.SITE_A}', $now)")
        sql.execSQL("INSERT INTO offline_collections (id, name, siteNickname, createdAt) VALUES (2, 'Trip', '${FakeSmugMugServer.SITE_A}', $now)")
        vm.selectSite(FakeSmugMugServer.SITE_A)
        screens.awaitSiteQuiet()
    }

    @After fun tearDown() = screens.close()

    private fun bookmarked(collection: Long) = runBlocking { vm.isBookmarked(collection, "Image", KEY) }

    private fun seedBookmark() = runBlocking {
        screens.rig.dao.addBookmark(CollectionBookmark(collectionId = 1, type = "Image", itemKey = KEY, title = "Sunset", albumKey = "GAL", albumTitle = "Gallery"))
    }

    private fun open(albumKey: String = "GAL", resolver: (suspend () -> String?)? = null) {
        dismissed = false
        screens.setContent {
            AddToCollectionsDialog(
                type = "Image", itemKey = KEY, title = "Sunset", albumKey = albumKey, albumTitle = "Gallery",
                thumbnailUrl = "https://photos.smugmug.com/Family/School/2026-09-01--New-School-Year/i-$KEY/0/Th/x-Th.jpg",
                albumKeyResolver = resolver, onDismissRequest = { dismissed = true }, viewModel = vm
            )
        }
        screens.waitUntil(message = "the collections listed") { compose.onAllNodesWithText("Trip").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun tickedCount() = compose.onAllNodes(isToggleable()).fetchSemanticsNodes()
        .count { it.config.getOrNull(SemanticsProperties.ToggleableState) == ToggleableState.On }

    private fun awaitTicked(n: Int) = screens.waitUntil(message = "$n ticked") { tickedCount() == n }

    /** Red on the old dialog: opening it bookmarked the photo in every last-used collection, and Cancel did not exist. */
    @Test fun `opening the dialog and pressing Cancel writes nothing`() {
        vm.lastSelectedCollectionIds = setOf(1L)
        open()
        awaitTicked(1)

        compose.onNodeWithText("Cancel").performClick()
        compose.waitForIdle()

        assertTrue("the dialog closed", dismissed)
        assertFalse("nothing was saved to Favorites", bookmarked(1))
        assertFalse("nothing was saved to Trip", bookmarked(2))
    }

    @Test fun `Save writes the ticked collections and remembers them`() {
        vm.lastSelectedCollectionIds = setOf(1L)
        open()
        awaitTicked(1)
        assertFalse("still nothing written while the dialog is open", bookmarked(1))

        compose.onNodeWithText("Trip").performClick()
        awaitTicked(2)
        assertFalse("ticking writes nothing", bookmarked(2))
        compose.onNodeWithText("Save").performClick()
        screens.waitUntil(message = "dialog closed") { dismissed }

        assertTrue(bookmarked(1))
        assertTrue(bookmarked(2))
        assertEquals(setOf(1L, 2L), vm.lastSelectedCollectionIds)
        val saved = runBlocking { screens.rig.dao.getBookmarksForCollection(1).first() }.single()
        assertEquals("GAL", saved.albumKey)
    }

    @Test fun `unticking a saved collection removes it on Save and not before`() {
        seedBookmark()
        open()
        awaitTicked(1)

        compose.onNodeWithText("Favorites").performClick()
        awaitTicked(0)
        assertTrue("unticking writes nothing", bookmarked(1))
        compose.onNodeWithText("Save").performClick()
        screens.waitUntil(message = "dialog closed") { dismissed }

        assertFalse(bookmarked(1))
        assertEquals(emptySet<Long>(), vm.lastSelectedCollectionIds)
    }

    @Test fun `Cancel after changing the ticks leaves everything as it was`() {
        seedBookmark()
        open()
        awaitTicked(1)

        compose.onNodeWithText("Favorites").performClick()
        compose.onNodeWithText("Trip").performClick()
        compose.onNodeWithText("Cancel").performClick()
        compose.waitForIdle()

        assertTrue(bookmarked(1))
        assertFalse(bookmarked(2))
    }

    /** Red on the old screens: a search result's photo has no album link, so the bookmark was stored with albumKey = "". */
    @Test fun `a search photo is saved with the key of its gallery`() {
        runBlocking {
            screens.rig.dao.upsertAlbums(
                listOf(
                    CachedAlbum(
                        albumKey = "FfHCms", nodeId = "LCdk7F", name = "New School Year", securityType = "None", passwordHint = null,
                        uri = "/api/v2/album/FfHCms", webUri = null, urlPath = "/Family/School/2026-09-01--New-School-Year",
                        imageCount = 150, dateModified = null, galleryStyle = null, highlightImageUrl = null,
                        nickname = FakeSmugMugServer.SITE_A
                    )
                )
            )
        }
        val photo = AlbumImageData(
            imageKey = KEY, title = "Sunset",
            thumbnailUrl = "https://photos.smugmug.com/Family/School/2026-09-01--New-School-Year/i-$KEY/0/Th/x-Th.jpg"
        )
        vm.lastSelectedCollectionIds = setOf(1L)
        open(albumKey = "", resolver = { vm.albumKeyFor(photo) })
        awaitTicked(1)

        compose.onNodeWithText("Save").performClick()
        screens.waitUntil(message = "dialog closed") { dismissed }

        val saved = runBlocking { screens.rig.dao.getBookmarksForCollection(1).first() }.single()
        assertEquals("FfHCms", saved.albumKey)
    }

    private companion object { const val KEY = "AbC123" }
}
