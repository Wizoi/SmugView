package com.smugview.app.data.repository

import com.smugview.app.scenario.GridProbe
import com.smugview.app.scenario.ScenarioRig
import com.smugview.app.scenario.awaitUntil
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Phase 4 step 4-4 (design 3.2, R-27, P13): every page of a gallery carries `_expand=LargestVideo`, so
 * a video on page 2 has its `videoUrl`. Page 2 used to be fetched through `Pages.NextPage`, the echo of
 * the request, which drops `_expand`: videos 501+ came back with no `videoUrl`. `getAllAlbumImages`
 * (Cast, Collections downloads) never applied the expansion at all, not even on page 1.
 *
 * `Vd5Qx9` is the 620-image gallery (album cap 500: 500 + 120) with videos at #3, #510 and #615.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class AlbumPage2VideoTest {
    private lateinit var rig: ScenarioRig

    @Before fun setUp() { rig = ScenarioRig() }
    @After fun tearDown() = rig.close()

    private val album = FakeSmugMugServer.BIG_GALLERY_ALBUM
    private fun imageRequests() = synchronized(rig.server.requestLog) {
        rig.server.requestLog.filter { it.url.encodedPath.endsWith("album/$album!images") }
    }

    @Test fun `the grid gives the videos on page 2 their videoUrl`() {
        rig.viewModel.selectAlbum(album)
        awaitUntil("the album is complete") { GridProbe.complete(rig.viewModel) == true }
        val photos = rig.viewModel.albumLoader.photos.value
        assertEquals(620, photos.size)
        val keys = rig.server.imageKeysOf(album)
        assertNotNull("videoUrl of #3 (page 1, the pin)", photos.first { it.imageKey == keys[2] }.videoUrl)
        assertNotNull("videoUrl of #510 expected not null", photos.first { it.imageKey == keys[509] }.videoUrl)
        assertNotNull("videoUrl of #615 expected not null", photos.first { it.imageKey == keys[614] }.videoUrl)
        assertEquals("photos that are not videos have none", 3, photos.count { it.videoUrl != null })
    }

    @Test fun `getAllAlbumImages gives every video its videoUrl`() = runBlocking {
        val all = rig.repository.getAllAlbumImages(album, "test-key")
        assertEquals(620, all.size)
        val keys = rig.server.imageKeysOf(album)
        assertNotNull("videoUrl of #3 (page 1) expected not null", all.first { it.imageKey == keys[2] }.videoUrl)
        assertNotNull("videoUrl of #510 expected not null", all.first { it.imageKey == keys[509] }.videoUrl)
        assertNotNull("videoUrl of #615 expected not null", all.first { it.imageKey == keys[614] }.videoUrl)
        assertEquals(3, all.count { it.videoUrl != null })
    }

    @Test fun `every page request of the grid and of getAllAlbumImages carries the expansion and the filters`() = runBlocking {
        rig.viewModel.selectAlbum(album)
        awaitUntil("the album is complete") { GridProbe.complete(rig.viewModel) == true }
        rig.repository.getAllAlbumImages(album, "test-key")
        val requests = imageRequests()
        assertEquals("two pages each", 4, requests.size)
        assertEquals("pages without _expand=LargestVideo", 0, requests.count { it.url.queryParameter("_expand") != "LargestVideo" })
        assertEquals("pages without the filter", 0, requests.count { it.url.queryParameter("_filteruri") != "LargestVideo,Album" })
        // the grid and the download may interleave, so compare the starts sorted
        assertEquals(listOf(1, 1, 501, 501), requests.map { (it.url.queryParameter("start") ?: "1").toInt() }.sorted())
    }

    @Test fun `a gallery of one page makes one request and is complete`() {
        rig.viewModel.selectAlbum("N74KSK")
        awaitUntil("the album is complete") { GridProbe.complete(rig.viewModel) == true }
        assertEquals(12, rig.viewModel.albumLoader.photos.value.size)
        assertTrue(synchronized(rig.server.requestLog) { rig.server.requestLog.count { it.url.encodedPath.endsWith("album/N74KSK!images") } } == 1)
    }
}
