@file:OptIn(kotlinx.cinterop.BetaInteropApi::class)

package com.smugview.app.util

import com.smugview.app.data.offline.KeyValueOfflineSettings
import com.smugview.app.data.offline.OfflineNetworkRule
import com.smugview.app.data.repository.KeyValueSyncStateStore
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSUUID
import platform.Foundation.NSUserDefaults
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UserDefaultsStoreTest {
    private val suites = mutableListOf<String>()

    private fun newSuite(): String = ("smugview.test." + NSUUID().UUIDString).also { suites += it }

    @AfterTest
    fun cleanUp() {
        suites.forEach { NSUserDefaults(suiteName = it).removePersistentDomainForName(it) }
        suites.clear()
    }

    @Test
    fun stringsAndLongsRoundTripAndDefaultsApply() {
        val s = UserDefaultsStore(newSuite())
        assertEquals("fallback", s.getString("missing", "fallback"))
        assertNull(s.getString("missing", null))
        assertEquals(42L, s.getLong("missing", 42L))

        s.putString("name", "hello")
        s.putLong("n", 1L shl 40)
        s.putLong("neg", -7L)
        assertEquals("hello", s.getString("name", "x"))
        assertEquals(1L shl 40, s.getLong("n", 0))
        assertEquals(-7L, s.getLong("neg", 0))

        s.putString("name", "again")
        assertEquals("again", s.getString("name", null))
    }

    @Test
    fun zeroIsAStoredValueNotADefault() {
        val s = UserDefaultsStore(newSuite())
        s.putLong("zero", 0L)
        assertEquals(0L, s.getLong("zero", 99L))
    }

    @Test
    fun removeDeletesTheKey() {
        val s = UserDefaultsStore(newSuite())
        s.putString("a", "1")
        s.putLong("b", 2L)
        s.remove("a"); s.remove("b")
        assertNull(s.getString("a", null))
        assertEquals(5L, s.getLong("b", 5L))
        s.remove("never-existed") // must not throw
    }

    @Test
    fun suitesDoNotSeeEachOther() {
        val one = UserDefaultsStore(newSuite())
        val two = UserDefaultsStore(newSuite())
        one.putString("k", "from one")
        one.putLong("l", 1L)
        assertNull(two.getString("k", null))
        assertEquals(0L, two.getLong("l", 0L))
        two.putString("k", "from two")
        assertEquals("from one", one.getString("k", null))
    }

    @Test
    fun aNewInstanceOverTheSameSuiteSeesTheValues() {
        val name = newSuite()
        UserDefaultsStore(name).putString("k", "v")
        assertEquals("v", UserDefaultsStore(name).getString("k", null))
    }

    @Test
    fun offlineSettingsPersistAcrossInstances() = runTest {
        val name = newSuite()
        val first = KeyValueOfflineSettings(UserDefaultsStore(name))
        assertEquals(OfflineNetworkRule.WIFI_ONLY, first.rule.value)
        first.set(OfflineNetworkRule.WIFI_AND_MOBILE)
        assertEquals(OfflineNetworkRule.WIFI_AND_MOBILE, first.rule.value)
        assertEquals(OfflineNetworkRule.WIFI_AND_MOBILE, KeyValueOfflineSettings(UserDefaultsStore(name)).rule.value)
        first.set(OfflineNetworkRule.WIFI_ONLY)
        assertEquals(OfflineNetworkRule.WIFI_ONLY, KeyValueOfflineSettings(UserDefaultsStore(name)).rule.value)
    }

    @Test
    fun anUnknownStoredRuleFallsBackToWifiOnly() {
        val store = UserDefaultsStore(newSuite())
        store.putString(KeyValueOfflineSettings.KEY, "SOMETHING_ELSE")
        assertEquals(OfflineNetworkRule.WIFI_ONLY, KeyValueOfflineSettings(store).rule.value)
    }

    @Test
    fun syncStatePersistsAcrossInstances() {
        val name = newSuite()
        val a = KeyValueSyncStateStore(UserDefaultsStore(name))
        assertEquals(0L, a.getLong("lastFullCrawlAt.nick"))
        assertFalse(a.getBoolean("treeRepairDone.nick"))
        assertTrue(a.getBoolean("treeRepairDone.nick", true))

        a.putLong("lastFullCrawlAt.nick", 1_700_000_000_000L)
        a.putBoolean("treeRepairDone.nick", true)
        a.putBoolean("off", false)

        val b = KeyValueSyncStateStore(UserDefaultsStore(name))
        assertEquals(1_700_000_000_000L, b.getLong("lastFullCrawlAt.nick"))
        assertTrue(b.getBoolean("treeRepairDone.nick"))
        assertFalse(b.getBoolean("off", true), "a stored false beats the default")
        assertEquals(5L, b.getLong("other", 5L))
    }
}
