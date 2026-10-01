package com.smugview.app.data.repository

import android.content.Context

/**
 * Small persisted flags/timestamps for the background sync (design 3.3, 3.6): the one-time tree
 * repair (`treeRepairDone.<nick>`) and the crawl gate (`lastFullCrawlAt.<nick>`). Not Room: these
 * are not data, and a schema change is not needed to add one.
 */
interface SyncStateStore {
    fun getLong(key: String, default: Long = 0L): Long
    fun putLong(key: String, value: Long)
    fun getBoolean(key: String, default: Boolean = false): Boolean
    fun putBoolean(key: String, value: Boolean)
}

/** Production store: the `sync_state` SharedPreferences file. */
class PrefsSyncStateStore(private val context: Context) : SyncStateStore {
    private val prefs by lazy { context.getSharedPreferences("sync_state", Context.MODE_PRIVATE) }
    override fun getLong(key: String, default: Long) = prefs.getLong(key, default)
    override fun putLong(key: String, value: Long) { prefs.edit().putLong(key, value).apply() }
    override fun getBoolean(key: String, default: Boolean) = prefs.getBoolean(key, default)
    override fun putBoolean(key: String, value: Boolean) { prefs.edit().putBoolean(key, value).apply() }
}

/** Process-lifetime store; the repository's default so tests and previews need no Android context. */
class InMemorySyncStateStore : SyncStateStore {
    private val map = java.util.concurrent.ConcurrentHashMap<String, Any>()
    override fun getLong(key: String, default: Long) = map[key] as? Long ?: default
    override fun putLong(key: String, value: Long) { map[key] = value }
    override fun getBoolean(key: String, default: Boolean) = map[key] as? Boolean ?: default
    override fun putBoolean(key: String, value: Boolean) { map[key] = value }
}
