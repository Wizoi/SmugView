package com.smugview.app.data.repository

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import retrofit2.HttpException

/**
 * Step 4-7 (Q2 (a), R-23, R-54): password-gallery JSON now enters the HTTP cache, because `Password=` no
 * longer marks a request as uncacheable. This pins what that means, through the PRODUCTION client over the
 * fake on a real socket: reuse online is bounded by the session (a cookie change or a new process asks the
 * server again, so an expired or absent session meets the locked answer, never stale data), and offline
 * reuse is the accepted 7-day cache of the app-private directory, which Android excludes from backup.
 * Fixture F: Family (2sDN5x) is password protected; FfHCms is its gallery and answers the locked shape
 * (200, no `AlbumImage`, `Total` 0) until the `!unlock` cookie is sent.
 */
class PasswordGalleryCacheTest {
    @get:Rule val tmp = TemporaryFolder()

    private val fake = FakeSmugMugServer().apply { cookieGate = true; gateImages = true }
    private val cacheDir by lazy { tmp.newFolder("cache") }
    private val loop by lazy { LoopbackSmugMug(fake, cacheDir) }

    @After fun tearDown() { loop.close() }

    private fun imagesRequests() = synchronized(fake.requestLog) { fake.requestLog.count { it.url.encodedPath.endsWith("album/FfHCms!images") } }

    private fun unlock() = runBlocking { loop.api().unlockNode("2sDN5x", "k", "family-pw", null) }.also { assertEquals(200, it.code()) }

    private fun images(api: com.smugview.app.data.api.SmugMugApi = loop.api()) =
        runBlocking { api.getAlbumImages("FfHCms", "k", ignoreErrors = "true") }

    @Test fun `a gallery unlocked in this session loads, is reused online, and no request sends Password`() {
        unlock()
        Thread.sleep(1_100) // the unlock bumped the epoch; OkHttp's age is whole seconds
        val first = images()
        val second = images()

        assertEquals(100, first.response.images?.size)
        assertEquals(100, second.response.images?.size)
        assertEquals("the second read is the 5-minute reuse of ordinary navigation", 1, imagesRequests())
        assertTrue("no request carried Password", synchronized(fake.requestLog) { fake.requestLog.none { it.url.queryParameter("Password") != null } })
    }

    /** The session ended (a new process has an empty cookie jar): online, the cached unlocked page is not reused. */
    @Test fun `an absent session after a restart meets the locked answer, not the cached gallery`() {
        unlock()
        Thread.sleep(1_100)
        assertEquals(100, images().response.images?.size)
        loop.client.cache!!.close()

        val fresh = loop.newClient(cacheDir)
        val afterRestart = images(loop.api(fresh))

        assertTrue("the locked shape: 200 with no AlbumImage", afterRestart.response.images.isNullOrEmpty())
        assertEquals("the restart reached the server instead of the cache", 2, imagesRequests())
    }

    /** The same for a folder: the session is gone, so it is 401 (the prompt path), not the cached 200. */
    @Test fun `a locked folder after a restart is 401, not its cached listing`() {
        unlock()
        Thread.sleep(1_100)
        val listed = runBlocking { loop.api().getNodeChildren("2sDN5x", "k") }
        assertNotNull(listed.response.nodes)
        loop.client.cache!!.close()

        val fresh = loop.newClient(cacheDir)
        val code = try { runBlocking { loop.api(fresh).getNodeChildren("2sDN5x", "k") }; 200 } catch (e: HttpException) { e.code() }

        assertEquals(401, code)
    }

    /** Accepted (Q2 (a), R-54): offline after a restart serves what was cached, for up to 7 days, from the app-private cache. */
    @Test fun `offline after a restart still serves the cached gallery`() {
        unlock()
        Thread.sleep(1_100)
        val online = images()
        loop.client.cache!!.close()
        val fresh = loop.newClient(cacheDir)
        loop.online = false

        val offline = images(loop.api(fresh))

        assertEquals(online.response.images?.size, offline.response.images?.size)
        assertEquals("served from the cache: no new request", 1, imagesRequests())
    }
}
