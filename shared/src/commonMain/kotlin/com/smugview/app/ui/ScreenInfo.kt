package com.smugview.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** The window's shape, which decides the column counts and header heights. */
class ScreenInfo(val isLandscape: Boolean, val heightDp: Dp, val widthDp: Dp = 0.dp) {
    companion object {
        /** Columns of a staggered grid (gallery tiles, folders): the phone counts 2 portrait / 4 landscape, more on a wide window (an iPad). */
        fun staggeredColumns(widthDp: Float, isLandscape: Boolean): Int = maxOf(if (isLandscape) 4 else 2, (widthDp / 190f).toInt())

        /** Columns of a square-thumbnail grid: the phone counts 3 portrait / 6 landscape, more on a wide window. */
        fun squareColumns(widthDp: Float, isLandscape: Boolean): Int = maxOf(if (isLandscape) 6 else 3, (widthDp / 125f).toInt())
    }

    val staggeredColumns: Int get() = staggeredColumns(widthDp.value, isLandscape)
    val squareColumns: Int get() = squareColumns(widthDp.value, isLandscape)
}

/** Landscape when the window is wider than it is tall (Android's `Configuration.orientation` says the same for a phone). */
@Composable
fun rememberScreenInfo(): ScreenInfo {
    val size = LocalWindowInfo.current.containerSize
    val density = LocalDensity.current
    return with(density) { ScreenInfo(isLandscape = size.width > size.height, heightDp = size.height.toDp(), widthDp = size.width.toDp()) }
}
