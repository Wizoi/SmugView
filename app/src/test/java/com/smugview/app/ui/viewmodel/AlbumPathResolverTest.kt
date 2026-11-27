package com.smugview.app.ui.viewmodel

import com.smugview.app.data.db.CachedNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * R-66: this file used to test its own copies of the URL-to-gallery logic (one copy, `getPasswordForPhotoUrl`, was deleted from the app in
 * step 4-7), so it could stay green while the app's code broke. It now calls the one implementation the view model uses.
 */
class AlbumPathResolverTest {

    private fun node(id: String, webUri: String) = CachedNode(
        nodeId = id, parentNodeId = "root", type = "Folder", title = id, description = "", access = "Password", passwordHint = null,
        uri = "/api/v2/node/$id", childNodesUri = null, albumUri = null, highlightImageUrl = null, childCount = null, sortIndex = 0, webUri = webUri
    )

    @Test
    fun aThumbnailUrlGivesTheGalleryPathThatMatchesTheAlbumsUrlPath() {
        val thumbUrl = "https://photos.smugmug.com/Family/Holidays/2025-11-27--Family-Thanksgiving-in-Chicago/i-FQ56kDH/0/Th/2025-11%20THANKSGIVING%20-%20251%20-%20CLARA-Th.jpg"
        assertEquals(
            "family/holidays/2025-11-27--family-thanksgiving-in-chicago",
            PhotoUrlPath.normalized(thumbUrl)
        )
    }

    @Test
    fun aGalleryWebUriWithAQueryAndPercentEscapesIsDecoded() {
        assertEquals(
            "family/events/2016 to current",
            PhotoUrlPath.normalized("https://gallery.idzifamily.com/Family/Events/2016%20to%20Current?tab=photos")
        )
    }

    @Test
    fun nothingUsableGivesNull() {
        assertNull(PhotoUrlPath.normalized(null))
        assertNull(PhotoUrlPath.normalized(""))
    }

    @Test
    fun theNearestCachedAncestorIsTheDeepestFolderAlreadyCached() {
        val nodes = listOf(
            node("2sDN5x", "https://gallery.idzifamily.com/Family"),
            node("EvEnTs", "https://gallery.idzifamily.com/Family/Events")
        )
        val photoUrl = "https://photos.smugmug.com/Family/Events/2016-to-Current/2026-04-08--Roozen-Gaarde-Tulip-Fields/i-bXphZ8T/0/Th/clara-Th.jpg"
        assertEquals("the deepest cached folder wins", "EvEnTs", PhotoUrlPath.nearestAncestor(photoUrl, nodes)?.nodeId)
        assertEquals("2sDN5x", PhotoUrlPath.nearestAncestor(photoUrl, nodes.take(1))?.nodeId)
        assertNotNull(PhotoUrlPath.nearestAncestor(photoUrl, nodes))
        assertNull("no cached folder on the path", PhotoUrlPath.nearestAncestor(photoUrl, emptyList()))
    }
}
