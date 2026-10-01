package com.smugview.app.ui.viewmodel

import com.smugview.app.diag.Diag
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Phase 6 step 6-6 (N1, design 3.4): a job in a site's scope that nobody catches must not reach the thread's
 * uncaught handler (that closes the app). The session's handler logs it and counts it for `report.txt`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SiteSessionTest {
    private val uncaught = CopyOnWriteArrayList<Throwable>()
    private var previous: Thread.UncaughtExceptionHandler? = null

    @Before fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, e -> uncaught += e }
    }

    @After fun tearDown() {
        Thread.setDefaultUncaughtExceptionHandler(previous)
        Dispatchers.resetMain()
    }

    @Test fun `an uncaught job error is logged and counted, not thrown to the thread`() = runBlocking {
        val session = SiteSession("idzifamily", parent = null)
        val before = Diag.uncaughtSiteErrors.get()
        val done = CompletableDeferred<Unit>()

        session.scope.launch {
            try { throw java.io.IOException("no network") } finally { done.complete(Unit) }
        }
        done.await()
        Thread.sleep(100)

        assertEquals("nothing reaches the uncaught handler: $uncaught", emptyList<Throwable>(), uncaught.toList())
        assertEquals("the failure is counted for report.txt", before + 1, Diag.uncaughtSiteErrors.get())
        session.close()
    }

    @Test fun `a cancelled job is not counted as an error`() = runBlocking {
        val session = SiteSession("idzifamily", parent = null)
        val before = Diag.uncaughtSiteErrors.get()
        val job = session.scope.launch { kotlinx.coroutines.awaitCancellation() }

        session.close()
        job.join()

        assertTrue("cancellation is not an error", Diag.uncaughtSiteErrors.get() == before)
        assertTrue(uncaught.isEmpty())
    }
}
