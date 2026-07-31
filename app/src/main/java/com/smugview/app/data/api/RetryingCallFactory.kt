package com.smugview.app.data.api

import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import okio.Timeout
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Retries 429/5xx responses with the same exponential backoff + `Retry-After` honoring the OkHttp
 * `Interceptor` this replaces used, but via a [ScheduledExecutorService] instead of `Thread.sleep`.
 *
 * Why this can't be a plain `Interceptor`: `Interceptor.intercept()` is a synchronous, blocking API
 * — the only way to "wait, then retry" from inside one is to actually block the calling thread for
 * the backoff duration. For Retrofit's `suspend fun` endpoints (which call OkHttp via the async
 * `Call.enqueue()` path), that thread is one of OkHttp's own bounded dispatcher worker threads —
 * verified to starve unrelated concurrent requests when several calls are backing off at once (see
 * the "retryInterceptor blocks the dispatcher" note in AGENTS.md). Implementing retry as a
 * decorating [Call.Factory] instead means a backing-off request schedules its retry on a timer and
 * releases the dispatcher thread entirely in the meantime.
 *
 * Deliberately Android/Toast-agnostic (no [android.content.Context] dependency) so it's unit
 * testable in isolation — [onFinalResponse]/[onFinalFailure] are injected so the caller (see
 * `AppModule.provideRetryingCallFactory`) can still surface exactly one user-facing error report
 * per *logical* request, matching the original interceptor's behavior, without this class needing
 * to know anything about Toasts or friendly-error mapping.
 */
class RetryingCallFactory(
    private val delegate: Call.Factory,
    private val maxAttempts: Int = 5,
    private val initialDelayMs: Long = 500L,
    private val scheduler: ScheduledExecutorService = defaultScheduler,
    private val onFinalResponse: (Request, Response) -> Unit = { _, _ -> },
    private val onFinalFailure: (Request, IOException) -> Unit = { _, _ -> }
) : Call.Factory {

    override fun newCall(request: Request): Call = RetryingCall(request)

    private fun shouldRetry(response: Response, attempt: Int): Boolean =
        !response.isSuccessful && (response.code == 429 || response.code in 500..599) && attempt < maxAttempts

    private fun retryDelayMs(response: Response, backoffMs: Long): Long {
        if (response.code == 429) {
            val retryAfterSeconds =
                (response.header("Retry-After") ?: response.header("retry-after"))?.toLongOrNull()
            if (retryAfterSeconds != null) return retryAfterSeconds * 1000L
        }
        return backoffMs
    }

    private inner class RetryingCall(private val originalRequest: Request) : Call {
        private val executed = AtomicBoolean(false)
        private val canceled = AtomicBoolean(false)

        @Volatile private var activeCall: Call = delegate.newCall(originalRequest)

        override fun request(): Request = originalRequest

        override fun execute(): Response {
            check(executed.compareAndSet(false, true)) { "Already Executed" }
            // Synchronous execute() already blocks its caller by contract (that's what the caller
            // asked for), so Thread.sleep here doesn't reintroduce the dispatcher-starvation
            // problem this class exists to fix — nothing in this app calls execute() today
            // (Retrofit's suspend functions use enqueue()), this is just for Call.Factory
            // interface completeness.
            var attempt = 0
            var backoffMs = initialDelayMs
            var response = activeCall.execute()
            while (!canceled.get() && shouldRetry(response, attempt)) {
                val sleepMs = retryDelayMs(response, backoffMs)
                response.close()
                attempt++
                backoffMs *= 2
                try {
                    Thread.sleep(sleepMs)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw IOException(e)
                }
                activeCall = activeCall.clone()
                response = activeCall.execute()
            }
            if (!response.isSuccessful) onFinalResponse(originalRequest, response)
            return response
        }

        override fun enqueue(responseCallback: Callback) {
            check(executed.compareAndSet(false, true)) { "Already Executed" }
            attempt(activeCall, 0, initialDelayMs, responseCallback)
        }

        private fun attempt(call: Call, attemptNumber: Int, backoffMs: Long, userCallback: Callback) {
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (canceled.get()) return
                    // Matches the original interceptor: only HTTP-level 429/5xx responses are
                    // retried, not network/IO exceptions (timeouts, connection failures, etc.) —
                    // those propagate immediately, same as before.
                    onFinalFailure(originalRequest, e)
                    userCallback.onFailure(call, e)
                }

                override fun onResponse(call: Call, response: Response) {
                    if (canceled.get()) {
                        response.close()
                        return
                    }
                    if (shouldRetry(response, attemptNumber)) {
                        val sleepMs = retryDelayMs(response, backoffMs)
                        response.close()
                        val nextCall = activeCall.clone()
                        activeCall = nextCall
                        scheduler.schedule({
                            if (!canceled.get()) {
                                attempt(nextCall, attemptNumber + 1, backoffMs * 2, userCallback)
                            }
                        }, sleepMs, TimeUnit.MILLISECONDS)
                    } else {
                        if (!response.isSuccessful) onFinalResponse(originalRequest, response)
                        userCallback.onResponse(call, response)
                    }
                }
            })
        }

        override fun cancel() {
            canceled.set(true)
            activeCall.cancel()
        }

        override fun isExecuted(): Boolean = executed.get()
        override fun isCanceled(): Boolean = canceled.get()
        override fun timeout(): Timeout = activeCall.timeout()

        override fun clone(): Call = RetryingCall(originalRequest)
    }

    companion object {
        private val defaultScheduler: ScheduledExecutorService =
            Executors.newSingleThreadScheduledExecutor { runnable ->
                Thread(runnable, "SmugMugRetryScheduler").apply { isDaemon = true }
            }
    }
}
