package com.smugview.app.data.api

import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.Parameters
import io.ktor.http.contentType
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

/** A non-2xx answer from SmugMug (or the synthetic 504 of an `only-if-cached` request that nothing answered: [syntheticCacheMiss]). */
class ApiException(
    val code: Int, val description: String = "", val syntheticCacheMiss: Boolean = false,
    /** The `Retry-After` header of the answer, as sent. */
    val retryAfter: String? = null
) :
    RuntimeException("HTTP $code $description".trim())

/** Only the status of an answer whose body the app does not read (the unlock calls, a metadata update). */
data class ApiStatus(val code: Int) {
    val isSuccessful: Boolean get() = code in 200..299
}

/** The header the Android engine adds to the answer OkHttp made up for an `only-if-cached` request nothing satisfied (findings #6). */
const val SYNTHETIC_CACHE_MISS_HEADER = "X-Synthetic-Cache-Miss"

/**
 * The calls [KtorSmugMugApi] makes, over one Ktor [client]. The engine behind the client owns caching, retry and cookies; this
 * class only builds the request, throws [ApiException] for a non-2xx answer and reads the body with [ApiJson].
 */
class ApiHttp(
    private val client: HttpClient,
    private val baseUrl: String = "https://api.smugmug.com/api/v2/",
    /** Runs on the CALLER'S coroutine for every request, before the engine: the engine runs in its own context and cannot see the caller's. */
    private val onRequest: HttpRequestBuilder.() -> Unit = {}
) {

    private fun urlOf(path: String): String =
        if (path.startsWith("/")) baseUrl.substringBefore("/api/v2/").trimEnd('/') + path else baseUrl + path

    /** Reads a body nobody wants, so the engine's reader finishes now instead of failing later when the connection closes. */
    private suspend fun HttpResponse.drain() { try { bodyAsText() } catch (_: Exception) { } }

    private suspend fun HttpResponse.failure(): ApiException {
        drain()
        return ApiException(status.value, status.description, headers[SYNTHETIC_CACHE_MISS_HEADER] != null, headers["Retry-After"])
    }

    /**
     * SmugMug answers a request without `Accept: application/json` with 200 and an HTML page. Android's OkHttp interceptor adds the header; the
     * shared code owns it too, so a platform with no interceptor (iOS) works. A caller that sets its own `Accept` keeps it, once.
     */
    private fun HttpRequestBuilder.askForJson(callerHeaders: List<Pair<String, String?>>) {
        if (callerHeaders.none { (k, v) -> v != null && k.equals("Accept", ignoreCase = true) }) header("Accept", "application/json")
    }

    @PublishedApi internal suspend fun fetchText(path: String, query: List<Pair<String, Any?>>, headers: List<Pair<String, String?>>): String {
        val response = client.request {
            method = HttpMethod.Get
            url(urlOf(path))
            onRequest()
            query.forEach { (k, v) -> parameter(k, v) }
            askForJson(headers)
            headers.forEach { (k, v) -> if (v != null) header(k, v) }
        }
        if (response.status.value !in 200..299) throw response.failure()
        return response.bodyAsText()
    }

    suspend inline fun <reified T> get(path: String, query: List<Pair<String, Any?>>, headers: List<Pair<String, String?>>): T =
        ApiJson.decodeFromString<T>(fetchText(path, query, headers))

    /** A form POST whose answer's body is not read: only the status comes back, and a non-2xx is NOT thrown (the callers read the code). */
    suspend fun postForm(
        path: String, query: List<Pair<String, Any?>>, headers: List<Pair<String, String?>>, fields: List<Pair<String, String>>
    ): ApiStatus {
        val response = client.request {
            method = HttpMethod.Post
            url(urlOf(path))
            onRequest()
            query.forEach { (k, v) -> parameter(k, v) }
            askForJson(headers)
            headers.forEach { (k, v) -> if (v != null) header(k, v) }
            setBody(FormDataContent(Parameters.build { fields.forEach { (k, v) -> append(k, v) } }))
        }
        response.drain()
        return ApiStatus(response.status.value)
    }

    suspend fun <T> patchJson(
        path: String, query: List<Pair<String, Any?>>, body: T, serializer: KSerializer<T>
    ): ApiStatus {
        val response = client.request {
            method = HttpMethod.Patch
            url(urlOf(path))
            onRequest()
            query.forEach { (k, v) -> parameter(k, v) }
            header("Accept", "application/json")
            contentType(ContentType.Application.Json)
            setBody(ApiJson.encodeToString(serializer, body))
        }
        response.drain()
        return ApiStatus(response.status.value)
    }
}
