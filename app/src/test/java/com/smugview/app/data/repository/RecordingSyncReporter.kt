package com.smugview.app.data.repository

import com.smugview.app.diag.StopReason
import com.smugview.app.diag.SyncKind
import com.smugview.app.diag.SyncReporter
import com.smugview.app.diag.SyncRun
import com.smugview.app.diag.UnlockAttempt
import java.util.concurrent.CopyOnWriteArrayList

/** Keeps every run (and unlock attempt) the repository reports, for assertions. */
class RecordingSyncReporter : SyncReporter {
    val runs = CopyOnWriteArrayList<SyncRun>()
    val unlockAttempts = CopyOnWriteArrayList<UnlockAttempt>()
    @Volatile private var open = 0

    override fun begin(kind: SyncKind, nickname: String, actionId: String): SyncRun =
        SyncRun(actionId, kind, nickname, System.currentTimeMillis()).also { runs.add(it); open++ }

    override fun finish(run: SyncRun) {
        if (run.stop == null) run.stop = StopReason.Completed
        run.endedAt = System.currentTimeMillis()
        open--
    }

    override fun recordUnlock(a: UnlockAttempt) { unlockAttempts.add(a) }
    override fun recent(n: Int) = runs.toList().takeLast(n)
    override fun hasOpenRun() = open > 0

    fun runsOf(kind: SyncKind) = runs.filter { it.kind == kind }
}
