package com.smugview.app.ui

import androidx.compose.runtime.Composable

/** What the screens ask of the operating system's share sheet. */
interface PlatformShare {
    /** Opens the share sheet with plain text. */
    fun shareText(text: String, chooserTitle: String)

    /** Opens the share sheet with a JPEG on disk, [text] (a link, or null) and [subject]. */
    fun shareJpeg(path: String, text: String?, subject: String, chooserTitle: String)
}

@Composable
expect fun rememberPlatformShare(): PlatformShare

/** A permission the screens may have to ask for. */
enum class AppPermission {
    /** Posting the new-gallery notifications (Android 13+ asks; earlier versions are always allowed). */
    Notifications,

    /** Writing a downloaded photo to the shared Pictures folder (Android 8 and 9 only). */
    SavePhotos
}

interface PermissionRequest {
    /** True when the permission is held now, or the platform does not need it. */
    val granted: Boolean

    /** Asks the user; the result goes to the `onResult` given to [rememberPermissionRequest]. */
    fun launch()
}

/** [onResult] receives the user's answer after [PermissionRequest.launch]. */
@Composable
expect fun rememberPermissionRequest(permission: AppPermission, onResult: (Boolean) -> Unit): PermissionRequest
