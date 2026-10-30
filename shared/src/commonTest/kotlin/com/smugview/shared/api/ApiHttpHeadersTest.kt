package com.smugview.shared.api

import com.smugview.app.data.api.ApiHttp
import com.smugview.app.data.api.KtorSmugMugApi
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * The PRODUCTION request path (`KtorSmugMugApi` over `ApiHttp`, the one the repository uses), not the Stage 0 spike client.
 *
 * Found on iOS (CI run 41129062647): without `Accept: application/json` SmugMug answers 200 with an HTML page, which the app then fails to
 * decode (`JsonDecoding`) and shows as a failed site. Android never hit it because its OkHttp interceptor adds the header; the shared code
 * did not, so iOS, which has no such interceptor, sent none.
 */
class ApiHttpHeadersTest {
    private val jsonAnswer = headersOf(HttpHeaders.ContentType, "application/json")

    @Test fun aGetAsksForJsonWithoutAnInterceptorToAddTheHeader() = runTest {
        var seen: HttpRequestData? = null
        val api = KtorSmugMugApi(ApiHttp(HttpClient(MockEngine { request ->
            seen = request
            respond(readFixture("getUserProfile__site.json"), HttpStatusCode.OK, jsonAnswer)
        })))
        api.getUserProfile("idzifamily", "K")
        val request = assertNotNull(seen)
        assertEquals(listOf("application/json"), request.headers.getAll(HttpHeaders.Accept))
    }

    @Test fun aHeaderTheCallerSetsIsNotDuplicated() = runTest {
        var seen: HttpRequestData? = null
        val http = ApiHttp(HttpClient(MockEngine { request ->
            seen = request
            respond("{}", HttpStatusCode.OK, jsonAnswer)
        }))
        http.fetchText("user/x", emptyList(), listOf("Accept" to "application/json", "X-Ignore-Errors" to "true"))
        val request = assertNotNull(seen)
        assertEquals(listOf("application/json"), request.headers.getAll(HttpHeaders.Accept))
        assertEquals("true", request.headers["X-Ignore-Errors"])
    }
}
