package com.smugview.app.diag

import kotlinx.coroutines.asContextElement
import kotlin.coroutines.CoroutineContext

private val action = ThreadLocal<String?>()

internal actual fun actionElement(id: String): CoroutineContext.Element = action.asContextElement(id)
internal actual fun currentAction(): String? = action.get()
