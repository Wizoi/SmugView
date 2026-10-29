package com.smugview.app.data.repository

import com.smugview.app.data.api.isVideo
import com.smugview.app.data.db.toAlbumImageData
import com.smugview.app.scenario.ScenarioRig
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Owner report (2026-10-29): videos returned by a search do not play. The viewer plays `videoUrl`, else the item's `ArchivedUri`, which for
 * a video is a JPEG still. Search never asked SmugMug for the video (`_expand=LargestVideo`), so `videoUrl` was always empty; album pages do
 * ask (R-27). Live check: "concert" on idzifamily gives 28 MP4s; without the expansion the answer has no `Uris.LargestVideo` at all.
 *
 * Fixture: results s00002 and s00004 are videos, the rest photos.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class SearchVideoUrlTest {
    private lateinit var rig: ScenarioRig

    @Before fun setUp() {
        rig = ScenarioRig()
        rig.server.searchVideoKeys = setOf("s00002", "s00004")
        rig.server.imageSearchTotal = 5
    }

    @After fun tearDown() = rig.close()

    private fun searchRequests() = synchronized(rig.server.requestLog) { rig.server.requestLog.filter { it.url.encodedPath.endsWith("image!search") } }

    @Test fun `a video found by search gets its MP4 address, a photo gets none`() = runBlocking {
        rig.repository.performBackgroundSearchImages("idzifamily", "/api/v2/user/idzifamily", "idzifamily", "concert", "key")
        val results = rig.db.collectionDao().getSearchResults("concert", "idzifamily", "Photo").map { it.toAlbumImageData() }.associateBy { it.imageKey }
        assertEquals(5, results.size)
        assertTrue("s00002 is a video", results.getValue("s00002").isVideo)
        assertEquals("https://photos.smugmug.com/Kentridge/Public/i-s00002/0/abc123/s00002-video.mp4", results.getValue("s00002").videoUrl)
        assertNotNull(results.getValue("s00004").videoUrl)
        assertNull("a photo has no video address", results.getValue("s00001").videoUrl)
    }

    @Test fun `the search asks for the video address`() = runBlocking {
        rig.repository.performBackgroundSearchImages("idzifamily", "/api/v2/user/idzifamily", "idzifamily", "concert", "key")
        val request = searchRequests().first()
        assertEquals("LargestVideo", request.url.queryParameter("_expand"))
        assertTrue(request.url.queryParameter("_filteruri").orEmpty().split(',').containsAll(listOf("ImageSizeDetails", "LargestVideo")))
    }

    @Test fun `a video found by the tag search gets its MP4 address too`() = runBlocking {
        val images = rig.repository.getImagesByKeywordPage("/api/v2/user/idzifamily", "kentridge", "key").response.images.orEmpty().associateBy { it.imageKey }
        assertEquals("https://photos.smugmug.com/Kentridge/Public/i-s00002/0/abc123/s00002-video.mp4", images.getValue("s00002").videoUrl)
        assertNull(images.getValue("s00001").videoUrl)
        val request = searchRequests().last()
        assertEquals("LargestVideo", request.url.queryParameter("_expand"))
    }
}
