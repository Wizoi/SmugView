package com.smugview.app.data.cast

enum class CastType {
    GOOGLE,
    ROKU,
    AMAZON
}

enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    FAILED
}

/**
 * Whether a device can be cast to, as far as the device itself says. [Unknown] is every device nothing was asked of, and one that could
 * not be asked: it stays selectable. Only a device that reports it cannot be cast to is [Unsupported], and it carries the reason to show.
 */
sealed interface CastSupport {
    data object Unknown : CastSupport
    data object Supported : CastSupport
    data class Unsupported(val reason: String) : CastSupport
}

data class CastDevice(
    val id: String,
    val name: String,
    val ipAddress: String,
    val type: CastType,
    val state: ConnectionState = ConnectionState.DISCONNECTED,
    val support: CastSupport = CastSupport.Unknown
)

/** The picker's order: devices that can be cast to first, in the order found; the ones that cannot sink to the bottom. */
fun List<CastDevice>.unsupportedLast(): List<CastDevice> = sortedBy { it.support is CastSupport.Unsupported }
