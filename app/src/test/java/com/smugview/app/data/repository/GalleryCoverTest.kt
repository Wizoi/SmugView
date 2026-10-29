package com.smugview.app.data.repository

import com.smugview.app.scenario.ScenarioRig
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

/**
 * Owner report (2026-10-29): the gallery page's header showed the gallery's feature (highlight) image, not its COVER image.
 * SmugMug has both: `Uris.HighlightImage` (the tile in folder lists) and `Uris.NodeCoverImage` (`node/{id}!cover`). They differ on
 * 8 of 12 galleries of the owner's site. The cover is fetched when the gallery opens (the owner chose that over a database column).
 *
 * Fixture: gallery AlbumKey N74KSK is NodeID sXQz4G; its cover is `cover-sXQz4G`, its highlight (in a folder listing) is `hl-sXQz4G`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class GalleryCoverTest {
    private lateinit var rig: ScenarioRig

    @Before fun setUp() { rig = ScenarioRig() }
    @After fun tearDown() = rig.close()

    private fun cover(albumKey: String) = runBlocking { rig.repository.getGalleryCoverUrl(albumKey, "key") }

    @Test fun `the cover is the gallery's cover picture at a size for a header, not its highlight picture`() {
        val url = cover("N74KSK")!!
        assertEquals("https://photos.smugmug.com/photos/cover-sXQz4G/0/L/cover-sXQz4G-L.jpg", url)
        assertFalse("not the highlight picture", url.contains("hl-"))
    }

    @Test fun `the gallery is asked for by its NodeID with only the cover expanded, even when the gallery was never browsed`() {
        cover("N74KSK")
        val request = rig.server.requestLog.first { it.url.encodedPath.endsWith("node/sXQz4G") }
        assertEquals("NodeCoverImage", request.url.queryParameter("_expand"))
        assertEquals("NodeCoverImage", request.url.queryParameter("_filteruri"))
    }

    @Test fun `a gallery whose owner switched the cover off gets no cover, so the header uses the feature picture or the first photo`() {
        rig.server.nodeShowCoverImage = false
        assertNull(cover("N74KSK"))
    }

    @Test fun `a gallery with no ShowCoverImage flag at all still shows its cover`() {
        rig.server.nodeShowCoverImage = null
        assertEquals("https://photos.smugmug.com/photos/cover-sXQz4G/0/L/cover-sXQz4G-L.jpg", cover("N74KSK"))
    }

    @Test fun `the flag is asked for`() {
        cover("N74KSK")
        val request = rig.server.requestLog.first { it.url.encodedPath.endsWith("node/sXQz4G") }
        assertTrue(request.url.queryParameter("_filter").orEmpty().split(',').contains("ShowCoverImage"))
    }

    @Test fun `offline gives no cover and no crash, so the header can fall back`() {
        rig.server.failWith = { IOException("offline") }
        assertNull(cover("N74KSK"))
    }

    @Test fun `an unknown gallery gives no cover`() {
        assertNull(cover("NoSuchKey"))
    }

    @Test fun `asking twice through the view model makes one request`() {
        runBlocking {
            val first = rig.viewModel.galleryCoverUrl("N74KSK")
            val second = rig.viewModel.galleryCoverUrl("N74KSK")
            assertEquals(first, second)
        }
        assertTrue(first(rig).isNotEmpty())
        assertEquals(1, rig.server.requestLog.count { it.url.encodedPath.endsWith("node/sXQz4G") })
    }

    private fun first(rig: ScenarioRig) = runBlocking { rig.viewModel.galleryCoverUrl("N74KSK").orEmpty() }
}
