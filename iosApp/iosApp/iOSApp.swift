import SwiftUI
import Shared

@main
struct iOSApp: App {
    init() {
        CastSetup.initialize()
        // BGTaskScheduler handlers must be registered before launch finishes.
        MainViewControllerKt.registerBackgroundTasks()
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}
