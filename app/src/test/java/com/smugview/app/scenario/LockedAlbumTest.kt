package com.smugview.app.scenario

import com.google.gson.Gson
import com.smugview.app.data.api.AlbumImagesResponse
import com.smugview.app.data.api.AlbumResponse
import com.smugview.app.data.cast.CastManager
import com.smugview.app.data.db.CollectionBookmark
import com.smugview.app.data.repository.AlbumLockedException
import com.smugview.app.ui.viewmodel.CastController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Step 4-8 (design 3.4, Q5 (a), R-32): a locked gallery is not an empty one. `!images` of a locked gallery
 * is `200`, no `AlbumImage`, `Pages.Total` 0 (P7): Gson gives `emptyList()` (never null, so the old
 * `images == null` checks were dead) and the screens showed an empty, complete grid or cast nothing.
 * The locked signal is the album's own `ResponseLevel == "Password"`.
 *
 * Fixture F: Family (2sDN5x, password) holds the gallery FfHCms (NodeID LCdk7F, AlbumKey != NodeID);
 * with `gateImages` it answers the locked shape until the unlock cookie exists. N74KSK is public.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class LockedAlbumTest {
    private lateinit var rig: ScenarioRig
    private val messages = CopyOnWriteArrayList<String>()

    @Before fun setUp() {
        rig = ScenarioRig()
        rig.server.gateImages = true
    }
    @After fun tearDown() = rig.close()

    private fun fixture(name: String): String =
        LockedAlbumTest::class.java.classLoader!!.getResourceAsStream("api-contract/$name")!!.reader().use { it.readText() }

    private fun lockedAlbumError(block: suspend () -> Any?): AlbumLockedException? =
        try { runBlocking { block() }; null } catch (e: AlbumLockedException) { e }

    // --- the payloads (pins: the premise of the fix, green with or without it) ---

    /** The real locked `!images` (P7, 4-0 fixture) parses to an EMPTY LIST: the old `== null` check could never fire. */
    @Test fun `the real locked images payload parses to an empty list and Total 0, not null`() {
        val locked = Gson().fromJson(fixture("album-locked-images.json"), AlbumImagesResponse::class.java)

        assertEquals(emptyList<Any>(), locked.response.images)
        assertEquals(0, locked.response.pages?.total)
    }

    @Test fun `the real locked album says ResponseLevel Password`() {
        val album = Gson().fromJson(fixture("album-locked.json"), AlbumResponse::class.java).response.album

        assertEquals("Password", album.responseLevel)
        assertTrue(album.isLocked)
    }

    // --- the repository ---

    /** Red on the old code: it returned an empty list, silently. */
    @Test fun `getAllAlbumImages of a locked gallery throws AlbumLockedException`() {
        val e = lockedAlbumError { rig.repository.getAllAlbumImages("FfHCms", "test-key") }

        assertNotNull("getAllAlbumImages answered instead of throwing", e)
        assertEquals("FfHCms", e!!.albumKey)
        assertFalse(e.unlockPending)
        assertEquals("This gallery needs its password. Open it once to unlock it.", e.message)
    }

    @Test fun `page 1 of a locked gallery throws AlbumLockedException`() {
        val e = lockedAlbumError { rig.repository.getAlbumImagesPage("FfHCms", "test-key") }

        assertNotNull("getAlbumImagesPage answered instead of throwing", e)
    }

    /** A gallery with no photos at all is not locked ("Public"): it stays an empty grid. */
    @Test fun `a public gallery that really is empty stays empty`() = runBlocking {
        rig.server.imageCounts["N74KSK"] = 0

        val page = rig.repository.getAlbumImagesPage("N74KSK", "test-key")
        val all = rig.repository.getAllAlbumImages("N74KSK", "test-key")

        assertTrue(page.response.images.isNullOrEmpty())
        assertEquals(emptyList<Any>(), all)
    }

    /** With the session the same gallery lists its photos and the locked check is never reached. */
    @Test fun `after the unlock the gallery lists its photos`() = runBlocking {
        rig.passwords.savePassword("2sDN5x", "family-pw")

        val page = rig.repository.getAlbumImagesPage("FfHCms", "test-key", password = "family-pw")

        assertEquals(100, page.response.images?.size)
    }

    /**
     * A saved password whose use is inconclusive (503 on the unlock): the gallery is still locked, but the
     * password is not known to be wrong, so the exception says "pending" and nothing is deleted. SmugMug is
     * failing, not the device: the text says SmugMug is busy.
     */
    @Test fun `a saved password whose unlock is transient throws pending and keeps the password`() {
        rig.passwords.savePassword("2sDN5x", "family-pw")
        rig.server.unlockCode = 503

        val e = lockedAlbumError { rig.repository.getAlbumImagesPage("FfHCms", "test-key", password = "family-pw") }

        assertNotNull(e)
        assertTrue("the unlock could not be tried, not refused", e!!.unlockPending)
        assertEquals("SmugMug is busy right now. Try again in a moment.", e.message)
        assertEquals("the saved password is untouched", "family-pw", rig.passwords.getPassword("2sDN5x"))
    }

    /** A 429 on the unlock is SmugMug being busy too. */
    @Test fun `an unlock answered 429 says SmugMug is busy`() {
        rig.passwords.savePassword("2sDN5x", "family-pw")
        rig.server.unlockCode = 429

        val e = lockedAlbumError { rig.repository.getAlbumImagesPage("FfHCms", "test-key", password = "family-pw") }

        assertNotNull(e)
        assertTrue(e!!.unlockPending)
        assertEquals("SmugMug is busy right now. Try again in a moment.", e.message)
        assertEquals("family-pw", rig.passwords.getPassword("2sDN5x"))
    }

    /** The unlock POST threw (no network): the device is offline, and the text says so. */
    @Test fun `an unlock that cannot reach SmugMug says the device is offline`() {
        rig.passwords.savePassword("2sDN5x", "family-pw")
        rig.server.unlockFailure = java.net.UnknownHostException("api.smugmug.com")

        val e = lockedAlbumError { rig.repository.getAlbumImagesPage("FfHCms", "test-key", password = "family-pw") }

        assertNotNull(e)
        assertTrue("offline is still 'pending', not 'refused'", e!!.unlockPending)
        assertEquals("You're offline. This gallery will open once you're back online.", e.message)
        assertEquals("family-pw", rig.passwords.getPassword("2sDN5x"))
    }

    /** A refused password (401) is not pending: the plain locked message, so the screen prompts. */
    @Test fun `a saved password the server refuses is not pending`() {
        rig.passwords.savePassword("2sDN5x", "stale-pw")
        rig.server.unlockCode = 401

        val e = lockedAlbumError { rig.repository.getAlbumImagesPage("FfHCms", "test-key", password = "stale-pw") }

        assertNotNull(e)
        assertFalse(e!!.unlockPending)
        assertEquals("This gallery needs its password. Open it once to unlock it.", e.message)
        assertEquals("stale-pw", rig.passwords.getPassword("2sDN5x"))
    }

    // --- the screens ---

    /**
     * The grid with no saved password. `!parents` is unreadable (a 429, offline), so nothing says the gallery
     * needs a password before its photos are asked for: the locked answer is what raises the prompt. Red on
     * the old code: the grid ended `complete` and empty, with no prompt.
     */
    @Test fun `opening a locked gallery with no saved password prompts instead of showing an empty complete grid`() {
        rig.server.parentsOverride = { throw IOException("lineage unreadable") }

        rig.viewModel.selectAlbum("FfHCms")

        awaitUntil("the password prompt") { rig.viewModel.passwordPromptNode != null }
        assertEquals("the gallery is the target of the prompt", "LCdk7F", rig.viewModel.passwordPromptNode?.nodeId)
        assertNotEquals(true, GridProbe.complete(rig.viewModel))
        assertNull("nothing is shown as an error", GridProbe.error(rig.viewModel))
        assertEquals(emptyMap<String, String>(), rig.passwords.all())
    }

    /** Saved password, unlock inconclusive (503): no prompt, the password stays, the grid says so (not "empty"). */
    @Test fun `a saved password with an inconclusive unlock shows a message, no prompt, and deletes nothing`() {
        rig.passwords.savePassword("2sDN5x", "family-pw")
        rig.server.unlockCode = 503
        cacheFamilyTree() // browsed before: the saved password is found without the network

        rig.viewModel.selectAlbum("FfHCms")

        awaitUntil("the grid reports why it is empty") { GridProbe.error(rig.viewModel) != null }
        Thread.sleep(300) // negative wait: nothing may prompt or delete
        assertNull("no prompt over a password that was never refused", rig.viewModel.passwordPromptNode)
        assertEquals("family-pw", rig.passwords.getPassword("2sDN5x"))
        assertEquals("SmugMug is busy right now. Try again in a moment.", GridProbe.error(rig.viewModel))
        assertNotEquals(true, GridProbe.complete(rig.viewModel))
    }

    /** The same screen when the unlock POST could not leave the device: it says offline, still no prompt. */
    @Test fun `a saved password with an offline unlock shows the offline message and no prompt`() {
        rig.passwords.savePassword("2sDN5x", "family-pw")
        rig.server.unlockFailure = java.net.ConnectException("no route")
        cacheFamilyTree()

        rig.viewModel.selectAlbum("FfHCms")

        awaitUntil("the grid reports why it is empty") { GridProbe.error(rig.viewModel) != null }
        Thread.sleep(300)
        assertNull(rig.viewModel.passwordPromptNode)
        assertEquals("family-pw", rig.passwords.getPassword("2sDN5x"))
        assertEquals("You're offline. This gallery will open once you're back online.", GridProbe.error(rig.viewModel))
    }

    /** A saved password the server REFUSES (401): the prompt opens, and the saved one survives until a typed one fails. */
    /** The rows a browse of Family -> School -> the gallery leaves behind (NodeID LCdk7F, AlbumKey FfHCms). */
    private fun cacheFamilyTree() = runBlocking {
        fun row(id: String, parent: String?, type: String, title: String, album: String? = null) = com.smugview.app.data.db.CachedNode(
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

    @Test fun `a saved password the server refuses prompts and is not deleted by the read`() {
        rig.passwords.savePassword("2sDN5x", "stale-pw")
        rig.server.unlockCode = 401
        cacheFamilyTree()

        rig.viewModel.selectAlbum("FfHCms")

        awaitUntil("the password prompt") { rig.viewModel.passwordPromptNode != null }
        assertEquals("stale-pw", rig.passwords.getPassword("2sDN5x"))
    }

    // --- Cast and the downloads (Q5 (a)) ---

    private fun lockedCollection(): Long = runBlocking {
        val id = rig.repository.createLocalCollection("Trip", "idzifamily")
        rig.dao.addBookmark(CollectionBookmark(collectionId = id, type = "Album", itemKey = "FfHCms", title = "New School Year"))
        id
    }

    @Test fun `casting a collection with a locked gallery stops with the one message`() {
        val castManager = Mockito.mock(CastManager::class.java)
        val cast = CastController(
            castManager, rig.repository, "test-key", CoroutineScope(SupervisorJob() + Dispatchers.Default),
            getUnlockedPassword = { null }, onMessage = { messages += it }
        )

        cast.castCollection(lockedCollection())

        awaitUntil("the message") { messages.isNotEmpty() }
        assertEquals(listOf("This gallery needs its password. Open it once to unlock it."), messages.toList())
        Mockito.verify(castManager, Mockito.never()).castSlideshow(Mockito.anyList(), Mockito.anyInt())
    }
}
