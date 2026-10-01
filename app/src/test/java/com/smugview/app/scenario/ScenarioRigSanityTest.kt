package com.smugview.app.scenario

import com.smugview.app.ui.viewmodel.BrowserUiState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Step 3-0: the harness itself works (green on the old code). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class ScenarioRigSanityTest {
    private lateinit var rig: ScenarioRig

    @Before fun setUp() { rig = ScenarioRig() }
    @After fun tearDown() = rig.close()

    private fun listedIds() = (rig.viewModel.browserState.value as? BrowserUiState.Success)?.nodes?.map { it.nodeId }

    @Test fun `the rig opens site A and the root listing shows Family and Kentridge`() {
        rig.viewModel.selectSite("idzifamily")
        awaitUntil("root listing of A") { listedIds()?.containsAll(listOf("2sDN5x", "3BxbFF")) == true }
        assertEquals(setOf("2sDN5x", "3BxbFF"), listedIds()!!.toSet())
    }

    @Test fun `the rig opens site B and the root listing shows its public folder`() {
        rig.viewModel.selectSite("siteb")
        awaitUntil("root listing of B") { listedIds()?.contains("Hq2Lm9") == true }
        assertEquals(listOf("Hq2Lm9"), listedIds())
    }

    @Test fun `a held request is observed arrived and completes on release`() {
        val gate = rig.server.hold("node/4zqWw!children")
        rig.viewModel.selectSite("idzifamily")
        assertTrue("the held request reaches the fake", gate.awaitArrived())
        assertFalse("nothing is listed while it is held", rig.viewModel.browserState.value is BrowserUiState.Success)
        gate.release()
        awaitUntil("listing after release") { listedIds()?.contains("2sDN5x") == true }
    }

    @Test fun `a 429 is served with Retry-After 0 the given number of times`() {
        rig.server.respond429("node/4zqWw!children", 2)
        val api = rig.server.api()
        val codes = (1..3).map {
            try { kotlinx.coroutines.runBlocking { api.getNodeChildren("4zqWw", "k") }; 200 }
            catch (e: retrofit2.HttpException) { assertEquals("0", e.response()?.headers()?.get("Retry-After")); e.code() }
        }
        assertEquals(listOf(429, 429, 200), codes)
    }

    @Test fun `album images page 1 of a 150 image gallery has NextPage`() {
        val api = rig.server.api()
        val page1 = kotlinx.coroutines.runBlocking { api.getAlbumImages("FfHCms", "k") }.response
        assertEquals(100, page1.images!!.size)
        assertTrue(page1.pages!!.next!!.contains("start=101"))
        assertEquals(rig.server.imageKeysOf("FfHCms").take(100), page1.images!!.map { it.imageKey })
    }
}
