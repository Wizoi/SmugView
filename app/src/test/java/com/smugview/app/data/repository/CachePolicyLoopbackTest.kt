package com.smugview.app.data.repository

import kotlinx.coroutines.runBlocking
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.concurrent.thread

/**
 * Phase 4 step 4-6 (design 3.3, R-35): one owner of the cache policy, run through the PRODUCTION client
 * (real OkHttp cache, cookie jar, cache rewrite, offline rule) over the fake on a real socket. SmugMug
 * sends no `Vary: Cookie`, so the cache is keyed by URL only: a listing fetched before an unlock must not
 * be served again after it. The loopback's own client models a settled session (its epoch is 10 minutes
 * old), so a plain second GET is a hit; a client from `newClient(dir)` models a process that just started.
 * OkHttp's cache ages with real time, so the epoch rule is tested with real sleeps past the first second.
 */
class CachePolicyLoopbackTest {
    @get:Rule val tmp = TemporaryFolder()

    private val fake = FakeSmugMugServer()
    private val cacheDir by lazy { tmp.newFolder("cache") }
    private val loop by lazy { LoopbackSmugMug(fake, cacheDir) }

    @After fun tearDown() { loop.close() }

    private val albumsPath = "user/idzifamily!albums?count=100&_expand=HighlightImage"

    private fun get(path: String, client: okhttp3.OkHttpClient = loop.client, cacheControl: String? = null): String {
        val b = Request.Builder().url("${loop.baseUrl}$path")
        if (cacheControl != null) b.header("Cache-Control", cacheControl)
        return client.newCall(b.build()).execute().use { it.body!!.string() }
    }

    private fun albumsHits() = fake.albumsRequests().size

    /**
     * The Hub's listing was SENT anonymously and ANSWERED after the unlock (a request in flight across the
     * `Set-Cookie`). Whatever its bytes, the cache must not serve it once the session has changed.
     */
    @Test fun `a listing sent before an unlock is not reused after it`() {
        fake.cookieGate = true
        val held = fake.hold("user/idzifamily!albums", answerFirst = true)
        var inFlight: String? = null
        val t = thread { inFlight = get(albumsPath) }
        assertTrue("the anonymous listing reached the server", held.awaitArrived())

        runBlocking { loop.api().unlockNode("2sDN5x", "k", "family-pw", null) }.also { assertEquals(200, it.code()) }
        held.release()
        t.join(5_000)
        assertFalse("the anonymous listing hides Family's gallery", inFlight!!.contains("FfHCms"))

        Thread.sleep(1_100) // past the first second: OkHttp's age resolution is whole seconds
        val after = get(albumsPath)

        assertEquals("the request after the unlock reached the server", 2, albumsHits())
        assertTrue("and listed the gallery the unlock revealed", after.contains("FfHCms"))
    }

    /** Green pin: with no cookie change, the 5-minute reuse of ordinary navigation is untouched. */
    @Test fun `with no session change a second identical GET is still a hit`() {
        get(albumsPath)
        get(albumsPath)
        assertEquals(1, albumsHits())
    }

    /** Green pin: a response fetched AFTER the session changed is reused (the epoch is not a blanket no-cache). */
    @Test fun `a listing fetched after an unlock is reused`() {
        fake.cookieGate = true
        runBlocking { loop.api().unlockNode("2sDN5x", "k", "family-pw", null) }.also { assertEquals(200, it.code()) }
        Thread.sleep(1_100)
        get(albumsPath)
        get(albumsPath)
        assertEquals(1, albumsHits())
    }

    /**
     * Process death: the cookie jar is empty again and the epoch is the new process's start, so nothing the
     * old process cached is reused ONLINE, even though it is well inside the 5 minutes.
     */
    @Test fun `a new process does not reuse what the old one cached online`() {
        get(albumsPath)
        assertEquals(1, albumsHits())
        loop.client.cache!!.close()

        val fresh = loop.newClient(cacheDir)
        get(albumsPath, fresh)

        assertEquals("the first online request of a new process reached the server", 2, albumsHits())
    }

    /** Green pin: offline after a restart still serves what the old process cached (7 days). */
    @Test fun `a new process offline still serves the old cache`() {
        val online = get(albumsPath)
        loop.client.cache!!.close()
        val fresh = loop.newClient(cacheDir)
        loop.online = false

        assertEquals(online, get(albumsPath, fresh))
        assertEquals(1, albumsHits())
    }

    /** Green pin: the offline rule beats the epoch, after an unlock too. */
    @Test fun `offline after an unlock still serves the cache`() {
        val online = get(albumsPath)
        runBlocking { loop.api().unlockNode("2sDN5x", "k", "family-pw", null) }.also { assertEquals(200, it.code()) }
        loop.online = false

        assertEquals(online, get(albumsPath))
        assertEquals(1, albumsHits())
    }

    /** Green pin: Refresh and Retry ask for `no-cache` and always reach the server. */
    @Test fun `a no-cache caller reaches the server`() {
        get(albumsPath)
        get(albumsPath, cacheControl = "no-cache")
        assertEquals(2, albumsHits())
    }

    /** Green pin: offline with nothing cached is a synthetic 504 that reaches the caller (RetryingCallFactory skips it). */
    @Test fun `offline with nothing cached is a 504 through the retrying factory`() {
        loop.online = false
        val api = loop.api(retrying = true)

        val failure = runCatching { runBlocking { api.getNodeChildren("4zqWw", "k") } }.exceptionOrNull()

        assertTrue("a 504 reached the caller: $failure", failure is retrofit2.HttpException && failure.code() == 504)
        assertEquals("nothing reached the server", 0, fake.requestsTo("node/4zqWw!children").size)
    }
}
