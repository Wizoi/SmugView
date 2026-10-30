package com.smugview.app.util

import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException

// Darwin reports NSURLErrorTimedOut (-1001) inside a DarwinHttpRequestException whose message says "timed out".
actual fun isTimeoutError(error: Throwable): Boolean =
    error is SocketTimeoutException || error is ConnectTimeoutException || error is HttpRequestTimeoutException ||
        error.message?.contains("timed out", ignoreCase = true) == true
