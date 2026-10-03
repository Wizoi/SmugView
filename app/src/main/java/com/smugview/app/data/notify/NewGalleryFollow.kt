package com.smugview.app.data.notify

import com.smugview.app.data.db.NotifyDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlin.coroutines.cancellation.CancellationException

/** The Hub's switch: follow a site for new-gallery notifications, or stop. */
class NewGalleryFollow(
    private val dao: NotifyDao,
    private val check: NewGalleryCheck,
    private val unlock: suspend (nickname: String) -> Unit,
    private val scheduler: NewGalleryScheduler
) {
    fun isFollowing(nickname: String): Flow<Boolean> = dao.followingCount(nickname).map { it > 0 }

    /**
     * Follows the site and takes the baseline NOW, after unlocking the saved password folders: a baseline taken without
     * the session would leave every Family gallery out of "known", and the first background run would announce them all.
     * Offline now is fine: the first background run takes the baseline instead.
     */
    suspend fun follow(nickname: String) {
        dao.follow(nickname)
        try { unlock(nickname) } catch (e: CancellationException) { throw e } catch (e: Exception) { }
        check.run(nickname)
        scheduler.reschedule()
    }

    suspend fun unfollow(nickname: String) {
        dao.unfollow(nickname)
        scheduler.reschedule()
    }
}

/** What a tap on the bell does. Pure, so the permission rule is tested without a screen. */
enum class FollowTap {
    Unfollow, AskPermission, Follow;

    companion object {
        fun of(following: Boolean, canNotify: Boolean): FollowTap = when {
            following -> Unfollow
            !canNotify -> AskPermission
            else -> Follow
        }
    }
}
