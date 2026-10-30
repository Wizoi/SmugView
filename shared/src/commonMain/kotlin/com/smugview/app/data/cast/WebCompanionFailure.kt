package com.smugview.app.data.cast

/** Step 6-11c: why the screen link has no URL. They are different problems with different fixes, so the UI says which. */
enum class WebCompanionFailure {
    /** No port from 8080 to 8089 could be bound. */
    PortsInUse,

    /** The server could bind, but this phone has no Wi-Fi address to type on the screen. */
    NoWifi
}
