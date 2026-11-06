import UIKit

/// CI only: SIMCTL_CHILD_SMUGVIEW_DEMO_ORIENTATION=landscape|portrait asks the window scene to rotate a few seconds after start
/// (SMUGVIEW_DEMO_ORIENTATION_DELAY, default 12 s), the programmatic twin of turning the device. The simulator cannot be turned from the
/// command line, so ios-checks.yml uses this to see the app lay out in landscape. Unset in every normal launch: nothing happens.
enum DemoRotation {
    static func startIfRequested() {
        let env = ProcessInfo.processInfo.environment
        guard let value = env["SMUGVIEW_DEMO_ORIENTATION"], !value.isEmpty else { return }
        let delay = Double(env["SMUGVIEW_DEMO_ORIENTATION_DELAY"] ?? "") ?? 12
        DispatchQueue.main.asyncAfter(deadline: .now() + delay) {
            guard let scene = UIApplication.shared.connectedScenes.compactMap({ $0 as? UIWindowScene }).first else {
                print("smugview-check rotate no-window-scene")
                fflush(stdout)
                return
            }
            let mask: UIInterfaceOrientationMask = value == "landscape" ? .landscapeRight : .portrait
            scene.requestGeometryUpdate(.iOS(interfaceOrientations: mask)) { error in
                print("smugview-check rotate error \(error)")
                fflush(stdout)
            }
            print("smugview-check rotate requested \(value)")
            fflush(stdout)
            // `simctl io screenshot` always saves the unrotated portrait frame, so the picture's size says nothing: ask the scene instead.
            DispatchQueue.main.asyncAfter(deadline: .now() + 3) {
                let size = scene.coordinateSpace.bounds.size
                print("smugview-check rotate scene landscape=\(scene.interfaceOrientation.isLandscape) size=\(Int(size.width))x\(Int(size.height))")
                fflush(stdout)
            }
        }
    }
}
