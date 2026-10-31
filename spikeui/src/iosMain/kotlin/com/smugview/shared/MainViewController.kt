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

/** Swift calls this once at launch (iOSApp.init) with its Google Cast wrapper, before the first screen builds the cast manager. */
fun installCastBridge(bridge: SwiftCastBridge) {
    com.smugview.app.data.cast.CastBridgeHost.bridge = SwiftCastBridgeAdapter(bridge)
}
