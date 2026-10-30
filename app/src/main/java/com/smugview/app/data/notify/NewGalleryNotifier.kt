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
