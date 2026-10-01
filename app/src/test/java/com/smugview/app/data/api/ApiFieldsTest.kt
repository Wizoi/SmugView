package com.smugview.app.data.api

import com.smugview.app.data.repository.FakeSmugMugServer
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

/**
 * Step 4-9 (design 3.5, 3.1, R-29, P4): the fields the app reads must be asked for. `Uris.ImageSizeDetails`
 * is present only when `_filteruri` lists it, and the pinch zoom reads it, so a query without it left the
 * zoom without its real tiers. `DateTime`, `WebUri` and `ImageAlbum` are never present on these payloads,
 * so they are not requested. The fake honours `_filteruri` the way the live API does (it drops `Uris` that
 * are not listed).
 */
class ApiFieldsTest {
    private val server = FakeSmugMugServer().also { it.imageSearchTotal = 3 }
    private var sent: Request? = null
    private val api: SmugMugApi = run {
        val client = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain -> sent = chain.request(); chain.proceed(chain.request()) })
            .addInterceptor(server.interceptor)
            .build()
        Retrofit.Builder().baseUrl("https://api.smugmug.com/api/v2/")
            .addConverterFactory(GsonConverterFactory.create()).client(client).build().create(SmugMugApi::class.java)
    }

    private fun requested(name: String): Set<String> =
        sent!!.url.queryParameter(name)?.split(',')?.toSet().orEmpty()

    /** Red on the old code: `uris.imageSizeDetails` was null (`_filteruri` lacked it). */
    @Test fun `an album's photos carry the ImageSizeDetails link the zoom reads`() = runBlocking {
        val image = api.getAlbumImages("N74KSK", "test-key").response.images!!.first()

        assertNotNull("Uris.ImageSizeDetails", image.uris?.imageSizeDetails)
        assertEquals("/api/v2/image/${image.imageKey}-0!sizedetails", image.uris?.imageSizeDetails)
    }

    @Test fun `search results carry the ImageSizeDetails link`() = runBlocking {
        val image = api.searchImages("test-key", scope = "/api/v2/user/idzifamily", text = "kentridge").response.images!!.first()

        assertNotNull("Uris.ImageSizeDetails", image.uris?.imageSizeDetails)
    }

    @Test fun `keyword results carry the ImageSizeDetails link`() = runBlocking {
        val image = api.getImagesByKeyword("test-key", scope = "/api/v2/user/idzifamily", text = "kentridge").response.images!!.first()

        assertNotNull("Uris.ImageSizeDetails", image.uris?.imageSizeDetails)
    }

    @Test fun `a single photo's details carry the ImageSizeDetails link`() = runBlocking {
        val image = api.getImage("N74KSKi001-0", "test-key").response.image

        assertNotNull("Uris.ImageSizeDetails", image.uris?.imageSizeDetails)
    }

    /** DateTime, WebUri and ImageAlbum are never present (P4): asking for them only lengthens the URL. */
    @Test fun `fields that are never present are not requested`() = runBlocking {
        val calls: List<Pair<String, suspend () -> Any?>> = listOf(
            "getAlbumImages" to { api.getAlbumImages("N74KSK", "test-key") },
            "searchImages" to { api.searchImages("test-key", scope = "/api/v2/user/idzifamily", text = "kentridge") },
            "getImagesByKeyword" to { api.getImagesByKeyword("test-key", scope = "/api/v2/user/idzifamily", text = "kentridge") },
            "getUserRecentImages" to { runCatching { api.getUserRecentImages("idzifamily", "test-key") } },
            "getImage" to { api.getImage("N74KSKi001-0", "test-key") }
        )
        for ((name, call) in calls) {
            runCatching { call() }
            val fields = requested("_filter")
            val links = requested("_filteruri")
            assertFalse("$name requests DateTime", "DateTime" in fields)
            assertFalse("$name requests WebUri", "WebUri" in fields)
            assertFalse("$name requests ImageAlbum", "ImageAlbum" in links)
        }
    }
}
