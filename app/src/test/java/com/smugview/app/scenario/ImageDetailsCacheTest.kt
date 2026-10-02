package com.smugview.app.scenario

import com.smugview.app.ui.viewmodel.KeyedResultCache
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** R-61: the viewer's per-photo caches keep a failure for the rest of the session and are never evicted. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class ImageDetailsCacheTest {
    private lateinit var rig: ScenarioRig
    private val key = "N74KSKi001"

    @Before fun setUp() { rig = ScenarioRig(httpCache = true) }
    @After fun tearDown() = rig.close()

    private suspend fun <T> settled(flow: StateFlow<Result<T>?>): Result<T> =
        withTimeout(10_000) { flow.first { it != null }!! }

    @Test fun `EXIF that failed offline is asked for again at the next open`() = runBlocking {
        rig.loopback!!.online = false
        val failed = settled(rig.viewModel.getImageExif(key))
        assertTrue("offline should fail", failed.isFailure)

        rig.loopback!!.online = true
        val again = settled(rig.viewModel.getImageExif(key))

        assertTrue("second open: ${again.exceptionOrNull()}", again.isSuccess)
        assertEquals("Canon", again.getOrNull()?.make)
    }

    @Test fun `details that failed offline are asked for again at the next open`() = runBlocking {
        rig.loopback!!.online = false
        val failed = settled(rig.viewModel.getImageDetails(key))
        assertTrue("offline should fail", failed.isFailure)

        rig.loopback!!.online = true
        val again = settled(rig.viewModel.getImageDetails(key))

        assertTrue("second open: ${again.exceptionOrNull()}", again.isSuccess)
        assertEquals(key, again.getOrNull()?.imageKey)
    }

    @Test fun `a result that worked is kept, so a second open asks nothing`() = runBlocking {
        settled(rig.viewModel.getImageExif(key))
        val requests = rig.server.requestsTo("image/").size

        settled(rig.viewModel.getImageExif(key))

        assertEquals(requests, rig.server.requestsTo("image/").size)
    }

    @Test fun `the 201st key evicts the least recently read one`() {
        val cache = KeyedResultCache<String>(limit = 200)
        (1..200).forEach { n -> cache.flowFor("k$n") { it.publish(Result.success("v$n")) } }
        cache.flowFor("k1") { error("k1 is still cached") }

        cache.flowFor("k201") { it.publish(Result.success("v201")) }

        assertTrue("k1 was read last, so it stays", cache.contains("k1"))
        assertFalse("k2 was the least recently read", cache.contains("k2"))
        assertTrue(cache.contains("k201"))
    }

    @Test fun `a failure is shown to its readers and then forgotten`() {
        val cache = KeyedResultCache<String>()
        val flow = cache.flowFor("k") { it.publish(Result.failure(java.io.IOException("x"))) }

        assertTrue(flow.value!!.isFailure)
        assertFalse(cache.contains("k"))
    }
}
