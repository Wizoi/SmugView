package com.smugview.app.data.repository

import com.smugview.app.util.KeyValueStore

/**
 * [SyncStateStore] over a [KeyValueStore] (UserDefaults on iOS). Android's `PrefsSyncStateStore` keeps longs and booleans in
 * typed slots under the caller's own key (`treeRepairDone.<nick>`, `lastFullCrawlAt.<nick>`), nothing prefixed. Here a boolean is
 * stored as a long (1/0) under the same key; a missing key gives the default.
 */
class KeyValueSyncStateStore(private val store: KeyValueStore) : SyncStateStore {
    override fun getLong(key: String, default: Long): Long = store.getLong(key, default)
    override fun putLong(key: String, value: Long) = store.putLong(key, value)
    override fun getBoolean(key: String, default: Boolean): Boolean {
        val raw = store.getLong(key, NONE)
        return if (raw == NONE) default else raw != 0L
    }
    override fun putBoolean(key: String, value: Boolean) = store.putLong(key, if (value) 1L else 0L)

    private companion object {
        const val NONE = Long.MIN_VALUE
    }
}
