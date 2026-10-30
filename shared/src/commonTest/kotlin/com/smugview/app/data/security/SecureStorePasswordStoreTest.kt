package com.smugview.app.data.security

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class FakeSecureStorage(var usable: Boolean = true) : SecureStorage {
    val map = LinkedHashMap<String, String>()
    override fun get(key: String): String? = if (usable) map[key] else null
    override fun put(key: String, value: String): Boolean {
        if (!usable) return false
        map[key] = value
        return true
    }
    override fun remove(key: String) { if (usable) map.remove(key) }
    override fun all(): Map<String, String>? = if (usable) map.toMap() else null
}

class SecureStorePasswordStoreTest {
    @Test fun saveStoresInStorageAndPublishesKeys() {
        val storage = FakeSecureStorage()
        val store = SecureStorePasswordStore(storage)
        store.savePassword("node1", "pw")
        assertEquals("pw", storage.map["node1"])
        assertEquals("pw", store.getPassword("node1"))
        assertEquals(setOf("node1"), store.unlockedKeys.value)
        assertFalse(store.keptOnlyThisSession.value)
        assertEquals(mapOf("node1" to "pw"), store.all())
    }

    @Test fun revisionCountsEverySaveIncludingAChangedValueAndRemove() {
        val store = SecureStorePasswordStore(FakeSecureStorage())
        assertEquals(0L, store.revision.value)
        store.savePassword("k", "one")
        store.savePassword("k", "two") // same key set, new value
        assertEquals(2L, store.revision.value)
        assertEquals(setOf("k"), store.unlockedKeys.value)
        assertEquals("two", store.getPassword("k"))
        store.remove("k")
        assertEquals(3L, store.revision.value)
        assertEquals(emptySet(), store.unlockedKeys.value)
        assertNull(store.getPassword("k"))
    }

    @Test fun existingEntriesArePrimedAtStart() {
        val storage = FakeSecureStorage()
        storage.map["a"] = "x"
        storage.map["b"] = ""
        val store = SecureStorePasswordStore(storage)
        assertEquals(setOf("a"), store.unlockedKeys.value) // an empty password is not an unlock
        assertFalse(store.keptOnlyThisSession.value)
    }

    @Test fun unusableStorageKeepsPasswordsInMemoryAndSaysSo() {
        val store = SecureStorePasswordStore(FakeSecureStorage(usable = false))
        assertTrue(store.keptOnlyThisSession.value)
        store.savePassword("k", "pw")
        assertEquals("pw", store.getPassword("k"))
        assertEquals(setOf("k"), store.unlockedKeys.value)
        assertEquals(mapOf("k" to "pw"), store.all())
        assertEquals(1L, store.revision.value)
        store.remove("k")
        assertNull(store.getPassword("k"))
        assertEquals(emptySet(), store.unlockedKeys.value)
    }

    @Test fun aWriteThatFailsLaterFallsBackToMemory() {
        val storage = FakeSecureStorage()
        val store = SecureStorePasswordStore(storage)
        assertFalse(store.keptOnlyThisSession.value)
        storage.usable = false
        store.savePassword("k", "pw")
        assertTrue(store.keptOnlyThisSession.value)
        assertEquals("pw", store.getPassword("k"))
    }

    @Test fun storageWinsOverMemoryAndSavedValueClearsMemory() {
        val storage = FakeSecureStorage(usable = false)
        val store = SecureStorePasswordStore(storage)
        store.savePassword("k", "mem")
        storage.usable = true
        store.savePassword("k", "secure")
        assertEquals("secure", store.getPassword("k"))
        storage.usable = false
        assertNull(store.getPassword("k")) // the memory copy was dropped once the storage had it
    }

    @Test fun blankKeyHasNoPassword() {
        val store = SecureStorePasswordStore(FakeSecureStorage())
        assertNull(store.getPassword(null))
        assertNull(store.getPassword(" "))
    }
}
