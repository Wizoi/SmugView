package com.smugview.app.scenario

import com.smugview.app.ui.viewmodel.BrowserUiState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Step 4-6 (design 3.3): the cache policy as the user meets it, through the production HTTP client.
 * Fixture F: Family (2sDN5x) is password-protected and, with [com.smugview.app.data.repository.FakeSmugMugServer.cookieGate],
 * `user!albums` hides its gallery (AlbumKey FfHCms, NodeID LCdk7F, ImagesLastUpdated 2 days ago, so lit until
 * viewed) until the `!unlock` session cookie is in the jar. N74KSK (public, 40 days old) is never lit.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class CachePolicyScenarioTest {
    private lateinit var rig: ScenarioRig

    @Before fun setUp() { rig = ScenarioRig(httpCache = true) }
    @After fun tearDown() = rig.close()

    private val vm get() = rig.viewModel
    private fun listedIds() = (vm.browserState.value as? BrowserUiState.Success)?.nodes?.map { it.nodeId }
    private fun lit() = runBlocking { rig.dao.getNodesWithActiveUpdates("idzifamily").first() }

    /** The Hub's `user!albums` calls: every listing that is not the crawl (which always sends no-cache). */
    private fun hubListings(): List<Request> =
        rig.server.albumsRequests().filter { it.url.encodedPath.contains("idzifamily") && it.header("Cache-Control") != "no-cache" }

    private fun cacheControls(pathEndsWith: String): List<String?> = synchronized(rig.server.requestLog) {
        rig.server.requestLog.filter { it.url.encodedPath.endsWith(pathEndsWith) }.map { it.header("Cache-Control") }
    }

    /**
     * The bug (R-35, the Hub right after an unlock): the Hub asked `user!albums` at launch, anonymously, and the
     * OkHttp cache kept that answer for 5 minutes. After the unlock the Hub asked again, got the cached
     * anonymous listing and showed no gallery under Family.
     */
    @Test fun `the Hub after an unlock reaches the server and lists the gallery the unlock revealed`() {
        rig.server.cookieGate = true
        vm.selectSite("idzifamily")
        awaitUntil("A's root listing") { listedIds()?.contains("2sDN5x") == true }
        awaitUntil("the launch Hub finished") { !vm.isActiveSiteDetailsLoading.value && hubListings().isNotEmpty() }
        assertTrue("the launch Hub is anonymous: no gallery under Family", vm.activeSiteAlbums.value.none { it.albumKey == "FfHCms" })
        vm.navigateToChildFolder(runBlocking { rig.repository.getNodeById("2sDN5x")!! })
        awaitUntil("the prompt for Family") { vm.passwordPromptNode?.nodeId == "2sDN5x" }

        vm.submitPassword("family-pw")

        awaitUntil("the Hub lists the revealed gallery", 10_000) { vm.activeSiteAlbums.value.any { it.albumKey == "FfHCms" } }
        assertEquals("the Hub request after the unlock reached the server", 2, hubListings().size)
    }

    /** A lit gallery opens from the network: the dot says the cached copy is behind. */
    @Test fun `a lit gallery asks for the network on its details and every images page`() {
        rig.passwords.savePassword("2sDN5x", "family-pw") // Family is locked; the saved password opens it at launch
        vm.selectSite("idzifamily")
        awaitUntil("the launch crawl lit Family's gallery", 10_000) { "LCdk7F" in lit() }

        vm.selectAlbum("FfHCms")

        awaitUntil("the album loaded", 10_000) { com.smugview.app.scenario.GridProbe.complete(vm) == true }
        assertEquals(listOf<String?>("no-cache"), cacheControls("album/FfHCms"))
        assertEquals("both image pages (150 images, 100 per page)", listOf<String?>("no-cache", "no-cache"), cacheControls("album/FfHCms!images"))
        awaitUntil("opening it still clears the dot") { "LCdk7F" !in lit() }
    }

    /** Green pin: a gallery that is not lit follows the normal policy (no `no-cache`). */
    @Test fun `a gallery that is not lit does not force the network`() {
        vm.selectSite("idzifamily")
        awaitUntil("the launch crawl indexed the public gallery", 10_000) {
            runBlocking { rig.dao.getAlbumIndex("idzifamily") }.any { it.albumKey == "N74KSK" }
        }
        assertTrue("N74KSK (40 days old) is not lit", "sXQz4G" !in lit())

        vm.selectAlbum("N74KSK")

        awaitUntil("the album loaded", 10_000) { com.smugview.app.scenario.GridProbe.complete(vm) == true }
        assertTrue("no no-cache on its details: ${cacheControls("album/N74KSK")}", cacheControls("album/N74KSK").none { it == "no-cache" })
        assertTrue("no no-cache on its images: ${cacheControls("album/N74KSK!images")}", cacheControls("album/N74KSK!images").none { it == "no-cache" })
    }

    /** Retry on a failed gallery always goes to the network. */
    @Test fun `selecting a gallery with force asks for the network even when it is not lit`() {
        vm.selectSite("idzifamily")
        awaitUntil("the launch crawl indexed the public gallery", 10_000) {
            runBlocking { rig.dao.getAlbumIndex("idzifamily") }.any { it.albumKey == "N74KSK" }
        }

        vm.selectAlbum("N74KSK", force = true)

        awaitUntil("the album loaded", 10_000) { com.smugview.app.scenario.GridProbe.complete(vm) == true }
        assertEquals(listOf<String?>("no-cache"), cacheControls("album/N74KSK"))
        assertTrue(cacheControls("album/N74KSK!images").all { it == "no-cache" })
    }
}
