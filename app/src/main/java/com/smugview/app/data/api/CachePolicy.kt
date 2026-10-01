package com.smugview.app.data.api

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.Interceptor
import java.util.concurrent.ConcurrentHashMap

/**
 * Phase 4 step 4-6 (design 3.3, R-35, R-36): the ONE owner of "may this cached API response be reused?".
 *
 * OkHttp's [okhttp3.Cache] keys a response by URL only, and SmugMug sends no `Vary: Cookie`, so a listing
 * fetched before an unlock (anonymous: the password folder's galleries are missing) was served again for
 * 5 minutes after it (the Hub right after an unlock). The 5-minute reuse stays for ordinary navigation,
 * but a response whose request was SENT before the last session-cookie change is never reused online.
 */

/** When the session cookies last changed: process start, an unlock, a rotated or dropped cookie. */
class CredentialEpoch(
    private val clock: () -> Long = { System.currentTimeMillis() },
    /** Production: now (process start). A test harness may start it in the past to model a settled session. */
    startedAtMs: Long = clock()
) {
    @Volatile var changedAtMs: Long = startedAtMs
        private set

    fun bump() { changedAtMs = clock() }
}

/**
 * The in-memory, host-keyed cookie jar (empty at every launch, findings #16) that also owns the cache
 * epoch: it bumps [epoch] when a host's cookie set changes by NAME to VALUE (a new cookie, a new value, a
 * removed one). Re-sending the same value, or only a new expiry, is not a change (E1: session GETs set no
 * cookie at all; an `!unlock` sets `shm` and `SMSESS`).
 */
class SessionCookieJar(
    private val epoch: CredentialEpoch,
    private val clock: () -> Long = { System.currentTimeMillis() }
) : CookieJar {
    // OkHttp may invoke these from several dispatcher threads at once.
    private val store = ConcurrentHashMap<String, ConcurrentHashMap<String, Cookie>>()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val host = store.getOrPut(url.host) { ConcurrentHashMap() }
        var changed = false
        for (cookie in cookies) {
            if (cookie.expiresAt <= clock()) { // the server dropping a cookie
                if (host.remove(cookie.name) != null) changed = true
            } else {
                val old = host.put(cookie.name, cookie)
                if (old == null || old.value != cookie.value) changed = true
            }
        }
        // After the store: a request that reads the jar from now on carries the new cookies.
        if (changed) epoch.bump()
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val host = store[url.host] ?: return emptyList()
        val now = clock()
        return host.values.filter { it.expiresAt > now }
    }
}

internal const val CACHE_API_HOST = "api.smugmug.com"
internal const val CACHE_REUSE_SECONDS = 300
internal const val OFFLINE_MAX_STALE_SECONDS = 60 * 60 * 24 * 7

/**
 * An APPLICATION interceptor, first in the chain. Rules for a GET, in order:
 * 1. Offline: `only-if-cached, max-stale=7d` (any host). It wins over a caller's `no-cache`, so Refresh
 *    offline shows the cached copy. An uncached request gets OkHttp's synthetic 504, which
 *    `RetryingCallFactory` does not retry ([isSyntheticCacheMiss]).
 * 2. A request that already carries `Cache-Control` (Refresh, Retry, the crawl, a lit gallery: `no-cache`)
 *    is left alone.
 * 3. API host: `Cache-Control: max-age=N`, N = whole seconds since the epoch, while N < 300. OkHttp's
 *    cached-response age is at least `now - sentRequestAt`, so a response is reused online only if its
 *    request was sent after the last cookie change. This also covers a request sent anonymously and
 *    answered after the unlock.
 * 4. Otherwise nothing: the network interceptor's 5-minute rewrite applies as before.
 * The epoch starts at process start, so after process death nothing the old process cached is reused
 * online (the cookie jar is empty again) while it all stays available offline. Image bytes are not part
 * of this policy (Coil's own cache, step 4-11).
 */
internal fun cachePolicyInterceptor(
    isOnline: () -> Boolean,
    epoch: CredentialEpoch,
    clock: () -> Long = { System.currentTimeMillis() }
): Interceptor = Interceptor { chain ->
    var request = chain.request()
    if (request.method == "GET") {
        if (!isOnline()) {
            request = request.newBuilder()
                .header("Cache-Control", "public, only-if-cached, max-stale=$OFFLINE_MAX_STALE_SECONDS")
                .build()
        } else if (request.header("Cache-Control") == null && request.url.host == CACHE_API_HOST) {
            val sinceChange = ((clock() - epoch.changedAtMs) / 1000).coerceAtLeast(0)
            if (sinceChange < CACHE_REUSE_SECONDS) {
                request = request.newBuilder().header("Cache-Control", "max-age=$sinceChange").build()
            }
        }
    }
    chain.proceed(request)
}
