package com.smugview.app.ui

import android.Manifest
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File

private class AndroidPlatformShare(private val context: Context) : PlatformShare {
    override fun shareText(text: String, chooserTitle: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        context.startActivity(Intent.createChooser(intent, chooserTitle))
    }

    override fun shareJpeg(path: String, text: String?, subject: String, chooserTitle: String) {
        val fileUri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", File(path))
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, fileUri)
            if (text != null) putExtra(Intent.EXTRA_TEXT, text)
            putExtra(Intent.EXTRA_SUBJECT, subject)
            clipData = ClipData.newRawUri("", fileUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(intent, chooserTitle).apply { addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        context.startActivity(chooser)
    }
}

@Composable
actual fun rememberPlatformShare(): PlatformShare {
    val context = LocalContext.current
    return remember(context) { AndroidPlatformShare(context) }
}

/** The manifest permission, or null when this Android version needs none. */
private fun manifestPermission(permission: AppPermission): String? = when (permission) {
    AppPermission.Notifications -> if (Build.VERSION.SDK_INT >= 33) Manifest.permission.POST_NOTIFICATIONS else null
    AppPermission.SavePhotos -> if (Build.VERSION.SDK_INT < 29) Manifest.permission.WRITE_EXTERNAL_STORAGE else null
}

@Composable
actual fun rememberPermissionRequest(permission: AppPermission, onResult: (Boolean) -> Unit): PermissionRequest {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission(), onResult)
    val name = manifestPermission(permission)
    return object : PermissionRequest {
        override val granted: Boolean
            get() = name == null || ContextCompat.checkSelfPermission(context, name) == PackageManager.PERMISSION_GRANTED

        override fun launch() {
            if (name == null) onResult(true) else launcher.launch(name)
        }
    }
}
