package com.smugview.app.data.notify

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * BGTaskScheduler in a test process: the request may be refused (no registered handler, no Info.plist identifier). The
 * scheduling helpers must not let an exception escape. Only "no exception" is asserted; the outcome goes to the log.
 */
class IosBackgroundTasksTest {
    @Test
    fun cancelDoesNotThrow() {
        val outcome = runCatching { IosBackgroundTasks.cancel() }
        println("IosBackgroundTasksTest.cancel: ${if (outcome.isSuccess) "ok" else "threw ${outcome.exceptionOrNull()}"}")
        assertTrue(outcome.isSuccess, "cancel threw: ${outcome.exceptionOrNull()}")
    }

    @Test
    fun scheduleNextDoesNotThrow() {
        val outcome = runCatching { IosBackgroundTasks.scheduleNext() }
        println("IosBackgroundTasksTest.scheduleNext: ${if (outcome.isSuccess) "ok" else "threw ${outcome.exceptionOrNull()}"}")
        assertTrue(outcome.isSuccess, "scheduleNext threw: ${outcome.exceptionOrNull()}")
        runCatching { IosBackgroundTasks.cancel() }
    }

    @Test
    fun runOnOpenWithoutARunnerDoesNothing() {
        val saved = IosBackgroundTasks.runner
        IosBackgroundTasks.runner = null
        try {
            IosBackgroundTasks.runOnOpen()
        } finally {
            IosBackgroundTasks.runner = saved
        }
    }
}
