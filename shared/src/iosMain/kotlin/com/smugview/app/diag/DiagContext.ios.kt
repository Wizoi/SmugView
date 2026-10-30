package com.smugview.app.diag

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

private class ActionElement(val id: String) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ActionElement>
}

// No thread-local on iOS: the element rides in the coroutine context, but the HTTP layer cannot read it from a thread yet.
internal actual fun actionElement(id: String): CoroutineContext.Element = ActionElement(id)
internal actual fun currentAction(): String? = null
