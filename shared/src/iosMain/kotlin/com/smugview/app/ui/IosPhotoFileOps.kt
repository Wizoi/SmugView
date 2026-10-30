package com.smugview.app.ui

import com.smugview.app.data.db.nowMillis
import com.smugview.app.download.PermissionNeededException
import com.smugview.app.download.SaveFailedException
import com.smugview.app.util.ioDispatcher
import com.smugview.shared.api.platformEngine
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsBytes
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.withContext
import kotlinx.io.IOException
import platform.Foundation.NSData
import platform.Foundation.NSDate
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileModificationDate
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.create
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.timeIntervalSince1970
import platform.Foundation.writeToFile
import platform.Photos.PHAssetCreationRequest
import platform.Photos.PHAssetResourceCreationOptions
import platform.Photos.PHAssetResourceTypePhoto
import platform.Photos.PHPhotoLibrary
import platform.posix.memcpy

/**
 * iOS's [PhotoFileOps]: the share cache is `NSTemporaryDirectory()/shared_images` (swept after a day), saving goes through
 * Photos with add-only access. The decisions are in [PhotoFileLogic]; this class is the HTTP, disk and Photos plumbing.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class IosPhotoFileOps(private val client: HttpClient = HttpClient(platformEngine())) : PhotoFileOps {
    private val logic = PhotoFileLogic(fetch = ::fetch, readSaved = ::readFile)

    override suspend fun prepareShareFile(imageKey: String, savedPath: String?, thumbnailUrl: String?): String {
        val dir = NSTemporaryDirectory() + PhotoFileLogic.SHARE_DIR
        withContext(ioDispatcher) { makeDir(dir); sweep(dir) }
        val bytes = logic.shareBytes(savedPath, thumbnailUrl)
        val path = "$dir/${PhotoFileLogic.shareFileName(imageKey)}"
        withContext(ioDispatcher) {
            // atomically = true writes a temp file and renames it, like the Android copy
            if (!bytes.toNSData().writeToFile(path, atomically = true)) throw IOException("could not write the shared picture")
        }
        return path
    }

    override suspend fun saveToPhotoLibrary(
        imageKey: String,
        fileName: String?,
        savedPath: String?,
        sourceUrl: String?,
        smaller: List<String>
    ): Boolean {
        if (!IosPermissions.requestPhotosAdd()) throw PermissionNeededException()
        val source = logic.saveSource(savedPath, sourceUrl, smaller)
        val dir = NSTemporaryDirectory() + "save_tmp"
        val tmpPath = "$dir/save_${imageKey.filter { it.isLetterOrDigit() || it == '-' || it == '_' }}.${PhotoFileLogic.extensionOf(source.bytes)}"
        withContext(ioDispatcher) {
            makeDir(dir)
            if (!source.bytes.toNSData().writeToFile(tmpPath, atomically = true)) throw SaveFailedException("could not stage the picture")
        }
        val displayName = PhotoFileLogic.photoName(imageKey, fileName) + "." + PhotoFileLogic.extensionOf(source.bytes)
        try {
            addToLibrary(NSURL.fileURLWithPath(tmpPath), displayName)
        } finally {
            NSFileManager.defaultManager.removeItemAtPath(tmpPath, error = null)
        }
        return source.original
    }

    private suspend fun addToLibrary(file: NSURL, displayName: String) = suspendCoroutine<Unit> { c ->
        PHPhotoLibrary.sharedPhotoLibrary().performChanges(
            changeBlock = {
                val request = PHAssetCreationRequest.creationRequestForAsset()
                val options = PHAssetResourceCreationOptions()
                options.originalFilename = displayName
                request.addResourceWithType(PHAssetResourceTypePhoto, fileURL = file, options = options)
            },
            completionHandler = { ok, error ->
                if (ok) c.resume(Unit)
                else c.resumeWithException(SaveFailedException(error?.localizedDescription ?: "Photos refused the picture"))
            }
        )
    }

    /** A transport failure is an IOException (offline); an answer of any status is a [FetchedImage]. */
    private suspend fun fetch(url: String): FetchedImage = try {
        val response = client.get(url)
        val code = response.status.value
        FetchedImage(code, if (code in 200..299) response.bodyAsBytes() else null)
    } catch (e: CancellationException) {
        throw e
    } catch (e: IOException) {
        throw e
    } catch (e: Exception) {
        throw IOException(e.message ?: "network error", e)
    }

    private fun readFile(path: String): ByteArray? = NSData.dataWithContentsOfFile(path)?.toByteArray()

    private fun makeDir(path: String) {
        NSFileManager.defaultManager.createDirectoryAtPath(path, withIntermediateDirectories = true, attributes = null, error = null)
    }

    private fun sweep(dir: String) {
        val fm = NSFileManager.defaultManager
        val now = nowMillis()
        (fm.contentsOfDirectoryAtPath(dir, error = null) ?: return).forEach { name ->
            val path = "$dir/$name"
            val modified = (fm.attributesOfItemAtPath(path, error = null)?.get(NSFileModificationDate) as? NSDate)
                ?.timeIntervalSince1970?.times(1000)?.toLong() ?: return@forEach
            if (PhotoFileLogic.isStale(modified, now)) fm.removeItemAtPath(path, error = null)
        }
    }
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private fun ByteArray.toNSData(): NSData =
    if (isEmpty()) NSData() else usePinned { NSData.create(bytes = it.addressOf(0), length = size.toULong()) }

@OptIn(ExperimentalForeignApi::class)
private fun NSData.toByteArray(): ByteArray {
    val n = length.toInt()
    val out = ByteArray(n)
    if (n > 0) out.usePinned { memcpy(it.addressOf(0), bytes, length) }
    return out
}
