package com.smugview.app.data.notify

import kotlinx.coroutines.flow.Flow

/** What the Hub's bell needs: follow a site for new-gallery notifications, or stop. */
interface SiteFollow {
    fun isFollowing(nickname: String): Flow<Boolean>
    suspend fun follow(nickname: String)
    suspend fun unfollow(nickname: String)
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
