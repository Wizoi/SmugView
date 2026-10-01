package com.smugview.app.scenario

import com.smugview.app.diag.StopReason
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

/**
 * Step 3-8 (design 3.6): an unlock that lands while a crawl is in flight. Fixture F is cookie-gated:
 * without the `!unlock` session cookie `user!albums` lists only the public gallery (N74KSK) and hides
 * Family's gallery (NodeID LCdk7F, AlbumKey FfHCms).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class UnlockRacingCrawlTest {
    private lateinit var rig: ScenarioRig

    @Before fun setUp() { rig = ScenarioRig() }
    @After fun tearDown() = rig.close()

    private val vm get() = rig.viewModel
    private fun listedIds() = (vm.browserState.value as? BrowserUiState.Success)?.nodes?.map { it.nodeId }
    private fun aRuns() = rig.reporter.runsOf(SyncKind.GallerySync).filter { it.nickname == "idzifamily" }
    private fun indexKeys() = runBlocking { rig.dao.getAlbumIndex("idzifamily") }.map { it.albumKey }

    /**
     * Launch crawl in flight (its page 1 was answered by the server before the unlock, so it is the
     * anonymous listing), then the owner types Family's password. The crawl's write must not prune the
     * gallery the unlock just made visible, and one resync redoes the crawl with the session.
     */
    @Test fun `an unlock while the launch crawl is in flight does not prune what it revealed and resyncs once`() {
        rig.server.cookieGate = true
        val launchPage = rig.server.hold("user/idzifamily!albums", answerFirst = true)

        vm.selectSite("idzifamily")
        assertTrue("the launch crawl's page 1 is in flight", launchPage.awaitArrived())
        awaitUntil("A's root listing") { listedIds()?.contains("2sDN5x") == true }
        vm.navigateToChildFolder(runBlocking { rig.repository.getNodeById("2sDN5x")!! })
        awaitUntil("the prompt for Family") { vm.passwordPromptNode?.nodeId == "2sDN5x" }

        vm.submitPassword("family-pw")

        awaitUntil("the subtree index merged the hidden gallery") { "FfHCms" in indexKeys() }
        launchPage.release()
        awaitUntil("two crawls ended", 10_000) { aRuns().size >= 2 && aRuns().all { it.endedAt != null } }

        val first = aRuns()[0].notes.orEmpty()
        assertTrue("the launch crawl did not prune the revealed gallery: $first", first.contains("pruned=0"))
        assertTrue("the revealed gallery is in the index", "FfHCms" in indexKeys())
        assertEquals("the resync crawled with the session", true, aRuns()[1].notes.orEmpty().startsWith("complete=true"))
        Thread.sleep(500) // negative wait: no third crawl
        assertEquals("one launch crawl and one resync", 2, aRuns().size)
        assertEquals(setOf("FfHCms", "N74KSK"), indexKeys().toSet())
    }

    /** The resync belongs to the site session: switching site while it is in flight cancels it. */
    @Test fun `switching site during the resync cancels it`() {
        rig.server.cookieGate = true
        vm.selectSite("idzifamily")
        awaitUntil("A's root listing") { listedIds()?.contains("2sDN5x") == true }
        awaitUntil("the launch sync ended") { aRuns().size == 1 && aRuns()[0].endedAt != null }
        awaitUntil("the launch sync's tree walk ended") {
            rig.reporter.runsOf(SyncKind.FolderTreeSync).let { it.isNotEmpty() && it.all { r -> r.endedAt != null } }
        }
        val resyncPage = rig.server.hold("user/idzifamily!albums")
        vm.navigateToChildFolder(runBlocking { rig.repository.getNodeById("2sDN5x")!! })
        awaitUntil("the prompt for Family") { vm.passwordPromptNode?.nodeId == "2sDN5x" }

        vm.submitPassword("family-pw")
        assertTrue("the resync's page 1 is held", resyncPage.awaitArrived())

        vm.selectSite("siteb")

        awaitUntil("the resync is cancelled") { aRuns().size == 2 && aRuns()[1].stop == StopReason.Cancelled }
    }

    /** Launch unlocks a saved password (bumping the epoch) and crawls: no second crawl for that bump. */
    @Test fun `a saved password unlocked at launch does not start a resync on top of the launch crawl`() {
        rig.server.cookieGate = true
        rig.passwords.savePassword("2sDN5x", "family-pw")

        vm.selectSite("idzifamily")
        awaitUntil("A's root listing") { listedIds()?.contains("2sDN5x") == true }
        awaitUntil("the launch sync ended", 10_000) {
            aRuns().isNotEmpty() && aRuns().all { it.endedAt != null } &&
                rig.reporter.runsOf(SyncKind.FolderTreeSync).all { it.endedAt != null }
        }
        Thread.sleep(500) // negative wait: a resync would start now

        assertEquals("only the launch crawl", 1, aRuns().size)
        assertEquals(setOf("FfHCms", "N74KSK"), indexKeys().toSet())
    }
}
