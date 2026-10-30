package com.smugview.shared.api

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * iOS spike, Stage 0 step 3: the ported models read the REAL answers recorded by `ParityRecorder` (app/src/test/resources/parity), with
 * the strict reader. The same files the Gson models parse in `ModelParityTest`.
 */
class RealAnswersTest {
    // Unknown fields are ignored (SmugMug sends many); nothing is coerced.
    private val json = Json { ignoreUnknownKeys = true }
    private fun fixture(name: String) = readFixture(name)

    @Test fun siteProfileGivesTheNicknameAndTheRootNode() {
        val user = json.decodeFromString<UserResponse>(fixture("getUserProfile__site.json")).response.user
        assertEquals("idzifamily", user.nickName)
        assertNotNull(user.uris?.node)
        assertTrue(user.uris!!.node!!.contains("/node/"))
    }

    @Test fun folderListingGivesItsNodesAndItsPaging() {
        val page = json.decodeFromString<NodeListResponse>(fixture("getNodeChildren__root.json")).response
        val nodes = page.nodes.orEmpty()
        assertTrue(nodes.isNotEmpty(), "the root has children")
        assertTrue(nodes.all { it.nodeId.isNotEmpty() && (it.type == "Folder" || it.type == "Album") })
        assertNotNull(page.pages)
        assertEquals(nodes.size, page.pages!!.count)
    }

    @Test fun userSearchWithAnAccountThatHasNoGalleryReadsBecauseThoseTwoPropertiesAreOptional() {
        val users = json.decodeFromString<UserSearchResponse>(fixture("searchUsers__idzi.json")).response.users.orEmpty()
        assertTrue(users.size >= 2)
        val noGallery = users.firstOrNull { it.uris?.node == null }
        assertNotNull(noGallery, "the recorded search has a user with no node link")
        assertTrue(noGallery.nickName.isNotEmpty())
    }
}
