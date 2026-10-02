package com.smugview.app.scenario

import com.smugview.app.data.db.CachedNode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.net.UnknownHostException
import java.time.OffsetDateTime

/**
 * Step 6-12 (design 3.8, Q3 (a), N6): "viewed" means the user was shown the gallery's photos, nothing less. It was written the
 * moment a gallery was SELECTED (`selectAlbum`), after a password was typed (`submitPassword`), and by `getAlbum` raising the
 * index date it is compared with, so a prompt that was dismissed, an offline open with nothing to show, or a tap on a lit
 * gallery that was never loaded all put out the dot. Now `loadAlbumPages` writes it once, after page 1 is published.
 *
 * Fixture F: Family (2sDN5x, password) -> School (P4BKB) -> FfHCms (AlbumKey; NodeID LCdk7F, ImagesLastUpdated now - 2 days, so
 * lit until viewed). N74KSK (public, 40 days old) is never lit.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class ViewedMarkScenarioTest {
    private lateinit var rig: ScenarioRig

    @Before fun setUp() { rig = ScenarioRig() }
    @After fun tearDown() = rig.close()

    private val vm get() = rig.viewModel
    private val sql get() = rig.db.openHelper.readableDatabase

    private fun lit() = runBlocking { rig.dao.getNodesWithActiveUpdates("idzifamily").first() }

    /** nodeId -> the viewed mark, as the table holds it. */
    private fun viewed(): Map<String, String> =
        sql.query("SELECT nodeId, lastViewedDateModified FROM viewed_gallery_updates").use { c ->
            buildMap { while (c.moveToNext()) put(c.getString(0), c.getString(1)) }
        }

    private fun indexIlu(albumKey: String): String? =
        sql.query("SELECT imagesLastUpdated FROM cached_albums WHERE albumKey = '$albumKey'").use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }

    private fun instant(s: String) = OffsetDateTime.parse(s).toInstant()

    private fun serverIlu(albumKey: String) = rig.server.albums.first { it.albumKey == albumKey }.imagesLastUpdated!!

    /** Every `album/...` request so far (details and images pages). */
    private fun albumRequests(): Int = synchronized(rig.server.requestLog) {
        rig.server.requestLog.count { it.url.encodedPath.contains("/album/") }
    }

    private fun requestsFor(pathEndsWith: String): Int = synchronized(rig.server.requestLog) {
        rig.server.requestLog.count { it.url.encodedPath.endsWith(pathEndsWith) }
    }

    /** The launch crawl indexed the gallery and it is lit. */
    private fun launchAndLight() {
        vm.selectSite("idzifamily")
        awaitUntil("the launch crawl lit Family's gallery", 10_000) { "LCdk7F" in lit() }
    }

    /** The rows a browse of Family -> School -> the gallery leaves behind (depth 2, AlbumKey != NodeID). */
    private fun cacheFamilyTree() = runBlocking {
        fun row(id: String, parent: String?, type: String, title: String, album: String? = null) = CachedNode(
            nodeId = id, parentNodeId = parent, type = type, title = title, description = null,
            access = if (id == "2sDN5x") "Password" else "None", passwordHint = null, uri = "/api/v2/node/$id",
            childNodesUri = null, albumUri = album, nickname = "idzifamily"
        )
        rig.dao.insertNodes(listOf(
            row("2sDN5x", "root", "Folder", "Family"),
            row("P4BKB", "2sDN5x", "Folder", "School"),
            row("LCdk7F", "P4BKB", "Album", "New School Year", "/api/v2/album/FfHCms")
        ))
    }

    /**
     * A locked gallery whose prompt is dismissed was never shown, so it stays new. Red on the old code: `selectAlbum` wrote the
     * viewed row as soon as the album was selected, before the lock was even found.
     */
    @Test fun `opening the locked School gallery and dismissing the prompt leaves no viewed row and the dot lit`() {
        rig.server.gateImages = true
        rig.server.parentsOverride = { throw IOException("lineage unreadable") } // nothing says "locked" until the photos are asked for
        launchAndLight()

        vm.selectAlbum("FfHCms")
        awaitUntil("the password prompt") { vm.passwordPromptNode != null }
        vm.dismissPasswordPrompt()
        Thread.sleep(300) // negative wait: nothing may be written late

        assertEquals("no viewed row", emptyMap<String, String>(), viewed())
        assertTrue("the dot is still lit", "LCdk7F" in lit())
    }

    /**
     * Offline with nothing cached: the load fails and shows no photo. Red on the old code: the row was already written.
     */
    @Test fun `opening a lit gallery offline with nothing cached leaves no viewed row`() {
        rig.passwords.savePassword("2sDN5x", "family-pw")
        launchAndLight()
        cacheFamilyTree() // browsed before: the node is known, so the old code could mark it
        rig.server.failWith = { UnknownHostException("offline") }

        vm.selectAlbum("FfHCms")
        awaitUntil("the load ended with a problem and no photo", 10_000) { GridProbe.error(vm) != null }
        Thread.sleep(300)

        assertEquals(emptyMap<String, String>(), viewed())
        assertTrue("the dot is still lit", "LCdk7F" in lit())
        assertTrue("and no photo was shown", GridProbe.keys(vm).isEmpty())
    }

    /** The prompt's own path: a typed password opens the gallery through the caller, and the open alone writes nothing. */
    @Test fun `a typed password that only opens the gallery writes no viewed row`() {
        launchAndLight()
        cacheFamilyTree()
        val opened = java.util.concurrent.CopyOnWriteArrayList<String>()
        val node = runBlocking { rig.repository.getNodeById("LCdk7F")!! }

        vm.requestPassword(node, onUnlocked = { opened += it })
        awaitUntil("the prompt") { vm.passwordPromptNode != null }
        vm.submitPassword("family-pw")
        awaitUntil("the caller was told to open the gallery", 10_000) { opened.isNotEmpty() }
        Thread.sleep(300)

        assertEquals(listOf("FfHCms"), opened.toList())
        assertEquals("opening is not viewing", emptyMap<String, String>(), viewed())
        assertTrue("the dot is still lit", "LCdk7F" in lit())
    }

    /**
     * A gallery left before its first page arrived was never shown. Red on the old code: the selection marked it.
     */
    @Test fun `a gallery left before its first page arrives is not marked`() {
        rig.passwords.savePassword("2sDN5x", "family-pw")
        launchAndLight()
        val gate = rig.server.hold("album/FfHCms!images")

        vm.selectAlbum("FfHCms")
        assertTrue("page 1 was asked for", gate.awaitArrived())
        vm.selectAlbum("N74KSK")
        awaitUntil("the public gallery is on screen") { GridProbe.complete(vm) == true && vm.albumState.value?.albumKey == "N74KSK" }
        gate.release()
        Thread.sleep(300)

        assertEquals("only the gallery that was shown is marked", setOf("sXQz4G"), viewed().keys)
        assertTrue("the dot is still lit", "LCdk7F" in lit())
    }

    /**
     * Green pin and the contract: an online open writes ONE row, equal to the album's own `ImagesLastUpdated`, even when the
     * index (the crawl, up to 15 minutes old) is behind it.
     */
    @Test fun `opening online writes one viewed row equal to the album's own date even when the index is older`() {
        rig.passwords.savePassword("2sDN5x", "family-pw")
        rig.server.imageCounts["FfHCms"] = 12
        launchAndLight()
        val older = rig.server.daysAgo(4)
        sql.execSQL("UPDATE cached_albums SET imagesLastUpdated = '$older' WHERE albumKey = 'FfHCms'")
        assertTrue("the index is behind the album", instant(indexIlu("FfHCms")!!).isBefore(instant(serverIlu("FfHCms"))))

        vm.selectAlbum("FfHCms")
        awaitUntil("the album loaded", 10_000) { GridProbe.complete(vm) == true }
        awaitUntil("the mark was written") { viewed().isNotEmpty() }

        val rows = viewed()
        assertEquals("one row, for the gallery's NodeID", setOf("LCdk7F"), rows.keys)
        assertEquals(instant(serverIlu("FfHCms")), instant(rows.getValue("LCdk7F")))
        awaitUntil("and the dot is out") { "LCdk7F" !in lit() }
    }

    /**
     * Reading the album's details (Hub, a bookmark, a share) is not viewing it, and must not move the date the dot compares.
     * Red on the old code: `getAlbum` raised the index date, so a gallery the crawl had not caught up with lit its dot early.
     */
    @Test fun `reading an album's details does not touch the index date`() = runBlocking {
        launchAndLight()
        val older = rig.server.daysAgo(4)
        sql.execSQL("UPDATE cached_albums SET imagesLastUpdated = '$older' WHERE albumKey = 'FfHCms'")

        rig.repository.getAlbum("FfHCms", "test-key")

        assertEquals(older, indexIlu("FfHCms"))
    }

    /**
     * A finished gallery comes back from memory with no request (the loader's LRU), unless the dot says it has grown since:
     * then it is asked again, and the new photo is there. Red on the old code: the restore never looked, and the mark put the
     * dot out over photos that were behind.
     */
    @Test fun `restoring a gallery whose date rose since asks the network and shows the new photo`() {
        rig.passwords.savePassword("2sDN5x", "family-pw")
        rig.server.imageCounts["FfHCms"] = 12
        launchAndLight()
        vm.selectAlbum("FfHCms")
        awaitUntil("FfHCms loaded", 10_000) { GridProbe.complete(vm) == true }
        awaitUntil("FfHCms marked") { viewed().isNotEmpty() }
        vm.selectAlbum("N74KSK")
        awaitUntil("N74KSK on screen and complete") { vm.albumState.value?.albumKey == "N74KSK" && GridProbe.complete(vm) == true }
        assertTrue("FfHCms is kept for a quick return", "FfHCms" in vm.albumLoader.keptKeys)

        // A photo was added; the crawl noticed (it raises the index date, which un-marks the gallery).
        val newer = rig.server.daysAgo(0)
        rig.server.albums = rig.server.albums.map { if (it.albumKey == "FfHCms") it.copy(imagesLastUpdated = newer) else it }
        rig.server.imageCounts["FfHCms"] = 13
        runBlocking { rig.dao.raiseAlbumImagesLastUpdated("FfHCms", newer) }
        assertTrue("the dot is lit again", "LCdk7F" in lit())
        val before = requestsFor("album/FfHCms!images")

        vm.selectAlbum("FfHCms")

        awaitUntil("the new photo is shown", 10_000) { "FfHCmsi013" in GridProbe.keys(vm) }
        assertTrue("the restore went to the network", requestsFor("album/FfHCms!images") > before)
        awaitUntil("and is marked once it is shown") { "LCdk7F" !in lit() }
        assertEquals(instant(newer), instant(viewed().getValue("LCdk7F")))
    }

    /** Green pin: a kept gallery that is not lit is restored with no request at all. */
    @Test fun `restoring a gallery that is not lit makes no request`() {
        rig.server.imageCounts["N74KSK"] = 12
        rig.passwords.savePassword("2sDN5x", "family-pw")
        rig.server.imageCounts["FfHCms"] = 12
        launchAndLight()
        vm.selectAlbum("N74KSK")
        awaitUntil("N74KSK complete") { vm.albumState.value?.albumKey == "N74KSK" && GridProbe.complete(vm) == true }
        vm.selectAlbum("FfHCms")
        awaitUntil("FfHCms complete") { vm.albumState.value?.albumKey == "FfHCms" && GridProbe.complete(vm) == true }
        awaitUntil("FfHCms marked") { viewed().isNotEmpty() }
        val before = albumRequests()

        vm.selectAlbum("N74KSK")
        awaitUntil("N74KSK back on screen") { vm.albumState.value?.albumKey == "N74KSK" }
        Thread.sleep(300)

        assertEquals("no album request was made", before, albumRequests())
        assertTrue(GridProbe.complete(vm) == true)
    }
}
