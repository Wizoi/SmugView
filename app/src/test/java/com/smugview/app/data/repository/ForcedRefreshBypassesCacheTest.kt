package com.smugview.app.data.repository

import com.smugview.app.data.api.SmugMugApi
import com.smugview.app.data.db.AppDatabase
import com.smugview.app.data.db.CollectionDao
import com.smugview.app.data.db.TestDb
import com.smugview.app.data.security.FakePasswordStore
import com.smugview.app.di.smugMugCacheRewriteInterceptor
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.Cache
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.net.ServerSocket
import kotlin.concurrent.thread

/**
 * Phase 2 step 2-4 / R-35. SmugMug sends `Cache-Control: private, no-store, no-cache, max-age=0`
 * (findings V11) and the app's network interceptor rewrites it to `max-age=300`, so a forced listing
 * inside the next 5 minutes was answered from the HTTP cache and never reached the server.
 * Real OkHttp [Cache] + the production cache rewrite + a real loopback server.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class ForcedRefreshBypassesCacheTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var db: AppDatabase
    private lateinit var dao: CollectionDao
    private lateinit var server: ServerSocket
    private val hits = java.util.Collections.synchronizedList(mutableListOf<String>())

    @Before fun setUp() {
        db = TestDb.inMemory()
        dao = db.collectionDao()
        server = ServerSocket(0)
        thread(isDaemon = true) {
            while (!server.isClosed) {
                runCatching {
                    server.accept().use { s ->
                        val r = s.getInputStream().bufferedReader()
                        val first = r.readLine().orEmpty()
                        while (r.readLine().orEmpty().isNotEmpty()) { /* skip headers */ }
                        hits += first.substringBefore(" HTTP")
                        val body = """{"Response":{"Node":[{"Uri":"/api/v2/node/LCdk7F","NodeID":"LCdk7F","Type":"Album","Name":"New School Year","Uris":{"Album":"/api/v2/album/FfHCms"}}]},"Code":200}"""
                        s.getOutputStream().apply {
                            write(
                                ("HTTP/1.1 200 OK\r\nCache-Control: private, no-store, no-cache, max-age=0\r\n" +
                                    "Content-Type: application/json\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body").toByteArray()
                            )
                            flush()
                        }
                    }
                }
            }
        }
    }

    @After fun tearDown() {
        server.close()
        db.close()
    }

    private fun api(): SmugMugApi {
        val client = OkHttpClient.Builder()
            .cache(Cache(tmp.newFolder("http_cache"), 10L * 1024 * 1024))
            .addNetworkInterceptor(smugMugCacheRewriteInterceptor())
            .build()
        return Retrofit.Builder().baseUrl("http://localhost:${server.localPort}/api/v2/").client(client)
            .addConverterFactory(GsonConverterFactory.create()).build().create(SmugMugApi::class.java)
    }

    private fun repo(api: SmugMugApi) = SmugMugRepository(
        api, dao, FakePasswordStore(), org.mockito.Mockito.mock(android.content.Context::class.java)
    )

    @Test fun control_withoutTheHeader_theSecondCallIsServedFromTheCache() {
        val api = api()
        runBlocking {
            api.getNodeChildren("P4BKB", "k")
            api.getNodeChildren("P4BKB", "k")
        }
        assertEquals("the rewrite makes the 2nd call a cache hit (R-35 premise)", 1, hits.size)
    }

    @Test fun apiHeader_noCache_reachesTheServerEveryTime() {
        val api = api()
        runBlocking {
            api.getNodeChildren("P4BKB", "k", cacheControl = "no-cache")
            api.getNodeChildren("P4BKB", "k", cacheControl = "no-cache")
        }
        assertEquals(2, hits.size)
    }

    @Test fun forcedListings_twiceWithinFiveMinutes_bothReachTheServer() {
        val repository = repo(api())
        runBlocking {
            repository.getNodeChildren("P4BKB", "k", forceRefresh = true).toList()
            repository.getNodeChildren("P4BKB", "k", forceRefresh = true).toList()
        }
        assertEquals("both forced fetches must hit the network: $hits", 2, hits.size)
        assertTrue(hits.all { it.startsWith("GET /api/v2/node/P4BKB!children") })
    }

    @Test fun unforcedListing_isStillServedFromRoom_withNoRequestAtAll() {
        val repository = repo(api())
        runBlocking {
            repository.getNodeChildren("P4BKB", "k", forceRefresh = true).toList()
            repository.getNodeChildren("P4BKB", "k").toList()
        }
        assertEquals(1, hits.size)
    }
}
