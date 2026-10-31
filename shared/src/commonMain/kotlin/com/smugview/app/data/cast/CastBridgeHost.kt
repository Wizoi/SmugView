package com.smugview.app.data.cast

import kotlinx.coroutines.CoroutineScope

/**
 * Where Swift hands in the real [CastBridge] (a Google Cast wrapper) before the app's first screen is built. Null means no real bridge
 * yet, and [createCastManager] falls back to the simulator's [DebugCastBridge].
 */
object CastBridgeHost {
    var bridge: CastBridge? = null
}

/** The iOS cast manager: over [bridge], or the fake target when there is none. [scope] must run one coroutine at a time. */
fun createCastManager(bridge: CastBridge?, scope: CoroutineScope): CastManager =
    BridgedCastManager(bridge ?: DebugCastBridge(scope), scope)
