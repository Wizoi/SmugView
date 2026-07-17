package com.smugview.app.ui.viewmodel

import com.smugview.app.data.api.AlbumDetails
import com.smugview.app.data.db.CachedNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.URLDecoder

class AlbumPathResolverTest {

    // Prospective helper method implementation to be tested
    fun resolveAlbumKeyFromUrl(targetUri: String?, userAlbums: List<AlbumDetails>): String? {
        val uriStr = targetUri ?: return null
        if (uriStr.isEmpty()) return null

        val path = try {
            val cleanedUriStr = uriStr.substringBefore("?")
            val pathStr = if (cleanedUriStr.contains("://")) {
                cleanedUriStr.substringAfter("://").substringAfter("/")
            } else {
                cleanedUriStr
            }
            val pathBeforeImage = if (pathStr.contains("/i-")) {
                pathStr.substringBefore("/i-")
            } else {
                pathStr
            }
            URLDecoder.decode(pathBeforeImage, "UTF-8")
        } catch (e: Exception) {
            null
        } ?: return null

        val normalizedPath = path.trim('/').lowercase()

        val matchedAlbum = userAlbums.find { album ->
            val albumPath = album.urlPath?.trim('/')?.lowercase() ?: ""
            albumPath == normalizedPath
        }

        return matchedAlbum?.albumKey
    }

    // Prospective password resolver from URL path traversing cached nodes
    fun getPasswordForPhotoUrl(photoUrl: String?, cachedNodes: List<CachedNode>, passwordPrefs: Map<String, String>): String? {
        if (photoUrl.isNullOrEmpty()) return null
        
        val path = try {
            val cleanedUriStr = photoUrl.substringBefore("?")
            val pathStr = if (cleanedUriStr.contains("://")) {
                cleanedUriStr.substringAfter("://").substringAfter("/")
            } else {
                cleanedUriStr
            }
            val pathBeforeImage = if (pathStr.contains("/i-")) {
                pathStr.substringBefore("/i-")
            } else {
                pathStr
            }
            URLDecoder.decode(pathBeforeImage, "UTF-8")
        } catch (e: Exception) {
            null
        } ?: return null

        val segments = path.trim('/').split('/')
        
        // Traverse parent paths up to the root (e.g. Family/Events/2016-to-Current -> Family/Events -> Family)
        for (i in segments.indices.reversed()) {
            val parentPath = segments.subList(0, i + 1).joinToString("/").lowercase()
            val matchedNode = cachedNodes.find { node ->
                val nodePath = node.webUri?.substringAfter("://")?.substringAfter("/")?.trim('/')?.lowercase() ?: ""
                nodePath == parentPath
            }
            if (matchedNode != null) {
                val pw = passwordPrefs[matchedNode.nodeId] ?: passwordPrefs[matchedNode.getAlbumKey()]
                if (!pw.isNullOrEmpty()) return pw
            }
        }
        return null
    }

    // Prospective finder of the nearest cached ancestor node (public or locked)
    fun getNearestCachedAncestorNode(photoUrl: String?, cachedNodes: List<CachedNode>): CachedNode? {
        if (photoUrl.isNullOrEmpty()) return null
        
        val path = try {
            val cleanedUriStr = photoUrl.substringBefore("?")
            val pathStr = if (cleanedUriStr.contains("://")) {
                cleanedUriStr.substringAfter("://").substringAfter("/")
            } else {
                cleanedUriStr
            }
            val pathBeforeImage = if (pathStr.contains("/i-")) {
                pathStr.substringBefore("/i-")
            } else {
                pathStr
            }
            URLDecoder.decode(pathBeforeImage, "UTF-8")
        } catch (e: Exception) {
            null
        } ?: return null

        val segments = path.trim('/').split('/')
        
        // Traverse parent paths up to the root (e.g. Family/Events/2016-to-Current -> Family/Events -> Family)
        for (i in segments.indices.reversed()) {
            val parentPath = segments.subList(0, i + 1).joinToString("/").lowercase()
            val matchedNode = cachedNodes.find { node ->
                val nodePath = node.webUri?.substringAfter("://")?.substringAfter("/")?.trim('/')?.lowercase() ?: ""
                nodePath == parentPath
            }
            if (matchedNode != null) {
                return matchedNode
            }
        }
        return null
    }

    @Test
    fun testResolveAlbumKeyFromThumbnailUrl() {
        val userAlbums = listOf(
            AlbumDetails(
                uri = "",
                albumKey = "ScfKZw",
                urlPath = "Family/Holidays/2025-11-27--Family-Thanksgiving-in-Chicago",
                name = "Thanksgiving"
            ),
            AlbumDetails(
                uri = "",
                albumKey = "abc123",
                urlPath = "MVYSO/2026-07-03-MVYSO-4th-of-July-Fireworks",
                name = "Fireworks"
            )
        )

        val thumbUrl = "https://photos.smugmug.com/Family/Holidays/2025-11-27--Family-Thanksgiving-in-Chicago/i-FQ56kDH/0/Th/2025-11%20THANKSGIVING%20-%20251%20-%20CLARA-Th.jpg"
        val resolvedKey1 = resolveAlbumKeyFromUrl(thumbUrl, userAlbums)
        assertEquals("ScfKZw", resolvedKey1)
    }

    @Test
    fun testResolvePasswordForLockedPhotoUrl() {
        val cachedNodes = listOf(
            CachedNode(
                nodeId = "2sDN5x",
                parentNodeId = "root",
                type = "Folder",
                title = "Family",
                description = "",
                access = "Password",
                passwordHint = "Check hints",
                uri = "/api/v2/node/2sDN5x",
                childNodesUri = null,
                albumUri = null,
                highlightImageUrl = null,
                childCount = null,
                sortIndex = 0,
                webUri = "https://gallery.idzifamily.com/Family"
            )
        )

        val passwordPrefs = mapOf("2sDN5x" to "unlocked_family_pass")
        val photoUrl = "https://photos.smugmug.com/Family/Events/2016-to-Current/2026-04-08--Roozen-Gaarde-Tulip-Fields/i-bXphZ8T/0/Th/clara-Th.jpg"

        // 1. Verify password resolving
        val resolvedPassword = getPasswordForPhotoUrl(photoUrl, cachedNodes, passwordPrefs)
        assertEquals("unlocked_family_pass", resolvedPassword)

        // 2. Verify we locate the nearest cached ancestor (Family folder)
        val ancestor = getNearestCachedAncestorNode(photoUrl, cachedNodes)
        assertNotNull(ancestor)
        assertEquals("2sDN5x", ancestor?.nodeId)
    }
}
