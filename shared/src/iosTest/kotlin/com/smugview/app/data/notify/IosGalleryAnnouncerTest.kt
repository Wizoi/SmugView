package com.smugview.app.data.notify

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.time.Duration.Companion.seconds

/**
 * Only the empty-list case can run here. A test process on the simulator has no app bundle, so touching `UNUserNotificationCenter`
 * raises `NSInternalInconsistencyException` ('bundleProxyForCurrentProcess is nil'), which Kotlin/Native cannot catch and which aborts the
 * whole test run (CI run 41154411925). The "not authorised, so nothing is posted" case therefore needs the real app, not this test
 * process: it is checked on the simulator or a device with the app running.
 */
class IosGalleryAnnouncerTest {
    @Test
    fun anEmptyListPostsNothing() = runTest(timeout = 30.seconds) {
        assertFalse(IosGalleryAnnouncer().announce("example", emptyList()))
    }
}
