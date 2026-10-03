package com.smugview.app.data.notify

import com.smugview.app.data.api.SmugMugApi

/** The poll: page 1 of `user!albums` by date added, as [ListedGallery]. A gallery with no NodeID cannot be told apart, so it is dropped. */
class NewestGalleries(private val api: SmugMugApi, private val apiKey: String) {
    suspend operator fun invoke(nickname: String): List<ListedGallery> =
        api.getNewestAlbums(nickname, apiKey).response.albums.orEmpty().mapNotNull { a ->
            a.nodeId?.takeIf { it.isNotEmpty() }?.let { ListedGallery(it, a.name, a.webUri, a.urlPath) }
        }
}
