import SwiftUI
import UserNotifications
import Shared

@main
struct iOSApp: App {
    init() {
        CastSetup.initialize()
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
