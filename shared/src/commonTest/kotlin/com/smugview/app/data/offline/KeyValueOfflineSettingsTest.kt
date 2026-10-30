package com.smugview.app.data.offline

import com.smugview.app.data.repository.KeyValueSyncStateStore
import com.smugview.app.util.KeyValueStore
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal class FakeKeyValueStore : KeyValueStore {
    val map = HashMap<String, Any>()
    override fun getString(key: String, default: String?): String? = map[key] as? String ?: default
    override fun putString(key: String, value: String) { map[key] = value }
    override fun getLong(key: String, default: Long): Long = map[key] as? Long ?: default
    override fun putLong(key: String, value: Long) { map[key] = value }
    override fun remove(key: String) { map.remove(key) }
}

class KeyValueOfflineSettingsTest {
    @Test fun defaultsToWifiOnly() {
        assertEquals(OfflineNetworkRule.WIFI_ONLY, KeyValueOfflineSettings(FakeKeyValueStore()).rule.value)
    }

    @Test fun setIsStoredUnderTheAndroidKeyBeforeItReturns() = runTest {
        val store = FakeKeyValueStore()
        val settings = KeyValueOfflineSettings(store)
        settings.set(OfflineNetworkRule.WIFI_AND_MOBILE)
        assertEquals("WIFI_AND_MOBILE", store.map["galleryNetwork"])
        assertEquals(OfflineNetworkRule.WIFI_AND_MOBILE, settings.rule.value)
        // a fresh instance (a new launch) reads it back
        assertEquals(OfflineNetworkRule.WIFI_AND_MOBILE, KeyValueOfflineSettings(store).rule.value)
    }

    @Test fun unknownStoredValueIsWifiOnly() {
        val store = FakeKeyValueStore()
        store.putString("galleryNetwork", "SOMETHING_ELSE")
        assertEquals(OfflineNetworkRule.WIFI_ONLY, KeyValueOfflineSettings(store).rule.value)
    }
}

class KeyValueSyncStateStoreTest {
    @Test fun missingKeysGiveTheDefaults() {
        val s = KeyValueSyncStateStore(FakeKeyValueStore())
        assertEquals(0L, s.getLong("lastFullCrawlAt.kev"))
        assertEquals(7L, s.getLong("x", 7L))
        assertFalse(s.getBoolean("treeRepairDone.kev"))
        assertTrue(s.getBoolean("y", true))
    }

    @Test fun longsAndBooleansRoundTripPerKey() {
        val store = FakeKeyValueStore()
        val s = KeyValueSyncStateStore(store)
        s.putLong("lastFullCrawlAt.kev", 1_700_000_000_000L)
        s.putBoolean("treeRepairDone.kev", true)
        s.putBoolean("other", false)
        val again = KeyValueSyncStateStore(store)
        assertEquals(1_700_000_000_000L, again.getLong("lastFullCrawlAt.kev"))
        assertTrue(again.getBoolean("treeRepairDone.kev"))
        assertFalse(again.getBoolean("other", true)) // an explicit false is not "missing"
        assertEquals("treeRepairDone.kev" in store.map, true)
    }
}
