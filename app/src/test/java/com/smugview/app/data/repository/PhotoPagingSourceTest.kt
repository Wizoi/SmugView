package com.smugview.app.data.repository

import androidx.paging.PagingConfig
import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.smugview.app.data.api.AlbumImageData
import com.smugview.app.data.api.AlbumImagesPayload
import com.smugview.app.data.api.AlbumImagesResponse
import com.smugview.app.data.api.PagesData
import com.smugview.app.data.api.SmugMugApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.mockito.Mockito

class PhotoPagingSourceTest {

    private fun source(api: SmugMugApi) =
        PhotoPagingSource(api = api, apiKey = "KEY", albumKey = "album1", password = null)

    private fun response(images: List<String>, next: String?) = AlbumImagesResponse(
        response = AlbumImagesPayload(
            images = images.map { AlbumImageData(imageKey = it, title = it) },
            pages = PagesData(start = 1, count = images.size, total = 100, nextField = next)
        )
    )

    @Test
    fun load_firstPage_returnsPageWithNextKey() = runTest {
        val api = Mockito.mock(SmugMugApi::class.java)
        Mockito.`when`(
            api.getAlbumImages(
                Mockito.anyString(), Mockito.anyString(), Mockito.isNull(),
                Mockito.anyInt(), Mockito.anyString(), Mockito.anyString(),
                Mockito.anyString(), Mockito.anyInt(), Mockito.isNull()
            )
        ).thenReturn(response(listOf("a", "b"), next = "/api/v2/album/album1!images?start=3"))

        val result = source(api).load(
            PagingSource.LoadParams.Refresh(key = null, loadSize = 2, placeholdersEnabled = false)
        )

        assertTrue(result is PagingSource.LoadResult.Page)
        val page = result as PagingSource.LoadResult.Page
        assertEquals(listOf("a", "b"), page.data.map { it.imageKey })
        assertNull(page.prevKey)
        assertEquals("/api/v2/album/album1!images?start=3", page.nextKey)
    }

    @Test
    fun load_lastPage_hasNullNextKey() = runTest {
        val api = Mockito.mock(SmugMugApi::class.java)
        Mockito.`when`(
            api.getAlbumImages(
                Mockito.anyString(), Mockito.anyString(), Mockito.isNull(),
                Mockito.anyInt(), Mockito.anyString(), Mockito.anyString(),
                Mockito.anyString(), Mockito.anyInt(), Mockito.isNull()
            )
        ).thenReturn(response(listOf("z"), next = null))

        val result = source(api).load(
            PagingSource.LoadParams.Refresh(key = null, loadSize = 2, placeholdersEnabled = false)
        )
        val page = result as PagingSource.LoadResult.Page
        assertNull(page.nextKey)
    }

    @Test
    fun load_apiError_returnsLoadResultError() = runTest {
        val api = Mockito.mock(SmugMugApi::class.java)
        Mockito.`when`(
            api.getAlbumImages(
                Mockito.anyString(), Mockito.anyString(), Mockito.isNull(),
                Mockito.anyInt(), Mockito.anyString(), Mockito.anyString(),
                Mockito.anyString(), Mockito.anyInt(), Mockito.isNull()
            )
        ).thenThrow(RuntimeException("boom"))

        val result = source(api).load(
            PagingSource.LoadParams.Refresh(key = null, loadSize = 2, placeholdersEnabled = false)
        )
        assertTrue(result is PagingSource.LoadResult.Error)
    }

    @Test
    fun load_cancellation_isRethrown_notWrappedAsError() = runBlocking {
        val api = Mockito.mock(SmugMugApi::class.java)
        Mockito.`when`(
            api.getAlbumImages(
                Mockito.anyString(), Mockito.anyString(), Mockito.isNull(),
                Mockito.anyInt(), Mockito.anyString(), Mockito.anyString(),
                Mockito.anyString(), Mockito.anyInt(), Mockito.isNull()
            )
        ).thenThrow(CancellationException("cancelled"))

        try {
            source(api).load(
                PagingSource.LoadParams.Refresh(key = null, loadSize = 2, placeholdersEnabled = false)
            )
            fail("Expected CancellationException to propagate")
        } catch (e: CancellationException) {
            // expected: structured concurrency must see the cancellation, not a LoadResult.Error
        }
        Unit
    }

    @Test
    fun getRefreshKey_withAnchor_derivesKeyFromClosestPage() {
        val src = source(Mockito.mock(SmugMugApi::class.java))
        val page = PagingSource.LoadResult.Page(
            data = listOf(AlbumImageData(imageKey = "a", title = "a")),
            prevKey = "PREV",
            nextKey = "NEXT"
        )
        val state = PagingState(
            pages = listOf(page),
            anchorPosition = 0,
            config = PagingConfig(pageSize = 1),
            leadingPlaceholderCount = 0
        )
        // Non-null and drawn from the anchored page (prevKey preferred, else nextKey).
        assertEquals("PREV", src.getRefreshKey(state))
    }

    @Test
    fun getRefreshKey_withoutAnchor_isNull() {
        val src = source(Mockito.mock(SmugMugApi::class.java))
        val state = PagingState<String, AlbumImageData>(
            pages = emptyList(),
            anchorPosition = null,
            config = PagingConfig(pageSize = 1),
            leadingPlaceholderCount = 0
        )
        assertNull(src.getRefreshKey(state))
    }
}
