package com.smugview.app.data.notify

import com.smugview.app.PendingOpen
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * A tapped new-gallery notification waiting to be opened, the iOS twin of Android's intent extras. The Swift notification delegate
 * calls [set] (a tap can arrive before the UI exists, on a cold start), and the root composable observes [pending] and calls [clear]
 * from `SmugViewNavigation(onOpenHandled = ...)`.
 */
object IosPendingOpen {
    private val state = MutableStateFlow<PendingOpen?>(null)
    val pending: StateFlow<PendingOpen?> get() = state

    fun set(nickname: String, albumKey: String?, albumTitle: String?) {
        if (nickname.isBlank()) return
        state.value = PendingOpen(nickname, albumKey?.takeIf { it.isNotBlank() }, albumTitle)
    }

    fun clear() { state.value = null }
}
