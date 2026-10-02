package com.smugview.app.scenario

import com.smugview.app.data.db.CachedNode
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Step 6-13 (design 3.9, Q6 (a), N8): SmugMug returns a password folder's photos to anyone (L4), so Home's "recent photos" row must
 * leave out a photo whose gallery sits under a password folder this phone has no session for, and show it once unlocked.
 *
 * The fake's recent images are `XVRvVTM` (public, `/Kentridge/Public`) and `ttDKqjt` (`/Family/Events/2025-to-Current/Fall-Picnic`,
 * under Family, a password folder). The cache holds Family as `Password` with its web address, as a browse leaves it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class HomeRecentPrivacyTest {
    private lateinit var rig: ScenarioRig

    @Before fun setUp() { rig = ScenarioRig() }
    @After fun tearDown() = rig.close()

    private val vm get() = rig.viewModel

    private val family = CachedNode(
        nodeId = "2sDN5x", parentNodeId = "root", type = "Folder", title = "Family", description = null,
        access = "Password", passwordHint = null, uri = "/api/v2/node/2sDN5x", childNodesUri = null, albumUri = null,
        webUri = "https://idzifamily.smugmug.com/Family", nickname = "idzifamily"
    )

    private fun cacheFamily() = runBlocking { rig.dao.insertNodes(listOf(family)) }

    private fun recent() = vm.activeSiteRecentImages.value.map { it.imageKey }

    private fun awaitHubLoaded() =
        awaitUntil("the hub finished loading", 10_000) { vm.activeSiteAlbums.value.isNotEmpty() && !vm.isActiveSiteDetailsLoading.value }

    /** Red on the old code: both photos were shown. */
    @Test fun `a recent photo under a password folder with no session is left out`() {
        cacheFamily()

        vm.selectSite("idzifamily")
        awaitHubLoaded()
        Thread.sleep(300) // negative wait: a late refilter must not bring it back

        assertEquals(listOf("XVRvVTM"), recent())
    }

    @Test fun `with a saved password the launch unlock gives a session and both photos show`() {
        cacheFamily()
        rig.passwords.savePassword("2sDN5x", "family-pw")

        vm.selectSite("idzifamily")
        awaitHubLoaded()

        awaitUntil("both recent photos") { recent().toSet() == setOf("XVRvVTM", "ttDKqjt") }
    }

    /** Red on the old code (the first assertion): hidden until the password is typed, then back without a reload. */
    @Test fun `typing the password later brings the photo back without reloading Home`() {
        cacheFamily()
        vm.selectSite("idzifamily")
        awaitHubLoaded()
        assertEquals(listOf("XVRvVTM"), recent())

        runBlocking { rig.repository.unlocks.submit(family, "family-pw", "test-key") }

        awaitUntil("the unlocked photo shows") { recent().toSet() == setOf("XVRvVTM", "ttDKqjt") }
    }

    @Test fun `a photo the cache cannot place under any folder still shows`() {
        vm.selectSite("idzifamily")
        awaitHubLoaded()

        assertEquals(setOf("XVRvVTM", "ttDKqjt"), recent().toSet())
    }
}
