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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
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
import com.smugview.app.ui.browser.BrowserScreen
import com.smugview.app.ui.detail.PhotoDetailScreen
import com.smugview.app.ui.detail.SearchPhotoDetailScreen
import com.smugview.app.ui.grid.PhotoGridScreen
import com.smugview.app.ui.explorer.KeywordImagesScreen
import com.smugview.app.ui.detail.KeywordPhotoDetailScreen
import com.smugview.app.ui.theme.SmugViewTheme
import coil.imageLoader
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE)
        val currentVersion = try {
            packageManager.getPackageInfo(packageName, 0).versionCode
        } catch (e: Exception) { 1 }

        prefs.edit().putInt("last_app_version", currentVersion).apply()

        setContent {
            SmugViewTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    SmugViewNavigation()
                }
            }
        }
    }
}

@Composable
fun SmugViewNavigation() {
    val navController = rememberNavController()
    val viewModel: com.smugview.app.ui.viewmodel.SmugViewModel = hiltViewModel()
    val coroutineScope = rememberCoroutineScope()

    Box(modifier = Modifier.fillMaxSize()) {
        NavHost(navController = navController, startDestination = "browser") {
            composable("browser") {
                BrowserScreen(
                    viewModel = viewModel,
                    onNavigateToAlbum = { albumKey, albumTitle ->
                        viewModel.cancelSearchJob()
                        navController.navigate("photo_grid/$albumKey/${Uri.encode(albumTitle)}") { launchSingleTop = true }
                    },
                    onNavigateToExplorer = {},
                    onNavigateToSearchPhotoDetail = { imageKey, index ->
                        navController.navigate("search_photo_detail/$imageKey?index=$index")
                    },
                    onNavigateToPhotoDetail = { albumKey, imageKey ->
                        navController.navigate("photo_detail/$albumKey/$imageKey")
                    },
                    onNavigateToKeywordImages = {
                        navController.navigate("keyword_images")
                    },
                    onNavigateToCastController = { title ->
                        navController.navigate(com.smugview.app.ui.navigation.Routes.castController(title))
                    }
                )
            }
            composable("keyword_images") {
                KeywordImagesScreen(
                    viewModel = viewModel,
                    onBackClick = { navController.navigateUp() },
                    onNavigateToPhotoDetail = { imageKey, index ->
                        navController.navigate("keyword_photo_detail/$imageKey?index=$index")
                    }
                )
            }
            composable(
                route = "keyword_photo_detail/{imageKey}?index={index}",
                arguments = listOf(
                    navArgument("imageKey") { type = NavType.StringType },
                    navArgument("index") { type = NavType.IntType; defaultValue = 0 }
                )
            ) { backStackEntry ->
                val imageKey = backStackEntry.arguments?.getString("imageKey") ?: ""
                val index = backStackEntry.arguments?.getInt("index") ?: 0
                KeywordPhotoDetailScreen(
                    targetImageKey = imageKey,
                    initialIndex = index,
                    viewModel = viewModel,
                    onBackClick = {
                        navController.navigateUp()
                    },
                    onNavigateToKeywordImages = {
                        navController.navigateUp()
                    },
                    onNavigateToGallery = { key, targetKey ->
                        // Halt the keyword loader's multi-thousand-image pagination so it doesn't
                        // starve the destination gallery's load (it resumes when the keyword screen
                        // is shown again).
                        viewModel.setScopeLoadingSuppressed(true)
                        coroutineScope.launch {
                            val node = viewModel.getCachedNodeById(key)
                            if (node != null && node.type == "Folder") {
                                viewModel.navigateToChildFolder(node)
                                navController.popBackStack("browser", false)
                            } else {
                                // Navigate straight to the gallery grid. If the album is
                                // password-protected, PhotoGridScreen's selectAlbum path renders the
                                // password prompt (the detail screen has no prompt dialog).
                                val title = viewModel.getAlbumName(key)
                                navController.navigate("photo_grid/$key/${Uri.encode(title)}") { launchSingleTop = true }
                            }
                        }
                    }
                )
            }
            composable(
                route = "photo_grid/{albumKey}/{albumTitle}",
                arguments = listOf(
                    navArgument("albumKey") { type = NavType.StringType },
                    navArgument("albumTitle") { type = NavType.StringType }
                )
            ) { backStackEntry ->
                val albumKey = backStackEntry.arguments?.getString("albumKey") ?: ""
                val albumTitle = backStackEntry.arguments?.getString("albumTitle") ?: ""
                PhotoGridScreen(
                    albumKey = albumKey,
                    albumTitle = albumTitle,
                    viewModel = viewModel,
                    onNavigateToPhotoDetail = { imageKey ->
                        navController.navigate("photo_detail/$albumKey/$imageKey")
                    },
                    onBackClick = {
                        navController.navigateUp()
                    },
                    onNavigateToFolder = {
                        navController.popBackStack("browser", false)
                    },
                    onNavigateToCastController = { title ->
                        navController.navigate(com.smugview.app.ui.navigation.Routes.castController(title))
                    }
                )
            }
            composable(
                route = "photo_detail/{albumKey}/{imageKey}",
                arguments = listOf(
                    navArgument("albumKey") { type = NavType.StringType },
                    navArgument("imageKey") { type = NavType.StringType }
                )
            ) { backStackEntry ->
                val albumKey = backStackEntry.arguments?.getString("albumKey") ?: ""
                val imageKey = backStackEntry.arguments?.getString("imageKey") ?: ""
                val index = backStackEntry.arguments?.getInt("index") ?: 0
                PhotoDetailScreen(
                    albumKey = albumKey,
                    targetImageKey = imageKey,
                    viewModel = viewModel,
                    onBackClick = {
                        navController.navigateUp()
                    },
                    onNavigateToFolder = {
                        navController.popBackStack("browser", false)
                    },
                    onNavigateToGallery = { key, title ->
                        navController.navigate("photo_grid/$key/${Uri.encode(title)}") {
                            popUpTo("browser") { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    onNavigateToKeywordImages = {
                        navController.navigate("keyword_images")
                    },
                    onNavigateToCastController = { title ->
                        navController.navigate(com.smugview.app.ui.navigation.Routes.castController(title))
                    }
                )
            }
            composable(
                route = "search_photo_detail/{imageKey}?index={index}",
                arguments = listOf(
                    navArgument("imageKey") { type = NavType.StringType },
                    navArgument("index") { type = NavType.IntType; defaultValue = 0 }
                )
            ) { backStackEntry ->
                val imageKey = backStackEntry.arguments?.getString("imageKey") ?: ""
                val index = backStackEntry.arguments?.getInt("index") ?: 0
                SearchPhotoDetailScreen(
                    targetImageKey = imageKey,
                    initialIndex = index,
                    viewModel = viewModel,
                    onBackClick = {
                        navController.navigateUp()
                    },
                    onNavigateToPhotoDetail = { albumKey, targetImageKey ->
                        navController.navigate("photo_detail/$albumKey/$targetImageKey")
                    },
                    onNavigateToGallery = { albumKey, targetKey ->
                        viewModel.cancelSearchJob()
                        coroutineScope.launch {
                            val node = viewModel.getCachedNodeById(albumKey)
                            if (node != null && node.type == "Folder") {
                                viewModel.navigateToChildFolder(node)
                                navController.popBackStack("browser", false)
                            } else {
                                // Navigate straight to the gallery grid; PhotoGridScreen prompts
                                // for a password if the album is locked (see handleAlbumLoadError).
                                val title = viewModel.getAlbumName(albumKey)
                                navController.navigate("photo_grid/$albumKey/${Uri.encode(title)}") { launchSingleTop = true }
                            }
                        }
                    },
                    onNavigateToKeywordImages = {
                        navController.navigate("keyword_images")
                    }
                )
            }
            composable(
                route = "cast_controller/{albumTitle}",
                arguments = listOf(
                    navArgument("albumTitle") { type = NavType.StringType }
                )
            ) { backStackEntry ->
                val albumTitle = backStackEntry.arguments?.getString("albumTitle") ?: ""
                val activeDevice by viewModel.activeCastDevice.collectAsState()
                val currentImageUri by viewModel.currentCastedImageUri.collectAsState()
                val isSlideshowPlaying by viewModel.isCastSlideshowPlaying.collectAsState()
                val slideshowInterval by viewModel.castSlideshowInterval.collectAsState()
                val volume by viewModel.castVolume.collectAsState()
                val isMuted by viewModel.isCastMuted.collectAsState()
                val isWebCompanionActive by viewModel.isWebCompanionActive.collectAsState()
                val localIpAddress = remember { viewModel.getLocalIpAddress() ?: "" }

                activeDevice?.let { device ->
                    com.smugview.app.ui.component.CastControllerScreen(
                        activeDevice = device,
                        albumTitle = albumTitle,
                        currentImageUri = currentImageUri,
                        isSlideshowPlaying = isSlideshowPlaying,
                        slideshowInterval = slideshowInterval,
                        volume = volume,
                        isMuted = isMuted,
                        isWebCompanionActive = isWebCompanionActive,
                        localIpAddress = localIpAddress,
                        onPlayPauseToggle = { viewModel.toggleCastSlideshowPlay() },
                        onNextClick = { viewModel.castNextPhoto() },
                        onPrevClick = { viewModel.castPreviousPhoto() },
                        onIntervalChange = { viewModel.setCastSlideshowInterval(it) },
                        onVolumeChange = { viewModel.setCastVolume(it) },
                        onToggleMute = { viewModel.toggleCastMute() },
                        onStopCasting = {
                            viewModel.disconnectCast()
                        },
                        onBackClick = { navController.navigateUp() }
                    )
                } ?: LaunchedEffect(Unit) {
                    navController.navigateUp()
                }
            }
        }
    }
}
