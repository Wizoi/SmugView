package com.smugview.app.data.worker

import android.content.Context
import android.os.StatFs
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.smugview.app.data.db.CollectionPhoto
import com.smugview.app.data.repository.SmugMugRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

@HiltWorker
class OfflineDownloadWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted workerParams: WorkerParameters,
    private val repository: SmugMugRepository,
    private val okHttpClient: OkHttpClient
) : CoroutineWorker(context, workerParams) {

    class PermanentDownloadException(message: String) : Exception(message)

    override suspend fun doWork(): Result {
        // 1. Check free storage capacity (Require at least 50MB)
        if (!hasSufficientStorage()) {
            return Result.failure()
        }

        // 2. Fetch pending downloads from DB
        val pendingPhotos = repository.getPendingDownloads()
        if (pendingPhotos.isEmpty()) {
            return Result.success()
        }

        var hasFailure = false
        val isLastAttempt = runAttemptCount >= 3

        for (photo in pendingPhotos) {
            val downloadUrl = photo.archivedUri ?: photo.thumbnailUrl
            if (downloadUrl.isNullOrEmpty()) {
                continue
            }

            try {
                val file = downloadImageToFile(okHttpClient, downloadUrl, photo.imageKey)
                if (file != null) {
                    repository.updateDownloadStatus(
                        imageKey = photo.imageKey,
                        collectionId = photo.collectionId,
                        localPath = file.absolutePath,
                        isDownloaded = true
                    )
                } else {
                    if (isLastAttempt) {
                        repository.updateDownloadStatus(
                            imageKey = photo.imageKey,
                            collectionId = photo.collectionId,
                            localPath = "", // empty path indicates failure
                            isDownloaded = true
                        )
                    } else {
                        hasFailure = true
                    }
                }
            } catch (e: PermanentDownloadException) {
                // For permanent errors, mark as downloaded with empty path so it won't be retried
                repository.updateDownloadStatus(
                    imageKey = photo.imageKey,
                    collectionId = photo.collectionId,
                    localPath = "",
                    isDownloaded = true
                )
            } catch (e: Exception) {
                e.printStackTrace()
                if (isLastAttempt) {
                    // Mark as downloaded with empty path on last attempt to prevent infinite retries
                    repository.updateDownloadStatus(
                        imageKey = photo.imageKey,
                        collectionId = photo.collectionId,
                        localPath = "",
                        isDownloaded = true
                    )
                } else {
                    hasFailure = true
                }
            }
        }

        return if (hasFailure) Result.retry() else Result.success()
    }

    private fun hasSufficientStorage(): Boolean {
        return try {
            val stat = StatFs(context.filesDir.path)
            val bytesAvailable = stat.blockSizeLong * stat.availableBlocksLong
            val megabytesAvailable = bytesAvailable / (1024 * 1024)
            megabytesAvailable >= 50
        } catch (e: Exception) {
            false
        }
    }

    private fun downloadImageToFile(client: OkHttpClient, url: String, imageKey: String): File? {
        val request = Request.Builder().url(url).build()
        val response = client.newCall(request).execute()
        
        if (!response.isSuccessful) {
            val code = response.code
            response.close()
            if (code in 400..499) {
                throw PermanentDownloadException("Permanent HTTP error: $code")
            }
            return null
        }

        val body = response.body ?: return null
        val directory = File(context.filesDir, "offline_photos")
        if (!directory.exists()) {
            directory.mkdirs()
        }

        val file = File(directory, "${imageKey}.jpg")
        var inputStream: InputStream? = null
        var outputStream: FileOutputStream? = null

        return try {
            inputStream = body.byteStream()
            outputStream = FileOutputStream(file)
            val buffer = ByteArray(4096)
            var bytesRead: Int
            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                outputStream.write(buffer, 0, bytesRead)
            }
            outputStream.flush()
            file
        } catch (e: Exception) {
            if (file.exists()) {
                file.delete()
            }
            null
        } finally {
            inputStream?.close()
            outputStream?.close()
            response.close()
        }
    }
}
