package com.smugview.app.scenario

import com.smugview.app.data.db.CachedNode
import com.smugview.app.data.repository.UnlockManager.Access
import com.smugview.app.diag.SyncKind
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
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Step 3-7 (design 3.4, Q5, Q7, R-24): one password prompt owned by UnlockManager.
 * Fixture F: site A (idzifamily), password folders Family 2sDN5x (-> School P4BKB -> gallery NodeID
 * LCdk7F / AlbumKey FfHCms) and Work Wq8Rz3.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class PasswordPromptScenarioTest {
    private lateinit var rig: ScenarioRig

    @Before fun setUp() { rig = ScenarioRig() }
    @After fun tearDown() = rig.close()

    private val vm get() = rig.viewModel
    private val unlocks get() = rig.repository.unlocks
    private fun listedIds() = (vm.browserState.value as? BrowserUiState.Success)?.nodes?.map { it.nodeId }
    private fun node(id: String) = runBlocking { rig.repository.getNodeById(id)!! }

    private fun settleSiteA() {
        vm.selectSite("idzifamily")
        awaitUntil("A's root listing") { listedIds()?.contains("2sDN5x") == true }
        awaitUntil("A's syncs to end") {
            val runs = rig.reporter.runs.filter { it.nickname == "idzifamily" }
            // The tree walk starts only after the crawl run ends, so wait for its run too: it lists Work (and 401s).
            runs.isNotEmpty() && runs.all { it.endedAt != null } && runs.any { it.kind == SyncKind.FolderTreeSync }
        }
    }

    /**
     * Two roots, one bad saved password. Work's saved password is rejected in the background: that
     * marks Work Invalid and nothing else. Tapping Work asks for a password (no request to Work's
     * children first) and keeps the saved one until the user's own attempt is rejected too; Family,
     * the other root, is untouched throughout.
     */
    @Test fun `a wrong password on one of several roots touches only that root and is deleted only at the prompt`() {
        rig.server.listSecondPasswordRoot()
        rig.passwords.savePassword("2sDN5x", "family-pw")
        rig.passwords.savePassword("Wq8Rz3", "stale-work-pw")
        rig.server.respondWith("Wq8Rz3!unlock", 401, 1000)
        settleSiteA()
        awaitUntil("Work is Invalid, Family is Session") {
            unlocks.access.value["Wq8Rz3"] == Access.Invalid && unlocks.access.value["2sDN5x"] == Access.Session
        }
        assertEquals("a background 401 deletes nothing", "stale-work-pw", rig.passwords.getPassword("Wq8Rz3"))
        assertEquals("family-pw", rig.passwords.getPassword("2sDN5x"))
        val workChildrenBefore = rig.server.requestsTo("node/Wq8Rz3!children").size

        vm.navigateToChildFolder(node("Wq8Rz3"))

        awaitUntil("the prompt for Work") { vm.passwordPromptNode?.nodeId == "Wq8Rz3" }
        Thread.sleep(300) // negative wait: nothing may delete or fetch behind the prompt
        assertEquals("the saved password survives until the prompt rejects one", "stale-work-pw", rig.passwords.getPassword("Wq8Rz3"))
        assertEquals("no request to Work's children before asking", workChildrenBefore, rig.server.requestsTo("node/Wq8Rz3!children").size)
        assertEquals("family-pw", rig.passwords.getPassword("2sDN5x"))
        assertEquals(Access.Session, unlocks.access.value["2sDN5x"])

        vm.submitPassword("also-wrong")

        awaitUntil("the prompt reports the wrong password") { vm.passwordError == "Incorrect password" }
        assertNotNull("the prompt stays open", vm.passwordPromptNode)
        awaitUntil("the rejected password is deleted at the prompt") { rig.passwords.getPassword("Wq8Rz3") == null }
        assertEquals("the other root keeps its password", "family-pw", rig.passwords.getPassword("2sDN5x"))
        assertEquals(Access.Session, unlocks.access.value["2sDN5x"])
    }

    /**
     * R-24: a gallery tapped from Search under a locked password folder. The prompt is for the ROOT
     * (Family); after a good password the TARGET opens (the caller's navigation gets its AlbumKey, once)
     * and the gallery is NOT marked viewed (6-12: opening is not viewing; the load marks it once page 1 is shown).
     */
    @Test fun `a gallery opened from Search under a locked folder prompts for the root then opens the target without marking it viewed`() {
        settleSiteA()
        awaitUntil("the album index has the gallery") { runBlocking { rig.dao.getGalleryIlusAtOrBelow("LCdk7F") }.isNotEmpty() }
        // What Search hands over: the gallery by NodeID, its AlbumKey only in albumUri, access unknown.
        val fromSearch = CachedNode(
            nodeId = "LCdk7F", parentNodeId = "P4BKB", type = "Album", title = "Class Photos", description = null,
            access = null, passwordHint = null, uri = "/api/v2/node/LCdk7F", childNodesUri = null,
            albumUri = "/api/v2/album/FfHCms"
        )
        val opened = CopyOnWriteArrayList<String>()

        vm.checkAndNavigateToAlbum(fromSearch) { opened += it }

        awaitUntil("the prompt for the Family root") { vm.passwordPromptNode?.nodeId == "2sDN5x" }
        Thread.sleep(300)
        assertTrue("nothing opened behind the prompt", opened.isEmpty())
        assertEquals("nothing is saved before the user types it", emptyMap<String, String>(), rig.passwords.all())

        vm.submitPassword("family-pw")

        awaitUntil("the target opens") { opened.isNotEmpty() }
        Thread.sleep(500) // negative wait: no second navigation
        assertEquals(listOf("FfHCms"), opened.toList())
        assertNull(vm.passwordPromptNode)
        assertEquals("saved under the root's key only", mapOf("2sDN5x" to "family-pw"), rig.passwords.all())
        assertEquals(Access.Session, unlocks.access.value["2sDN5x"])
        val viewed = rig.db.openHelper.readableDatabase
            .query("SELECT nodeId FROM viewed_gallery_updates").use { c ->
                generateSequence { if (c.moveToNext()) c.getString(0) else null }.toList()
            }
        assertEquals("a typed password opens the gallery; it does not mark it viewed (6-12)", emptyList<String>(), viewed)
    }

    /**
     * Q5 (a): when the phone's secure storage isn't working, the prompt says once that the password will be asked for again. A phone
     * whose storage works never shows it.
     */
    @Test fun `the prompt says PASSWORD_NOT_KEPT once when secure storage is broken, and never when it works`() {
        settleSiteA()
        vm.navigateToChildFolder(node("2sDN5x"))
        awaitUntil("the prompt for Family") { vm.passwordPromptNode?.nodeId == "2sDN5x" }
        assertNull("secure storage works: no note", vm.passwordNotKeptNote)
        vm.dismissPasswordPrompt()

        rig.passwords.keptOnlyThisSessionFlow.value = true
        vm.navigateToChildFolder(node("2sDN5x"))
        awaitUntil("the prompt for Family again") { vm.passwordPromptNode?.nodeId == "2sDN5x" }
        assertEquals(
            "This phone's secure storage isn't working, so this password will be asked for again next time SmugView starts.",
            vm.passwordNotKeptNote
        )
        vm.dismissPasswordPrompt()

        vm.navigateToChildFolder(node("2sDN5x"))
        awaitUntil("the prompt for Family a third time") { vm.passwordPromptNode?.nodeId == "2sDN5x" }
        assertNull("once: it was shown already", vm.passwordNotKeptNote)
    }
}
