package com.smugview.app.ui.viewmodel

import com.smugview.app.data.db.CachedNode
import io.ktor.http.decodeURLQueryComponent

/** The one place that turns a SmugMug photo or gallery URL into a gallery path, and finds the cached folder it sits under (R-66). */
object PhotoUrlPath {
    /** Host and query dropped, cut before `/i-` (the image part), percent-decoded, slashes trimmed, lower-cased. Null when there is nothing to match. */
    fun normalized(url: String?): String? {
        if (url.isNullOrEmpty()) return null
        val path = try {
            val withoutQuery = url.substringBefore("?")
            val afterHost = if (withoutQuery.contains("://")) withoutQuery.substringAfter("://").substringAfter("/") else withoutQuery
            val beforeImage = if (afterHost.contains("/i-")) afterHost.substringBefore("/i-") else afterHost
            beforeImage.decodeURLQueryComponent(plusIsSpace = true)
        } catch (e: Exception) {
            return null
        }
        return path.trim('/').lowercase()
    }

    /** The deepest cached node whose web path is a prefix of the photo's gallery path, or null. */
    fun nearestAncestor(photoUrl: String?, nodes: List<CachedNode>): CachedNode? {
        val segments = normalized(photoUrl)?.split('/') ?: return null
        for (i in segments.indices.reversed()) {
            val parentPath = segments.subList(0, i + 1).joinToString("/")
            val matched = nodes.find { node ->
                val nodePath = node.webUri?.substringAfter("://")?.substringAfter("/")?.trim('/')?.lowercase() ?: ""
                nodePath == parentPath
            }
            if (matched != null) return matched
        }
        return null
    }
}
