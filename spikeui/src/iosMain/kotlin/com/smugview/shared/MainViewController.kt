package com.smugview.shared

import androidx.compose.ui.window.ComposeUIViewController
import com.smugview.spikeui.FolderListApp
import platform.UIKit.UIViewController

/** What the iOS app shows: the shared Compose screen inside a UIViewController (`MainViewControllerKt.MainViewController()` in Swift). */
fun MainViewController(): UIViewController = ComposeUIViewController { FolderListApp() }
