package com.smugview.app.ui.external

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ExternalViewerTest {
    @Test fun blackWhenNothing() = assertNull(externalImageToShow(null, null))
    @Test fun blankIsBlack() = assertNull(externalImageToShow("", " "))
    @Test fun viewerImageShown() = assertEquals("a", externalImageToShow("a", null))
    @Test fun castWinsOverViewer() = assertEquals("c", externalImageToShow("a", "c"))

    @Test fun displayFollowsShowAndClear() {
        ExternalDisplay.show("x")
        assertEquals("x", ExternalDisplay.image.value)
        ExternalDisplay.clear()
        assertNull(ExternalDisplay.image.value)
    }
}
