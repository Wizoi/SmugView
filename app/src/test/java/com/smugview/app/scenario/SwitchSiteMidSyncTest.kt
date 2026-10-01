package com.smugview.app.scenario

import com.smugview.app.data.repository.FakeSmugMugServer.FakeAlbum
import com.smugview.app.diag.StopReason
import com.smugview.app.diag.SyncKind
import com.smugview.app.ui.viewmodel.BrowserUiState
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
 * Step 3-2 (design 3.1, 4 "Switch A->B mid-sync", 7 Q1): tapping another site closes everything bound
 * to the old one at tap time. Site A has more than 100 galleries so its crawl has a page 2 to hold.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class SwitchSiteMidSyncTest {
    private lateinit var rig: ScenarioRig

    @Before fun setUp() { rig = ScenarioRig() }
    @After fun tearDown() = rig.close()

    private val vm get() = rig.viewModel
    private fun listedIds() = (vm.browserState.value as? BrowserUiState.Success)?.nodes?.map { it.nodeId }
    private fun aRuns() = rig.reporter.runsOf(SyncKind.GallerySync).filter { it.nickname == "idzifamily" }

    /** A's two default galleries plus 120 old public ones, so `user!albums` has a second page. */
    private fun giveSiteAMoreThanOnePage() {
        val bulk = (1..120).map {
            val n = it.toString().padStart(4, '0')
            FakeAlbum("aK$n", "nD$n", "Old gallery $n", "/Kentridge/Old-$n", rig.server.daysAgo(60), rig.server.daysAgo(60))
        }
        rig.server.albums = rig.server.albums + bulk
    }

    /** True for a request that belongs to site A (its user, its nodes, its album keys). */
    private fun isSiteA(request: String): Boolean {
        val aIds = listOf("idzifamily", "4zqWw", "2sDN5x", "P4BKB", "LCdk7F", "3BxbFF", "sXQz4G", "Wq8Rz3", "FfHCms", "N74KSK")
        return aIds.any { request.contains(it) }
    }

    /** Waits until no new request has reached the fake for [quietMs]: the in-flight A work is all blocked or done. */
    private fun awaitQuiet(quietMs: Long = 400) {
        var last = -1
        var since = System.currentTimeMillis()
        awaitUntil("requests to go quiet", 10_000) {
            val now = rig.server.requests.size
            if (now != last) { last = now; since = System.currentTimeMillis() }
            System.currentTimeMillis() - since >= quietMs
        }
    }

    @Test fun `switching site mid-sync shows only the new site and cancels the old site's work`() {
        giveSiteAMoreThanOnePage()
        val childrenGate = rig.server.hold("node/4zqWw!children")
        val pageTwoGate = rig.server.hold("start=101")
        val hubGate = rig.server.hold("idzifamily!recentimages")

        vm.selectSite("idzifamily")
        assertTrue("A's root listing is in flight", childrenGate.awaitArrived())
        assertTrue("A's crawl reached page 2", pageTwoGate.awaitArrived())
        assertTrue("A's hub load is in flight", hubGate.awaitArrived())
        awaitQuiet()

        vm.selectSite("siteb")
        val mark = rig.server.requests.size

        awaitUntil("B's root listing") { listedIds()?.contains("Hq2Lm9") == true }
        awaitUntil("B's hub albums") { vm.activeSiteAlbums.value.any { it.albumKey == "jX9wQe" } }

        // B's own sync (and the folder reload it may trigger) must be settled first, so that A's late
        // answers are the last thing to arrive and the old code's overwrite is deterministic.
        awaitUntil("B's syncs to end") {
            val bRuns = rig.reporter.runs.filter { it.nickname == "siteb" }
            bRuns.any { it.kind == SyncKind.GallerySync } && bRuns.all { it.endedAt != null }
        }
        awaitQuiet()

        // A's late answers arrive now, after B is on screen.
        childrenGate.release()
        pageTwoGate.release()
        hubGate.release()
        awaitUntil("A's crawl run to end") { aRuns().firstOrNull()?.endedAt != null }
        Thread.sleep(500) // negative wait: give any late A write time to land

        assertEquals("the listing is B's, not A's late root", listOf("Hq2Lm9"), listedIds())
        assertEquals("the hub shows B's galleries", listOf("jX9wQe"), vm.activeSiteAlbums.value.map { it.albumKey })
        assertEquals(StopReason.Cancelled, aRuns().first().stop)
        val lateA = rig.server.requests.drop(mark).filter { isSiteA(it) }
        assertTrue("no request for A starts after the switch: $lateA", lateA.isEmpty())
        val aNodeIds = setOf("4zqWw", "2sDN5x", "P4BKB", "LCdk7F", "3BxbFF", "sXQz4G", "Wq8Rz3") +
            rig.server.albums.map { it.nodeId }
        val bRows = kotlinx.coroutines.runBlocking {
            rig.dao.getCachedNodesForNickname("siteb").filter { it.nickname == "siteb" }.map { it.nodeId } +
                rig.dao.getAlbumIndex("siteb").filter { it.nickname == "siteb" }.map { it.nodeId }
        }
        assertTrue("no siteb row carries an A id: ${bRows.filter { it in aNodeIds }}", bRows.none { it in aNodeIds })
    }

    @Test fun `the back-state kept for a search jump is dropped when the site changes`() {
        vm.selectSite("idzifamily")
        awaitUntil("A's root listing") { listedIds()?.contains("3BxbFF") == true }
        val kentridge = (vm.browserState.value as BrowserUiState.Success).nodes.first { it.nodeId == "3BxbFF" }

        vm.navigateToFolderFromSearch(kentridge)
        awaitUntil("Kentridge listing") { listedIds()?.contains("sXQz4G") == true }
        assertNotNull("a search jump saves where it came from", vm.savedFolderStateBeforeSearch)

        vm.selectSite("siteb")
        awaitUntil("B's root listing") { listedIds()?.contains("Hq2Lm9") == true }
        assertNull("the saved folder state belonged to A", vm.savedFolderStateBeforeSearch)

        vm.navigateBack()
        Thread.sleep(500) // negative wait: a restore of A's folder would load asynchronously
        assertEquals("back does not bring A's folder onto B", listOf("Hq2Lm9"), listedIds())
    }

    @Test fun `switching away from a crawl and straight back runs the crawl again without the 15 minute gate`() {
        giveSiteAMoreThanOnePage()
        val pageTwoGate = rig.server.hold("start=101")

        vm.selectSite("idzifamily")
        assertTrue("A's crawl reached page 2", pageTwoGate.awaitArrived())

        vm.selectSite("siteb")
        awaitUntil("B's root listing") { listedIds()?.contains("Hq2Lm9") == true }
        awaitUntil("A's first crawl to be cancelled") { aRuns().firstOrNull()?.stop == StopReason.Cancelled }
        assertEquals("a cancelled crawl leaves no stamp", 0L, rig.syncState.getLong("lastFullCrawlAt.idzifamily"))

        pageTwoGate.release()
        vm.selectSite("idzifamily")
        awaitUntil("A's second crawl to finish") { aRuns().size >= 2 && aRuns()[1].stop == StopReason.NoNextPage }

        val second = aRuns()[1]
        assertEquals("the crawl starts again from page 1 and reads both pages", 2, second.pagesFetched)
        assertTrue("a completed crawl stamps the gate", rig.syncState.getLong("lastFullCrawlAt.idzifamily") > 0L)
        assertEquals("the second run was not gated", null, second.notes?.takeIf { it.startsWith("gated") })
    }
}
