package com.smugview.app.ui.viewmodel

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Per-photo results the viewer reads (R-61): details, EXIF, sizes. A failed or empty result is dropped from the cache
 * at once, so the next open asks again instead of showing the old error for the rest of the session, and the cache
 * keeps at most [limit] keys, the least recently read going first.
 */
internal class KeyedResultCache<T>(private val limit: Int = 200) {
    private val map = object : LinkedHashMap<String, MutableStateFlow<Result<T>?>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, MutableStateFlow<Result<T>?>>?) = size > limit
    }

    /** The flow of [key]; [load] runs once when it is new and reports through [Publisher]. */
    @Synchronized
    fun flowFor(key: String, load: (Publisher) -> Unit): StateFlow<Result<T>?> {
        map[key]?.let { return it.asStateFlow() }
        val flow = MutableStateFlow<Result<T>?>(null)
        map[key] = flow
        load(Publisher(key, flow))
        return flow.asStateFlow()
    }

    @Synchronized fun clear() = map.clear()

    @Synchronized fun contains(key: String) = map.containsKey(key)

    @Synchronized private fun forget(key: String, flow: MutableStateFlow<Result<T>?>) {
        if (map[key] === flow) map.remove(key)
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
