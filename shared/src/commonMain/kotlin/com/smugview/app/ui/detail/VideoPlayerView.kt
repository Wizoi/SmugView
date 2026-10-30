package com.smugview.app.ui.detail

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.smugview.app.ui.icons.AppIcons
import com.smugview.app.ui.theme.NeonBlue
import kotlinx.coroutines.delay

/** What the video controls need from a platform player (ExoPlayer on Android, AVPlayer on iOS). Times are in milliseconds. */
interface VideoEngine {
    val isPlaying: Boolean
    val currentPosition: Long
    val duration: Long
    fun play()
    fun pause()
    fun seekTo(positionMs: Long)
}

/** A player for [videoUrl] that starts playing and repeats, released when it leaves the composition. */
@Composable
expect fun rememberVideoEngine(videoUrl: String): VideoEngine

/** The picture of [engine]'s video, filling its box. The controls are drawn by [VideoPlayerView]. */
@Composable
expect fun VideoSurface(engine: VideoEngine, modifier: Modifier)

@Composable
fun VideoPlayerView(
    videoUrl: String,
    isActive: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {}
) {
    var isPlaying by remember { mutableStateOf(true) }
    var currentPosition by remember { mutableStateOf(0L) }
    var duration by remember { mutableStateOf(0L) }
    var showControls by remember { mutableStateOf(true) }

    // Auto-hide controls after 3 seconds if playing
    LaunchedEffect(showControls, isPlaying) {
        if (showControls && isPlaying) {
            delay(3000)
            showControls = false
        }
    }

    val player = rememberVideoEngine(videoUrl)

    // Keep position/duration updated
    LaunchedEffect(player, isPlaying) {
        while (isPlaying) {
            currentPosition = player.currentPosition
            duration = player.duration.coerceAtLeast(0L)
            delay(500)
        }
    }

    // Sync play status when active page changes
    LaunchedEffect(isActive) {
        if (isActive) {
            player.play()
            isPlaying = true
        } else {
            player.pause()
            isPlaying = false
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                showControls = !showControls
                onClick()
            },
        contentAlignment = Alignment.Center
    ) {
        // The Video Player Surface
        VideoSurface(player, Modifier.fillMaxSize())

        // Custom Overlay Controls
        AnimatedVisibility(
            visible = showControls,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.4f)),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(32.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(32.dp))
                        .padding(horizontal = 24.dp, vertical = 12.dp)
                ) {
                    // Rewind 15s
                    IconButton(
                        onClick = {
                            val newPos = (player.currentPosition - 15000).coerceAtLeast(0L)
                            player.seekTo(newPos)
                            currentPosition = newPos
                        }
                    ) {
                        Icon(
                            imageVector = AppIcons.FastRewind,
                            contentDescription = "-15s",
                            tint = Color.White,
                            modifier = Modifier.size(36.dp)
                        )
                    }

                    // Play/Pause
                    IconButton(
                        onClick = {
                            if (player.isPlaying) {
                                player.pause()
                                isPlaying = false
                            } else {
                                player.play()
                                isPlaying = true
                            }
                        }
                    ) {
                        Icon(
                            imageVector = if (isPlaying) AppIcons.Pause else AppIcons.PlayArrow,
                            contentDescription = if (isPlaying) "Pause" else "Play",
                            tint = Color.White,
                            modifier = Modifier.size(48.dp)
                        )
                    }

                    // Forward 15s
                    IconButton(
                        onClick = {
                            val newPos = (player.currentPosition + 15000).coerceAtMost(player.duration)
                            player.seekTo(newPos)
                            currentPosition = newPos
                        }
                    ) {
                        Icon(
                            imageVector = AppIcons.FastForward,
                            contentDescription = "+15s",
                            tint = Color.White,
                            modifier = Modifier.size(36.dp)
                        )
                    }
                }

                // Progress Bar at the bottom of the video controls overlay
                if (duration > 0) {
                    val progress = currentPosition.toFloat() / duration.toFloat()
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 16.dp, start = 24.dp, end = 24.dp),
                        color = NeonBlue,
                        trackColor = Color.White.copy(alpha = 0.2f)
                    )
                }
            }
        }
    }
}
