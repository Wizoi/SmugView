package com.smugview.app.data.offline

/**
 * What the phone's default network is, as far as a kept gallery cares (addendum 4): [METERED_WIFI] is a Wi-Fi network marked
 * as metered (a phone hotspot), which counts as mobile data for "Wi-Fi only" and so needs its own words.
 */
enum class NetworkKind {
    NONE, UNMETERED, METERED_WIFI, METERED_OTHER;

    companion object {
        /** From the three `NetworkCapabilities` facts; pure so it is tested without a device. */
        fun of(hasInternet: Boolean, notMetered: Boolean, isWifi: Boolean): NetworkKind = when {
            !hasInternet -> NONE
            notMetered -> UNMETERED
            isWifi -> METERED_WIFI
            else -> METERED_OTHER
        }
    }
}
