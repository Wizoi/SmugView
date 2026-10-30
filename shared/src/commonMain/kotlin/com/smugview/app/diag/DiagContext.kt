package com.smugview.app.diag

import com.smugview.shared.platform.SimpleLock
import kotlin.coroutines.CoroutineContext

/**
 * Carries a short "action id" (e.g. `tree#12`) from a user action through coroutines to the HTTP
 * layer, so every request line in the diagnostics log can be tied to what triggered it.
 * Wrap the work in `withContext(DiagContext.element(id))`.
 */
object DiagContext {
    private val lock = SimpleLock()
    private var counter = 0L

    fun newActionId(kind: String): String = "$kind#${lock.withLock { ++counter }}"

    /** A context element that makes [id] the current action while the coroutine runs. */
    fun element(id: String): CoroutineContext.Element = actionElement(id)

    /** The id active on the calling thread, or null outside any action (Android; on iOS there is no thread-local yet, so always null). */
    fun currentActionId(): String? = currentAction()
}

internal expect fun actionElement(id: String): CoroutineContext.Element
internal expect fun currentAction(): String?
