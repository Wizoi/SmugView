package com.smugview.app.data.notify

import com.smugview.app.data.db.NotifyDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlin.coroutines.cancellation.CancellationException

/** The Hub's bell for iOS: same rules as Android's NewGalleryFollow, with rescheduling behind [FollowRescheduler]. */
class SharedSiteFollow(
    private val dao: NotifyDao,
    private val check: NewGalleryCheck,
    private val unlock: suspend (nickname: String) -> Unit,
    private val rescheduler: FollowRescheduler
) : SiteFollow {
    override fun isFollowing(nickname: String): Flow<Boolean> = dao.followingCount(nickname).map { it > 0 }

    override suspend fun follow(nickname: String) {
        dao.follow(nickname)
        // Baseline after unlocking the saved password folders, or the first run would announce every Family gallery.
        try { unlock(nickname) } catch (e: CancellationException) { throw e } catch (e: Exception) { }
        check.run(nickname)
        rescheduler.reschedule()
    }

    override suspend fun unfollow(nickname: String) {
        dao.unfollow(nickname)
        rescheduler.reschedule()
    }
}
