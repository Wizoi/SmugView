package com.smugview.app.data.api

import com.google.gson.JsonParser
import com.smugview.app.data.repository.AcceptedParams
import com.smugview.app.data.repository.FakeSmugMugServer
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

/**
 * Phase 4 step 4-1 (design 3.1, R-23, R-28, P14): the app may send only parameters SmugMug accepts, and
 * what it sends must equal what SmugMug echoes in `Response.Uri` (which drops every parameter the endpoint
 * does not accept, silently). One case per [SmugMugApi] method, every optional parameter non-null,
 * against [FakeSmugMugServer], which answers the echo the way the live API does ([AcceptedParams]).
 *
 * `Password` is sent on the GETs below and is accepted by none of them (P1, R-23): until step 4-7 removes
 * it, [EXPECTED_VIOLATIONS] is the EXACT set of methods and keys that still break the contract. A case
 * fails when a violation appears or disappears, so the set can only shrink on purpose.
 */
@RunWith(Parameterized::class)
class ApiContractTest(private val method: String) {

    companion object {
        /** A placeholder; the fake never checks it and the echo never carries it. */
        private const val K = "test-key"

        /** Method name to the parameter names it sends that its endpoint does not accept. */
        val EXPECTED_VIOLATIONS: Map<String, Set<String>> = mapOf(
            "getNodeChildren" to setOf("Password"),
            "getNodeChildrenByUri" to setOf("Password"),
            "getAlbum" to setOf("Password"),
            "getAlbumImages" to setOf("Password"),
            "getAlbumImagesByUri" to setOf("Password"),
            "getImageSizeDetailsByUri" to setOf("Password"),
            "searchImagesUserByUri" to setOf("Password"),
            "searchNodes" to setOf("Password"),
            "getUserRecentImages" to setOf("Password"),
            "getImage" to setOf("Password"),
            "getImageExif" to setOf("Password"),
            "getUserAlbums" to setOf("Password"),
            "getUserTopKeywords" to setOf("Password"),
            "getAlbumKeywords" to setOf("Password")
        )

        /** POST and PATCH calls: their form and body fields are not query parameters; the query is `APIKey` only. */
        private val NOT_QUERY_CONTRACT = setOf("unlockNode", "unlockAlbum", "updateImageMetadata")

        /** One call per method, with every optional parameter given. Keys must equal the interface's methods. */
        val CASES: Map<String, suspend SmugMugApi.() -> Any?> = linkedMapOf(
            "getUserProfile" to { getUserProfile("idzifamily", K, ignoreErrors = "true") },
            "getUserBioImage" to { getUserBioImage("idzifamily", K) },
            "getNodeChildren" to { getNodeChildren("4zqWw", K, password = "pw", ignoreErrors = "true", cacheControl = "no-cache") },
            "getNodeChildrenByUri" to {
                getNodeChildrenByUri("/api/v2/node/4zqWw!children?count=100&start=101", K, password = "pw", ignoreErrors = "true", cacheControl = "no-cache")
            },
            "getNode" to { getNode("4zqWw", K, expand = "HighlightImage", ignoreErrors = "true") },
            "getNodeParents" to { getNodeParents("sXQz4G", K, ignoreErrors = "true") },
            "getAlbum" to { getAlbum("N74KSK", K, password = "pw", ignoreErrors = "true") },
            "getAlbumImages" to { getAlbumImages("N74KSK", K, password = "pw", ignoreErrors = "true") },
            "getAlbumImagesByUri" to {
                getAlbumImagesByUri("/api/v2/album/N74KSK!images?start=11&count=10", K, password = "pw", ignoreErrors = "true")
            },
            "getImageSizeDetailsByUri" to { getImageSizeDetailsByUri("/api/v2/image/N74KSKi001-0!sizedetails", K, password = "pw") },
            "searchImages" to {
                searchImages(K, scope = "/api/v2/node/4zqWw", text = "kentridge", sortMethod = "DateAdded", sortDirection = "Descending", expand = "ImageAlbum")
            },
            "searchImagesByUri" to { searchImagesByUri("/api/v2/image!search?Text=kentridge&start=101&count=100", K) },
            "searchUsers" to { searchUsers(K, "idzi") },
            "getUserRecentImages" to { getUserRecentImages("idzifamily", K, password = "pw") },
            "searchImagesUserByUri" to { searchImagesUserByUri("/api/v2/image!search?Text=kentridge&start=101&count=100", K, password = "pw") },
            "searchNodes" to { searchNodes(K, scope = "/api/v2/node/4zqWw", text = "meet", password = "pw") },
            "getImage" to { getImage("N74KSKi001-0", K, password = "pw") },
            "getImageExif" to { getImageExif("N74KSKi001-0", K, password = "pw") },
            "unlockNode" to { unlockNode("2sDN5x", K, "pw", "true") },
            "unlockAlbum" to { unlockAlbum("FfHCms", K, "pw", "true") },
            "getUserAlbums" to { getUserAlbums("idzifamily", K, start = 1, password = "pw", cacheControl = "no-cache") },
            "getUserAlbumsByUri" to { getUserAlbumsByUri("/api/v2/user/idzifamily!albums?start=101&count=100", K) },
            "getAlbumKeywords" to { getAlbumKeywords("FfHCms", K, password = "pw") },
            "getUserTopKeywords" to { getUserTopKeywords("idzifamily", K, nodeUri = "/api/v2/node/P4BKB", password = "pw") },
            "getImagesByKeyword" to { getImagesByKeyword(K, scope = "/api/v2/node/4zqWw", text = "kentridge") },
            "updateImageMetadata" to { updateImageMetadata("N74KSKi001-0", K, UpdateImageMetadataRequest(keywords = "a;b")) }
        )

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun methods(): List<String> = CASES.keys.toList()
    }

    @Test fun `sent parameters are accepted by the endpoint and equal the echo`() {
        val server = FakeSmugMugServer().also { it.genericAnswers = true }
        var sent: okhttp3.Request? = null
        var echoed: String? = null
        val client = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                sent = chain.request()
                val resp = chain.proceed(chain.request())
                echoed = runCatching {
                    JsonParser.parseString(resp.peekBody(Long.MAX_VALUE).string()).asJsonObject
                        .getAsJsonObject("Response").get("Uri").asString
                }.getOrNull()
                resp
            })
            .addInterceptor(server.interceptor)
            .build()
        val api = Retrofit.Builder().baseUrl("https://api.smugmug.com/api/v2/")
            .addConverterFactory(GsonConverterFactory.create()).client(client).build().create(SmugMugApi::class.java)

        // The call may fail to parse the placeholder body; the request and the echo are what is checked.
        runCatching { runBlocking { CASES.getValue(method)(api) } }
        val request = sent ?: throw AssertionError("$method sent no request")
        val url = request.url
        val path = url.encodedPath

        if (method in NOT_QUERY_CONTRACT) {
            assertEquals("$method: the query of a ${request.method} is APIKey only", setOf("APIKey"), (0 until url.querySize).map { url.queryParameterName(it) }.toSet())
            return
        }

        val sentPairs = (0 until url.querySize).map { url.queryParameterName(it) to url.queryParameterValue(it).orEmpty() }
            .filter { it.first !in AcceptedParams.notEchoed }
        val accepted = AcceptedParams.accepted(path)
        val notAccepted = sentPairs.map { it.first }.filter { it !in accepted }.toSet()
        val template = AcceptedParams.templateOf(path)
        val want = EXPECTED_VIOLATIONS[method] ?: emptySet()
        assertEquals(
            "$method: parameters sent that $template does not accept (it accepts ${AcceptedParams.endpointParams(path).sorted()} plus the meta list)",
            want, notAccepted
        )

        val echo = echoed ?: throw AssertionError("$method: no Response.Uri echo (the fake answered ${request.method} $path without one)")
        val echoPairs = echo.substringAfter('?', "").let { q ->
            if (q.isEmpty()) emptyList() else "https://x/?$q".toHttpUrl().let { u -> (0 until u.querySize).map { u.queryParameterName(it) to u.queryParameterValue(it).orEmpty() } }
        }
        val dropped = sentPairs.map { it.first }.toSet() - echoPairs.map { it.first }.toSet()
        assertEquals("$method: parameters sent but not echoed (dropped by the server)", want, dropped)
        assertEquals("$method: echoed values differ from what was sent", sentPairs.filter { it.first !in want }, echoPairs)
    }
}

/** Every [SmugMugApi] method has a contract case, and every case names a method (a new endpoint cannot skip it). */
class ApiContractCoverageTest {
    @Test fun `every SmugMugApi method has an ApiContractTest case`() {
        val methods = SmugMugApi::class.java.declaredMethods.map { it.name }.toSet()
        assertEquals("methods without a case, or cases without a method", methods, ApiContractTest.CASES.keys)
    }

    @Test fun `the expected violations name real methods and only unaccepted keys`() {
        assertTrue(ApiContractTest.EXPECTED_VIOLATIONS.keys.all { it in ApiContractTest.CASES.keys })
    }
}
