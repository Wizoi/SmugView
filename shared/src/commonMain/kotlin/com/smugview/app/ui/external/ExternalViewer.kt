package com.smugview.app.ui.external

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * What an external display (AirPlay, a cable) shows: the phone viewer's current photo. Process-wide on purpose: the phone viewer screens
 * set [show] / [clear]; the second Compose root on the external UIScene only reads [image]. Android never reads it.
 */
object ExternalDisplay {
    private val _image = MutableStateFlow<String?>(null)
    val image: StateFlow<String?> = _image

    /** The photo the phone viewer is on now. */
    fun show(uri: String?) { _image.value = uri }

    /** The viewer was left: the big screen goes black. */
    fun clear() { _image.value = null }
}

/** What to draw, given the phone viewer's photo and the Cast manager's photo (a Cast session wins: it is explicit). Blank means black. */
fun externalImageToShow(viewerImage: String?, castImage: String?): String? =
    (castImage?.takeIf { it.isNotBlank() } ?: viewerImage?.takeIf { it.isNotBlank() })

/** Full-screen black, the picture fitted, nothing else. Pure: give it the uri. */
@Composable
fun ExternalViewer(imageUri: String?) {
    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        Crossfade(targetState = imageUri, label = "externalImage") { uri ->
            if (uri != null) {
                AsyncImage(
                    model = uri,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

/** The root the external scene hosts: reads [ExternalDisplay]. */
@Composable
fun ExternalViewerRoot() {
    val image by ExternalDisplay.image.collectAsState()
    ExternalViewer(image)
}

/**
 * Called by a photo viewer with the picture it is showing: the second screen (AirPlay or a cable) follows it, and goes black when the viewer
 * is left. Nothing reads this on Android, so calling it there changes nothing.
 */
@Composable
fun PublishToExternalDisplay(uri: String?) {
    LaunchedEffect(uri) { ExternalDisplay.show(uri) }
    DisposableEffect(Unit) { onDispose { ExternalDisplay.clear() } }
}
