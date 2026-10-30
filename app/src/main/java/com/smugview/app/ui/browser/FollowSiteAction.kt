package com.smugview.app.ui.browser

import com.smugview.app.ui.icons.AppIcons
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.smugview.app.data.notify.FollowTap
import com.smugview.app.data.notify.NewGalleryFollow
import com.smugview.app.data.notify.NewGalleryText
import com.smugview.app.ui.theme.NeonBlue
import kotlinx.coroutines.launch

/** The Hub top bar bell: follow this site for new-gallery notifications, asking for the notification permission first when Android needs it. */
@Composable
internal fun FollowSiteAction(follow: NewGalleryFollow, nickname: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val following by remember(nickname) { follow.isFollowing(nickname) }.collectAsState(initial = false)

    fun startFollowing() {
        scope.launch { follow.follow(nickname) }
        Toast.makeText(context, NewGalleryText.followed(nickname), Toast.LENGTH_LONG).show()
    }

    val askPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startFollowing() else Toast.makeText(context, NewGalleryText.PERMISSION_DENIED, Toast.LENGTH_LONG).show()
    }

    IconButton(onClick = {
        val canNotify = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        when (FollowTap.of(following, canNotify)) {
            FollowTap.Unfollow -> {
                scope.launch { follow.unfollow(nickname) }
                Toast.makeText(context, NewGalleryText.unfollowed(nickname), Toast.LENGTH_SHORT).show()
            }
            FollowTap.AskPermission -> askPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            FollowTap.Follow -> startFollowing()
        }
    }) {
        Icon(
            imageVector = if (following) AppIcons.NotificationsActive else AppIcons.NotificationsNone,
            contentDescription = if (following) NewGalleryText.UNFOLLOW_DESCRIPTION else NewGalleryText.FOLLOW_DESCRIPTION,
            tint = NeonBlue
        )
    }
}
