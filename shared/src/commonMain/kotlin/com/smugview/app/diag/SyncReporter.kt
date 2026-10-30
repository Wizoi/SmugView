package com.smugview.app.diag

/** Records gallery syncs and unlock runs. All methods are non-suspending and never throw. */
interface SyncReporter {
    fun begin(kind: SyncKind, nickname: String, actionId: String): SyncRun?
    fun finish(run: SyncRun)
    fun recordUnlock(a: UnlockAttempt)
    fun recent(n: Int): List<SyncRun>

    /** True while any run has begun and not finished (the doctor flags `ranDuringSync`). */
    fun hasOpenRun(): Boolean

    companion object {
        val NOOP: SyncReporter = object : SyncReporter {
            override fun begin(kind: SyncKind, nickname: String, actionId: String): SyncRun? = null
            override fun finish(run: SyncRun) {}
            override fun recordUnlock(a: UnlockAttempt) {}
            override fun recent(n: Int): List<SyncRun> = emptyList()
            override fun hasOpenRun() = false
        }
    }
}
