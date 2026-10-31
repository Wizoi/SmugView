package com.smugview.app.ui.detail

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitView
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.readValue
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryPlayback
import platform.AVFoundation.AVLayerVideoGravityResizeAspect
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.AVPlayerItemDidPlayToEndTimeNotification
import platform.AVFoundation.AVPlayerLayer
import platform.AVFoundation.currentItem
import platform.AVFoundation.currentTime
import platform.AVFoundation.duration
import platform.AVFoundation.pause
import platform.AVFoundation.play
import platform.AVFoundation.rate
import platform.AVFoundation.replaceCurrentItemWithPlayerItem
import platform.AVFoundation.seekToTime
import platform.CoreGraphics.CGRectZero
import platform.CoreMedia.CMTimeGetSeconds
import platform.CoreMedia.CMTimeMakeWithSeconds
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSURL
import platform.QuartzCore.CATransaction
import platform.UIKit.UIColor
import platform.UIKit.UIView

@OptIn(ExperimentalForeignApi::class)
internal class AvVideoEngine(val player: AVPlayer) : VideoEngine {
    override val isPlaying: Boolean get() = player.rate != 0f
    override val currentPosition: Long get() = secondsToMillis(CMTimeGetSeconds(player.currentTime()))
    override val duration: Long
        get() {
            val item = player.currentItem ?: return 0L
            return secondsToMillis(CMTimeGetSeconds(item.duration))
        }
    override fun play() = player.play()
    override fun pause() = player.pause()
    override fun seekTo(positionMs: Long) {
        player.seekToTime(CMTimeMakeWithSeconds(positionMs / 1000.0, 600))
    }
}

/**
 * Playback category, so sound plays with the silent switch on. Call it once at app start (for example from the iOS entry point before the
 * first screen); it is also called here, harmlessly, each time a player is made.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun activatePlaybackAudioSession() {
    val session = AVAudioSession.sharedInstance()
    session.setCategory(AVAudioSessionCategoryPlayback, error = null)
    session.setActive(true, error = null)
}

@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun rememberVideoEngine(videoUrl: String): VideoEngine {
    val engine = remember(videoUrl) {
        activatePlaybackAudioSession()
        val url = NSURL.URLWithString(videoUrl)
        val item = if (url != null) AVPlayerItem(uRL = url) else null
        val player = AVPlayer()
        if (item != null) player.replaceCurrentItemWithPlayerItem(item)
        AvVideoEngine(player)
    }
    DisposableEffect(engine) {
        val player = engine.player
        val item = player.currentItem
        // Repeat: when the item reaches its end, go back to the start and keep playing.
        val token = NSNotificationCenter.defaultCenter.addObserverForName(
            name = AVPlayerItemDidPlayToEndTimeNotification,
            `object` = item,
            queue = NSOperationQueue.mainQueue,
            usingBlock = { _ ->
                player.seekToTime(CMTimeMakeWithSeconds(0.0, 600))
                player.play()
            }
        )
        player.play()
        onDispose {
            NSNotificationCenter.defaultCenter.removeObserver(token)
            player.pause()
            player.replaceCurrentItemWithPlayerItem(null)
        }
    }
    return engine
}

/** A plain UIView that keeps its AVPlayerLayer the size of the view (layerClass is awkward to override from Kotlin/Native). */
@OptIn(ExperimentalForeignApi::class)
private class PlayerHostView(val playerLayer: AVPlayerLayer) : UIView(frame = CGRectZero.readValue()) {
    init {
        backgroundColor = UIColor.blackColor
        layer.addSublayer(playerLayer)
    }

    override fun layoutSubviews() {
        super.layoutSubviews()
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        playerLayer.frame = bounds
        CATransaction.commit()
    }
}

@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun VideoSurface(engine: VideoEngine, modifier: Modifier) {
    val player = (engine as AvVideoEngine).player
    UIKitView(
        factory = {
            val layer = AVPlayerLayer.playerLayerWithPlayer(player)
            layer.videoGravity = AVLayerVideoGravityResizeAspect
            PlayerHostView(layer)
        },
        modifier = modifier,
        update = { view -> view.setNeedsLayout() }
    )
}
