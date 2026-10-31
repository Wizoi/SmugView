package com.smugview.app.data.cast

import kotlin.test.Test
import kotlin.test.assertEquals

class CastDeviceMappingTest {
    @Test fun stateCodesMapAndUnknownCodesAreDisconnected() {
        assertEquals(ConnectionState.DISCONNECTED, connectionStateOf(0))
        assertEquals(ConnectionState.CONNECTING, connectionStateOf(1))
        assertEquals(ConnectionState.CONNECTED, connectionStateOf(2))
        assertEquals(ConnectionState.FAILED, connectionStateOf(3))
        assertEquals(ConnectionState.DISCONNECTED, connectionStateOf(99))
        assertEquals(ConnectionState.DISCONNECTED, connectionStateOf(-1))
    }

    @Test fun deviceIsGoogleAndKeepsTheUniqueIdAsTheId() {
        val d = googleCastDeviceOf("uuid-1234", "Living Room TV", "192.168.1.20", 2)
        assertEquals(CastType.GOOGLE, d.type)
        assertEquals("uuid-1234", d.id)
        assertEquals("Living Room TV", d.name)
        assertEquals("192.168.1.20", d.ipAddress)
        assertEquals(ConnectionState.CONNECTED, d.state)
    }

    @Test fun blankNameFallsBackToTheId() {
        assertEquals("uuid-1234", googleCastDeviceOf("uuid-1234", "  ", "", 0).name)
    }
}
