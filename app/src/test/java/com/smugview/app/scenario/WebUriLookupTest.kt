package com.smugview.app.scenario

import com.smugview.app.data.repository.FakeSmugMugServer.FakeAlbum
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Phase 4 step 4-10 (design 3.6, review "page-1 lookups"): a search photo's WebUri is matched to its gallery
 * through `user!albums` page 1, so a photo in gallery 101 or later could not be opened in its gallery. The
 * gallery index has every gallery's UrlPath.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class WebUriLookupTest {
    private lateinit var rig: ScenarioRig

    @Before fun setUp() {
        rig = ScenarioRig()
        val bulk = (1..148).map {
            val n = it.toString().padStart(4, '0')
            FakeAlbum("aK$n", "nD$n", "Old gallery $n", "/Kentridge/Old-$n", rig.server.daysAgo(60), rig.server.daysAgo(60))
        }
        rig.server.albums = rig.server.albums + bulk // 150 galleries; the last is #150
    }

    @After fun tearDown() = rig.close()

    private val vm get() = rig.viewModel

    @Test fun `a photo in gallery 150 resolves to its AlbumKey, not the NodeID`() {
        vm.selectSite("idzifamily")
        awaitUntil("the crawl indexed all 150", 20_000) { runBlocking { rig.dao.getAlbumIndexCount("idzifamily") } == 150 }

        val key = runBlocking { vm.getAlbumKeyFromWebUri("https://gallery.idzifamily.com/Kentridge/Old-0148/i-AbCdEf?x=1") }

        assertEquals("aK0148", key)
    }

    @Test fun `the match ignores case and a trailing slash, and an unknown path is null`() {
        vm.selectSite("idzifamily")
        awaitUntil("the crawl indexed all 150", 20_000) { runBlocking { rig.dao.getAlbumIndexCount("idzifamily") } == 150 }

        assertEquals("aK0001", runBlocking { vm.getAlbumKeyFromWebUri("https://gallery.idzifamily.com/kentridge/OLD-0001/") })
        assertNull(runBlocking { vm.getAlbumKeyFromWebUri("https://gallery.idzifamily.com/Kentridge/Nope/i-x") })
    }
}
