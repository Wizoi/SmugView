import UserNotifications
import Shared

/// Opens the gallery a new-gallery notification is about. The announcer writes `notify.nickname`, and for a single gallery
/// `notify.albumKey` and `notify.albumTitle`, into the notification's userInfo (the same keys as Android's intent extras).
final class NotificationDelegate: NSObject, UNUserNotificationCenterDelegate {
    static let shared = NotificationDelegate()

    func userNotificationCenter(_ center: UNUserNotificationCenter,
                                didReceive response: UNNotificationResponse,
                                withCompletionHandler completionHandler: @escaping () -> Void) {
        let info = response.notification.request.content.userInfo
        if let nickname = info["notify.nickname"] as? String {
            MainViewControllerKt.openFromNotification(
                nickname: nickname,
                albumKey: info["notify.albumKey"] as? String,
                albumTitle: info["notify.albumTitle"] as? String)
        }
        completionHandler()
    }

    /// Show the banner while the app is open too: the notification is about a new gallery, which the open screen does not show by itself,
    /// and tapping it jumps straight there. No sound, no badge.
    func userNotificationCenter(_ center: UNUserNotificationCenter,
                                willPresent notification: UNNotification,
                                withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) {
        completionHandler([.banner, .list])
    }
}
