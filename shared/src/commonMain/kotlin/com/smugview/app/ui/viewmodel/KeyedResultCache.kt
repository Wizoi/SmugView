package com.smugview.app.ui.viewmodel

import com.smugview.shared.platform.SimpleLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Per-photo results the viewer reads (R-61): details, EXIF, sizes. A failed or empty result is dropped from the cache
 * at once, so the next open asks again instead of showing the old error for the rest of the session, and the cache
 * keeps at most [limit] keys, the least recently read going first.
 */
class KeyedResultCache<T>(private val limit: Int = 200) {
    private val lock = SimpleLock()
    // A plain LinkedHashMap keeps insertion order in common code (the access-order constructor is JVM only), so a read re-inserts its key.
    private val map = LinkedHashMap<String, MutableStateFlow<Result<T>?>>()

    /** The flow of [key]; [load] runs once when it is new and reports through [Publisher]. */
    fun flowFor(key: String, load: (Publisher) -> Unit): StateFlow<Result<T>?> = lock.withLock {
        map.remove(key)?.let { map[key] = it; return@withLock it.asStateFlow() }
        val flow = MutableStateFlow<Result<T>?>(null)
        map[key] = flow
        while (map.size > limit) map.remove(map.keys.first())
        load(Publisher(key, flow))
        flow.asStateFlow()
    }

    fun clear() = lock.withLock { map.clear() }

    fun contains(key: String) = lock.withLock { map.containsKey(key) }

    private fun forget(key: String, flow: MutableStateFlow<Result<T>?>) = lock.withLock {
        if (map[key] === flow) map.remove(key)
        Unit
    }

    inner class Publisher(private val key: String, private val flow: MutableStateFlow<Result<T>?>) {
        /** Shows [result] to whoever reads this flow now; a failure or null is not kept for the next open. */
        fun publish(result: Result<T>?) {
            flow.value = result
            if (result == null || result.isFailure) {
                forget(key, flow)
            }
        }
    }
}
