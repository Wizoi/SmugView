package com.smugview.app.data.api

import io.ktor.client.HttpClient
import okhttp3.Call

/** The API client every screen and worker uses: Ktor on top of the app's OkHttp stack ([CallFactoryEngine]). */
fun smugMugApi(callFactory: Call.Factory, baseUrl: String = "https://api.smugmug.com/api/v2/"): SmugMugApi =
    KtorSmugMugApi(
        ApiHttp(
            HttpClient(CallFactoryEngineFactory) { engine { this.callFactory = callFactory } }, baseUrl,
            // Ktor runs the engine outside the caller's coroutine context, where the diagnostics action id (a thread local) is lost.
            onRequest = { com.smugview.app.diag.DiagContext.currentActionId()?.let { attributes.put(ActionIdKey, it) } }
        )
    )

/** The diagnostics action the request belongs to, carried from the caller to the engine. */
internal val ActionIdKey = io.ktor.util.AttributeKey<String>("smugview.actionId")
