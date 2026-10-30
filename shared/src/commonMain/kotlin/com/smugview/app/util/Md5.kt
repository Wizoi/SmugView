package com.smugview.app.util

/** An incremental MD5 (the CDN's ETag of an original is its MD5; the download is verified against it). */
expect class Md5() {
    fun update(bytes: ByteArray, offset: Int, length: Int)
    /** The digest as lowercase hex; the object is finished after this. */
    fun hex(): String
}
