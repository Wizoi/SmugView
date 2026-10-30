import SwiftUI
import Shared

// Hosts the real app: the shared screens and navigation graph (Kotlin: MainViewController.kt, which calls IosApp.kt in :shared).
struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.SmugViewAppViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

struct ContentView: View {
    var body: some View {
        ComposeView().ignoresSafeArea(.all)
    }
}
