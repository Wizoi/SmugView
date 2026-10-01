package com.smugview.app.ui.viewmodel

import com.smugview.app.diag.Diag
import com.smugview.app.diag.DiagContext
import com.smugview.app.util.SmugLog
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Everything that is bound to ONE active site runs in [scope] (design 3.1): the site sync, folder and
 * gallery loads, the header image, the hub, search, the tag scan, subtree indexing, image details.
 * Closing the session at the moment the owner taps another site (or disconnects) cancels all of it, so
 * the old site's late answers cannot land on the new site's screen.
 *
 * It is a child of the ViewModel's job, so clearing the ViewModel still cancels it. Work that outlives
 * a site (casting, collections and downloads, history prefs) stays on `viewModelScope`.
 *
 * [rootNodeId] is the site root's NodeID, filled in when the profile resolves.
 */
class SiteSession(val nickname: String, parent: Job?) {
    @Volatile var rootNodeId: String? = null

    /**
     * N1 (6-6): the net under every job of this site. A job that throws without catching is logged and counted
     * (`uncaught_site_errors` in `report.txt`) and never reaches the thread's uncaught handler, which closes the
     * app. Each screen has its own failure state; this only catches what none of them did.
     */
    private val handler = CoroutineExceptionHandler { _, e ->
        Diag.uncaughtSiteErrors.incrementAndGet()
        SmugLog.e("site", "uncaught error in a site job: ${e.javaClass.simpleName}", e)
    }

    val scope: CoroutineScope = CoroutineScope(
        SupervisorJob(parent) + Dispatchers.Main.immediate + DiagContext.element(DiagContext.newActionId("site")) + handler
    )

    fun close() = scope.cancel()
}
