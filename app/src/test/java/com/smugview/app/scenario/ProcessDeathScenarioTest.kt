package com.smugview.app.scenario

import android.os.Bundle
import androidx.lifecycle.SavedStateHandle
import com.smugview.app.data.repository.UnlockManager.Access
import com.smugview.app.ui.viewmodel.BrowserTab
import com.smugview.app.ui.viewmodel.BrowserUiState
import com.smugview.app.ui.viewmodel.SmugViewModel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

/**
 * Step 3-9 (design 3.2 "Saved state (R-17)", 4 "Process death", Q4): what a killed process hands back
 * through the saved-state Bundle. Fixture F: root 4zqWw -> Family 2sDN5x (Password) -> School P4BKB ->
 * gallery LCdk7F. The new process starts with the network off for folder listings, an empty
 * repository and an empty session, over the same database, preferences and saved passwords.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class ProcessDeathScenarioTest {
    private lateinit var rig: ScenarioRig

    @Before fun setUp() { rig = ScenarioRig() }
    @After fun tearDown() = rig.close()

    private val vm get() = rig.viewModel
    private fun SmugViewModel.listed() = (browserState.value as? BrowserUiState.Success)?.nodes?.map { it.nodeId }
    private fun SmugViewModel.stackIds() = folderNavigationStack.map { it.nodeId }

    /** Root -> Family -> School in the running process, with a query typed and searched. */
    private fun browseToSchool() {
        rig.passwords.savePassword("2sDN5x", "family-pw")
        vm.selectSite("idzifamily")
        awaitUntil("A's root listing") { vm.listed()?.contains("2sDN5x") == true }
        awaitUntil("Family is unlocked at launch") { rig.repository.unlocks.access.value["2sDN5x"] == Access.Session }
        vm.navigateToChildFolder(runBlocking { rig.repository.getNodeById("2sDN5x")!! })
        awaitUntil("Family's listing") { vm.listed()?.contains("P4BKB") == true }
        vm.navigateToChildFolder(runBlocking { rig.repository.getNodeById("P4BKB")!! })
        awaitUntil("School's listing") { vm.listed()?.contains("LCdk7F") == true }
        assertEquals(listOf("2sDN5x", "P4BKB"), vm.stackIds())
    }

    /** What the system stores when it kills the process, and hands to the next one. */
    private fun savedBundle(): Bundle = rig.savedState.savedStateProvider().saveState()

    private fun restartOffline(bundle: Bundle?, edit: (SavedStateHandle) -> Unit = {}): SmugViewModel {
        rig.server.childrenOverride = { _, _ -> throw IOException("offline") }
        return rig.restartProcess(SavedStateHandle.createHandle(bundle, null).also(edit))
    }

    @Test fun `after process death the Folders tab is back in School from the cache, with the query and the tab`() {
        browseToSchool()
        vm.performSearch("class")
        vm.setActiveTab(BrowserTab.Search)
        awaitUntil("the typed query is kept") { vm.searchQuery == "class" }
        vm.setActiveTab(BrowserTab.Folders)
        val bundle = savedBundle()

        val restored = restartOffline(bundle)

        awaitUntil("the stack is rebuilt", 10_000) { restored.stackIds() == listOf("2sDN5x", "P4BKB") }
        awaitUntil("School's cached listing", 10_000) { restored.listed()?.contains("LCdk7F") == true }
        assertEquals(BrowserTab.Folders, restored.activeTab.value)
        assertEquals("class", restored.searchQuery)
        assertEquals("P4BKB", restored.currentFolderId)
        Thread.sleep(500) // negative wait: the failing refresh must not replace the cached listing
        assertTrue("still listed offline", restored.listed()?.contains("LCdk7F") == true)
    }

    @Test fun `the tab and the back-to-search marker survive too, results do not`() {
        browseToSchool()
        vm.performSearch("class")
        awaitUntil("a search ran") { vm.searchQuery == "class" }
        val school = runBlocking { rig.repository.getNodeById("P4BKB")!! }
        vm.navigateToFolderFromSearch(school) // Back should return to the Search tab
        awaitUntil("on Folders, School open") { vm.activeTab.value == BrowserTab.Folders && vm.savedFolderStateBeforeSearch != null }
        vm.setActiveTab(BrowserTab.Search)
        val bundle = savedBundle()

        val restored = restartOffline(bundle)

        awaitUntil("the stack is rebuilt", 10_000) { restored.stackIds().lastOrNull() == "P4BKB" }
        assertEquals(BrowserTab.Search, restored.activeTab.value)
        awaitUntil("the marker is rebuilt") { restored.savedFolderStateBeforeSearch != null }
        assertEquals("class", restored.searchQuery)
        assertEquals(
            "results are not saved: the new process has run no search",
            com.smugview.app.ui.viewmodel.SearchUiState.Idle, restored.searchState.value
        )
        assertTrue("no tag selection", restored.selectedTags.value.isEmpty())
    }

    @Test fun `a saved folder whose row is gone opens the root`() {
        browseToSchool()
        val bundle = savedBundle()
        runBlocking { rig.db.openHelper.writableDatabase.execSQL("DELETE FROM cached_nodes WHERE nodeId = '2sDN5x'") }

        val restored = restartOffline(bundle)

        // Family's own row is gone, so the cached root listing no longer holds it; Kentridge is still there.
        awaitUntil("the root listing", 10_000) { restored.listed()?.contains("3BxbFF") == true }
        assertEquals("no row, no breadcrumb", emptyList<String>(), restored.stackIds())
        assertEquals("4zqWw", restored.currentFolderId)
    }

    @Test fun `a saved stack belonging to another site is ignored`() {
        browseToSchool()
        val bundle = savedBundle()

        // The active site, per prefs, is idzifamily.
        val restored = restartOffline(bundle) { it["nav.nickname"] = "siteb" }

        awaitUntil("the root listing", 10_000) { restored.listed()?.contains("2sDN5x") == true }
        assertEquals(emptyList<String>(), restored.stackIds())
        assertEquals("4zqWw", restored.currentFolderId)
        assertEquals("the other site's query is not shown either", "", restored.searchQuery)
    }

    @Test fun `no saved state means a normal launch at the root`() {
        browseToSchool()
        val restored = restartOffline(null)

        awaitUntil("the root listing", 10_000) { restored.listed()?.contains("2sDN5x") == true }
        assertEquals(emptyList<String>(), restored.stackIds())
        assertNull(restored.savedFolderStateBeforeSearch)
        assertEquals(BrowserTab.Folders, restored.activeTab.value)
    }
}
