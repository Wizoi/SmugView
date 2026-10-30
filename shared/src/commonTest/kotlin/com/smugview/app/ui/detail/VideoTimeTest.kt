package com.smugview.app.ui.detail

import kotlin.test.Test
import kotlin.test.assertEquals

class VideoTimeTest {
    @Test fun wholeAndFractionalSeconds() {
        assertEquals(12_500L, secondsToMillis(12.5))
        assertEquals(1_000L, secondsToMillis(1.0))
    }

    @Test fun indefiniteTimesGiveZero() {
        assertEquals(0L, secondsToMillis(Double.NaN))
        assertEquals(0L, secondsToMillis(Double.POSITIVE_INFINITY))
        assertEquals(0L, secondsToMillis(-3.0))
        assertEquals(0L, secondsToMillis(0.0))
    }
}
