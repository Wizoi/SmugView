package com.smugview.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

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

@Composable
fun SmugViewTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    overridePrimary: Color? = null, // Allows dynamic primary color injection (from Palette API)
    content: @Composable () -> Unit
) {
    val colorScheme = when {
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
    DarkStatusBarEffect()
    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}

/** Every screen paints its own dark background (DeepDarkBackground), in the light system theme too, so the status bar is always dark with light icons. */
@Composable
internal expect fun DarkStatusBarEffect()
