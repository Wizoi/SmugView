package com.smugview.app.data.security

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDictionaryAddValue
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFMutableDictionaryRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFRetain
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFAllocatorDefault
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSData
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.SecItemUpdate
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleAfterFirstUnlock
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitAll
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnAttributes
import platform.Security.kSecReturnData
import platform.Security.kSecValueData

/**
 * The SmugMug unlock passwords in the iOS Keychain (one generic-password item per key, service [SERVICE], account = the key,
 * readable after the first unlock so a background refresh can use them). The logic (key set, revision counter, memory fallback
 * when the Keychain cannot be used) is [SecureStorePasswordStore], tested in common code; only the Keychain calls live here.
 * Written without a Mac: the macOS CI job is its first compile.
 */
class KeychainPasswordStore(storage: SecureStorage = KeychainStorage()) : PasswordStore by SecureStorePasswordStore(storage)

private const val SERVICE = "com.smugview.passwords"
private const val STATUS_OK = 0
private const val STATUS_NOT_FOUND = -25300 // errSecItemNotFound

/** [SecureStorage] over `SecItem*`. Any status other than success / not-found counts as "Keychain unusable" (for example -34018 on an unsigned build). */
@OptIn(ExperimentalForeignApi::class)
internal class KeychainStorage(private val service: String = SERVICE) : SecureStorage {

    override fun get(key: String): String? = readData(key)?.let { dataToString(it) }

    override fun put(key: String, value: String): Boolean {
        val data = stringToData(value)
        val update = Cf()
        val add = Cf()
        val attrs = Cf()
        try {
            val updateStatus = SecItemUpdate(
                update.dict(baseQuery(update, key)),
                attrs.dict { d -> d.add(kSecValueData, attrs.retained(data)) }
            )
            if (updateStatus == STATUS_OK) return true
            if (updateStatus != STATUS_NOT_FOUND) return false
            val addStatus = SecItemAdd(
                add.dict {
                    baseQuery(add, key)(it)
                    it.add(kSecValueData, add.retained(data))
                    it.add(kSecAttrAccessible, kSecAttrAccessibleAfterFirstUnlock)
                },
                null
            )
            return addStatus == STATUS_OK
        } finally {
            update.release(); add.release(); attrs.release()
        }
    }

    override fun remove(key: String) {
        val cf = Cf()
        try {
            SecItemDelete(cf.dict(baseQuery(cf, key)))
        } finally {
            cf.release()
        }
    }

    override fun all(): Map<String, String>? {
        val keys = allKeys() ?: return null
        val result = LinkedHashMap<String, String>()
        for (k in keys) get(k)?.let { result[k] = it }
        return result
    }

    private fun allKeys(): List<String>? {
        val cf = Cf()
        try {
            val query = cf.dict {
                it.add(kSecClass, kSecClassGenericPassword)
                it.add(kSecAttrService, cf.retained(service))
                it.add(kSecReturnAttributes, kCFBooleanTrue)
                it.add(kSecMatchLimit, kSecMatchLimitAll)
            }
            var status = 0
            val found: Any? = memScoped {
                val out = alloc<CFTypeRefVar>()
                status = SecItemCopyMatching(query, out.ptr)
                if (status == STATUS_OK) CFBridgingRelease(out.value) else null
            }
            if (status == STATUS_NOT_FOUND) return emptyList()
            if (status != STATUS_OK) return null
            val accountKey = CFBridgingRelease(CFRetain(kSecAttrAccount)) as? String ?: "acct"
            val rows = found as? List<*> ?: return emptyList()
            return rows.mapNotNull { (it as? Map<*, *>)?.get(accountKey) as? String }
        } finally {
            cf.release()
        }
    }

    private fun readData(key: String): NSData? {
        val cf = Cf()
        try {
            val query = cf.dict {
                baseQuery(cf, key)(it)
                it.add(kSecReturnData, kCFBooleanTrue)
                it.add(kSecMatchLimit, kSecMatchLimitOne)
            }
            var status = 0
            val found: Any? = memScoped {
                val out = alloc<CFTypeRefVar>()
                status = SecItemCopyMatching(query, out.ptr)
                if (status == STATUS_OK) CFBridgingRelease(out.value) else null
            }
            return found as? NSData
        } finally {
            cf.release()
        }
    }

    /** class + service + account: the item's identity. */
    private fun baseQuery(cf: Cf, key: String): (CFMutableDictionaryRef?) -> Unit = { d ->
        d.add(kSecClass, kSecClassGenericPassword)
        d.add(kSecAttrService, cf.retained(service))
        d.add(kSecAttrAccount, cf.retained(key))
    }

    private fun CFMutableDictionaryRef?.add(key: Any?, value: Any?) {
        @Suppress("UNCHECKED_CAST")
        CFDictionaryAddValue(this, key as kotlinx.cinterop.COpaquePointer?, value as kotlinx.cinterop.COpaquePointer?)
    }

    private fun stringToData(s: String): NSData {
        val bytes = s.encodeToByteArray()
        if (bytes.isEmpty()) return NSData()
        return bytes.usePinned { NSData.create(bytes = it.addressOf(0), length = bytes.size.toULong()) }
    }

    private fun dataToString(d: NSData): String? = NSString.create(data = d, encoding = NSUTF8StringEncoding)?.toString()

    /** Tracks the Core Foundation objects one call created, so they are released together. */
    private class Cf {
        private val owned = ArrayList<kotlinx.cinterop.COpaquePointer>()

        /** A +1 Core Foundation reference for a Kotlin String / NSData (the dictionary retains its own). */
        fun retained(value: Any): kotlinx.cinterop.COpaquePointer? =
            CFBridgingRetain(value)?.also { owned += it }

        fun dict(fill: (CFMutableDictionaryRef?) -> Unit): CFMutableDictionaryRef? {
            val d = CFDictionaryCreateMutable(
                kCFAllocatorDefault, 0, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr
            )
            fill(d)
            d?.let { owned += it }
            return d
        }

        fun release() {
            owned.forEach { CFRelease(it) }
            owned.clear()
        }
    }
}
