package com.smugview.app.data.repository

import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Phase 4 step 4-0: the production HTTP stack (`buildSmugMugClient`: real OkHttp cache, cache rewrite,
 * cookie jar, offline fallback) over the fake on a real socket. Plain JUnit: no Android classes needed.
 */
class LoopbackSmugMugTest {
    @get:Rule val tmp = TemporaryFolder()

    private val fake = FakeSmugMugServer()
    private val loop by lazy { LoopbackSmugMug(fake, tmp.newFolder("cache")) }

    @After fun tearDown() { loop.close() }

    private fun get(path: String, cacheControl: String? = null): okhttp3.Response {
        val b = Request.Builder().url("${loop.baseUrl}$path")
        if (cacheControl != null) b.header("Cache-Control", cacheControl)
        return loop.client.newCall(b.build()).execute()
    }

    @Test fun a_second_identical_get_is_a_cache_hit_even_though_smugmug_says_no_store() {
        val path = "node/4zqWw!children?count=10"
        get(path).use { it.body!!.string() }
        get(path).use { assertEquals(200, it.code); it.body!!.string() }
        assertEquals(1, fake.requestsTo("node/4zqWw!children").size)
    }

    @Test fun a_no_cache_caller_reaches_the_server() {
        val path = "node/4zqWw!children?count=10"
        get(path).use { it.body!!.string() }
        get(path, cacheControl = "no-cache").use { it.body!!.string() }
        assertEquals(2, fake.requestsTo("node/4zqWw!children").size)
    }

    @Test fun offline_with_nothing_cached_is_a_synthetic_504() {
        loop.online = false
        get("node/4zqWw!children?count=10").use { assertEquals(504, it.code) }
        assertEquals(0, fake.requestsTo("node/4zqWw!children").size)
    }

    @Test fun offline_serves_what_was_cached() {
        val path = "node/4zqWw!children?count=10"
        get(path).use { it.body!!.string() }
        loop.online = false
        get(path).use { assertEquals(200, it.code) }
        assertEquals(1, fake.requestsTo("node/4zqWw!children").size)
    }

    @Test fun the_cookie_jar_carries_smsess_after_unlock_and_a_new_process_has_none() = runBlocking {
        loop.api().unlockNode("2sDN5x", "k", "pw", null).also { assertEquals(200, it.code()) }
        assertTrue(fake.hasSession())
        fake.cookieGate = true
        // session cookie present: the gated folder answers
        get("node/2sDN5x!children?count=10", cacheControl = "no-cache").use { assertEquals(200, it.code) }
        val names = loop.cookieJar.loadForRequest("https://api.smugmug.com/".toHttpUrl()).map { it.name }.toSet()
        assertEquals(setOf("shm", "SMSESS"), names)
        // a new process: empty cookie jar, the server still remembers the unlock
        val fresh = loop.newClient(tmp.newFolder("cache2"))
        fresh.newCall(Request.Builder().url("${loop.baseUrl}node/2sDN5x!children?count=10").build()).execute().use {
            assertEquals("a locked folder is a 404 (L2)", 404, it.code)
        }
    }

    @Test fun a_301_is_followed_by_the_real_client() {
        get("image/FfHCmsi001").use {
            assertEquals(200, it.code)
            assertTrue(it.request.url.encodedPath.endsWith("FfHCmsi001-0"))
        }
    }

    @Test fun the_app_api_works_over_loopback() = runBlocking {
        val r = loop.api().getNodeChildren("4zqWw", "k")
        assertEquals(2, r.response.nodes!!.size)
    }
}
