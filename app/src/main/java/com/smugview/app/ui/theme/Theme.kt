package com.smugview.app.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkColorScheme = darkColorScheme(
    primary = Purple80,
    secondary = PurpleGrey80,
    tertiary = Pink80,
    background = DeepDarkBackground,
    surface = SurfaceDark,
    onBackground = Color.White,
    onSurface = Color.White
)

private val LightColorScheme = lightColorScheme(
    primary = Purple40,
    secondary = PurpleGrey40,
    tertiary = Pink40,
    background = Color(0xFFFBFBFE),
    surface = Color.White,
    onBackground = Color(0xFF1C1B1F),
    onSurface = Color(0xFF1C1B1F)
)

/** How many screens that are dark by design (the photo viewers) are showing; the system bars follow them, whatever the system theme. */
private val LocalDarkScreens = compositionLocalOf<MutableIntState?> { null }

/** Put in a screen that is black by design: the status bar goes black with light icons while it is shown. */
@Composable
fun DarkSystemBars() {
    val screens = LocalDarkScreens.current ?: return
    DisposableEffect(screens) {
        screens.intValue++
        onDispose { screens.intValue-- }
    }
}

@Composable
fun SmugViewTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false, // Set to false to prioritize our custom premium color scheme
    overridePrimary: Color? = null, // Allows dynamic primary color injection (from Palette API)
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> {
            if (overridePrimary != null) {
                DarkColorScheme.copy(primary = overridePrimary)
            } else {
                DarkColorScheme
            }
        }
        else -> {
            if (overridePrimary != null) {
                LightColorScheme.copy(primary = overridePrimary)
            } else {
                LightColorScheme
            }
        }
    }
    val darkScreens = remember { mutableIntStateOf(0) }
    val darkBars = darkScreens.intValue > 0
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = if (darkBars) Color.Black.toArgb() else colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme && !darkBars
        }
    }

    CompositionLocalProvider(LocalDarkScreens provides darkScreens) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content
        )
    }
}
