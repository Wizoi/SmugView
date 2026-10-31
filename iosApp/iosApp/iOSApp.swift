import SwiftUI
import UserNotifications
import Shared

@main
struct iOSApp: App {
    // Casting (external display scene): see ExternalSceneDelegate.swift
    @UIApplicationDelegateAdaptor(ExternalDisplayAppDelegate.self) var externalDisplayAppDelegate

    init() {
        CastSetup.initialize()
        // Cast bridge (docs/design/ios-casting.md): must be installed before the first screen builds the cast manager. It starts no discovery.
        MainViewControllerKt.installCastBridge(bridge: GoogleCastBridge())
        // BGTaskScheduler handlers must be registered before launch finishes.
        MainViewControllerKt.registerBackgroundTasks()
        MainViewControllerKt.registerOfflineBackgroundTask()
        UNUserNotificationCenter.current().delegate = NotificationDelegate.shared
        MainViewControllerKt.activatePlaybackAudioSessionAtStartup()
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}
