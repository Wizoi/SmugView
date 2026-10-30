package com.smugview.app.util

import coil3.network.CacheStrategy
import coil3.network.NetworkRequest
import coil3.network.NetworkResponse
import coil3.request.Options

/**
 * What Coil 2's `respectCacheHeaders(false)` did: a photo answer is cached whatever SmugMug's CDN headers say, and a cached one is used
 * without asking again (R-37: images never share the API's HTTP cache, and their own cache is not governed by the CDN's headers).
 */
object IgnoreCacheHeaders : CacheStrategy {
    override suspend fun read(cacheResponse: NetworkResponse, networkRequest: NetworkRequest, options: Options): CacheStrategy.ReadResult =
        CacheStrategy.ReadResult(cacheResponse)

    override suspend fun write(
        cacheResponse: NetworkResponse?, networkRequest: NetworkRequest, networkResponse: NetworkResponse, options: Options
    ): CacheStrategy.WriteResult = CacheStrategy.WriteResult(networkResponse)
}
