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

data class CastDevice(
    val id: String,
    val name: String,
    val ipAddress: String,
    val type: CastType,
    val state: ConnectionState = ConnectionState.DISCONNECTED
)
