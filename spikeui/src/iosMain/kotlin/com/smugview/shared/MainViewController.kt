package com.smugview.shared

import androidx.compose.ui.window.ComposeUIViewController
import com.smugview.app.IosAppViewController
import com.smugview.spikeui.FolderListApp
import platform.UIKit.UIViewController

/** What the iOS app shows: the shared Compose screen inside a UIViewController (`MainViewControllerKt.MainViewController()` in Swift). */
fun MainViewController(): UIViewController = ComposeUIViewController { FolderListApp() }

/** The real app. The Swift host calls this, so the framework needs no `export(project(":shared"))` (which put all of :shared's API into the header and slowed the link). */
fun SmugViewAppViewController(): UIViewController = IosAppViewController()

/** Swift App.init calls this so the BGAppRefreshTask handler is registered before launch finishes. IosApp.kt fills `IosBackgroundTasks.runner`. */
fun registerBackgroundTasks() {
    com.smugview.app.data.notify.IosBackgroundTasks.register { com.smugview.app.data.notify.IosBackgroundTasks.runner }
}

// --- Stage 3 additions (offline background, notification tap, audio session). Swift calls these; each is additive. ---

/** Registers the offline `BGProcessingTask`. Call from the Swift App init, before launch finishes. */
fun registerOfflineBackgroundTask() = com.smugview.app.data.offline.IosOfflineBackground.register()

/** A tapped new-gallery notification (userInfo keys `notify.nickname`, `notify.albumKey`, `notify.albumTitle`). */
fun openFromNotification(nickname: String, albumKey: String?, albumTitle: String?) =
    com.smugview.app.data.notify.IosPendingOpen.set(nickname, albumKey, albumTitle)

/** Video sound plays with the silent switch on. Call once at startup. */
fun activatePlaybackAudioSessionAtStartup() = com.smugview.app.ui.activateIosPlaybackAudioSession()
