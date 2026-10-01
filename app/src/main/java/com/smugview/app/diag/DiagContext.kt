package com.smugview.app.diag

import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.asContextElement
import java.util.concurrent.atomic.AtomicLong

/**
 * Carries a short "action id" (e.g. `tree#12`) from a user action through coroutines to the HTTP
 * layer, so every request line in the diagnostics log can be tied to what triggered it.
 * Wrap the work in `withContext(DiagContext.element(id))`.
 */
object DiagContext {
    private val action = ThreadLocal<String?>()
    private val counter = AtomicLong()

    fun newActionId(kind: String): String = "$kind#${counter.incrementAndGet()}"

    fun element(id: String): ThreadContextElement<String?> = action.asContextElement(id)

    /** The id active on the calling thread, or null outside any action. */
    fun currentActionId(): String? = action.get()
}
