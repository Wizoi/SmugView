package com.smugview.app.scenario

import com.smugview.app.data.db.CachedAlbum
import com.smugview.app.data.repository.FakeSmugMugServer.FakeAlbum
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Phase 4 step 4-10 (design 3.6, P9, R-34, Q3): Home summed the one page `user!albums` returned, so with 150
 * galleries the photo total counted 100 of them (live: 10,802 of 13,986 photos, 23% under). The totals now
 * come from the gallery index, so they cover every gallery the app can show and follow each crawl.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class HubTotalsTest {
    private lateinit var rig: ScenarioRig

    @Before fun setUp() {
        rig = ScenarioRig()
        // fixture F's two galleries (one in Family/School), one more in Family, and 147 old public ones: 150, so
        // `user!albums` has a second page that the hub's own request never reads.
        val more = FakeAlbum("fK0001", "fN0001", "Trip", "/Family/Trips/2026-08-01--Trip", rig.server.daysAgo(30), rig.server.daysAgo(30))
        val bulk = (1..147).map {
            val n = it.toString().padStart(4, '0')
            FakeAlbum("aK$n", "nD$n", "Old gallery $n", "/Kentridge/Old-$n", rig.server.daysAgo(60), rig.server.daysAgo(60))
        }
        rig.server.albums = rig.server.albums + more + bulk
    }

    @After fun tearDown() = rig.close()

    private val vm get() = rig.viewModel

    @Test fun `the totals are the whole index, not the first page`() {
        vm.selectSite("idzifamily")

        awaitUntil("the crawl indexed all 150", 20_000) { runBlocking { rig.dao.getAlbumIndexCount("idzifamily") } == 150 }
        awaitUntil("the hub's totals are the index's") { vm.activeSiteTotalGalleries.value == 150 && vm.activeSiteTotalPhotos.value == 150 * 12 }
        assertEquals(150, vm.activeSiteTotalGalleries.value)
        assertEquals("the sum covers the 50 galleries past page 1", 150 * 12, vm.activeSiteTotalPhotos.value)
    }

    @Test fun `the totals follow the index when a crawl adds a gallery`() {
        vm.selectSite("idzifamily")
        awaitUntil("the crawl indexed all 150", 20_000) { runBlocking { rig.dao.getAlbumIndexCount("idzifamily") } == 150 }
        awaitUntil("the hub's totals are the index's") { vm.activeSiteTotalPhotos.value == 150 * 12 }

        runBlocking {
            rig.dao.upsertAlbums(
                listOf(
                    CachedAlbum(
                        albumKey = "zK9999", nodeId = "zN9999", name = "Brand new", securityType = "None", passwordHint = null,
                        uri = "/api/v2/album/zK9999", webUri = null, urlPath = "/Family/Brand-new", imageCount = 500,
                        dateModified = null, galleryStyle = null, highlightImageUrl = null, nickname = "idzifamily"
                    )
                )
            )
        }

        awaitUntil("the totals include the new gallery") { vm.activeSiteTotalGalleries.value == 151 && vm.activeSiteTotalPhotos.value == 150 * 12 + 500 }
    }
}
