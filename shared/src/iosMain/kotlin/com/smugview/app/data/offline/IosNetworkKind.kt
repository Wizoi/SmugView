package com.smugview.app.data.offline

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import platform.Network.nw_interface_type_wifi
import platform.Network.nw_path_get_status
import platform.Network.nw_path_is_expensive
import platform.Network.nw_path_monitor_cancel
import platform.Network.nw_path_monitor_create
import platform.Network.nw_path_monitor_set_queue
import platform.Network.nw_path_monitor_set_update_handler
import platform.Network.nw_path_monitor_start
import platform.Network.nw_path_status_satisfied
import platform.Network.nw_path_uses_interface_type
import platform.darwin.dispatch_queue_create

/**
 * The default network's [NetworkKind] from `NWPathMonitor` (the C `nw_path_monitor_*` API), the iOS twin of Android's `networkKindFlow`.
 * The monitor delivers the current path right after `start`, so the first emission is the current state; it is cancelled when the
 * collector goes away. Written without a Mac: the macOS CI job is the first compile.
 */
@OptIn(ExperimentalForeignApi::class)
fun iosNetworkKindFlow(): Flow<NetworkKind> = callbackFlow {
    val monitor = nw_path_monitor_create()
    val queue = dispatch_queue_create("com.smugview.app.netmonitor", null)
    nw_path_monitor_set_update_handler(monitor) { path ->
        if (path != null) {
            trySend(
                networkKindOfPath(
                    satisfied = nw_path_get_status(path) == nw_path_status_satisfied,
                    expensive = nw_path_is_expensive(path),
                    usesWifi = nw_path_uses_interface_type(path, nw_interface_type_wifi)
                )
            )
        }
    }
    nw_path_monitor_set_queue(monitor, queue)
    nw_path_monitor_start(monitor)
    awaitClose { nw_path_monitor_cancel(monitor) }
}.distinctUntilChanged().conflate()
