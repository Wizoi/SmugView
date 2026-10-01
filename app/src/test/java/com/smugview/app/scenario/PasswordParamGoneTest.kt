package com.smugview.app.scenario

import kotlinx.coroutines.flow.toList
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
 * Step 4-7 (design 3.4, Q2 (a), R-23): no GET sends `Password=`. No endpoint honours it, so access comes
 * only from the `!unlock` session cookie, and a password in a URL leaks into logs and caches. The
 * production client runs over the fake on a real socket. Fixture F: Family (2sDN5x) is password
 * protected, its gallery FfHCms (NodeID LCdk7F, AlbumKey != NodeID) answers the locked shape
 * (200, no `AlbumImage`) until the session exists.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class PasswordParamGoneTest {
    private lateinit var rig: ScenarioRig

    @Before fun setUp() {
        rig = ScenarioRig(httpCache = true)
        rig.server.cookieGate = true
        rig.server.gateImages = true
    }
    @After fun tearDown() = rig.close()

    private fun passwordParamRequests() = synchronized(rig.server.requestLog) {
        rig.server.requestLog.filter { it.url.queryParameterNames.any { n -> n.equals("Password", true) } }.map { "${it.method} ${it.url.encodedPath}" }
    }

    /**
     * The user typed Family's password in this session: the locked gallery's first answer is empty, the
     * repository unlocks through `!unlock` and asks again, and the gallery loads. On the old code every
     * one of those GETs carried `Password=<the password>`.
     */
    @Test fun `a gallery unlocked in this session loads and no GET carries a Password parameter`() = runBlocking {
        val page = rig.repository.getAlbumImagesPage("FfHCms", "test-key", password = "family-pw")

        assertEquals("the gallery loaded after the unlock", 100, page.response.images?.size)
        assertTrue("the session came from !unlock", rig.server.hasSession())
        assertEquals("no request sent a Password parameter", emptyList<String>(), passwordParamRequests())
    }

    /** The same through the other password-aware reads: the album's details and a folder listing. */
    @Test fun `album details and folder listings of a locked tree send no Password parameter either`() = runBlocking {
        rig.repository.getAlbumImagesPage("FfHCms", "test-key", password = "family-pw") // opens the session
        rig.repository.getAlbum("FfHCms", "test-key", password = "family-pw")
        rig.repository.getNodeChildren("idzifamily", "2sDN5x", "test-key", forceRefresh = true, password = "family-pw").toList()

        assertEquals(emptyList<String>(), passwordParamRequests())
    }

    /** Without a password and without a session the gallery stays locked: one empty answer, no unlock call, no throw. */
    @Test fun `a locked gallery with no password and no session stays locked`() = runBlocking {
        val locked = try { rig.repository.getAlbumImagesPage("FfHCms", "test-key") } catch (e: retrofit2.HttpException) { null }

        assertTrue("no images came back (${locked?.response?.images?.size})", locked == null || locked.response.images.isNullOrEmpty())
        assertEquals("nothing tried to unlock", false, rig.server.hasSession())
        assertEquals(emptyList<String>(), passwordParamRequests())
    }
}
