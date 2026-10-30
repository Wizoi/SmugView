@file:OptIn(io.ktor.utils.io.InternalAPI::class)

package com.smugview.app.data.api

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.HttpClientEngineBase
import io.ktor.client.engine.HttpClientEngineCapability
import io.ktor.client.engine.HttpClientEngineConfig
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.callContext
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpProtocolVersion
import io.ktor.http.HttpStatusCode
import io.ktor.http.Headers
import io.ktor.http.content.OutgoingContent
import io.ktor.util.date.GMTDate
import io.ktor.utils.io.jvm.javaio.toByteReadChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class CallFactoryEngineConfig : HttpClientEngineConfig() {
    lateinit var callFactory: Call.Factory
}

/**
 * A Ktor engine that sends every request through an OkHttp [Call.Factory]. The app's stack stays exactly what it was (the `http_cache`
 * with its offline rules, the retry that waits on a timer and not on a thread, the cookie jar, the telemetry): Ktor only builds the
 * request and reads the answer. Ktor's own OkHttp engine takes a client, not a factory, so it could not carry [RetryingCallFactory].
 *
 * Bodies: the app sends a form or a JSON string, both already bytes. The answer OkHttp made up for an `only-if-cached` request with
 * nothing cached is marked with [SYNTHETIC_CACHE_MISS_HEADER], since Ktor never sees OkHttp's network and cache responses.
 */
class CallFactoryEngine(override val config: CallFactoryEngineConfig) : HttpClientEngineBase("call-factory") {
    override val supportedCapabilities: Set<HttpClientEngineCapability<*>> = emptySet()

    override suspend fun execute(data: HttpRequestData): HttpResponseData {
        val callContext = callContext()
        val requestTime = GMTDate()
        // newCall reads the diagnostics action id from the calling thread (RetryingCallFactory), so put the caller's id back for that call.
        val actionId = data.attributes.getOrNull(ActionIdKey)
        val call = if (actionId == null) config.callFactory.newCall(data.toOkHttp())
        else withContext(com.smugview.app.diag.DiagContext.element(actionId)) { config.callFactory.newCall(data.toOkHttp()) }
        val response = call.await()
        val body = response.body?.byteStream()?.toByteReadChannel(callContext) ?: io.ktor.utils.io.ByteReadChannel.Empty
        val headers = Headers.build {
            response.headers.forEach { (k, v) -> append(k, v) }
            if (response.isSyntheticCacheMiss()) append(SYNTHETIC_CACHE_MISS_HEADER, "1")
        }
        return HttpResponseData(
            HttpStatusCode(response.code, response.message), requestTime, headers, response.protocol.toKtor(), body, callContext
        )
    }

    private fun HttpRequestData.toOkHttp(): Request {
        val builder = Request.Builder().url(url.toString())
        val bytes: ByteArray? = when (val content = body) {
            is OutgoingContent.ByteArrayContent -> content.bytes()
            is OutgoingContent.NoContent -> null
            else -> error("unsupported request body ${content::class.simpleName}")
        }
        headers.forEach { name, values -> values.forEach { builder.addHeader(name, it) } }
        val type = body.contentType?.toString()?.toMediaTypeOrNull()
        val requestBody = when {
            bytes != null -> bytes.toRequestBody(type)
            method.value == "POST" || method.value == "PATCH" || method.value == "PUT" -> ByteArray(0).toRequestBody(type)
            else -> null
        }
        builder.method(method.value, requestBody)
        return builder.build()
    }

    private fun Protocol.toKtor(): HttpProtocolVersion = when (this) {
        Protocol.HTTP_1_0 -> HttpProtocolVersion.HTTP_1_0
        Protocol.HTTP_1_1 -> HttpProtocolVersion.HTTP_1_1
        Protocol.HTTP_2 -> HttpProtocolVersion.HTTP_2_0
        else -> HttpProtocolVersion.HTTP_1_1
    }

    private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { cont.resumeWithException(e) }
            override fun onResponse(call: Call, response: Response) { cont.resume(response) }
        })
    }
}

object CallFactoryEngineFactory : HttpClientEngineFactory<CallFactoryEngineConfig> {
    override fun create(block: CallFactoryEngineConfig.() -> Unit): HttpClientEngine =
        CallFactoryEngine(CallFactoryEngineConfig().apply(block))
}
