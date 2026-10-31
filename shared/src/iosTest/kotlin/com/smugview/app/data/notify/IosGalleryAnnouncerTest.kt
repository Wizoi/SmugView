package com.smugview.app.data.notify

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.time.Duration.Companion.seconds

/**
 * A test process on the simulator has not been granted notification permission, so the announcer must say "not posted"
 * (false) and not throw. If a CI runner ever grants it, the empty-list case still holds.
 */
class IosGalleryAnnouncerTest {
    private val gallery = ListedGallery(
        nodeId = "nodeAAA", name = "Spring recital", webUri = "https://example.smugmug.com/Spring", urlPath = "/Spring", albumKey = "AbCd12"
    )

    @Test
    fun anEmptyListPostsNothing() = runTest(timeout = 30.seconds) {
        assertFalse(IosGalleryAnnouncer().announce("example", emptyList()))
    }

    @Test
    fun withoutAuthorisationNothingIsPostedAndNothingThrows() = runTest(timeout = 30.seconds) {
        val posted = IosGalleryAnnouncer().announce("example", listOf(gallery))
        println("IosGalleryAnnouncerTest: announce returned $posted")
        assertFalse(posted, "notifications are not authorised in a test process")
    }
}
