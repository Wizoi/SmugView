package com.smugview.app.data.offline

import kotlin.test.Test
import kotlin.test.assertEquals

class NetworkKindMappingTest {
    @Test fun noInternetIsNoneWhateverElse() {
        assertEquals(NetworkKind.NONE, networkKindOfPath(satisfied = false, expensive = false, usesWifi = true))
        assertEquals(NetworkKind.NONE, networkKindOfPath(satisfied = false, expensive = true, usesWifi = false))
    }

    @Test fun plainWifiIsUnmetered() = assertEquals(NetworkKind.UNMETERED, networkKindOfPath(true, expensive = false, usesWifi = true))

    @Test fun wiredIsUnmetered() = assertEquals(NetworkKind.UNMETERED, networkKindOfPath(true, expensive = false, usesWifi = false))

    @Test fun hotspotIsMeteredWifi() = assertEquals(NetworkKind.METERED_WIFI, networkKindOfPath(true, expensive = true, usesWifi = true))

    @Test fun cellularIsMeteredOther() = assertEquals(NetworkKind.METERED_OTHER, networkKindOfPath(true, expensive = true, usesWifi = false))
}
