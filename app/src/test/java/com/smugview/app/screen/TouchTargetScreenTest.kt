package com.smugview.app.screen

import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.smugview.app.data.repository.FakeSmugMugServer
import com.smugview.app.ui.browser.BrowserScreen
import com.smugview.app.ui.viewmodel.BrowserTab
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Step 6-17 (R-49): every tap target on the Collections tab has a 48 dp touch area that overlaps no neighbour. `Modifier.size(24.dp)` on an
 * IconButton keeps the 48 dp touch area but packs the buttons 28 dp apart, so a tap lands on whichever centre is nearer.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE, qualifiers = "w360dp-h2400dp")
class TouchTargetScreenTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var screens: ScreenRig
    private val vm get() = screens.viewModel
    private val sql get() = screens.rig.db.openHelper.writableDatabase
    private val now = System.currentTimeMillis()
    private val site = FakeSmugMugServer.SITE_A
    private var onScreen by androidx.compose.runtime.mutableStateOf(true)

    @Before fun setUp() {
        screens = ScreenRig(compose)
        sql.execSQL("INSERT INTO offline_collections (id, name, siteNickname, createdAt) VALUES (1, 'Favorites', '$site', $now)")
        sql.execSQL(
            "INSERT INTO collection_bookmarks (collectionId, type, itemKey, title, albumKey, albumTitle, thumbnailUrl, extraData) VALUES " +
                "(1, 'Folder', 'Fo1dEr', 'Summer trips', '', '', NULL, NULL), " +
                "(1, 'Album', 'FfHCms', 'Class photos', 'FfHCms', 'Class photos', NULL, NULL), " +
                "(1, 'Image', 'Hk42gZp', 'Field day', 'FfHCms', 'Class photos', NULL, NULL)"
        )
        sql.execSQL(
            "INSERT INTO collection_photos (imageKey, collectionId, albumKey, title, thumbnailUrl, archivedUri, localFilePath, dateTaken, keywords, isDownloaded) " +
                "VALUES ('ZZ9999', 1, 'FfHCms', 'Bake sale', NULL, NULL, NULL, NULL, NULL, 0)"
        )
        vm.selectSite(site)
        screens.awaitSiteQuiet()
        vm.setActiveTab(BrowserTab.Collections)
    }

    @After fun tearDown() { onScreen = false; screens.settle(); screens.close() }

    private fun shown(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    private fun awaitText(text: String) = screens.waitUntil(message = "\"$text\" on screen") { shown(text) }

    private class Target(val label: String, val touch: androidx.compose.ui.geometry.Rect, val layout: androidx.compose.ui.geometry.Rect)

    private fun targets(): List<Target> {
        val nodes = compose.onAllNodes(hasClickAction())
        return nodes.fetchSemanticsNodes().map { n ->
            val label = n.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.ContentDescription)?.firstOrNull()
                ?: n.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Text)?.firstOrNull()?.text ?: "(row)"
            Target(label, n.touchBoundsInRoot, n.boundsInRoot)
        }
    }

    private fun contains(outer: androidx.compose.ui.geometry.Rect, inner: androidx.compose.ui.geometry.Rect) =
        outer.left <= inner.left + 1 && outer.top <= inner.top + 1 && outer.right >= inner.right - 1 && outer.bottom >= inner.bottom - 1

    /** Targets whose 48 dp touch areas overlap a neighbour (a button inside a clickable row is nesting, not overlap). */
    private fun problems(): List<String> {
        val all = targets()
        val dp = compose.density.density
        val small = all.filter { it.touch.width / dp < 47.5f || it.touch.height / dp < 47.5f }.map { "${it.label}: touch area ${it.touch.width / dp} x ${it.touch.height / dp} dp" }
        val overlapping = all.indices.flatMap { i -> (i + 1 until all.size).mapNotNull { j ->
            val a = all[i]; val b = all[j]
            val hit = a.touch.intersect(b.touch)
            val nested = contains(a.layout, b.layout) || contains(b.layout, a.layout)
            if (!nested && hit.width > 2f && hit.height > 2f) "${a.label} and ${b.label} overlap by ${hit.width / dp} x ${hit.height / dp} dp" else null
        } }
        return small + overlapping
    }

    @Test fun `every tap target on the Collections tab and inside a collection has its own 48 dp`() {
        screens.setContent {
            if (onScreen) BrowserScreen(
                onNavigateToAlbum = { _, _ -> }, onNavigateToExplorer = {}, onNavigateToSearchPhotoDetail = { _, _ -> },
                onNavigateToPhotoDetail = { _, _ -> }, onNavigateToKeywordImages = {}, onNavigateToCastController = {}, viewModel = vm
            )
        }
        awaitText("Favorites")
        val onList = problems()

        compose.onNodeWithText("Favorites").performClick()
        awaitText("Summer trips")
        awaitText("Bake sale")
        awaitText("Class photos")
        compose.onAllNodesWithText("Class photos").let { it[it.fetchSemanticsNodes().size - 1] }.performClick()
        awaitText("Field day")
        val inCollection = problems()

        assertTrue("list: $onList\ninside the collection: $inCollection", onList.isEmpty() && inCollection.isEmpty())
    }
}
