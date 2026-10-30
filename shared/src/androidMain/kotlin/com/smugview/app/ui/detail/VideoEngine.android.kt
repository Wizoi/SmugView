package com.smugview.app.ui.detail

import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView

internal class ExoVideoEngine(val player: ExoPlayer) : VideoEngine {
    override val isPlaying: Boolean get() = player.isPlaying
    override val currentPosition: Long get() = player.currentPosition
    override val duration: Long get() = player.duration
    override fun play() = player.play()
    override fun pause() = player.pause()
    override fun seekTo(positionMs: Long) = player.seekTo(positionMs)
}

@OptIn(UnstableApi::class)
@Composable
actual fun rememberVideoEngine(videoUrl: String): VideoEngine {
    val context = LocalContext.current
    val exoPlayer = remember(videoUrl) {
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("SmugView-Android-App/1.0")
            .setAllowCrossProtocolRedirects(true)
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(httpDataSourceFactory))
            .build().apply {
                setMediaItem(MediaItem.fromUri(videoUrl))
                prepare()
                playWhenReady = true
                repeatMode = Player.REPEAT_MODE_ONE
            }
    }
    DisposableEffect(exoPlayer) {
        onDispose { exoPlayer.release() }
    }
    return remember(exoPlayer) { ExoVideoEngine(exoPlayer) }
}

@OptIn(UnstableApi::class)
@Composable
actual fun VideoSurface(engine: VideoEngine, modifier: Modifier) {
    val exo = (engine as ExoVideoEngine).player
    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                player = exo
                useController = false // We render our own beautiful Compose controls!
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            }
        },
        modifier = modifier
    )
}
