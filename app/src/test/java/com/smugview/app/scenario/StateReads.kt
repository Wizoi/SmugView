package com.smugview.app.scenario

import androidx.compose.runtime.snapshots.Snapshot

/**
 * Reads Compose state (a `mutableStateListOf`, such as `SmugViewModel.folderNavigationStack`) from a thread that is NOT the one
 * writing it, and gets a consistent copy.
 *
 * The view model writes its stack on its Main thread (`BrowserHost.render`: `clear()` then `addAll()`). Iterating a
 * `SnapshotStateList` while another thread commits a write throws `ConcurrentModificationException` (`StateListIterator.validateModification`),
 * which is how `BrowserNavigationScenarioTest` failed once in a full-suite run: the test thread's `awaitUntil { stackIds() == ... }`
 * iterated the list while Main was replacing it. The app itself reads the stack only on Main, so this is a test-side rule: a test
 * thread reads state through [readState], which pins a read-only snapshot for the duration of [block].
 */
internal fun <T> readState(block: () -> T): T {
    val snapshot = Snapshot.takeSnapshot()
    try {
        return snapshot.enter(block)
    } finally {
        snapshot.dispose()
    }
}
