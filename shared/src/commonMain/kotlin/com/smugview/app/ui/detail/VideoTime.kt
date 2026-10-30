package com.smugview.app.ui.detail

/** Milliseconds for a time in seconds as AVFoundation reports it. NaN, infinite (indefinite CMTime) and negative values give 0. */
fun secondsToMillis(seconds: Double): Long {
    if (seconds.isNaN() || seconds.isInfinite() || seconds <= 0.0) return 0L
    return (seconds * 1000.0).toLong()
}
