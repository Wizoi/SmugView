package com.smugview.app.scenario

import com.smugview.app.diag.SyncKind
import com.smugview.app.ui.viewmodel.BrowserUiState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Step 3-5 (design 3.4, 7 Q8): the gallery grid belongs to one album at a time. Album A is
 * `FfHCms` (150 images, so a second page), album B is `N74KSK` (12 images, one page).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class OpenBWhileAStreamsTest {
    private lateinit var rig: ScenarioRig

    @Before fun setUp() { rig = ScenarioRig() }
    @After fun tearDown() = rig.close()

    private val vm get() = rig.viewModel
    private val aKeys get() = rig.server.imageKeysOf("FfHCms")
    private val bKeys get() = rig.server.imageKeysOf("N74KSK")
    private fun grid() = GridProbe.keys(vm)
    private val aWebUri = "https://gallery.idzifamily.com/Family/School/2026-09-01--New-School-Year"
    private val bWebUri = "https://gallery.idzifamily.com/Kentridge/Public"

    private fun openSiteAndSettle() {
        rig.passwords.savePassword("2sDN5x", "pw")
        vm.selectSite("idzifamily")
        awaitUntil("A root listing") {
            (vm.browserState.value as? BrowserUiState.Success)?.nodes?.any { it.nodeId == "2sDN5x" } == true
        }
        awaitUntil("syncs to end") {
            val runs = rig.reporter.runs.filter { it.nickname == "idzifamily" }
            runs.any { it.kind == SyncKind.GallerySync } && runs.any { it.kind == SyncKind.FolderTreeSync } &&
                runs.all { it.endedAt != null }
        }
    }

    /** Select [key] and wait until its grid has every photo and the spinner is off. */
    private fun openAndFinish(key: String, keys: List<String>) {
        vm.selectAlbum(key)
        awaitUntil("$key photos") { grid() == keys }
        awaitUntil("$key spinner off") { !vm.isBackgroundLoading.value }
    }

    @Test fun `opening B while A is still streaming leaves B alone with its own photos title and webUri`() {
        openSiteAndSettle()
        val aPage2 = rig.server.hold("start=101")
        vm.selectAlbum("FfHCms")
        assertTrue("A second page is in flight", aPage2.awaitArrived())
        awaitUntil("A first page is shown") { grid().size == 100 }

        val bPage1 = rig.server.hold("album/N74KSK!images")
        vm.selectAlbum("N74KSK")
        assertTrue("B first page is in flight", bPage1.awaitArrived())
        aPage2.release()
        Thread.sleep(500) // negative wait: A's late second page must not reach B's grid

        assertEquals("B grid holds nothing of A", emptyList<String>(), grid())
        assertTrue("B is still loading", vm.isBackgroundLoading.value)

        bPage1.release()
        awaitUntil("B photos") { grid() == bKeys }
        awaitUntil("the spinner goes off when B is done") { !vm.isBackgroundLoading.value }
        assertEquals("Public Gallery", vm.currentAlbumTitle)
        assertEquals(bWebUri, vm.currentAlbumWebUri)
        assertEquals("N74KSK", vm.currentAlbumKey.value)
    }

    @Test fun `a gallery whose metadata is slow or fails does not show the previous gallery webUri`() {
        openSiteAndSettle()
        openAndFinish("FfHCms", aKeys)
        assertEquals(aWebUri, vm.currentAlbumWebUri)

        val meta = rig.server.hold("album/N74KSK?")
        rig.server.respondWith("album/N74KSK?", 500, 1)
        vm.selectAlbum("N74KSK")
        assertTrue("B metadata is in flight", meta.awaitArrived())
        assertEquals("while B loads, A webUri is gone (R-18)", "", vm.currentAlbumWebUri)
        assertEquals("", vm.currentAlbumTitle)

        meta.release()
        awaitUntil("B photos") { grid() == bKeys }
        awaitUntil("spinner off") { !vm.isBackgroundLoading.value }
        assertEquals("metadata failed: still not A webUri", "", vm.currentAlbumWebUri)
        assertEquals("", vm.currentAlbumTitle)
    }

    @Test fun `A failing on its first page after B was opened neither empties B nor prompts for A password`() {
        openSiteAndSettle()
        val aPage1 = rig.server.hold("album/FfHCms!images")
        rig.server.respondWith("album/FfHCms!images", 401, 1)
        vm.selectAlbum("FfHCms")
        assertTrue("A first page is in flight", aPage1.awaitArrived())

        openAndFinish("N74KSK", bKeys)
        aPage1.release()
        Thread.sleep(500) // negative wait: A's late 401 must not touch B (R-11)

        assertEquals("B grid is unchanged", bKeys, grid())
        assertNull("no prompt for A password", vm.passwordPromptNode)
        assertNull("B shows no error", vm.albumLoadError.value)
        assertEquals("Public Gallery", vm.currentAlbumTitle)
    }

    /** Pin: the old page loop swallowed a page-2 failure, so this was already harmless to B. */
    @Test fun `A second page failing after B was opened leaves B alone`() {
        openSiteAndSettle()
        val aPage2 = rig.server.hold("start=101")
        rig.server.respondWith("start=101", 500, 5)
        vm.selectAlbum("FfHCms")
        assertTrue(aPage2.awaitArrived())
        openAndFinish("N74KSK", bKeys)

        aPage2.release()
        Thread.sleep(500)

        assertEquals(bKeys, grid())
        assertNull(vm.passwordPromptNode)
        assertNull(vm.albumLoadError.value)
        assertEquals(bWebUri, vm.currentAlbumWebUri)
    }

    /** R-46: the pages that arrived stay, and the album says it is incomplete. */
    @Test fun `a stream that fails partway keeps its pages and is marked incomplete`() {
        openSiteAndSettle()
        rig.server.respondWith("start=101", 500, 1)
        vm.selectAlbum("FfHCms")
        awaitUntil("A first page") { grid().size == 100 }
        awaitUntil("A spinner off") { !vm.isBackgroundLoading.value }

        assertEquals(aKeys.take(100), grid())
        assertTrue("the failure is recorded", GridProbe.error(vm) != null)
        assertEquals(false, GridProbe.complete(vm))
        assertNull("the grid still shows the photos, not the error screen", vm.albumLoadError.value)
    }

    @Test fun `selecting a partly loaded album again restarts it and finishes`() {
        openSiteAndSettle()
        rig.server.respondWith("start=101", 500, 1)
        vm.selectAlbum("FfHCms")
        awaitUntil("the partial stream ended") { grid().size == 100 && !vm.isBackgroundLoading.value }

        vm.selectAlbum("FfHCms") // what the Retry button does

        awaitUntil("all 150 photos") { grid() == aKeys }
        awaitUntil("spinner off") { !vm.isBackgroundLoading.value }
        assertEquals(true, GridProbe.complete(vm))
        assertNull(GridProbe.error(vm))
    }

    @Test fun `going back to a finished album shows it from memory without asking the server again`() {
        openSiteAndSettle()
        openAndFinish("FfHCms", aKeys)
        openAndFinish("N74KSK", bKeys)
        val before = rig.server.requestsTo("album/FfHCms").size

        vm.selectAlbum("FfHCms")

        awaitUntil("A is back at once") { grid() == aKeys }
        assertEquals("New School Year", vm.currentAlbumTitle)
        assertEquals(aWebUri, vm.currentAlbumWebUri)
        assertFalse("no spinner for an album already in memory", vm.isBackgroundLoading.value)
        Thread.sleep(300)
        assertEquals("no request for A", before, rig.server.requestsTo("album/FfHCms").size)
    }
}
