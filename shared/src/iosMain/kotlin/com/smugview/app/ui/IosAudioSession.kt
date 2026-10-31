package com.smugview.app.ui

import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryPlayback
import platform.AVFAudio.setActive

/** Playback audio session: video sound plays with the ring/silent switch on. Call once at startup. */
@OptIn(ExperimentalForeignApi::class)
fun activateIosPlaybackAudioSession() {
    val session = AVAudioSession.sharedInstance()
    session.setCategory(AVAudioSessionCategoryPlayback, error = null)
    session.setActive(true, error = null)
}
