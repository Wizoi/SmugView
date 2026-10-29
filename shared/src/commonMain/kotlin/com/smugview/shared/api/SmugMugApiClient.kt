package com.smugview.shared.api

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

/** A SmugMug answer that is not a success. Replaces `retrofit2.HttpException`; [code] is the HTTP status. */
class ApiException(val code: Int, message: String) : Exception(message)

/** The engine of this platform: OkHttp on Android and the JVM, Darwin (NSURLSession) on iOS. */
expect fun platformEngine(): HttpClientEngine

/**
 * iOS spike: the first two calls of `SmugMugApi` (Retrofit) over Ktor, with the SAME request shape: the parameters, their order and
 * their defaults are copied from the interface, so the server sees the same request from either. Retry (429/5xx but not OkHttp's
 * synthetic 504), the session cookie and the response cache are not ported yet (Stage 1d).
 */
class SmugMugApiClient(
    engine: HttpClientEngine = platformEngine(),
    private val baseUrl: String = "https://api.smugmug.com/api/v2"
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val http = HttpClient(engine) {
        install(ContentNegotiation) { json(json) }
        expectSuccess = false
    }

    private suspend inline fun <reified T> get(path: String, ignoreErrors: String? = null, noinline params: io.ktor.client.request.HttpRequestBuilder.() -> Unit): T {
        val response = http.get("$baseUrl/$path") {
            header(HttpHeaders.Accept, ContentType.Application.Json.toString())
            ignoreErrors?.let { header("X-Ignore-Errors", it) }
            params()
        }
        if (response.status.value !in 200..299) throw ApiException(response.status.value, "SmugMug answered ${response.status.value} for $path")
        return response.body()
    }

    /** `GET user/{nickname}`: defaults of `SmugMugApi.getUserProfile`. */
    suspend fun getUserProfile(nickname: String, apiKey: String, expand: String = "BioImage", ignoreErrors: String? = null): UserResponse =
        get("user/$nickname", ignoreErrors) {
            parameter("APIKey", apiKey)
            parameter("_expand", expand)
            parameter("_verbosity", 1)
        }

    /** `GET node/{id}!children`: defaults of `SmugMugApi.getNodeChildren`. */
    suspend fun getNodeChildren(
        nodeId: String,
        apiKey: String,
        count: Int = 100,
        start: Int? = null,
        expand: String = "HighlightImage",
        filter: String = "Uri,NodeID,Type,Name,Description,SecurityType,Privacy,PasswordHint,Uris,WebUri,ThumbnailUrl,DateModified",
        filterUri: String = "ChildNodes,Album,HighlightImage",
        ignoreErrors: String? = null
    ): NodeListResponse = get("node/$nodeId!children", ignoreErrors) {
        parameter("APIKey", apiKey)
        parameter("count", count)
        start?.let { parameter("start", it) }
        parameter("_expand", expand)
        parameter("_filter", filter)
        parameter("_filteruri", filterUri)
        parameter("_verbosity", 1)
    }
}
