package com.smugview.app.ui

import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import platform.Photos.PHAccessLevelAddOnly
import platform.Photos.PHAuthorizationStatusAuthorized
import platform.Photos.PHAuthorizationStatusLimited
import platform.Photos.PHAuthorizationStatusNotDetermined
import platform.Photos.PHPhotoLibrary
import platform.UserNotifications.UNAuthorizationOptionAlert
import platform.UserNotifications.UNAuthorizationOptionBadge
import platform.UserNotifications.UNAuthorizationOptionSound
import platform.UserNotifications.UNAuthorizationStatusAuthorized
import platform.UserNotifications.UNAuthorizationStatusProvisional
import platform.UserNotifications.UNUserNotificationCenter

/** iOS answers permission questions asynchronously; these wrap its callbacks as suspend functions. */
internal object IosPermissions {
    /** Add-only access to the photo library is held now (Limited counts: add-only never needs more). */
    fun photosAddGranted(): Boolean {
        val s = PHPhotoLibrary.authorizationStatusForAccessLevel(PHAccessLevelAddOnly)
        return s == PHAuthorizationStatusAuthorized || s == PHAuthorizationStatusLimited
    }

    /** Held now, or the user says yes when asked. Asks only when the user has not been asked yet. */
    suspend fun requestPhotosAdd(): Boolean {
        if (photosAddGranted()) return true
        if (PHPhotoLibrary.authorizationStatusForAccessLevel(PHAccessLevelAddOnly) != PHAuthorizationStatusNotDetermined) return false
        val status = suspendCoroutine { c ->
            PHPhotoLibrary.requestAuthorizationForAccessLevel(PHAccessLevelAddOnly) { st -> c.resume(st) }
        }
        return status == PHAuthorizationStatusAuthorized || status == PHAuthorizationStatusLimited
    }

    suspend fun notificationsGranted(): Boolean = suspendCoroutine { c ->
        UNUserNotificationCenter.currentNotificationCenter().getNotificationSettingsWithCompletionHandler { settings ->
            val st = settings?.authorizationStatus
            c.resume(st == UNAuthorizationStatusAuthorized || st == UNAuthorizationStatusProvisional)
        }
    }

    /** Shows the system prompt the first time; afterwards iOS answers without asking, so the result is the current state. */
    suspend fun requestNotifications(): Boolean = suspendCoroutine { c ->
        UNUserNotificationCenter.currentNotificationCenter().requestAuthorizationWithOptions(
            UNAuthorizationOptionAlert or UNAuthorizationOptionSound or UNAuthorizationOptionBadge
        ) { granted, _ -> c.resume(granted) }
    }
}
