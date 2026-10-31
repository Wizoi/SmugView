package com.smugview.app.data.offline

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import platform.BackgroundTasks.BGProcessingTask
import platform.BackgroundTasks.BGProcessingTaskRequest
import platform.BackgroundTasks.BGTaskScheduler

/**
 * Kept-gallery downloads in the background: a `BGProcessingTask` (minutes of runtime, network required) that runs one
 * `runBackgroundPass()` and asks for another when [PassResult.more]. iOS decides when it runs (typically overnight on power);
 * there is no guarantee, so the foreground scheduler stays the main path. Written without a Mac: the macOS CI job is the first compile.
 */
object IosOfflineBackground {
    const val IDENTIFIER = "com.smugview.app.offline"

    /** Set by the app root once the scheduler exists (`{ scheduling.runBackgroundPass() }`); a task that fires before that ends unsuccessfully. */
    var provider: (suspend () -> PassResult)? = null

    /** Whether any kept gallery wants downloading; set by the app root. Default true so a request is never lost before it is wired. */
    var wanted: () -> Boolean = { true }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Must run before the app finishes launching (the SwiftUI App init). */
    @OptIn(ExperimentalForeignApi::class)
    fun register() {
        BGTaskScheduler.sharedScheduler.registerForTaskWithIdentifier(IDENTIFIER, null) { task ->
            val processing = task as? BGProcessingTask
            if (processing == null) {
                task?.setTaskCompletedWithSuccess(false)
            } else {
                handle(processing)
            }
        }
    }

    /** Asks iOS for a processing slot. Safe to call repeatedly (a new request for the same identifier replaces the old one). */
    @OptIn(ExperimentalForeignApi::class)
    fun scheduleOfflineIfWanted() {
        if (!wanted()) return
        val request = BGProcessingTaskRequest(IDENTIFIER)
        request.requiresNetworkConnectivity = true
        request.requiresExternalPower = false
        // Returns false when background refresh is off or the identifier is not in Info.plist; nothing to do then.
        BGTaskScheduler.sharedScheduler.submitTaskRequest(request, null)
    }

    private fun handle(task: BGProcessingTask) {
        val run = provider
        if (run == null) {
            task.setTaskCompletedWithSuccess(false)
            return
        }
        var finished = false
        val job: Job = scope.launch {
            val result = try { run() } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
            if (!finished) {
                finished = true
                if (result != null && result.more) scheduleOfflineIfWanted()
                task.setTaskCompletedWithSuccess(result != null)
            }
        }
        task.expirationHandler = {
            job.cancel()
            // Ask again so the rest resumes next time; a cancelled pass is resumable (only DONE files are ever opened).
            scheduleOfflineIfWanted()
            if (!finished) {
                finished = true
                task.setTaskCompletedWithSuccess(false)
            }
        }
    }
}
