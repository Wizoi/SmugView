package com.smugview.app.util

import com.smugview.shared.platform.SimpleLock

/**
 * A list that can be added to from any coroutine while another reads it: every change swaps in a new immutable snapshot (the
 * CopyOnWriteArrayList the sync report used, which is JVM only). Iterators walk the snapshot they started on and cannot remove.
 */
class CopyOnWriteList<T> : AbstractMutableList<T>() {
    private val lock = SimpleLock()
    private var items: List<T> = emptyList()

    override val size: Int get() = items.size
    override fun get(index: Int): T = items[index]
    override fun add(index: Int, element: T) { lock.withLock { items = items.toMutableList().also { it.add(index, element) } } }
    override fun removeAt(index: Int): T = lock.withLock {
        val copy = items.toMutableList(); val removed = copy.removeAt(index); items = copy; removed
    }
    override fun set(index: Int, element: T): T = lock.withLock {
        val copy = items.toMutableList(); val old = copy.set(index, element); items = copy; old
    }
    override fun clear() { lock.withLock { items = emptyList() } }
    override fun iterator(): MutableIterator<T> {
        val snapshot = items.iterator()
        return object : MutableIterator<T> {
            override fun hasNext() = snapshot.hasNext()
            override fun next() = snapshot.next()
            override fun remove() = throw UnsupportedOperationException("a snapshot iterator cannot remove")
        }
    }
}
