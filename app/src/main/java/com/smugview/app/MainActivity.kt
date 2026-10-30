package com.smugview.app

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.navArgument
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.widget.Toast
import com.smugview.app.ui.navigation.Routes
import com.smugview.app.ui.browser.BrowserScreen
import com.smugview.app.ui.detail.PhotoDetailScreen
import com.smugview.app.ui.detail.SearchPhotoDetailScreen
import com.smugview.app.ui.grid.PhotoGridScreen
import com.smugview.app.ui.explorer.KeywordImagesScreen
import com.smugview.app.ui.detail.KeywordPhotoDetailScreen
import com.smugview.app.ui.theme.SmugViewTheme
import coil3.SingletonImageLoader
import dagger.hilt.android.AndroidEntryPoint
import androidx.hilt.navigation.compose.hiltViewModel

import javax.inject.Inject
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var appDatabase: com.smugview.app.data.db.AppDatabase

    @Inject
    lateinit var newGalleryFollow: com.smugview.app.data.notify.NewGalleryFollow

    // A tap on a new-gallery notification: which site, and the gallery when there was exactly one. Cleared once handled.
    private var pendingOpen by mutableStateOf<PendingOpen?>(null)

    private fun readPendingOpen(intent: android.content.Intent?): PendingOpen? {
        val nickname = intent?.getStringExtra(com.smugview.app.data.notify.NewGalleryNotifier.EXTRA_NICKNAME) ?: return null
        return PendingOpen(
            nickname,
            intent.getStringExtra(com.smugview.app.data.notify.NewGalleryNotifier.EXTRA_ALBUM_KEY),
            intent.getStringExtra(com.smugview.app.data.notify.NewGalleryNotifier.EXTRA_ALBUM_TITLE)
        )
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingOpen = readPendingOpen(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE)
        val currentVersion = try {
            packageManager.getPackageInfo(packageName, 0).versionCode
        } catch (e: Exception) { 1 }

        prefs.edit().putInt("last_app_version", currentVersion).apply()
        pendingOpen = readPendingOpen(intent)

        val photoFileOps = com.smugview.app.ui.AndroidPhotoFileOps(applicationContext)
        val dominantColorSampler = com.smugview.app.ui.PaletteDominantColorSampler(applicationContext)
        setContent {
            androidx.compose.runtime.CompositionLocalProvider(
                com.smugview.app.ui.LocalDominantColorSampler provides dominantColorSampler,
                com.smugview.app.ui.LocalPhotoFileOps provides photoFileOps
            ) {
            SmugViewTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    SmugViewNavigation(
                        viewModel = hiltViewModel<com.smugview.app.ui.viewmodel.AppSmugViewModel>(),
                        newGalleryFollow = newGalleryFollow,
                        pendingOpen = pendingOpen,
                        onOpenHandled = {
                            pendingOpen = null
                            intent.removeExtra(com.smugview.app.data.notify.NewGalleryNotifier.EXTRA_NICKNAME)
                        }
                    )
                }
            }
            }
        }
    }
}
