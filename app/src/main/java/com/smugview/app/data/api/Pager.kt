package com.smugview.app.data.api

import kotlinx.coroutines.delay
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive

/** One page as the server answered it: [count] and [total] are the server's own (`Pages.Count`, `Pages.Total`). */
internal class Page<T>(val items: List<T>, val start: Int, val count: Int, val total: Int?)

/**
 * The one pagination loop (Phase 4 design 3.2, R-27, R-31, P13). Every page is the same typed call with
 * `start` and `count`, so `_expand`, `_filter`, `_filteruri`, `_verbosity` and the caller's
 * `Cache-Control` ride on every page by construction. It never follows `Pages.NextPage`: that link is
 * the echo of the request plus `start`, and the echo drops `_expand` and `_verbosity`, so a page fetched
 * through it came back without the expansions the first page had (the covers of folder rows 101+).
 *
 * It owns the next start (`start + page.count`, from the server's `Count`, because servers clamp the
 * requested count: 100 albums, 200 children, 500 images, 100 search results), the stop rule, the
 * search window, the page cap, cancellation and the pause between pages. It does not own parsing,
 * retries (`RetryingCallFactory`), cache headers, all-or-nothing writes or per-page side effects, and
 * it never catches: a failure or a cancellation propagates to the caller untouched.
 */
internal object Pager {
    /** The search backend answers `start + count - 1 > 10,000` with an empty 500 (P6). */
    const val SEARCH_WINDOW = 10_000

    /**
     * Calls [fetch] for `start = first, first + Count, ...` until the listing is done.
     *
     * Done means: a page with `count == 0`, or `start + count - 1 >= total`, or [onPage] returned false, or
     * (with a [window]) the next start lies past it. [pageSize] is trimmed so `start + count - 1` never
     * exceeds [window]. Throws `IllegalStateException("PageCap")` rather than fetching more than [maxPages].
     *
     * @return the start to resume from when it stopped with pages left (a resumable caller keeps this
     * `Int`, not a URL), or null when the listing is complete.
     */
    suspend fun <T> each(
        first: Int = 1,
        pageSize: Int,
        window: Int? = null,
        maxPages: Int = 200,
        delayMs: Long = 100,
        fetch: suspend (start: Int, count: Int) -> Page<T>,
        onPage: suspend (Page<T>) -> Boolean
    ): Int? {
        var start = first
        var pages = 0
        while (true) {
            if (window != null && start > window) return null
            if (pages >= maxPages) throw IllegalStateException("PageCap")
            if (pages > 0 && delayMs > 0) delay(delayMs)
            coroutineContext.ensureActive()
            val count = if (window != null) minOf(pageSize, window - start + 1) else pageSize
            val page = fetch(start, count)
            pages++
            val keepGoing = onPage(page)
            val next = start + page.count
            val total = page.total
            val done = page.count <= 0 || (total != null && next - 1 >= total) || (window != null && next > window)
            if (done) return null
            if (!keepGoing) return next
            start = next
        }
    }
}
