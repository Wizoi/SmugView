package com.smugview.app.data.notify

import com.smugview.app.data.db.NotifyDao
import com.smugview.app.data.db.NotifyKnownAlbum
import kotlin.coroutines.cancellation.CancellationException

/** One gallery of the newest-first listing. [nodeId] is the NodeID, never the AlbumKey. */
data class ListedGallery(val nodeId: String, val name: String, val webUri: String?, val urlPath: String?)

sealed interface CheckResult {
    /** The first listing of a followed site: recorded, nothing announced. */
    data class Baselined(val count: Int) : CheckResult
    data object NothingNew : CheckResult
    /** Galleries not seen before, newest first. They count as told only after [NewGalleryCheck.markNotified]. */
    data class Found(val galleries: List<ListedGallery>) : CheckResult
    data object NotFollowed : CheckResult
    /** The listing could not be had (offline, 429, 5xx, a locked folder); nothing was changed. */
    data object Skipped : CheckResult
}

/**
 * The one question this feature asks: is there a NodeID in the site's newest galleries that the owner has not been told about?
 * [newest] returns the first page of `user!albums` ordered by date added, newest first, or throws when it cannot.
 */
class NewGalleryCheck(
    private val dao: NotifyDao,
    private val newest: suspend (nickname: String) -> List<ListedGallery>
) {
    suspend fun run(nickname: String): CheckResult {
        val site = dao.site(nickname)?.takeIf { it.enabled } ?: return CheckResult.NotFollowed
        val listed = try {
            newest(nickname)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return CheckResult.Skipped
        }
        if (!site.baselineDone) {
            if (listed.isEmpty()) return CheckResult.Skipped
            dao.recordBaseline(nickname, listed.map { it.nodeId })
            return CheckResult.Baselined(listed.size)
        }
        val known = dao.knownIds(nickname).toHashSet()
        val fresh = listed.filter { it.nodeId !in known }
        return if (fresh.isEmpty()) CheckResult.NothingNew else CheckResult.Found(fresh)
    }

    /** Call after the notification was posted: the worst case of a crash in between is a repeated notification, never a lost one. */
    suspend fun markNotified(nickname: String, galleries: List<ListedGallery>) {
        dao.addKnown(galleries.map { NotifyKnownAlbum(nickname, it.nodeId) })
    }
}
