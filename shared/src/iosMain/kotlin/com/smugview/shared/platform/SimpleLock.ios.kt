package com.smugview.shared.platform

import platform.Foundation.NSRecursiveLock

actual class SimpleLock {
    @PublishedApi internal val lock = NSRecursiveLock()
    actual inline fun <T> withLock(block: () -> T): T {
        lock.lock()
        try { return block() } finally { lock.unlock() }
    }
}
