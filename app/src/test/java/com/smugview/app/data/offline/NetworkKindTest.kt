package com.smugview.app.data.offline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class NetworkKindTest {
    @Test fun `no internet capability is none whatever else is reported`() {
        assertEquals(NetworkKind.NONE, NetworkKind.of(hasInternet = false, notMetered = true, isWifi = true))
    }

    @Test fun `an unmetered network is unmetered`() {
        assertEquals(NetworkKind.UNMETERED, NetworkKind.of(hasInternet = true, notMetered = true, isWifi = true))
    }

    @Test fun `a metered Wi-Fi is told apart from other metered networks`() {
        assertEquals(NetworkKind.METERED_WIFI, NetworkKind.of(hasInternet = true, notMetered = false, isWifi = true))
        assertEquals(NetworkKind.METERED_OTHER, NetworkKind.of(hasInternet = true, notMetered = false, isWifi = false))
    }

    @Test fun `the metered Wi-Fi text says it counts as mobile data and names the progress`() {
        val text = OfflineMessages.waitingMeteredWifi(3, 10)
        assertEquals("This Wi-Fi is marked as metered, so it counts as mobile data. 3 of 10 saved so far.", text)
        assertFalse(text, text.contains("Will save on Wi-Fi"))
    }
}
