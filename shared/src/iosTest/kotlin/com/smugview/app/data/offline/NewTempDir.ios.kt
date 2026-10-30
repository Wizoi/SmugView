@file:OptIn(kotlinx.cinterop.BetaInteropApi::class, kotlinx.cinterop.ExperimentalForeignApi::class)

package com.smugview.app.data.offline

import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID

actual fun newTempDir(): String {
    val path = NSTemporaryDirectory().trimEnd('/') + "/smugview-fileops-" + NSUUID().UUIDString
    NSFileManager.defaultManager.createDirectoryAtPath(path, true, null, null)
    return path
}
