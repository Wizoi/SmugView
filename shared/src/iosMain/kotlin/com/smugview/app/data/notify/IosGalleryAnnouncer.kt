package com.smugview.app.data.notify

import kotlinx.coroutines.suspendCancellableCoroutine
import platform.UserNotifications.UNAuthorizationStatusAuthorized
import platform.UserNotifications.UNAuthorizationStatusProvisional
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNNotificationSound
import platform.UserNotifications.UNUserNotificationCenter
import kotlin.coroutines.resume

/** Posts one local notification per site per run. Returns false, posting nothing, when notifications are not authorised. */
class IosGalleryAnnouncer : GalleryAnnouncer {
    override suspend fun announce(nickname: String, galleries: List<ListedGallery>): Boolean {
        if (galleries.isEmpty()) return false
        val center = UNUserNotificationCenter.currentNotificationCenter()
        if (!isAuthorised(center)) return false
        val c = NewGalleryContent.of(nickname, galleries)
        val content = UNMutableNotificationContent()
        content.setTitle(c.title)
        content.setBody(c.body)
        content.setSound(UNNotificationSound.defaultSound)
        content.setUserInfo(c.userInfo.entries.associate { (k, v) -> k to v })
        // Same identifier per site: a second run replaces the first. Null trigger = deliver now.
        val request = UNNotificationRequest.requestWithIdentifier(c.identifier, content, null)
        return suspendCancellableCoroutine { cont ->
            center.addNotificationRequest(request) { error -> if (cont.isActive) cont.resume(error == null) }
        }
    }

    private suspend fun isAuthorised(center: UNUserNotificationCenter): Boolean =
        suspendCancellableCoroutine { cont ->
            center.getNotificationSettingsWithCompletionHandler { settings ->
                val s = settings?.authorizationStatus
                if (cont.isActive) cont.resume(s == UNAuthorizationStatusAuthorized || s == UNAuthorizationStatusProvisional)
            }
        }
}
