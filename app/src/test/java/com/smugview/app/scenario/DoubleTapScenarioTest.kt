package com.smugview.app.scenario

import com.smugview.app.data.db.CachedNode
import com.smugview.app.diag.SyncKind
import com.smugview.app.ui.viewmodel.BrowserUiState
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

/**
 * Step 3-4 (design 3.3, 7 Q4/Q5): a double tap is one navigation. The folder push is idempotent
 * (`Child(node)` is a no-op when the node is already on top or an identical Child is pending), and a
 * gallery tap whose pre-flight is still in flight is not started twice.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class DoubleTapScenarioTest {
    private lateinit var rig: ScenarioRig

    @Before fun setUp() { rig = ScenarioRig() }
    @After fun tearDown() = rig.close()

    private val vm get() = rig.viewModel
    private fun listedIds() = (vm.browserState.value as? BrowserUiState.Success)?.nodes?.map { it.nodeId }
    private fun stackIds() = readState { vm.folderNavigationStack.map { it.nodeId } }

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

    private fun kentridge(): CachedNode = runBlocking { rig.repository.getNodeById("3BxbFF")!! }

    /**
     * Pin: the access walk of a folder is held, the folder is tapped twice, and the walk is released.
     * Latest-wins (3-3) already cancels the first tap; the pending guard also stops the second tap from
     * restarting the walk, so the server sees one `!parents` request.
     */
    @Test fun `two quick taps on a folder push it once and walk its access once`() {
        openSiteAndSettle()
        val gate = rig.server.hold("node/3BxbFF!parents")
        val before = rig.server.requestsTo("node/3BxbFF!parents").size

        vm.navigateToChildFolder(kentridge())
        assertTrue("the access walk is in flight", gate.awaitArrived())
        vm.navigateToChildFolder(kentridge())
        Thread.sleep(300) // negative wait: the second tap must not start a second walk
        assertEquals("one walk in flight, not one per tap", 1, rig.server.requestsTo("node/3BxbFF!parents").size - before)
        gate.release()

        awaitUntil("Kentridge is listed") { listedIds()?.contains("sXQz4G") == true }
        Thread.sleep(300)
        assertEquals(listOf("3BxbFF"), stackIds())
    }

    @Test fun `tapping the folder you are already in does not push it again`() {
        openSiteAndSettle()
        vm.navigateToChildFolder(kentridge())
        awaitUntil("Kentridge is listed") { stackIds() == listOf("3BxbFF") && listedIds()?.contains("sXQz4G") == true }

        vm.navigateToChildFolder(kentridge()) // a late second tap, after the first push landed
        Thread.sleep(500)

        assertEquals(listOf("3BxbFF"), stackIds())
        assertEquals("3BxbFF", vm.currentFolderId)
        assertEquals(listOf("sXQz4G"), listedIds())
    }

    /**
     * The pre-flight (3 s timeout) is held; two taps on the same gallery must start one run. The
     * gallery is node sXQz4G / AlbumKey N74KSK with its access unknown, so the pre-flight really runs.
     */
    @Test fun `two taps on a gallery whose pre-flight is in flight navigate once`() {
        val node = CachedNode(
            nodeId = "sXQz4G", parentNodeId = "3BxbFF", type = "Album", title = "Public Gallery", description = null,
            access = null, passwordHint = null, uri = "/api/v2/node/sXQz4G", childNodesUri = null,
            albumUri = "/api/v2/album/N74KSK"
        )
        val gate = rig.server.hold("album/N74KSK?")
        val navigations = AtomicInteger(0)
        val keys = java.util.Collections.synchronizedList(mutableListOf<String>())

        vm.checkAndNavigateToAlbum(node) { navigations.incrementAndGet(); keys += it }
        assertTrue("the pre-flight is in flight", gate.awaitArrived())
        vm.checkAndNavigateToAlbum(node) { navigations.incrementAndGet(); keys += it }
        Thread.sleep(300)
        gate.release()

        awaitUntil("a navigation") { navigations.get() >= 1 }
        Thread.sleep(500) // negative wait: no second navigation
        assertEquals(listOf("N74KSK"), keys.toList())
        assertEquals(1, navigations.get())
    }
}
