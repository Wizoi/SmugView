package com.smugview.app.data.repository

import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.smugview.app.data.api.AlbumImageData
import com.smugview.app.data.api.isVideo
import com.smugview.app.data.api.SmugMugApi

class PhotoPagingSource(
    private val api: SmugMugApi,
    private val apiKey: String,
    private val albumKey: String,
    private val password: String? = null
) : PagingSource<String, AlbumImageData>() {

    override fun getRefreshKey(state: PagingState<String, AlbumImageData>): String? {
        // Return null to load from the start during refresh
        return null
    }

    override suspend fun load(params: LoadParams<String>): LoadResult<String, AlbumImageData> {
        val nextUrl = params.key
        return try {
            val response = if (nextUrl == null) {
                api.getAlbumImages(albumKey, apiKey, password)
            } else {
                val overriddenUrl = if (nextUrl.contains("count=")) {
                    nextUrl.replace(Regex("count=\\d+"), "count=500")
                } else {
                    val separator = if (nextUrl.contains("?")) "&" else "?"
                    "$nextUrl${separator}count=500"
                }
                val absoluteUrl = if (overriddenUrl.startsWith("http")) overriddenUrl else "https://api.smugmug.com$overriddenUrl"
                api.getAlbumImagesByUri(absoluteUrl, apiKey, password)
            }

            val images = response.response.images ?: emptyList()
            val expansions = response.expansions
            images.forEach { img ->
                if (img.isVideo) {
                    val largestVideoUri = img.uris?.largestVideo
                    if (largestVideoUri != null && expansions != null) {
                        img.videoUrl = expansions[largestVideoUri]?.largestVideo?.url
                    }
                }
            }

            val pages = response.response.pages
            val nextKey = pages?.next

            LoadResult.Page(
                data = images,
                prevKey = null, // Only paging forward
                nextKey = nextKey
            )
        } catch (e: Exception) {
            LoadResult.Error(e)
        }
    }
}
