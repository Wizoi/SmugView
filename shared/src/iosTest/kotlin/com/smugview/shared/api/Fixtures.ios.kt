package com.smugview.shared.api

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.stringWithContentsOfFile

/**
 * The simulator reads the host's files. The folder comes from the environment (CI sets `SIMCTL_CHILD_PARITY_DIR`, which `simctl spawn`
 * passes on as `PARITY_DIR`). Written without a Mac to compile it: the first macOS run is its test.
 */
@OptIn(ExperimentalForeignApi::class)
actual fun readFixture(name: String): String {
    val dir = NSProcessInfo.processInfo.environment["PARITY_DIR"] as? String ?: error("PARITY_DIR is not set")
    return NSString.stringWithContentsOfFile("$dir/$name", NSUTF8StringEncoding, null) ?: error("cannot read $dir/$name")
}
