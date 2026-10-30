package com.smugview.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp

/** The window's shape, which decides the column counts and header heights. */
class ScreenInfo(val isLandscape: Boolean, val heightDp: Dp)

/** Landscape when the window is wider than it is tall (Android's `Configuration.orientation` says the same for a phone). */
@Composable
fun rememberScreenInfo(): ScreenInfo {
    val size = LocalWindowInfo.current.containerSize
    val density = LocalDensity.current
    return with(density) { ScreenInfo(isLandscape = size.width > size.height, heightDp = size.height.toDp()) }
}
