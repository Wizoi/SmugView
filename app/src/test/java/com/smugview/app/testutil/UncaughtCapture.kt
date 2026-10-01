package com.smugview.app.testutil

import java.util.concurrent.CopyOnWriteArrayList

/**
 * Phase 6-0: captures exceptions that reach `Thread.setDefaultUncaughtExceptionHandler` while a test runs
 * (what the app's crash handler would see), and restores the previous handler on [close]. Lets a test
 * assert that nothing escaped a background coroutine (N1) instead of the process dying.
 *
 *     UncaughtCapture().use { cap -> ...; assertTrue(cap.captured.isEmpty()) }
 */
class UncaughtCapture : AutoCloseable {
    val captured = CopyOnWriteArrayList<Throwable>()
    private val previous = Thread.getDefaultUncaughtExceptionHandler()

    init {
        Thread.setDefaultUncaughtExceptionHandler { _, e -> captured += e }
    }

    override fun close() {
        Thread.setDefaultUncaughtExceptionHandler(previous)
    }
}
