package com.smugview.shared.platform

import java.util.concurrent.locks.ReentrantLock

actual class SimpleLock {
    @PublishedApi internal val lock = ReentrantLock()
    actual inline fun <T> withLock(block: () -> T): T {
        lock.lock()
        try { return block() } finally { lock.unlock() }
    }
}
