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
        // Resume from the page closest to the user's current position rather than always
        // restarting at page 1 (which snapped the user back to the top of the album on any
        // invalidation). Falling back to null simply reloads from the start.
        val anchorPosition = state.anchorPosition ?: return null
        val anchorPage = state.closestPageToPosition(anchorPosition) ?: return null
        return anchorPage.prevKey ?: anchorPage.nextKey
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
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Never convert cancellation into a load error — it must propagate so structured
            // concurrency can unwind (e.g. when the user navigates away mid-load).
            throw e
        } catch (e: Exception) {
            LoadResult.Error(e)
        }
    }
}
