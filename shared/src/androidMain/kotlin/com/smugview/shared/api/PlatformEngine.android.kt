package com.smugview.shared.api

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp

actual fun platformEngine(): HttpClientEngine = OkHttp.create()
