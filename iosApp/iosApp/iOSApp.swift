import SwiftUI

@main
struct iOSApp: App {
    init() {
        CastSetup.initialize()
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}
