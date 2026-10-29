package com.smugview.app.download

/**
 * Where a photo's smaller sizes are, for when SmugMug has no original to give (findings #24: 107 of 850 originals in one gallery
 * answer 404 while the photo displays). Largest first. 5K is left out: it answers a redirect, not the picture.
 */
object Renditions {
    private val SIZES = listOf("X5", "X4", "X3", "XL", "L")

    /** From a thumbnail address (`/Th/…-Th.jpg`); empty when it is not one. */
    fun fromThumbnail(thumbnailUrl: String?): List<String> = swap(thumbnailUrl, "Th")

    /** From an original's address (`/D/…-D.jpg`); empty when it is not one. */
    fun fromOriginal(archivedUri: String?): List<String> = swap(archivedUri, "D")

    private fun swap(url: String?, from: String): List<String> {
        if (url == null || !url.startsWith("https://") || !url.contains("/$from/")) return emptyList()
        return SIZES.map { size -> url.replace("/$from/", "/$size/").replace("-$from.", "-$size.") }
    }
}
