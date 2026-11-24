package com.smugview.app.ui.theme

import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

@Composable
internal actual fun DarkStatusBarEffect() {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            // No window.statusBarColor: deprecated, and ignored from Android 15. MainActivity.enableDarkEdgeToEdge() leaves the bars
            // transparent over the DeepDarkBackground every screen paints; only the icon contrast is set here.
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = false
            controller.isAppearanceLightNavigationBars = false
        }
    }
}
