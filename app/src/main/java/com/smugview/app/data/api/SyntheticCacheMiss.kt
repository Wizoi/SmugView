package com.smugview.app.data.api

import okhttp3.Response

/**
 * True for the 504 that OkHttp synthesizes when a request carries `only-if-cached` and nothing is
 * cached: it came from neither the network nor the cache. A real SmugMug 504 has a
 * `networkResponse`. See findings #6.
 */
internal fun Response.isSyntheticCacheMiss(): Boolean =
    code == 504 && networkResponse == null && cacheResponse == null
