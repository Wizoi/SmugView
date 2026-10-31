package com.smugview.app.data.cast

/**
 * The plain-value form of a device that crosses to Swift (no :shared types are visible there). State codes: 0 disconnected,
 * 1 connecting, 2 connected, 3 failed; anything else reads as disconnected so a new SDK state can never crash the picker.
 */
fun connectionStateOf(code: Int): ConnectionState = when (code) {
    1 -> ConnectionState.CONNECTING
    2 -> ConnectionState.CONNECTED
    3 -> ConnectionState.FAILED
    else -> ConnectionState.DISCONNECTED
}

/** A Google Cast device from the primitives Swift reports. A blank name falls back to the id so the picker never shows an empty row. */
fun googleCastDeviceOf(id: String, name: String, ip: String, stateCode: Int): CastDevice =
    CastDevice(
        id = id,
        name = name.ifBlank { id },
        ipAddress = ip,
        type = CastType.GOOGLE,
        state = connectionStateOf(stateCode)
    )
