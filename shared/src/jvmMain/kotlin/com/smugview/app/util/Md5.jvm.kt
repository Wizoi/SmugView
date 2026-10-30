package com.smugview.app.util

import java.security.MessageDigest

actual class Md5 actual constructor() {
    private val digest = MessageDigest.getInstance("MD5")
    actual fun update(bytes: ByteArray, offset: Int, length: Int) = digest.update(bytes, offset, length)
    actual fun hex(): String = digest.digest().joinToString("") { "%02x".format(it) }
}
