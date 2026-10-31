package com.smugview.app.data.offline

/**
 * The iOS path facts to a [NetworkKind], the same answer Android's `networkKindFlow` gives. Pure so it is tested without a device.
 * [satisfied]: the path can reach the internet. [expensive]: the OS marks it as costing money (cellular, or a Wi-Fi hotspot).
 * [usesWifi]: the path runs over a Wi-Fi interface. A hotspot is `expensive` + Wi-Fi, so it is [NetworkKind.METERED_WIFI].
 */
fun networkKindOfPath(satisfied: Boolean, expensive: Boolean, usesWifi: Boolean): NetworkKind =
    NetworkKind.of(hasInternet = satisfied, notMetered = !expensive, isWifi = usesWifi)
