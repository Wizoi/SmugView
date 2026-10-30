package com.smugview.app.data.repository

import com.smugview.shared.platform.SimpleLock

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

/** Process-lifetime store; the repository's default so tests and previews need no Android context. */
class InMemorySyncStateStore : SyncStateStore {
    private val lock = SimpleLock()
    private val map = HashMap<String, Any>()
    override fun getLong(key: String, default: Long) = lock.withLock { map[key] as? Long ?: default }
    override fun putLong(key: String, value: Long) { lock.withLock { map[key] = value } }
    override fun getBoolean(key: String, default: Boolean) = lock.withLock { map[key] as? Boolean ?: default }
    override fun putBoolean(key: String, value: Boolean) { lock.withLock { map[key] = value } }
}
