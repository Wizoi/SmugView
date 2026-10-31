package com.smugview.app.data.notify

import com.smugview.app.data.db.nowMillis
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import platform.BackgroundTasks.BGAppRefreshTaskRequest
import platform.BackgroundTasks.BGTask
import platform.BackgroundTasks.BGTaskScheduler
import platform.Foundation.NSDate
import platform.Foundation.dateWithTimeIntervalSinceNow
import kotlin.coroutines.cancellation.CancellationException

/**
 * New-gallery checks on iOS: a BGAppRefreshTask (iOS picks the real time, so this is best effort) plus a check on open.
 * [runner] is filled by IosApp once the repository exists; the task handler reads it when the task fires.
 */
@OptIn(ExperimentalForeignApi::class)
object IosBackgroundTasks {
    const val IDENTIFIER = "com.smugview.app.newgalleries"

    /** Set by IosApp.kt after it builds the runner. */
    var runner: NewGalleryRunner? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var lastRunMillis: Long? = null

    /** Call before the app finishes launching (Swift App.init). The handler asks [runnerProvider] when the task fires. */
    fun register(runnerProvider: () -> NewGalleryRunner?) {
        BGTaskScheduler.sharedScheduler.registerForTaskWithIdentifier(IDENTIFIER, null) { task ->
            if (task != null) handle(task, runnerProvider)
        }
    }

    private fun handle(task: BGTask, runnerProvider: () -> NewGalleryRunner?) {
        scheduleNext() // plan the next one first, so a crash or expiry does not end the chain
        val r = runnerProvider()
        if (r == null) {
            task.setTaskCompletedWithSuccess(false)
            return
        }
        val job = scope.launch {
            val ok = try {
                r.runAll()
                lastRunMillis = nowMillis()
                true
            } catch (e: CancellationException) {
                false
            } catch (e: Exception) {
                false
            }
            task.setTaskCompletedWithSuccess(ok)
        }
        task.expirationHandler = { job.cancel() }
    }

    /** Asks for a refresh about six hours from now. Replaces a pending request with the same identifier. */
    fun scheduleNext() {
        val request = BGAppRefreshTaskRequest(IDENTIFIER)
        val now = nowMillis()
        val delaySeconds = (NextRunPolicy.earliestBeginMillis(now) - now) / 1000.0
        request.earliestBeginDate = NSDate.dateWithTimeIntervalSinceNow(delaySeconds)
        BGTaskScheduler.sharedScheduler.submitTaskRequest(request, null)
    }

    /** Cancels the pending request when nobody is followed any more. */
    fun cancel() {
        BGTaskScheduler.sharedScheduler.cancelTaskRequestWithIdentifier(IDENTIFIER)
    }

    /** Runs the check now if it is due (app opened). Fire and forget; the Hub reads the result from the database. */
    fun runOnOpen() {
        val r = runner ?: return
        if (!NextRunPolicy.dueOnOpen(nowMillis(), lastRunMillis)) return
        scope.launch {
            try {
                r.runAll()
                lastRunMillis = nowMillis()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
            }
        }
        scheduleNext()
    }
}
