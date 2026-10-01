package com.smugview.app.data.repository

import com.smugview.app.scenario.ScenarioRig
import kotlinx.coroutines.flow.first
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
 * Phase 4 step 4-3 (design 3.2, R-27, P13): a folder of more than one page lists every row WITH its
 * cover. Page 2 used to be fetched through `Pages.NextPage`, which is the echo of the request and
 * drops `_expand`, so the 101st child onward came back without the `HighlightImage` expansion (no
 * cover). The fake echoes and drops exactly as the live API does. The 150-child folder `Kp7Wq2` is
 * 100 + 50 (children cap 200, the app asks for 100).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class FolderPage2CoversTest {
    private lateinit var rig: ScenarioRig

    @Before fun setUp() { rig = ScenarioRig() }
    @After fun tearDown() = rig.close()

    private val big = FakeSmugMugServer.BIG_FOLDER

    @Test fun `every row of a two-page folder has its cover`() = runBlocking {
        val rows = rig.repository.getNodeChildren("idzifamily", big, "test-key", forceRefresh = true).first().getOrThrow()
        assertEquals(150, rows.size)
        val without = rows.count { it.highlightImageUrl.isNullOrEmpty() }
        assertEquals("$without of ${rows.size} rows without a cover", 0, without)
        assertEquals((0 until 150).toList(), rows.map { it.sortIndex })
    }

    @Test fun `every page request carries the expansion and the forced no-cache`() = runBlocking {
        rig.repository.getNodeChildren("idzifamily", big, "test-key", forceRefresh = true).first().getOrThrow()
        val pages = synchronized(rig.server.requestLog) {
            rig.server.requestLog.filter { it.url.encodedPath.endsWith("node/$big!children") }
        }
        assertEquals("two pages", 2, pages.size)
        assertEquals("pages without _expand=HighlightImage", 0, pages.count { it.url.queryParameter("_expand") != "HighlightImage" })
        assertEquals(listOf("1", "101"), pages.map { it.url.queryParameter("start") ?: "1" })
        assertEquals(listOf<String?>("no-cache", "no-cache"), rig.server.childrenCacheControls(big))
    }

    @Test fun `the gallery crawl of a folder reaches the 150th gallery`() = runBlocking {
        val albums = rig.repository.fetchAlbumsInScopeRemote("idzifamily", big, "test-key")
        assertTrue("gallery #150 is missing (found ${albums.size} of 150)", albums.any { it.nodeId == FakeSmugMugServer.BIG_GALLERY_NODE })
        assertEquals(150, albums.size)
    }
}
