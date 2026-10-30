package com.smugview.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import kotlinx.coroutines.launch
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSURL
import platform.Foundation.setValue
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIApplication
import platform.UIKit.UISceneActivationStateForegroundActive
import platform.UIKit.UIViewController
import platform.UIKit.UIWindow
import platform.UIKit.UIWindowScene
import platform.UIKit.popoverPresentationController

/** The share sheet (UIActivityViewController) over the top-most view controller of the foreground window. */
private object IosShare : PlatformShare {
    override fun shareText(text: String, chooserTitle: String) = present(listOf(text), null)

    override fun shareJpeg(path: String, text: String?, subject: String, chooserTitle: String) {
        val items = mutableListOf<Any>(NSURL.fileURLWithPath(path))
        if (!text.isNullOrBlank()) items += text
        present(items, subject)
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun present(items: List<Any>, subject: String?) {
        val top = topViewController() ?: return
        val sheet = UIActivityViewController(activityItems = items, applicationActivities = null)
        if (!subject.isNullOrBlank()) sheet.setValue(subject, forKey = "subject")
        // On iPad the sheet is a popover and crashes without an anchor: the middle of the screen, no arrow.
        sheet.popoverPresentationController?.let { popover ->
            popover.sourceView = top.view
            val (cx, cy) = top.view.bounds.useContents { size.width / 2 to size.height / 2 }
            popover.sourceRect = CGRectMake(cx, cy, 0.0, 0.0)
        }
        top.presentViewController(sheet, animated = true, completion = null)
    }

    private fun topViewController(): UIViewController? {
        val scenes = UIApplication.sharedApplication.connectedScenes.filterIsInstance<UIWindowScene>()
        val scene = scenes.firstOrNull { it.activationState == UISceneActivationStateForegroundActive } ?: scenes.firstOrNull()
        val windows = scene?.windows?.filterIsInstance<UIWindow>().orEmpty()
        val window = windows.firstOrNull { it.isKeyWindow() } ?: windows.firstOrNull()
        var vc = window?.rootViewController
        while (true) vc = vc?.presentedViewController ?: break
        return vc
    }
}

@Composable
actual fun rememberPlatformShare(): PlatformShare = IosShare

/**
 * `granted` is a cached state: Photos add access is known at once, notifications are read from iOS (asynchronously) when the
 * screen is composed and again after each [PermissionRequest.launch].
 */
@Composable
actual fun rememberPermissionRequest(permission: AppPermission, onResult: (Boolean) -> Unit): PermissionRequest {
    val scope = rememberCoroutineScope()
    val latest = rememberUpdatedState(onResult)
    var held by remember(permission) {
        mutableStateOf(if (permission == AppPermission.SavePhotos) IosPermissions.photosAddGranted() else false)
    }
    LaunchedEffect(permission) {
        held = when (permission) {
            AppPermission.Notifications -> IosPermissions.notificationsGranted()
            AppPermission.SavePhotos -> IosPermissions.photosAddGranted()
        }
    }
    return remember(permission) {
        object : PermissionRequest {
            override val granted: Boolean get() = held
            override fun launch() {
                scope.launch {
                    val ok = when (permission) {
                        AppPermission.Notifications -> IosPermissions.requestNotifications()
                        AppPermission.SavePhotos -> IosPermissions.requestPhotosAdd()
                    }
                    held = ok
                    latest.value(ok)
                }
            }
        }
    }
}
