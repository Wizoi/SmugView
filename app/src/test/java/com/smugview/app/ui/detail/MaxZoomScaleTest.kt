package com.smugview.app.ui.detail

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The viewer's pinch zoom stops at one image pixel per screen pixel and no sooner. It used to stop at 10x, and it divided by the
 * viewer's width, which is wrong for a photo that is fitted by height. Sizes are real: the Montego Bay panorama (14326x2301), a
 * portrait photo in a landscape viewer, and a phone-sized viewer (1080x2200 px).
 */
class MaxZoomScaleTest {
    private val delta = 0.01f

    /** Red on the old code: it answered 10. */
    @Test fun `a panorama zooms to one image pixel per screen pixel, past 10x`() {
        assertEquals(14326f / 1080f, maxZoomScale(14326, 2301, 1080, 2200), delta)
    }

    /** Red on the old code: it answered 3000/2200 = 1.36. Fitted by height, the photo is 810 px wide at 1x. */
    @Test fun `a portrait photo in a landscape viewer is measured against its fitted width`() {
        assertEquals(3000f / 810f, maxZoomScale(3000, 4000, 2200, 1080), delta)
    }

    @Test fun `a landscape photo in a portrait viewer is measured against the viewer width`() {
        assertEquals(4000f / 1080f, maxZoomScale(4000, 3000, 1080, 2200), delta)
    }

    @Test fun `a photo smaller than the viewer does not zoom`() {
        assertEquals(1f, maxZoomScale(800, 600, 1080, 2200), delta)
    }

    @Test fun `an unknown size gets a generous limit rather than none`() {
        assertEquals(UNKNOWN_SIZE_MAX_ZOOM, maxZoomScale(null, null, 1080, 2200), delta)
    }
}
