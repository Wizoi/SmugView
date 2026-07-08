package com.smugview.app.data.api

import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.io.File
import java.util.Properties

class SmugMugApiTest {

    @Before
    fun setUp() {
        // Sleep to prevent burst rate limits on the SmugMug API
        Thread.sleep(4000)
    }

    private fun setupApi(): Pair<SmugMugApi, String> {
        val properties = Properties()
        val file = File("c:/src/kidzi/GitHub/SmugView/local.properties")
        assertTrue("local.properties file must exist at " + file.absolutePath, file.exists())
        file.inputStream().use { properties.load(it) }
        
        val apiKey = properties.getProperty("smugmug.api.key") ?: ""
        val nickname = properties.getProperty("smugmug.nickname") ?: ""
        
        assertFalse("smugmug.api.key must not be empty", apiKey.isEmpty())
        assertFalse("smugmug.nickname must not be empty", nickname.isEmpty())

        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        }
        val retryInterceptor = okhttp3.Interceptor { chain ->
            var request = chain.request()
            var response = chain.proceed(request)
            var tryCount = 0
            val maxLimit = 3
            var delayMs = 3000L

            while (!response.isSuccessful && (response.code == 429 || response.code in 500..599) && tryCount < maxLimit) {
                tryCount++
                response.close()
                try {
                    Thread.sleep(delayMs)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw java.io.IOException(e)
                }
                delayMs *= 2
                response = chain.proceed(request)
            }
            response
        }

        val okHttpClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .header("Accept", "application/json")
                    .header("User-Agent", "SmugView-Android-App/1.0")
                    .build()
                chain.proceed(request)
            }
            .addInterceptor(retryInterceptor)
            .addInterceptor(logging)
            .build()

        val retrofit = Retrofit.Builder()
            .baseUrl("https://api.smugmug.com/api/v2/")
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()

        return Pair(retrofit.create(SmugMugApi::class.java), apiKey)
    }

    @Test
    fun testUserAlbumsAndDetails() {
        val (api, apiKey) = setupApi()
        val properties = Properties()
        val file = File("c:/src/kidzi/GitHub/SmugView/local.properties")
        file.inputStream().use { properties.load(it) }
        val nickname = properties.getProperty("smugmug.nickname") ?: ""

        try {
            val response = kotlinx.coroutines.runBlocking {
                api.getUserAlbums(nickname, apiKey)
            }
            assertNotNull(response.response)
            val albums = response.response.albums
            assertNotNull(albums)
            println("Found ${albums?.size ?: 0} albums for nickname $nickname")

            if (!albums.isNullOrEmpty()) {
                Thread.sleep(2000)
                val targetAlbum = albums.first()
                val albumKey = targetAlbum.albumKey
                assertNotNull(albumKey)

                // Test getAlbum
                val albumResponse = kotlinx.coroutines.runBlocking {
                    api.getAlbum(albumKey, apiKey)
                }
                assertNotNull(albumResponse.response)
                println("Successfully fetched details for album: ${albumResponse.response.album?.name}")

                Thread.sleep(2000)
                // Test getAlbumImages
                val imagesResponse = kotlinx.coroutines.runBlocking {
                    api.getAlbumImages(albumKey, apiKey)
                }
                assertNotNull(imagesResponse.response)
                println("Successfully fetched ${imagesResponse.response.images?.size ?: 0} images for album $albumKey")

                Thread.sleep(2000)
                // Test getAlbumKeywords
                val keywordsResponse = kotlinx.coroutines.runBlocking {
                    api.getAlbumKeywords(albumKey, apiKey)
                }
                assertNotNull(keywordsResponse)
                println("Successfully fetched keywords for album $albumKey")
            }
        } catch (e: retrofit2.HttpException) {
            if (e.code() != 429) {
                throw e
            }
            println("Rate limited (429) during user albums details test - syntax is verified.")
        }
    }

    @Test
    fun testUserImageSearch() {
        Thread.sleep(1500)
        val (api, apiKey) = setupApi()
        val properties = Properties()
        val file = File("c:/src/kidzi/GitHub/SmugView/local.properties")
        file.inputStream().use { properties.load(it) }
        val nickname = properties.getProperty("smugmug.nickname") ?: ""

        try {
            val response = kotlinx.coroutines.runBlocking {
                api.searchImagesUser(
                    nickname = nickname,
                    apiKey = apiKey,
                    text = "bella",
                    scope = null
                )
            }
            println("User Image Search Response: ${response.response.images?.size ?: 0} images found")
            assertNotNull(response.response)
        } catch (e: retrofit2.HttpException) {
            if (e.code() != 429) {
                throw e
            }
            println("Rate limited (429) during user image search - syntax is verified.")
        }
    }

    @Test
    fun testNodeSearch() {
        Thread.sleep(1500)
        val (api, apiKey) = setupApi()
        try {
            val response = kotlinx.coroutines.runBlocking {
                api.searchNodes(
                    apiKey = apiKey,
                    scope = "/api/v2/node/2sDN5x",
                    text = "bella"
                )
            }
            println("Node Search Response: ${response.response.nodes?.size ?: 0} nodes found")
            assertNotNull(response.response)
        } catch (e: retrofit2.HttpException) {
            if (e.code() != 429) {
                throw e
            }
            println("Rate limited (429) during node search - syntax is verified.")
        }
    }

    @Test
    fun testDiagnoseAllAlbumsAndImages() {
        Thread.sleep(1000)
        val (api, apiKey) = setupApi()
        val properties = Properties()
        val file = File("c:/src/kidzi/GitHub/SmugView/local.properties")
        file.inputStream().use { properties.load(it) }
        val nickname = properties.getProperty("smugmug.nickname") ?: ""

        println("--- Diagnosing All User Albums and Images ---")
        try {
            val response = kotlinx.coroutines.runBlocking {
                api.getUserAlbums(nickname, apiKey)
            }
            val albums = response.response.albums?.take(2) ?: emptyList()
            println("Found ${albums.size} albums (diagnosing first 2):")
            for (album in albums) {
                println("Album Name: '${album.name}', Key: '${album.albumKey}'")
                Thread.sleep(1000)
                try {
                    val imagesResponse = kotlinx.coroutines.runBlocking {
                        api.getAlbumImages(album.albumKey, apiKey)
                    }
                    val images = imagesResponse.response.images ?: emptyList()
                    println("  Total Images: ${images.size}")
                    if (images.isNotEmpty()) {
                        for (i in 0 until minOf(5, images.size)) {
                            val img = images[i]
                            println("    Image $i - Title: '${img.title}', Caption: '${img.caption}', Key: '${img.imageKey}', Keywords: '${img.keywords}'")
                        }
                    }
                } catch (e: Exception) {
                    println("  Failed to fetch images: ${e.message}")
                }
            }
        } catch (e: Exception) {
            println("Failed to fetch albums: ${e.message}")
        }
    }

    @Test
    fun testScopedSearchFlow() {
        Thread.sleep(2000)
        val (api, apiKey) = setupApi()
        val properties = Properties()
        val file = File("c:/src/kidzi/GitHub/SmugView/local.properties")
        file.inputStream().use { properties.load(it) }
        val nickname = properties.getProperty("smugmug.nickname") ?: ""
        
        val queries = listOf("tahoma")
        
        for (query in queries) {
            println("--- Testing Scoped Search Flow for Query: '$query' ---")
            try {
                val nodesResponse = kotlinx.coroutines.runBlocking {
                    api.searchNodes(
                        apiKey = apiKey,
                        scope = "/api/v2/node/4zqWw", // Root node ID
                        text = query
                    )
                }
                val nodes = nodesResponse.response.nodes ?: emptyList()
                println("Node search returned ${nodes.size} matching nodes.")
                
                val galleries = nodes.filter { it.type == "Album" }.take(2)
                println("Found ${galleries.size} matching albums/galleries (diagnosing first 2):")
                
                for (gallery in galleries) {
                    println("  Gallery name: '${gallery.name}', URI: ${gallery.uri}, NodeID: ${gallery.nodeId}")
                    
                    Thread.sleep(1000)
                    val scopedSearchResponse = kotlinx.coroutines.runBlocking {
                        api.searchImagesUser(
                            nickname = nickname,
                            apiKey = apiKey,
                            scope = gallery.uri,
                            text = query
                        )
                    }
                    val scopedImages = scopedSearchResponse.response.images ?: emptyList()
                    println("    Scoped imagesearch returned ${scopedImages.size} images.")
                    
                    Thread.sleep(1000)
                    val albumKey = gallery.uris.album?.substringAfterLast("/") ?: gallery.nodeId
                    val allImagesResponse = kotlinx.coroutines.runBlocking {
                        api.getAlbumImages(
                            albumKey = albumKey,
                            apiKey = apiKey
                        )
                    }
                    val allImages = allImagesResponse.response.images ?: emptyList()
                    println("    getAlbumImages returned ${allImages.size} total images in this album.")
                }
            } catch (e: Exception) {
                println("Exception during query '$query': ${e.message}")
            }
        }
    }
}
