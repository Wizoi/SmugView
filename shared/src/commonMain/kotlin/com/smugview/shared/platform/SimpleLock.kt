package com.smugview.shared.platform

/** A re-entrant lock (`synchronized` is JVM only). */
expect class SimpleLock() {
    fun <T> withLock(block: () -> T): T
}
