package com.smugview.app.data.api

import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Tests the real [RetryingCallFactory] (previously this file duplicated the retry logic inline
 * as a copy-pasted `Interceptor`, which only verified the copy, not the production code — see the
 * "retryInterceptor blocks the dispatcher" tracker item in AGENTS.md for why this class exists).
 */
class RetryingCallFactoryTest {

    private fun jsonResponse(request: Request, code: Int, message: String, body: String = "", extraHeader: Pair<String, String>? = null): Response {
        val builder = Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(message)
            .body(body.toResponseBody("text/plain".toMediaTypeOrNull()))
        if (extraHeader != null) builder.header(extraHeader.first, extraHeader.second)
        return builder.build()
    }

    private fun request(): Request = Request.Builder().url("https://api.smugmug.com/api/v2/test").build()

    /** enqueue() and block on a latch — exercises the async path Retrofit's suspend functions use. */
    private fun enqueueAndAwait(call: Call, timeoutSeconds: Long = 5): Pair<Response?, IOException?> {
        val latch = CountDownLatch(1)
        var result: Response? = null
        var failure: IOException? = null
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                failure = e
                latch.countDown()
            }
            override fun onResponse(call: Call, response: Response) {
                result = response
                latch.countDown()
            }
        })
        assertTrue("callback did not fire within timeout", latch.await(timeoutSeconds, TimeUnit.SECONDS))
        return result to failure
    }

    @Test
    fun testRetriesOn429ThenSucceeds() {
        val requestCount = AtomicInteger(0)
        val delegate = Call.Factory { req ->
            FakeCall(req) {
                val count = requestCount.incrementAndGet()
                if (count < 3) jsonResponse(req, 429, "Too Many Requests", "rate limited", "Retry-After" to "0")
                else jsonResponse(req, 200, "OK", "Success Data")
            }
        }
        val factory = RetryingCallFactory(delegate = delegate, initialDelayMs = 5)

        val (response, failure) = enqueueAndAwait(factory.newCall(request()))

        assertNull(failure)
        assertEquals(200, response?.code)
        assertEquals("Success Data", response?.body?.string())
        assertEquals(3, requestCount.get()) // 1 initial + 2 retries
    }

    @Test
    fun testGivesUpAfterMaxAttempts() {
        val requestCount = AtomicInteger(0)
        val delegate = Call.Factory { req ->
            FakeCall(req) {
                requestCount.incrementAndGet()
                jsonResponse(req, 429, "Too Many Requests", "rate limited", "Retry-After" to "0")
            }
        }
        val factory = RetryingCallFactory(delegate = delegate, maxAttempts = 5, initialDelayMs = 5)

        val (response, failure) = enqueueAndAwait(factory.newCall(request()))

        assertNull(failure)
        assertEquals(429, response?.code)
        assertEquals(6, requestCount.get()) // 1 initial + 5 retries (max limit)
    }

    @Test
    fun testNonRetryableCodePassesThroughImmediately() {
        val requestCount = AtomicInteger(0)
        val delegate = Call.Factory { req ->
            FakeCall(req) {
                requestCount.incrementAndGet()
                jsonResponse(req, 404, "Not Found", "missing")
            }
        }
        val factory = RetryingCallFactory(delegate = delegate, initialDelayMs = 5)

        val (response, _) = enqueueAndAwait(factory.newCall(request()))

        assertEquals(404, response?.code)
        assertEquals(1, requestCount.get()) // no retry for a non-retryable code
    }

    @Test
    fun testNetworkExceptionIsNotRetried() {
        // Matches the original interceptor: only HTTP-level 429/5xx responses are retried, not
        // IOExceptions (timeouts, connection failures) — those propagate immediately.
        val requestCount = AtomicInteger(0)
        val delegate = Call.Factory { req ->
            FakeCall(req) {
                requestCount.incrementAndGet()
                throw IOException("boom")
            }
        }
        val factory = RetryingCallFactory(delegate = delegate, initialDelayMs = 5)

        val (response, failure) = enqueueAndAwait(factory.newCall(request()))

        assertNull(response)
        assertNotNull(failure)
        assertEquals(1, requestCount.get())
    }

    @Test
    fun testOnFinalResponseFiresExactlyOnceForFinalOutcome() {
        // The whole point of moving error-reporting into a callback instead of a chain-positioned
        // Interceptor: it must fire once for the settled outcome, not once per retry attempt.
        val requestCount = AtomicInteger(0)
        val finalResponseCount = AtomicInteger(0)
        val delegate = Call.Factory { req ->
            FakeCall(req) {
                val count = requestCount.incrementAndGet()
                if (count < 3) jsonResponse(req, 500, "Server Error", "oops")
                else jsonResponse(req, 200, "OK", "done")
            }
        }
        val factory = RetryingCallFactory(
            delegate = delegate,
            initialDelayMs = 5,
            onFinalResponse = { _, _ -> finalResponseCount.incrementAndGet() }
        )

        enqueueAndAwait(factory.newCall(request()))

        assertEquals(0, finalResponseCount.get()) // final outcome was a 200 — nothing to report
    }

    @Test
    fun testOnFinalResponseFiresForExhaustedRetries() {
        val finalResponseCount = AtomicInteger(0)
        val delegate = Call.Factory { req ->
            FakeCall(req) { jsonResponse(req, 500, "Server Error", "oops") }
        }
        val factory = RetryingCallFactory(
            delegate = delegate,
            maxAttempts = 2,
            initialDelayMs = 5,
            onFinalResponse = { _, _ -> finalResponseCount.incrementAndGet() }
        )

        enqueueAndAwait(factory.newCall(request()))

        assertEquals(1, finalResponseCount.get()) // exactly once, not once per attempt
    }

    @Test
    fun testExecuteSyncPathAlsoRetries() {
        val requestCount = AtomicInteger(0)
        val delegate = Call.Factory { req ->
            FakeCall(req) {
                val count = requestCount.incrementAndGet()
                if (count < 2) jsonResponse(req, 503, "Unavailable", "retry me")
                else jsonResponse(req, 200, "OK", "ok now")
            }
        }
        val factory = RetryingCallFactory(delegate = delegate, initialDelayMs = 5)

        val response = factory.newCall(request()).execute()

        assertEquals(200, response.code)
        assertEquals(2, requestCount.get())
    }

    // --- onComplete / actionIdProvider (Phase 1a-4) ---

    private fun outcomeCollector() = java.util.Collections.synchronizedList(mutableListOf<CallOutcome>())

    @Test
    fun onComplete_firesOnceOnSuccess_withAttemptsAndCodes() {
        val count = AtomicInteger(0)
        val delegate = Call.Factory { req ->
            FakeCall(req) {
                if (count.incrementAndGet() < 3) jsonResponse(req, 503, "Unavailable", "x")
                else jsonResponse(req, 200, "OK", "done")
            }
        }
        val outcomes = outcomeCollector()
        val factory = RetryingCallFactory(delegate = delegate, initialDelayMs = 5, onComplete = { outcomes.add(it) })

        enqueueAndAwait(factory.newCall(request()))
        Thread.sleep(50)

        assertEquals(1, outcomes.size)
        val o = outcomes.single()
        assertEquals(200, o.response?.code)
        assertEquals(3, o.attempts)
        assertEquals(listOf(503, 503, 200), o.codes)
        assertNull(o.error)
        assertFalse(o.canceled)
    }

    @Test
    fun onComplete_firesOnceWhenRetriesAreExhausted() {
        val delegate = Call.Factory { req -> FakeCall(req) { jsonResponse(req, 500, "Server Error", "oops") } }
        val outcomes = outcomeCollector()
        val factory = RetryingCallFactory(
            delegate = delegate, maxAttempts = 2, initialDelayMs = 5, onComplete = { outcomes.add(it) }
        )

        enqueueAndAwait(factory.newCall(request()))
        Thread.sleep(50)

        assertEquals(1, outcomes.size)
        assertEquals(listOf(500, 500, 500), outcomes.single().codes)
        assertEquals(3, outcomes.single().attempts)
    }

    @Test
    fun onComplete_firesOnceOnIoFailure() {
        val delegate = Call.Factory { req -> FakeCall(req) { throw IOException("boom") } }
        val outcomes = outcomeCollector()
        val factory = RetryingCallFactory(delegate = delegate, initialDelayMs = 5, onComplete = { outcomes.add(it) })

        enqueueAndAwait(factory.newCall(request()))
        Thread.sleep(50)

        assertEquals(1, outcomes.size)
        assertNull(outcomes.single().response)
        assertEquals("boom", outcomes.single().error?.message)
        assertEquals(1, outcomes.single().attempts)
        assertTrue(outcomes.single().codes.isEmpty())
    }

    @Test
    fun onComplete_firesOnceOnCancel_evenIfTheDelegateAlsoReportsFailure() {
        val delegate = Call.Factory { req -> PendingCall(req) }
        val outcomes = outcomeCollector()
        val factory = RetryingCallFactory(delegate = delegate, onComplete = { outcomes.add(it) })
        val call = factory.newCall(request())
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {}
            override fun onResponse(call: Call, response: Response) {}
        })

        call.cancel()
        Thread.sleep(50)

        assertEquals(1, outcomes.size)
        assertTrue(outcomes.single().canceled)
    }

    @Test
    fun onComplete_firesOnceOnTheSyncPath() {
        val count = AtomicInteger(0)
        val delegate = Call.Factory { req ->
            FakeCall(req) {
                if (count.incrementAndGet() < 2) jsonResponse(req, 503, "Unavailable", "x")
                else jsonResponse(req, 200, "OK", "ok")
            }
        }
        val outcomes = outcomeCollector()
        val factory = RetryingCallFactory(delegate = delegate, initialDelayMs = 5, onComplete = { outcomes.add(it) })

        factory.newCall(request()).execute()

        assertEquals(1, outcomes.size)
        assertEquals(listOf(503, 200), outcomes.single().codes)
    }

    @Test
    fun aTelemetryCallbackThatThrows_neverBreaksTheCall() {
        val delegate = Call.Factory { req -> FakeCall(req) { jsonResponse(req, 200, "OK", "fine") } }
        val factory = RetryingCallFactory(delegate = delegate, onComplete = { error("telemetry bug") })

        val (response, failure) = enqueueAndAwait(factory.newCall(request()))

        assertNull(failure)
        assertEquals(200, response?.code)
    }

    @Test
    fun actionId_isCapturedFromTheCallersThreadAtNewCall() {
        val current = ThreadLocal<String?>()
        val delegate = Call.Factory { req -> FakeCall(req) { jsonResponse(req, 200, "OK", "x") } }
        val outcomes = outcomeCollector()
        val factory = RetryingCallFactory(
            delegate = delegate, actionIdProvider = { current.get() }, onComplete = { outcomes.add(it) }
        )
        current.set("tree#9")
        val call = factory.newCall(request())
        current.set(null)

        // Completion happens on another thread, where the provider would return null.
        val t = Thread { enqueueAndAwait(call) }
        t.start(); t.join()

        assertEquals("tree#9", outcomes.single().actionId)
    }

    private interface PingApi {
        @retrofit2.http.GET("ping")
        suspend fun ping(): ResponseBody
    }

    @Test
    fun realRetrofitSuspendCall_insideDiagContext_reportsTheActionId() {
        val delegate = Call.Factory { req -> FakeCall(req) { jsonResponse(req, 200, "OK", "pong") } }
        val outcomes = outcomeCollector()
        val factory = RetryingCallFactory(
            delegate = delegate,
            actionIdProvider = com.smugview.app.diag.DiagContext::currentActionId,
            onComplete = { outcomes.add(it) }
        )
        val api = retrofit2.Retrofit.Builder()
            .baseUrl("https://api.smugmug.com/api/v2/")
            .callFactory(factory)
            .build()
            .create(PingApi::class.java)

        kotlinx.coroutines.runBlocking {
            kotlinx.coroutines.withContext(com.smugview.app.diag.DiagContext.element("t#1")) { api.ping().close() }
            api.ping().close()
        }

        assertEquals(listOf<String?>("t#1", null), outcomes.map { it.actionId })
    }

    /** Never answers by itself; like OkHttp, reports a failure to its callback when cancelled. */
    private class PendingCall(private val req: Request) : Call {
        private var callback: Callback? = null
        private var canceled = false
        override fun request(): Request = req
        override fun execute(): Response = throw IOException("not used")
        override fun enqueue(responseCallback: Callback) { callback = responseCallback }
        override fun cancel() { canceled = true; callback?.onFailure(this, IOException("Canceled")) }
        override fun isExecuted(): Boolean = callback != null
        override fun isCanceled(): Boolean = canceled
        override fun timeout(): okio.Timeout = okio.Timeout.NONE
        override fun clone(): Call = PendingCall(req)
    }

    /** Minimal synchronous fake — enqueue() runs [block] inline (fine for these deterministic tests,
     *  RetryingCallFactory itself is what provides the real async scheduling being tested). */
    private class FakeCall(private val req: Request, private val block: () -> Response) : Call {
        private var canceled = false
        override fun request(): Request = req
        override fun execute(): Response = block()
        override fun enqueue(responseCallback: Callback) {
            try {
                responseCallback.onResponse(this, block())
            } catch (e: IOException) {
                responseCallback.onFailure(this, e)
            }
        }
        override fun cancel() { canceled = true }
        override fun isExecuted(): Boolean = false
        override fun isCanceled(): Boolean = canceled
        override fun timeout(): okio.Timeout = okio.Timeout.NONE
        override fun clone(): Call = FakeCall(req, block)
    }
}
