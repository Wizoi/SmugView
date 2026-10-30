package com.smugview.app.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb

/** Reads the dominant colour of the picture at an address. Android uses Palette; the iOS host supplies its own. */
fun interface DominantColorSampler {
    /** The colour as ARGB, or null when the picture could not be loaded ([default] is what to use when it has no dominant colour). */
    suspend fun sample(imageUrl: String, default: Int): Int?
}

/** Provided by the host at the root. Without one the photo viewers keep their default background. */
val LocalDominantColorSampler = staticCompositionLocalOf<DominantColorSampler?> { null }

/** Palette-derived, animated radial-gradient background color for the current photo. */
@Composable
fun rememberDominantBackgroundColor(
    imageUrl: String?,
    defaultColor: Color = Color.Black,
    targetAlpha: Float = 0.85f,
    durationMs: Int = 500
): Color {
    val sampler = LocalDominantColorSampler.current
    var dominantColor by remember { mutableStateOf(defaultColor) }
    LaunchedEffect(imageUrl, sampler) {
        if (imageUrl != null && sampler != null) {
            try {
                sampler.sample(imageUrl, defaultColor.toArgb())?.let { dominantColor = Color(it) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // keep the current colour
            }
        }
    }
    val animated by animateColorAsState(
        targetValue = dominantColor.copy(alpha = targetAlpha),
        animationSpec = tween(durationMillis = durationMs),
        label = "bgColor"
    )
    return animated
}
