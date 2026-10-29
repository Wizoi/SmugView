package com.smugview.shared.api

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * iOS spike, Stage 0 step 3: the Ktor client sends the same requests as the Retrofit interface and reads the real recorded answers.
 * Ktor's MockEngine stands in for the network (no key, no connection), so the same tests run on the JVM and on the iOS simulator.
 */
class ApiClientTest {
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    @Test fun profileRequestHasTheShapeOfTheRetrofitCall() = runTest {
        var seen: HttpRequestData? = null
        val client = SmugMugApiClient(MockEngine { request ->
            seen = request
            respond(readFixture("getUserProfile__site.json"), HttpStatusCode.OK, jsonHeaders)
        })
        val user = client.getUserProfile("idzifamily", "K", ignoreErrors = "true").response.user
        assertEquals("idzifamily", user.nickName)
        val request = assertNotNull(seen)
        assertEquals("/api/v2/user/idzifamily", request.url.encodedPath)
        assertEquals(listOf("APIKey", "_expand", "_verbosity"), request.url.parameters.names().toList())
        assertEquals("BioImage", request.url.parameters["_expand"])
        assertEquals("1", request.url.parameters["_verbosity"])
        assertEquals("true", request.headers["X-Ignore-Errors"])
        assertEquals("application/json", request.headers[HttpHeaders.Accept])
    }

    @Test fun folderListingRequestKeepsTheBangAndTheParameterOrder() = runTest {
        var seen: HttpRequestData? = null
        val client = SmugMugApiClient(MockEngine { request ->
            seen = request
            respond(readFixture("getNodeChildren__root.json"), HttpStatusCode.OK, jsonHeaders)
        })
        val page = client.getNodeChildren("4zqWw", "K", start = 101).response
        assertTrue(page.nodes.orEmpty().isNotEmpty())
        val request = assertNotNull(seen)
        assertEquals("/api/v2/node/4zqWw!children", request.url.encodedPath)
        assertEquals(
            listOf("APIKey", "count", "start", "_expand", "_filter", "_filteruri", "_verbosity"),
            request.url.parameters.names().toList()
        )
        assertEquals("100", request.url.parameters["count"])
        assertEquals("101", request.url.parameters["start"])
        assertEquals("ChildNodes,Album,HighlightImage", request.url.parameters["_filteruri"])
        assertNull(request.headers["X-Ignore-Errors"])
    }

    @Test fun startIsLeftOutOfTheFirstPage() = runTest {
        var seen: HttpRequestData? = null
        val client = SmugMugApiClient(MockEngine { request ->
            seen = request
            respond(readFixture("getNodeChildren__root.json"), HttpStatusCode.OK, jsonHeaders)
        })
        client.getNodeChildren("4zqWw", "K")
        assertNull(assertNotNull(seen).url.parameters["start"])
    }

    @Test fun aSmugMugErrorStatusBecomesAnApiExceptionWithItsCode() = runTest {
        for (status in listOf(404, 429, 503)) {
            val client = SmugMugApiClient(MockEngine { respond("{}", HttpStatusCode.fromValue(status), jsonHeaders) })
            val e = assertFailsWith<ApiException> { client.getNodeChildren("4zqWw", "K") }
            assertEquals(status, e.code)
        }
    }
}
