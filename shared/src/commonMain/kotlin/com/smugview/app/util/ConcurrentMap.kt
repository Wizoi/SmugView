package com.smugview.app.util

import com.smugview.shared.platform.SimpleLock

/** A small thread-safe map (the `ConcurrentHashMap` the repository used is JVM only). Every operation holds one lock. */
class ConcurrentMap<K, V> {
    private val lock = SimpleLock()
    private val map = HashMap<K, V>()

    operator fun get(key: K): V? = lock.withLock { map[key] }
    operator fun set(key: K, value: V) { lock.withLock { map[key] = value } }
    fun getOrPut(key: K, create: () -> V): V = lock.withLock { map.getOrPut(key, create) }
    fun remove(key: K): V? = lock.withLock { map.remove(key) }
    fun clear() { lock.withLock { map.clear() } }
}

/** An integer that several coroutines count up and down (the `AtomicInteger` it replaces is JVM only). */
class AtomicCounter(initial: Int = 0) {
    private val lock = SimpleLock()
    private var value = initial
    fun get(): Int = lock.withLock { value }
    fun incrementAndGet(): Int = lock.withLock { ++value }
    fun decrementAndGet(): Int = lock.withLock { --value }
}

/** The simple class name of a failure, for diagnostics lines (`javaClass.simpleName` is JVM only). */
fun Throwable.kindName(): String = this::class.simpleName ?: "Throwable"

/** A small thread-safe set; [add] says whether the element was new (like `MutableSet.add`). */
class ConcurrentSet<T> {
    private val lock = SimpleLock()
    private val set = HashSet<T>()

    fun add(element: T): Boolean = lock.withLock { set.add(element) }
    fun remove(element: T): Boolean = lock.withLock { set.remove(element) }
    fun contains(element: T): Boolean = lock.withLock { element in set }
}
