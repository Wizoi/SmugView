package com.smugview.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.savedstate.read
import com.smugview.app.data.notify.SiteFollow
import com.smugview.app.ui.browser.BrowserScreen
import com.smugview.app.ui.detail.KeywordPhotoDetailScreen
import com.smugview.app.ui.detail.PhotoDetailScreen
import com.smugview.app.ui.detail.SearchPhotoDetailScreen
import com.smugview.app.ui.explorer.KeywordImagesScreen
import com.smugview.app.ui.grid.PhotoGridScreen
import com.smugview.app.ui.navigation.Routes
import com.smugview.app.util.UrlParts
import kotlinx.coroutines.launch

/** What a new-gallery notification tap asks for: the site, and the gallery when the notification was about exactly one. */
data class PendingOpen(val nickname: String, val albumKey: String?, val albumTitle: String?)

@Composable
fun SmugViewNavigation(
    viewModel: com.smugview.app.ui.viewmodel.SmugViewModel,
    newGalleryFollow: SiteFollow? = null,
    pendingOpen: PendingOpen? = null,
    onOpenHandled: () -> Unit = {}
) {
    val navController = rememberNavController()
    LaunchedEffect(pendingOpen) {
        val open = pendingOpen ?: return@LaunchedEffect
        if (viewModel.activeNickname.value != open.nickname) viewModel.selectSite(open.nickname)
        if (open.albumKey != null) {
            navController.navigate("photo_grid/${open.albumKey}/${UrlParts.encode(open.albumTitle.orEmpty())}") { launchSingleTop = true }
        }
        onOpenHandled()
    }

    val coroutineScope = rememberCoroutineScope()

    // Store-screenshot hook (DemoHooks.route): inert unless the iOS CI job set it.
    LaunchedEffect(Unit) {
        val demo = com.smugview.app.ui.DemoHooks.route ?: return@LaunchedEffect
        kotlinx.coroutines.delay(3000)
        navController.navigate(demo) { launchSingleTop = true }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        NavHost(navController = navController, startDestination = "browser") {
            composable("browser") {
                BrowserScreen(
                    viewModel = viewModel,
                    newGalleryFollow = newGalleryFollow,
                    onNavigateToAlbum = { albumKey, albumTitle ->
                        viewModel.cancelSearchJob()
                        navController.navigate("photo_grid/$albumKey/${UrlParts.encode(albumTitle)}") { launchSingleTop = true }
                    },
                    onNavigateToExplorer = {},
                    onNavigateToSearchPhotoDetail = { imageKey, index ->
                        navController.navigate("search_photo_detail/$imageKey?index=$index")
                    },
                    onNavigateToPhotoDetail = { albumKey, imageKey ->
                        Routes.photoDetail(albumKey, imageKey)?.let { navController.navigate(it) }
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
                val imageKey = backStackEntry.string("imageKey")
                val index = backStackEntry.int("index")
                // R-17: after process death the route is restored but the tag selection is not (the photos
                // are derived from it). A viewer with nothing to show goes back to its list.
                val selectedKeywordTags by viewModel.selectedTags.collectAsState()
                LaunchedEffect(selectedKeywordTags.isEmpty()) {
                    if (selectedKeywordTags.isEmpty()) navController.navigateUp()
                }
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
                                navController.navigate("photo_grid/$key/${UrlParts.encode(title)}") { launchSingleTop = true }
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
                val albumKey = backStackEntry.string("albumKey")
                val albumTitle = backStackEntry.string("albumTitle")
                PhotoGridScreen(
                    albumKey = albumKey,
                    albumTitle = albumTitle,
                    viewModel = viewModel,
                    onNavigateToPhotoDetail = { imageKey ->
                        Routes.photoDetail(albumKey, imageKey)?.let { navController.navigate(it) }
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
                val albumKey = backStackEntry.string("albumKey")
                val imageKey = backStackEntry.string("imageKey")
                val index = backStackEntry.int("index")
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
                        navController.navigate("photo_grid/$key/${UrlParts.encode(title)}") {
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
                val imageKey = backStackEntry.string("imageKey")
                val index = backStackEntry.int("index")
                // R-17: search results are not saved. A restored viewer in a process that has run no search
                // (Idle, or an Error) has nothing to show and goes back to its list.
                val searchStateNow by viewModel.searchState.collectAsState()
                LaunchedEffect(searchStateNow) {
                    val s = searchStateNow
                    if (s is com.smugview.app.ui.viewmodel.SearchUiState.Idle || s is com.smugview.app.ui.viewmodel.SearchUiState.Error) {
                        navController.navigateUp()
                    }
                }
                SearchPhotoDetailScreen(
                    targetImageKey = imageKey,
                    initialIndex = index,
                    viewModel = viewModel,
                    onBackClick = {
                        navController.navigateUp()
                    },
                    onNavigateToPhotoDetail = { albumKey, targetImageKey ->
                        Routes.photoDetail(albumKey, targetImageKey)?.let { navController.navigate(it) }
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
                                navController.navigate("photo_grid/$albumKey/${UrlParts.encode(title)}") { launchSingleTop = true }
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
                val albumTitle = backStackEntry.string("albumTitle")
                val activeDevice by viewModel.activeCastDevice.collectAsState()
                val currentImageUri by viewModel.currentCastedImageUri.collectAsState()
                val isSlideshowPlaying by viewModel.isCastSlideshowPlaying.collectAsState()
                val slideshowInterval by viewModel.castSlideshowInterval.collectAsState()
                val volume by viewModel.castVolume.collectAsState()
                val isMuted by viewModel.isCastMuted.collectAsState()
                val isWebCompanionActive by viewModel.isWebCompanionActive.collectAsState()
                val webCompanionUrl by viewModel.webCompanionUrl.collectAsState()
                val webCompanionFailure by viewModel.webCompanionFailure.collectAsState()

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
                        webCompanionUrl = webCompanionUrl,
                        webCompanionFailure = webCompanionFailure,
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

private fun NavBackStackEntry.string(key: String): String = arguments?.read { getStringOrNull(key) } ?: ""

private fun NavBackStackEntry.int(key: String): Int = arguments?.read { getIntOrNull(key) } ?: 0

