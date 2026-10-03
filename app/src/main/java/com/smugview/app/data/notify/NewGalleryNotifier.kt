package com.smugview.app.data.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.smugview.app.MainActivity
import com.smugview.app.R

/** What the notification says. Specific on purpose: which site and which galleries, never a generic "something changed". */
object NewGalleryText {
    private const val NAMES_SHOWN = 3

    fun followed(site: String) = "Following $site. You will get a notification when it has a new gallery."
    fun unfollowed(site: String) = "No longer following $site."
    const val PERMISSION_DENIED =
        "Notifications are turned off for SmugView, so there is nothing to notify you with. Turn them on in Android settings, then try again."
    const val FOLLOW_DESCRIPTION = "Notify me of new galleries"
    const val UNFOLLOW_DESCRIPTION = "Stop new gallery notifications"

    fun title(site: String, count: Int): String =
        if (count == 1) "New gallery on $site" else "$count new galleries on $site"

    fun body(names: List<String>): String {
        val shown = names.take(NAMES_SHOWN).joinToString(", ")
        val more = names.size - NAMES_SHOWN
        return if (more > 0) "$shown and $more more" else shown
    }
}

/** Posts one notification per site per run. Returns false, posting nothing, when the app may not post notifications. */
class NewGalleryNotifier(private val context: Context) : GalleryAnnouncer {
    override suspend fun announce(nickname: String, galleries: List<ListedGallery>): Boolean {
        val manager = NotificationManagerCompat.from(context)
        if (galleries.isEmpty() || !manager.areNotificationsEnabled()) return false
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "New galleries", NotificationManager.IMPORTANCE_DEFAULT)
        )
        val open = Intent(context, MainActivity::class.java)
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(EXTRA_NICKNAME, nickname)
            .apply { galleries.singleOrNull()?.albumKey?.let { putExtra(EXTRA_ALBUM_KEY, it); putExtra(EXTRA_ALBUM_TITLE, galleries.single().name) } }
        val tap = PendingIntent.getActivity(
            context, nickname.hashCode(), open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_new_gallery)
            .setContentTitle(NewGalleryText.title(nickname, galleries.size))
            .setContentText(NewGalleryText.body(galleries.map { it.name }))
            .setContentIntent(tap)
            .setAutoCancel(true)
            .build()
        // One notification per site, replaced by the next run's: a second run before it is read does not stack.
        manager.notify(nickname.hashCode(), notification)
        return true
    }

    companion object {
        const val CHANNEL_ID = "new-galleries"
        const val EXTRA_NICKNAME = "notify.nickname"
        const val EXTRA_ALBUM_KEY = "notify.albumKey"
        const val EXTRA_ALBUM_TITLE = "notify.albumTitle"
    }
}
