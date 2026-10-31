import SwiftUI
import UIKit
import Shared

// Stage 3 casting: AirPlay / cable external display. Written blind (no Mac): see docs/design/ios-casting.md section 4.
// If this scene never arrives, plain screen mirroring still shows the phone UI; nothing here is needed for that.

/// Adapts the SwiftUI app so UIKit can offer a scene configuration for the external display role.
final class ExternalDisplayAppDelegate: NSObject, UIApplicationDelegate {
    func application(_ application: UIApplication,
                     configurationForConnecting connectingSceneSession: UISceneSession,
                     options: UIScene.ConnectionOptions) -> UISceneConfiguration {
        if connectingSceneSession.role == .windowExternalDisplayNonInteractive {
            let config = UISceneConfiguration(name: "External Display", sessionRole: connectingSceneSession.role)
            config.delegateClass = ExternalSceneDelegate.self
            return config
        }
        // The phone's own scene: leave it to SwiftUI's WindowGroup.
        return UISceneConfiguration(name: nil, sessionRole: connectingSceneSession.role)
    }
}

final class ExternalSceneDelegate: UIResponder, UIWindowSceneDelegate {
    var window: UIWindow?

    func scene(_ scene: UIScene, willConnectTo session: UISceneSession, options connectionOptions: UIScene.ConnectionOptions) {
        guard let windowScene = scene as? UIWindowScene else { return }
        let window = UIWindow(windowScene: windowScene)
        window.rootViewController = MainViewControllerKt.ExternalViewerViewController()
        window.isHidden = false
        self.window = window
    }

    func sceneDidDisconnect(_ scene: UIScene) {
        window = nil
    }
}
