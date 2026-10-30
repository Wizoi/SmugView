package com.smugview.shared.platform

import platform.Foundation.NSRecursiveLock

actual class SimpleLock {
    private val lock = NSRecursiveLock()
    actual fun <T> withLock(block: () -> T): T {
        lock.lock()
        try { return block() } finally { lock.unlock() }
    }
}
