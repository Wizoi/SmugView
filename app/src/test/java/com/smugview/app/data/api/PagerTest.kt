package com.smugview.app.data.api

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 4 step 4-3 (design 3.2, P13): the one pagination helper. A new class, so there is no old
 * behaviour to be red on; each case pins one rule the four hand-rolled loops each had slightly wrong.
 * The fake server here clamps the requested count to [cap] like the live API (children 200, albums
 * 100, images 500, search 100) and reports `Count` and `Total`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PagerTest {
    /** A listing of [total] items that answers at most [cap] per request. Records every (start, count) asked. */
    private class Listing(val total: Int, val cap: Int, val reportTotal: Boolean = true) {
        val asked = mutableListOf<Pair<Int, Int>>()
        fun fetch(start: Int, count: Int): Page<Int> {
            asked += start to count
            val n = maxOf(0, minOf(count, cap, total - start + 1))
            return Page((start until start + n).toList(), start, n, if (reportTotal) total else null)
        }
    }

    private suspend fun collect(
        listing: Listing, pageSize: Int, window: Int? = null, maxPages: Int = 200, first: Int = 1,
        stopAfterItems: Int? = null
    ): Pair<List<Int>, Int?> {
        val seen = mutableListOf<Int>()
        val next = Pager.each(
            first = first, pageSize = pageSize, window = window, maxPages = maxPages, delayMs = 0,
            fetch = listing::fetch,
            onPage = { p -> seen += p.items; stopAfterItems == null || seen.size < stopAfterItems }
        )
        return seen to next
    }

    @Test fun `pages by the servers Count when the server clamps the requested count`() = runBlocking {
        val l = Listing(total = 450, cap = 200)
        val (seen, next) = collect(l, pageSize = 500)
        assertEquals((1..450).toList(), seen)
        assertNull(next)
        // asked 500 each time, got 200, 200, 50: the next start came from Count, not from the ask
        assertEquals(listOf(1 to 500, 201 to 500, 401 to 500), l.asked)
    }

    @Test fun `stops at the total with no extra request`() = runBlocking {
        val l = Listing(total = 200, cap = 100)
        val (seen, next) = collect(l, pageSize = 100)
        assertEquals(200, seen.size)
        assertNull(next)
        assertEquals(2, l.asked.size)
    }

    @Test fun `an empty listing is one request`() = runBlocking {
        val l = Listing(total = 0, cap = 100)
        val (seen, next) = collect(l, pageSize = 100)
        assertEquals(emptyList<Int>(), seen)
        assertNull(next)
        assertEquals(1, l.asked.size)
    }

    @Test fun `an empty page ends it when the server gives no total`() = runBlocking {
        val l = Listing(total = 130, cap = 100, reportTotal = false)
        val (seen, next) = collect(l, pageSize = 100)
        assertEquals(130, seen.size)
        assertNull(next)
        assertEquals(listOf(1, 101, 131), l.asked.map { it.first })
    }

    @Test fun `trims the count to the search window and never asks past it`() = runBlocking {
        val l = Listing(total = 25_000, cap = 100)
        val (seen, next) = collect(l, pageSize = 100, window = Pager.SEARCH_WINDOW)
        assertEquals(10_000, seen.size)
        assertNull(next)
        assertEquals(10_000, l.asked.last().first + l.asked.last().second - 1)
        assertTrue(l.asked.all { (s, c) -> s + c - 1 <= Pager.SEARCH_WINDOW })
    }

    @Test fun `the last window page is trimmed when the page size does not divide the window`() = runBlocking {
        val l = Listing(total = 5_000, cap = 500)
        val (_, next) = collect(l, pageSize = 300, window = 1_000)
        assertNull(next)
        assertEquals(listOf(1 to 300, 301 to 300, 601 to 300, 901 to 100), l.asked)
    }

    @Test fun `a first start past the window is done without a request`() = runBlocking {
        val l = Listing(total = 25_000, cap = 100)
        val (seen, next) = collect(l, pageSize = 100, window = 10_000, first = 10_001)
        assertEquals(emptyList<Int>(), seen)
        assertNull(next)
        assertEquals(0, l.asked.size)
    }

    @Test fun `onPage false stops and returns the start to resume from`() = runBlocking {
        val l = Listing(total = 450, cap = 100)
        val (seen, next) = collect(l, pageSize = 100, stopAfterItems = 150)
        assertEquals(200, seen.size)
        assertEquals(201, next)
        // resuming from the returned start finishes the listing
        val l2 = Listing(total = 450, cap = 100)
        val (rest, next2) = collect(l2, pageSize = 100, first = next!!)
        assertEquals((201..450).toList(), rest)
        assertNull(next2)
    }

    @Test fun `onPage false on the last page returns null, not a start past the end`() = runBlocking {
        val l = Listing(total = 100, cap = 100)
        val (_, next) = collect(l, pageSize = 100, stopAfterItems = 1)
        assertNull(next)
    }

    @Test fun `throws PageCap instead of fetching more than maxPages`() {
        val l = Listing(total = 1_000, cap = 100)
        val e = assertThrows(IllegalStateException::class.java) { runBlocking { collect(l, pageSize = 100, maxPages = 3) } }
        assertEquals("PageCap", e.message)
        assertEquals(3, l.asked.size)
    }

    @Test fun `exactly maxPages pages that finish the listing is not a PageCap`() = runBlocking {
        val l = Listing(total = 300, cap = 100)
        val (seen, next) = collect(l, pageSize = 100, maxPages = 3)
        assertEquals(300, seen.size)
        assertNull(next)
    }

    @Test fun `a failing fetch propagates and no further page is requested`() {
        val l = Listing(total = 500, cap = 100)
        var calls = 0
        val e = assertThrows(java.io.IOException::class.java) {
            runBlocking {
                Pager.each<Int>(pageSize = 100, delayMs = 0, fetch = { s, c ->
                    if (++calls == 2) throw java.io.IOException("boom") else l.fetch(s, c)
                }, onPage = { true })
            }
        }
        assertEquals("boom", e.message)
        assertEquals(1, l.asked.size)
    }

    @Test fun `cancellation propagates and stops the loop`() = runBlocking {
        val l = Listing(total = 10_000, cap = 100)
        val arrived = CompletableDeferred<Unit>()
        val never = CompletableDeferred<Unit>()
        val job = async {
            Pager.each<Int>(pageSize = 100, delayMs = 0, fetch = { s, c ->
                if (s == 201) { arrived.complete(Unit); never.await() }
                l.fetch(s, c)
            }, onPage = { true })
        }
        arrived.await()
        job.cancel()
        val e = runCatching { job.await() }.exceptionOrNull()
        assertTrue("expected cancellation, got $e", e is CancellationException)
        // pages 1 and 2 were fetched; the third was in flight when it was cancelled and no fourth began
        assertEquals(listOf(1 to 100, 101 to 100), l.asked)
    }

    @Test fun `a cancelled caller does not start the next page even when the fetch itself never suspends`() = runBlocking {
        val l = Listing(total = 10_000, cap = 100)
        val job = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            Pager.each<Int>(pageSize = 100, delayMs = 0, fetch = l::fetch, onPage = { p ->
                if (p.start == 201) kotlin.coroutines.coroutineContext[kotlinx.coroutines.Job]!!.cancel()
                true
            })
        }
        job.join()
        assertTrue(job.isCancelled)
        // ensureActive() before the next page: pages 1-3 fetched, the 4th never asked
        assertEquals(3, l.asked.size)
    }

    @Test fun `waits between pages and not before the first`() = runTest {
        val l = Listing(total = 300, cap = 100)
        val start = testScheduler.currentTime
        Pager.each<Int>(pageSize = 100, delayMs = 100, fetch = l::fetch, onPage = { true })
        assertEquals(200L, testScheduler.currentTime - start)
    }
}
