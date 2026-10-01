package com.smugview.app.ui.detail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Step 4-9 (design 3.5, R-29): the pinch zoom's instant tier-1 guess is the X3 rendition derived from the
 * thumbnail, never the original. It used to be `ArchivedUri` first, so every zoom downloaded the original
 * before the real tiers arrived. URL shapes are SmugMug's real ones (`/Th/...-Th.jpg`, `/D/...-D.jpg`).
 */
class ZoomFallbackUrlTest {
    private val thumb = "https://photos.smugmug.com/Kentridge/i-XVRvVTM/0/NbNCS8znqwdbpVdkj44bmsjzdHQKGxBBcNDwHh8NW/Th/photo%20-01-Th.jpg"
    private val original = "https://photos.smugmug.com/Kentridge/i-XVRvVTM/0/LpBgTg9cDN94GB8N7ktshHxFxThNk6GtVB496sn5J/D/photo%20-01-D.jpg"

    /** Red on the old code: it answered [original]. */
    @Test fun `the guess is the X3 URL derived from the thumbnail, not the original`() {
        assertEquals(
            "https://photos.smugmug.com/Kentridge/i-XVRvVTM/0/NbNCS8znqwdbpVdkj44bmsjzdHQKGxBBcNDwHh8NW/X3/photo%20-01-X3.jpg",
            zoomFallbackUrl(thumb, original)
        )
    }

    @Test fun `the original is the last resort when there is no thumbnail`() {
        assertEquals(original, zoomFallbackUrl(null, original))
    }

    @Test fun `no thumbnail and no original gives nothing`() {
        assertNull(zoomFallbackUrl(null, null))
    }

    /** 5-9 (Q2): a photo saved on this phone zooms from its own file, with no network. Red on the old code: it answered the X3 URL. */
    @Test fun `a saved copy wins over the X3 guess`() {
        val saved = "file:///data/user/0/com.smugview.app/files/offline/XVRvVTM.orig.jpg"
        assertEquals(saved, zoomFallbackUrl(thumb, original, saved))
        assertEquals(saved, viewerImageModel(thumb, original, saved))
        assertEquals(saved, viewerImageModel(null, null, saved))
    }
}
