import SwiftUI

@main
struct iOSApp: App {
    // Casting (external display scene): see ExternalSceneDelegate.swift
    @UIApplicationDelegateAdaptor(ExternalDisplayAppDelegate.self) var externalDisplayAppDelegate

    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}
