package com.smugview.app.ui.detail

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

// Stage 3 replaces this with AVPlayer behind a UIKitView; until then a video shows its controls over a blank surface.
private object NoVideoEngine : VideoEngine {
    override val isPlaying: Boolean get() = false
    override val currentPosition: Long get() = 0L
    override val duration: Long get() = 0L
    override fun play() {}
    override fun pause() {}
    override fun seekTo(positionMs: Long) {}
}

@Composable
actual fun rememberVideoEngine(videoUrl: String): VideoEngine = NoVideoEngine

@Composable
actual fun VideoSurface(engine: VideoEngine, modifier: Modifier) {
}
