import SwiftUI
import UserNotifications
import Shared

@main
struct iOSApp: App {
    // --- Stage 3 (offline background task, notification tap, audio session): additive block ---
    init() {
        MainViewControllerKt.registerOfflineBackgroundTask()
        UNUserNotificationCenter.current().delegate = NotificationDelegate.shared
        MainViewControllerKt.activatePlaybackAudioSessionAtStartup()
    }
    // --- end Stage 3 block ---

    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}
