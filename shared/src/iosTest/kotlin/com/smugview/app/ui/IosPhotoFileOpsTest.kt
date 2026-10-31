@file:OptIn(kotlinx.cinterop.BetaInteropApi::class, kotlinx.cinterop.ExperimentalForeignApi::class)

package com.smugview.app.ui

import com.smugview.app.data.db.nowMillis
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSData
import platform.Foundation.NSDate
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileModificationDate
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import platform.Foundation.create
import platform.Foundation.dateWithTimeIntervalSinceNow
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.writeToFile
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * The share cache of [IosPhotoFileOps]: `NSTemporaryDirectory()/shared_images`, swept after 24 h. No network: the client is a
 * MockEngine that fails the test if it is ever asked, and the picture comes from a saved JPEG on disk.
 */
class IosPhotoFileOpsTest {
    private val fm = NSFileManager.defaultManager
    private val shareDir = NSTemporaryDirectory() + PhotoFileLogic.SHARE_DIR
    private val scratch = NSTemporaryDirectory() + "iosphotofileops-" + NSUUID().UUIDString
    private val created = mutableListOf<String>()

    private val noNetwork = HttpClient(MockEngine { respondError(HttpStatusCode.InternalServerError) })

    /** SOI, one APP0 segment, SOS: the smallest thing JpegStrip accepts. */
    private val jpeg = byteArrayOf(
        0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0x00, 0x04, 0x01, 0x02,
        0xFF.toByte(), 0xDA.toByte(), 0x00, 0x02, 0x11, 0x22, 0xFF.toByte(), 0xD9.toByte()
    )

    @AfterTest
    fun cleanUp() {
        created.forEach { fm.removeItemAtPath(it, error = null) }
        fm.removeItemAtPath(scratch, error = null)
    }

    private fun writeBytes(path: String, bytes: ByteArray) {
        val data = usePinnedData(bytes)
        assertTrue(data.writeToFile(path, atomically = true), "could not write $path")
    }

    private fun usePinnedData(bytes: ByteArray): NSData =
        bytes.usePinned { NSData.create(bytes = it.addressOf(0), length = bytes.size.toULong()) }

    private fun setModified(path: String, secondsAgo: Double) {
        val ok = fm.setAttributes(
            mapOf(NSFileModificationDate to NSDate.dateWithTimeIntervalSinceNow(-secondsAgo)), ofItemAtPath = path, error = null
        )
        assertTrue(ok, "could not set the modification date of $path")
    }

    private fun exists(path: String) = fm.fileExistsAtPath(path)

    @Test
    fun sharingCreatesTheDirAndWritesAStrippedJpeg() = runTest(timeout = 30.seconds) {
        fm.createDirectoryAtPath(scratch, withIntermediateDirectories = true, attributes = null, error = null)
        val saved = "$scratch/saved.jpg"
        writeBytes(saved, jpeg)
        val key = "key-" + NSUUID().UUIDString
        val path = IosPhotoFileOps(noNetwork).prepareShareFile(key, saved, thumbnailUrl = null)
        created += path
        assertTrue(path.startsWith(shareDir), path)
        assertEquals(PhotoFileLogic.shareFileName(key), path.substringAfterLast('/'))
        assertTrue(exists(path))
        val written = NSData.dataWithContentsOfFile(path)
        assertTrue(written != null && written.length > 0uL)
    }

    @Test
    fun theSweepRemovesAFileOlderThanADayAndKeepsAFreshOne() = runTest(timeout = 30.seconds) {
        fm.createDirectoryAtPath(shareDir, withIntermediateDirectories = true, attributes = null, error = null)
        val tag = NSUUID().UUIDString
        val old = "$shareDir/old_$tag.jpg"
        val fresh = "$shareDir/fresh_$tag.jpg"
        val nearlyADay = "$shareDir/almost_$tag.jpg"
        created += listOf(old, fresh, nearlyADay)
        writeBytes(old, jpeg); writeBytes(fresh, jpeg); writeBytes(nearlyADay, jpeg)
        setModified(old, secondsAgo = 25.0 * 3600)
        setModified(nearlyADay, secondsAgo = 23.0 * 3600)

        fm.createDirectoryAtPath(scratch, withIntermediateDirectories = true, attributes = null, error = null)
        val saved = "$scratch/saved.jpg"
        writeBytes(saved, jpeg)
        created += IosPhotoFileOps(noNetwork).prepareShareFile("sweep-$tag", saved, null)

        assertFalse(exists(old), "a file last written 25 h ago is swept")
        assertTrue(exists(fresh))
        assertTrue(exists(nearlyADay), "23 h is still inside the day")
        assertTrue(PhotoFileLogic.isStale(nowMillis() - 25L * 3600 * 1000, nowMillis()))
    }

    @Test
    fun withNoSavedCopyAndNoAddressNothingIsFetched() = runTest(timeout = 30.seconds) {
        // NotAPictureException from the common logic; the mock engine would answer 500 if the code reached for the network
        assertFailsWith<Exception> { IosPhotoFileOps(noNetwork).prepareShareFile("k", null, null) }
    }
}
