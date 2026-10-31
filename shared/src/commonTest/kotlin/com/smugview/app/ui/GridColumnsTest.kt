package com.smugview.app.ui

import kotlin.test.Test
import kotlin.test.assertEquals

/** Real window widths in dp: iPhone 16 Pro 402x874, iPhone landscape 874, iPad Pro 13-inch 1032x1376, iPad 11-inch 834. */
class GridColumnsTest {
    @Test
    fun aPhoneKeepsItsOldColumnCounts() {
        assertEquals(2, ScreenInfo.staggeredColumns(widthDp = 402f, isLandscape = false))
        assertEquals(4, ScreenInfo.staggeredColumns(widthDp = 874f, isLandscape = true))
        assertEquals(3, ScreenInfo.squareColumns(widthDp = 402f, isLandscape = false))
        assertEquals(6, ScreenInfo.squareColumns(widthDp = 874f, isLandscape = true))
    }

    @Test
    fun aThirteenInchIpadGetsMoreThanThePhoneCount() {
        assertEquals(5, ScreenInfo.staggeredColumns(widthDp = 1032f, isLandscape = false))
        assertEquals(8, ScreenInfo.squareColumns(widthDp = 1032f, isLandscape = false))
    }

    @Test
    fun anElevenInchIpadSitsBetween() {
        assertEquals(4, ScreenInfo.staggeredColumns(widthDp = 834f, isLandscape = false))
        assertEquals(6, ScreenInfo.squareColumns(widthDp = 834f, isLandscape = false))
    }
}
