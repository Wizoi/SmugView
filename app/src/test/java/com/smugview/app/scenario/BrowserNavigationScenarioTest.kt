package com.smugview.app.scenario

import com.smugview.app.diag.SyncKind
import com.smugview.app.ui.viewmodel.BrowserTab
import com.smugview.app.ui.viewmodel.BrowserUiState
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Step 3-3 (design 3.2, 7 Q3): one BrowserNavigator owns the Folders tab. Topology of the fake:
 * root 4zqWw -> Family 2sDN5x (password) -> School P4BKB -> gallery node LCdk7F / AlbumKey FfHCms.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class BrowserNavigationScenarioTest {
    private lateinit var rig: ScenarioRig

    @Before fun setUp() { rig = ScenarioRig() }
    @After fun tearDown() = rig.close()

    private val vm get() = rig.viewModel
    private fun listedIds() = (vm.browserState.value as? BrowserUiState.Success)?.nodes?.map { it.nodeId }
    private fun stackIds() = vm.folderNavigationStack.map { it.nodeId }

    /** Site A selected, root listed, and every background run finished, so the tree is cached. */
    private fun openSiteAndSettle() {
        rig.passwords.savePassword("2sDN5x", "pw")
        vm.selectSite("idzifamily")
        awaitUntil("A's root listing") { listedIds()?.contains("2sDN5x") == true }
        awaitUntil("A's syncs to end") {
            val runs = rig.reporter.runs.filter { it.nickname == "idzifamily" }
            runs.any { it.kind == SyncKind.GallerySync } && runs.any { it.kind == SyncKind.FolderTreeSync } &&
                runs.all { it.endedAt != null }
        }
    }

    @Test fun `back while a child is still loading lists the folder you went back to`() {
        openSiteAndSettle()
        val family = runBlocking { rig.repository.getNodeById("2sDN5x")!! }
        val school = runBlocking { rig.repository.getNodeById("P4BKB")!! }
        vm.navigateToChildFolder(family)
        awaitUntil("Family's listing") { listedIds()?.contains("P4BKB") == true }

        // The tree sync cached School's children; un-cache them so opening School really waits on the network.
        runBlocking { rig.dao.deleteRealChildren("P4BKB") }
        val gate = rig.server.hold("node/P4BKB!children")
        vm.navigateToChildFolder(school)
        assertTrue("School's listing is in flight", gate.awaitArrived())
        awaitUntil("School is on the stack") { stackIds() == listOf("2sDN5x", "P4BKB") }

        vm.navigateBack()
        awaitUntil("back lands on Family") { stackIds() == listOf("2sDN5x") && listedIds() == listOf("P4BKB") }
        gate.release()
        Thread.sleep(500) // negative wait: School's late answer must not overwrite Family's listing

        assertEquals("the listing is Family's children, not School's late answer", listOf("P4BKB"), listedIds())
        assertEquals(listOf("2sDN5x"), stackIds())
        assertEquals("2sDN5x", vm.currentFolderId)
    }

    @Test fun `opening a gallery from search moves the breadcrumb and the listing to its folder together`() {
        openSiteAndSettle()
        assertEquals("the Folders tab starts at the root", emptyList<String>(), stackIds())

        vm.selectAlbum("FfHCms")

        awaitUntil("the listing moved to School") { listedIds() == listOf("LCdk7F") }
        assertEquals(listOf("2sDN5x", "P4BKB"), stackIds())
        assertEquals("P4BKB", vm.currentFolderId)
    }

    @Test fun `a collections shortcut builds the breadcrumb from the lineage and lists the folder`() {
        openSiteAndSettle()

        vm.openFolderShortcut("P4BKB")

        awaitUntil("School is listed") { listedIds() == listOf("LCdk7F") }
        assertEquals(listOf("2sDN5x", "P4BKB"), stackIds())
        assertEquals(BrowserTab.Folders, vm.activeTab.value)
    }

    @Test fun `back from a search jump returns to the search tab and the folder you left`() {
        openSiteAndSettle()
        val kentridge = (vm.browserState.value as BrowserUiState.Success).nodes.first { it.nodeId == "3BxbFF" }
        vm.setActiveTab(BrowserTab.Search)

        vm.navigateToFolderFromSearch(kentridge)
        awaitUntil("Kentridge is listed") { listedIds()?.contains("sXQz4G") == true }
        assertEquals(BrowserTab.Folders, vm.activeTab.value)
        assertNotNull(vm.savedFolderStateBeforeSearch)

        assertTrue(vm.navigateBack())
        awaitUntil("the root is listed again") { listedIds()?.contains("2sDN5x") == true }
        assertEquals(BrowserTab.Search, vm.activeTab.value)
        assertNull(vm.savedFolderStateBeforeSearch)
        assertEquals(emptyList<String>(), stackIds())
    }
}
