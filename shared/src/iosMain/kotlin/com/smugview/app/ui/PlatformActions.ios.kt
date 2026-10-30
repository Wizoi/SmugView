package com.smugview.app.ui

import androidx.compose.runtime.Composable

// Stage 3 replaces these with the share sheet (UIActivityViewController), the notification authorisation request and the Photos saver.
private object NoShare : PlatformShare {
    override fun shareText(text: String, chooserTitle: String) {}
    override fun shareJpeg(path: String, text: String?, subject: String, chooserTitle: String) {}
}

@Composable
actual fun rememberPlatformShare(): PlatformShare = NoShare

@Composable
actual fun rememberPermissionRequest(permission: AppPermission, onResult: (Boolean) -> Unit): PermissionRequest =
    object : PermissionRequest {
        override val granted: Boolean get() = true
        override fun launch() = onResult(true)
    }
