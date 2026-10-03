package com.smugview.app.data.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.smugview.app.data.notify.NewGalleryRunner
import com.smugview.app.diag.DiagLog
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException

/** Glue between WorkManager and [NewGalleryRunner]. Always succeeds: a failed site is simply asked again at the next run. */
@HiltWorker
class NewGalleryWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val runner: NewGalleryRunner,
    private val log: DiagLog
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        try {
            val results = runner.runAll()
            // Counts and outcome names only: no gallery name, address, nickname or password.
            log.i("newgallery", "run sites=${results.size} " + results.values.groupingBy { it.javaClass.simpleName }.eachCount())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.e("newgallery", "run failed: ${e.javaClass.simpleName}")
        }
        return Result.success()
    }
}
