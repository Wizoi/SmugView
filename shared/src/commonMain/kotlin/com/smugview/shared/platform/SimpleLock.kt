package com.smugview.shared.platform

/** A re-entrant lock (`synchronized` is JVM only). [withLock] is inline, so a `return` inside it leaves the caller, as inside `synchronized`. */
expect class SimpleLock() {
    inline fun <T> withLock(block: () -> T): T
}
