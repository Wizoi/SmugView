package com.smugview.app.scenario

import androidx.compose.runtime.mutableStateListOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * Pins the rule behind [readState]: another thread replacing a `SnapshotStateList` (as `BrowserHost.render` does on Main, `clear()`
 * then `addAll()`) must not make a reader on the test thread throw `ConcurrentModificationException`.
 *
 * Before [readState] the scenario tests iterated `vm.folderNavigationStack` directly, and one full-suite run died with
 * `ConcurrentModificationException` from `StateListIterator.validateModification`.
 */
class SnapshotReadTest {
    private val stackA = listOf("2sDN5x", "P4BKB")
    private val stackB = listOf("2sDN5x")

    /** Writes the way `render` does, from a second thread, while [read] runs [rounds] times on this one; returns the first failure. */
    private fun hammer(rounds: Int, read: (List<String>) -> List<String>): Throwable? {
        val list = mutableStateListOf<String>()
        val failure = AtomicReference<Throwable?>(null)
        val stop = java.util.concurrent.atomic.AtomicBoolean(false)
        val writer = thread(name = "main-stand-in") {
            var flip = false
            while (!stop.get()) {
                list.clear()
                list.addAll(if (flip) stackA else stackB)
                flip = !flip
            }
        }
        try {
            repeat(rounds) {
                try {
                    read(list)
                } catch (e: Throwable) {
                    failure.compareAndSet(null, e)
                    return failure.get()
                }
            }
        } finally {
            stop.set(true)
            writer.join()
        }
        return failure.get()
    }

    @Test fun `reading the stack through readState never throws while another thread replaces it`() {
        val failure = hammer(30_000) { list -> readState { list.map { it } } }
        assertEquals("a read on the test thread failed: $failure", null, failure)
    }

    @Test fun `a read through readState sees a whole stack that was committed, not a torn copy`() {
        val seen = HashSet<List<String>>()
        val failure = hammer(30_000) { list -> readState { list.map { it } }.also { seen += it } }
        assertEquals(null, failure)
        // It may catch the stack between clear() and addAll() (empty) but never half of a list.
        assertTrue("saw $seen", seen.all { it.isEmpty() || it == stackA || it == stackB })
    }
}
