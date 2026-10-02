package com.smugview.app.data.cast

import com.smugview.app.ui.text.UserMessages

/** A Google Cast route as the matching rule sees it: the MediaRouter's id, and the Cast device id carried in its `extras`. */
internal data class CastRouteRef(val routeId: String, val castDeviceId: String?)

/**
 * Step 6-9 (R-59): the MediaRouter lists a Google device by its route id, the Cast session reports the same device by its Cast
 * device id, and they are different strings. The session's device is matched to its route by the Cast device id the route carries
 * in `extras`, so the list can show it as connected. Null when no route carries [sessionDeviceId].
 */
internal fun routeIdFor(sessionDeviceId: String, routes: List<CastRouteRef>): String? =
    routes.firstOrNull { it.castDeviceId != null && it.castDeviceId == sessionDeviceId }?.routeId

/**
 * The name of a DIAL device that gave neither a friendly name nor a model. "Amazon" only when its manufacturer says so; every
 * other device that answered DIAL is not known to be a Fire TV and is called [UserMessages.CAST_DIAL_DEVICE].
 */
internal fun unnamedDialDeviceLabel(manufacturer: String?): String =
    if (manufacturer?.contains("Amazon", ignoreCase = true) == true) "Amazon Device" else UserMessages.CAST_DIAL_DEVICE

/** What to type on the screen for a server bound on [port] of this phone at [ip]. */
internal fun webCompanionUrlFor(ip: String, port: Int): String = "http://$ip:$port"

/** Step 6-11c: why the screen link has no URL. They are different problems with different fixes, so the UI says which. */
enum class WebCompanionFailure {
    /** No port from 8080 to 8089 could be bound. */
    PortsInUse,

    /** The server could bind, but this phone has no Wi-Fi address to type on the screen. */
    NoWifi
}
