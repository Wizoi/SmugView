@file:OptIn(kotlinx.cinterop.BetaInteropApi::class)

package com.smugview.app.data.security

import platform.Foundation.NSUUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Keychain on the simulator. An unsigned test process may get errSecMissingEntitlement (-34018); the design is then a
 * memory fallback with keptOnlyThisSession = true. Both outcomes must pass, each asserting its own intended behaviour,
 * and the log says which one ran. Every test uses its own unique service and removes what it wrote.
 */
class KeychainStorageTest {
    private fun newService() = "com.smugview.test.keychain." + NSUUID().UUIDString

    /** True when the Keychain accepts a write in this process. Leaves nothing behind. */
    private fun keychainWorks(storage: KeychainStorage): Boolean {
        val ok = storage.put("probe", "x")
        storage.remove("probe")
        return ok
    }

    @Test
    fun storageRoundTripsWhenUsableAndReportsFailureWhenNot() {
        val service = newService()
        val storage = KeychainStorage(service)
        val usable = keychainWorks(storage)
        println("KeychainStorageTest: Keychain usable in this process = $usable")
        try {
            if (usable) {
                assertTrue(storage.put("a", "one"))
                assertTrue(storage.put("b", "two"))
                assertEquals("one", storage.get("a"))
                assertEquals(mapOf("a" to "one", "b" to "two"), storage.all())
                assertTrue(storage.put("a", "uno"), "updating the same key")
                assertEquals("uno", storage.get("a"))
                assertEquals(mapOf("a" to "uno", "b" to "two"), storage.all())
                // a second instance sees the same items (they are in the Keychain, not in the object)
                assertEquals("uno", KeychainStorage(service).get("a"))
                storage.remove("a")
                assertNull(storage.get("a"))
                assertEquals(mapOf("b" to "two"), storage.all())
                storage.remove("b")
                assertEquals(emptyMap(), storage.all())
            } else {
                assertFalse(storage.put("a", "one"))
                assertNull(storage.get("a"))
                assertNull(storage.all(), "an unusable Keychain answers null, not an empty map")
            }
        } finally {
            storage.remove("a"); storage.remove("b")
        }
    }

    @Test
    fun passwordStoreRoundTripsInEitherCase() {
        val service = newService()
        val usable = keychainWorks(KeychainStorage(service))
        println("KeychainStorageTest.passwordStore: Keychain usable = $usable")
        val store = KeychainPasswordStore(KeychainStorage(service))
        try {
            assertEquals(!usable, store.keptOnlyThisSession.value)
            assertNull(store.getPassword("family"))
            val rev0 = store.revision.value

            store.savePassword("family", "pw1")
            assertEquals("pw1", store.getPassword("family"))
            assertEquals(mapOf("family" to "pw1"), store.all())
            assertEquals(setOf("family"), store.unlockedKeys.value)
            assertTrue(store.revision.value > rev0)
            assertEquals(!usable, store.keptOnlyThisSession.value)

            store.savePassword("family", "pw2")
            assertEquals("pw2", store.getPassword("family"))
            store.savePassword("school", "pw3")
            assertEquals(mapOf("family" to "pw2", "school" to "pw3"), store.all())
            assertEquals(setOf("family", "school"), store.unlockedKeys.value)

            val second = KeychainPasswordStore(KeychainStorage(service))
            if (usable) {
                assertEquals("pw2", second.getPassword("family"))
                assertFalse(second.keptOnlyThisSession.value)
                assertEquals(setOf("family", "school"), second.unlockedKeys.value)
            } else {
                // memory fallback: a new instance has nothing, and says so
                assertNull(second.getPassword("family"))
                assertTrue(second.keptOnlyThisSession.value)
            }

            store.remove("family")
            assertNull(store.getPassword("family"))
            assertEquals(mapOf("school" to "pw3"), store.all())
            assertEquals(setOf("school"), store.unlockedKeys.value)
            assertNull(store.getPassword(null))
            assertNull(store.getPassword(" "))
        } finally {
            store.remove("family"); store.remove("school")
        }
    }

    @Test
    fun unicodePasswordsSurvive() {
        val service = newService()
        val store = KeychainPasswordStore(KeychainStorage(service))
        try {
            store.savePassword("k", "pässwörd 日本語")
            assertEquals("pässwörd 日本語", store.getPassword("k"))
        } finally {
            store.remove("k")
        }
    }
}
