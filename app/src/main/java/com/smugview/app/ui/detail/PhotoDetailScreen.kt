package com.smugview.app.ui.detail

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Environment
import android.widget.Toast
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Forward
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.Add
import kotlinx.coroutines.flow.first
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.DataUsage
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Lens
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Iso
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Event
import androidx.compose.material3.*
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.runtime.saveable.rememberSaveable
import com.smugview.app.ui.grid.GridView
import com.smugview.app.ui.text.ProblemAction
import com.smugview.app.ui.text.Subject
import com.smugview.app.ui.text.UserMessages
import com.smugview.app.ui.text.forSubject
import com.smugview.app.ui.browser.PasswordPromptHost
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import android.view.ViewGroup
import android.content.res.Configuration
import androidx.compose.ui.platform.LocalConfiguration
import kotlinx.coroutines.delay
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.palette.graphics.Palette
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.smugview.app.data.api.AlbumImageData
import com.smugview.app.ui.component.AddToCollectionsDialog
import com.smugview.app.data.api.ExifData
import com.smugview.app.data.api.isVideo
import com.smugview.app.ui.theme.DarkSystemBars
import com.smugview.app.ui.theme.DeepDarkBackground
import com.smugview.app.ui.theme.GlowGreen
import com.smugview.app.ui.theme.NeonBlue
import com.smugview.app.ui.theme.SurfaceDark
import com.smugview.app.ui.theme.SurfaceGlass
import com.smugview.app.ui.viewmodel.SmugViewModel
import com.smugview.app.data.db.CachedNode
import com.smugview.app.ui.browser.BreadcrumbBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PhotoDetailScreen(
    albumKey: String,
    targetImageKey: String,
    onBackClick: () -> Unit,
    onNavigateToFolder: () -> Unit,
    onNavigateToGallery: (albumKey: String, albumTitle: String) -> Unit,
    onNavigateToKeywordImages: () -> Unit,
    onNavigateToCastController: (String) -> Unit,
    viewModel: SmugViewModel = hiltViewModel()
) {
    LaunchedEffect(albumKey, targetImageKey) {
        viewModel.selectAlbum(albumKey, targetImageKey)
    }

    val lazyPhotos = viewModel.photosFlow.collectAsLazyPagingItems()
    val localCollections by viewModel.localCollections.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val updatedKeywordsMap = remember { mutableStateMapOf<String, String>() }

    val ownAlbum by viewModel.albumState.collectAsState()
    val filterType by viewModel.filterType.collectAsState()
    val includedTags by viewModel.includedTags.collectAsState()
    val excludedTags by viewModel.excludedTags.collectAsState()

    if (lazyPhotos.itemCount == 0) {
        // 6-7 (R-45, design 3.5): nothing to show yet. The grid's pure function says why: still loading, failed (offline,
        // gone, locked, ...), empty, filtered out, or waiting for a password. The album state of ANOTHER gallery (the screen
        // is composed before `selectAlbum` ran) must not leak in, so only this album's own state counts.
        val album = ownAlbum?.takeIf { it.albumKey == albumKey }
        val view = GridView.of(
            rawCount = album?.photos?.size ?: 0,
            shownCount = 0,
            loading = album?.loading ?: true,
            problem = album?.problem,
            complete = album?.complete ?: false,
            notice = null,
            expected = null,
            filtersActive = filterType != com.smugview.app.ui.viewmodel.GalleryFilterType.ALL ||
                includedTags.isNotEmpty() || excludedTags.isNotEmpty()
        )
        ViewerWithoutPhoto(view, albumKey, targetImageKey, viewModel, onBackClick)
        return
    }

    val pagerState = rememberPagerState(
        initialPage = 0,
        pageCount = { lazyPhotos.itemCount }
    )

    // R-48: scroll to the photo that was asked for only until the user swipes once or it has been reached. Both survive
    // rotation, and so does the key of the photo the user is on, so a list that grows (page 2 streaming in) never pulls
    // the viewer back to the photo it was opened on.
    var userSwiped by rememberSaveable(albumKey, targetImageKey) { mutableStateOf(false) }
    var hasScrolledToTarget by rememberSaveable(albumKey, targetImageKey) { mutableStateOf(false) }
    var currentKey by rememberSaveable(albumKey, targetImageKey) { mutableStateOf<String?>(null) }
    LaunchedEffect(pagerState) {
        pagerState.interactionSource.interactions.collect { if (it is DragInteraction.Start) userSwiped = true }
    }
    LaunchedEffect(lazyPhotos.itemCount) {
        if (lazyPhotos.itemCount == 0 || pagerState.isScrollInProgress) return@LaunchedEffect
        val anchor = if (userSwiped) currentKey else targetImageKey
        if (anchor == null) return@LaunchedEffect
        var index = -1
        for (i in 0 until lazyPhotos.itemCount) {
            if (lazyPhotos[i]?.imageKey == anchor) { index = i; break }
        }
        if (index != -1 && (!hasScrolledToTarget || pagerState.currentPage != index)) {
            pagerState.scrollToPage(index)
            hasScrolledToTarget = true
        }
    }
    LaunchedEffect(pagerState.currentPage, lazyPhotos.itemCount) {
        if (hasScrolledToTarget || userSwiped) {
            // The page can sit past the end for a frame when the list shrinks (a filter), and `get` throws past the end.
            val photos = lazyPhotos.itemSnapshotList
            if (pagerState.currentPage in photos.indices) photos[pagerState.currentPage]?.imageKey?.let { currentKey = it }
        }
    }

    val currentPhoto = if (pagerState.currentPage < lazyPhotos.itemCount) lazyPhotos[pagerState.currentPage] else null

    // 6-7 (R-45): the one photo that was asked for can show before the gallery says it is locked (its image URL needs no
    // session). The prompt belongs here too, and dismissing it is Back.
    PasswordPromptHost(viewModel, onDismiss = onBackClick)



    // Preload next and previous images in the background when current page changes
    LaunchedEffect(pagerState.currentPage, lazyPhotos.itemCount) {
        val imageLoader = context.imageLoader
        val prevIndex = pagerState.currentPage - 1
        val nextIndex = pagerState.currentPage + 1
        listOf(prevIndex, nextIndex).forEach { index ->
            if (index in 0 until lazyPhotos.itemCount) {
                val photo = lazyPhotos[index]
                if (photo != null) {
                    val url = photo.thumbnailUrl?.replace("/Th/", "/L/")
                        ?.replace("/th/", "/l/")
                        ?.replace("-Th.", "-L.")
                        ?.replace("-th.", "-l.")
                        ?: photo.archivedUri
                    if (url != null) {
                        val request = ImageRequest.Builder(context)
                            .data(url)
                            .build()
                        imageLoader.enqueue(request)
                    }
                }
            }
        }
    }

    val animatedBgColor = rememberDominantBackgroundColor(currentPhoto?.thumbnailUrl)

    // Overlays & sheets state
    var showExifSheet by remember { mutableStateOf(false) }
    var showAddToCollectionDialog by remember { mutableStateOf(false) }
    var showControls by remember { mutableStateOf(false) }

    DarkSystemBars()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // Dynamic Radial Gradient Overlay matching the photo's dominant color
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(animatedBgColor, Color.Black),
                        radius = 1200f
                    )
                )
        )

        // Immersive horizontal swipe pager
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            pageSpacing = 16.dp
        ) { page ->
            val photo = lazyPhotos[page]
            if (photo != null) {
                ImmersivePhotoPage(
                    photo = photo,
                    isActive = pagerState.currentPage == page,
                    fallbackAlbumKey = albumKey,
                    localCollections = localCollections,
                    viewModel = viewModel,
                    onToggleControls = { showControls = !showControls },
                    onLongPress = { showExifSheet = true },
                    onRequestAddToCollection = { showAddToCollectionDialog = true }
                )
            }
        }

        val folderNavStack = viewModel.folderNavigationStack
        val albumTitle = viewModel.currentAlbumTitle ?: "Gallery"
        val albumNode = remember(albumKey, albumTitle) {
            CachedNode(
                nodeId = albumKey,
                parentNodeId = null,
                type = "Album",
                title = albumTitle,
                description = null,
                access = null,
                passwordHint = null,
                uri = "",
                childNodesUri = null,
                albumUri = null
            )
        }
        val fullNavStack = remember(folderNavStack.size, albumTitle) {
            folderNavStack + albumNode
        }

        // Navigation Top Bar
        AnimatedVisibility(
            visible = showControls,
            enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut()
        ) {
            val activeDevice by viewModel.activeCastDevice.collectAsState()
            val isCasting by viewModel.isCasting.collectAsState()
            val discoveredDevices by viewModel.discoveredDevices.collectAsState()
            val castedAlbumKey by viewModel.castedAlbumKeyFlow.collectAsState()
            var showCastSelector by remember { mutableStateOf(false) }
            var showRecastDialog by remember { mutableStateOf(false) }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
            ) {
                BreadcrumbBar(
                    navStack = fullNavStack,
                    onBreadcrumbClick = { index ->
                        if (index < folderNavStack.size) {
                            if (index == -1) {
                                viewModel.navigateToHome()
                            } else {
                                viewModel.navigateToStackFolder(index)
                            }
                            viewModel.setActiveTab(com.smugview.app.ui.viewmodel.BrowserTab.Folders)
                            onNavigateToFolder()
                        } else {
                            onNavigateToGallery(albumKey, albumTitle)
                        }
                    }
                )

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = onBackClick,
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(SurfaceGlass)
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }

                    val cState = activeDevice?.state ?: com.smugview.app.data.cast.ConnectionState.DISCONNECTED
                    com.smugview.app.ui.component.CastButton(
                        connectionState = cState,
                        onClick = {
                            if (isCasting) {
                                if (castedAlbumKey != null && castedAlbumKey != albumKey) {
                                    showRecastDialog = true
                                } else {
                                    onNavigateToCastController(albumTitle)
                                }
                            } else {
                                viewModel.startCastDiscovery()
                                showCastSelector = true
                            }
                        }
                    )
                }

                if (showRecastDialog) {
                    AlertDialog(
                        onDismissRequest = { showRecastDialog = false },
                        title = { Text("Recast Gallery") },
                        text = { Text("Do you want to recast using the photos from this gallery?") },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    showRecastDialog = false
                                    viewModel.castedAlbumKey = albumKey
                                    currentPhoto?.let { photo ->
                                        val targetUrl = if (photo.isVideo) {
                                            photo.videoUrl ?: photo.thumbnailUrl?.replace("/Th/", "/X3/")
                                        } else {
                                            photo.thumbnailUrl?.replace("/Th/", "/X3/")
                                        }
                                        targetUrl?.let { url ->
                                            viewModel.castImage(url, photo.title ?: "Casted Media")
                                        }
                                    }
                                    onNavigateToCastController(albumTitle)
                                }
                            ) {
                                Text("Yes")
                            }
                        },
                        dismissButton = {
                            TextButton(
                                onClick = {
                                    showRecastDialog = false
                                    onNavigateToCastController(albumTitle)
                                }
                            ) {
                                Text("No")
                            }
                        }
                    )
                }

                if (showCastSelector) {
                    com.smugview.app.ui.component.CastDeviceSelectorBottomSheet(
                        devices = discoveredDevices,
                        onDeviceSelected = { device ->
                            viewModel.connectToCastDevice(device)
                            viewModel.castedAlbumKey = albumKey
                            showCastSelector = false
                            
                             // Cast the active single media (photo or video)
                             currentPhoto?.let { photo ->
                                 val targetUrl = if (photo.isVideo) {
                                     photo.videoUrl ?: photo.thumbnailUrl?.replace("/Th/", "/X3/")
                                 } else {
                                     photo.thumbnailUrl?.replace("/Th/", "/X3/")
                                 }
                                 targetUrl?.let { url ->
                                     viewModel.castImage(url, photo.title ?: "Casted Media")
                                 }
                             }
                            onNavigateToCastController(albumTitle)
                        },
                        onGone = { viewModel.stopCastDiscovery() },
                        onDismiss = {
                            viewModel.stopCastDiscovery()
                            showCastSelector = false
                        }
                    )
                }
            }
        }

        // Floating Action Capsule & Text Details Block (Redesigned matching photo_viewer_rework)
        val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

        if (!isLandscape && currentPhoto != null) {
            val nickname by viewModel.activeNickname.collectAsState()
            val exifState by viewModel.getImageExif(currentPhoto.imageKey).collectAsState()

            val cameraDetails = remember(exifState) {
                exifState?.getOrNull()?.let { exif ->
                    val camera = exif.camera ?: ""
                    val focal = exif.focalLength ?: ""
                    val aperture = exif.aperture ?: ""
                    val shutter = exif.exposure ?: ""
                    val iso = exif.iso?.let { "ISO $it" } ?: ""
                    listOf(camera, focal, aperture, shutter, iso)
                        .filter { it.isNotEmpty() }
                        .joinToString(", ")
                        .let { if (it.isNotEmpty()) "Taken with $it" else "" }
                } ?: ""
            }

            AnimatedVisibility(
                visible = showControls,
                enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(bottom = 16.dp, start = 16.dp, end = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Title text displayed above the capsule button section
                    val photoTitle = currentPhoto.title ?: currentPhoto.caption ?: ""
                    if (photoTitle.trim().isNotEmpty()) {
                        Text(
                            text = photoTitle.trim(),
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            modifier = Modifier
                                .padding(bottom = 12.dp)
                                .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                                .padding(horizontal = 16.dp, vertical = 6.dp)
                        )
                    }

                    PhotoActionCapsule(
                        photo = currentPhoto,
                        localCollections = localCollections,
                        viewModel = viewModel,
                        scope = scope,
                        onShowExif = { showExifSheet = true },
                        onRequestAddToCollection = { showAddToCollectionDialog = true }
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // Metadata details text formatted as "Image X of Y"
                    val footerTitle = currentPhoto.title ?: currentPhoto.caption ?: ""
                    val footerText = if (footerTitle.trim().isNotEmpty()) {
                        "Image ${pagerState.currentPage + 1} of ${lazyPhotos.itemCount} • ${footerTitle.trim()} • Photo by ${nickname ?: "Owner"}"
                    } else {
                        "Image ${pagerState.currentPage + 1} of ${lazyPhotos.itemCount} • Photo by ${nickname ?: "Owner"}"
                    }
                    Text(
                        text = footerText,
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                    if (cameraDetails.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = cameraDetails,
                            color = Color.White.copy(alpha = 0.6f),
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        // EXIF Bottom Sheet Overlay
        if (showExifSheet && currentPhoto != null) {
            PhotoExifSheet(
                currentPhoto = currentPhoto,
                detailedPhoto = null,
                viewModel = viewModel,
                onDismiss = { showExifSheet = false },
                onNavigateToKeywordImages = onNavigateToKeywordImages
            )
        }

        // Add to Collection Dialog overlay
        if (showAddToCollectionDialog && currentPhoto != null) {
            AddToCollectionsDialog(
                type = "Image",
                itemKey = currentPhoto.imageKey,
                title = currentPhoto.title ?: currentPhoto.caption ?: "Untitled Photo",
                albumKey = albumKey,
                albumTitle = viewModel.currentAlbumTitle ?: "Gallery",
                thumbnailUrl = currentPhoto.thumbnailUrl,
                imageUrl = currentPhoto.archivedUri ?: currentPhoto.thumbnailUrl,
                onDismissRequest = { showAddToCollectionDialog = false },
                viewModel = viewModel
            )
        }
    }
}

@Composable
fun FloatingActionButtonItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    onClick: () -> Unit
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(54.dp)
            .clip(CircleShape)
            .background(SurfaceGlass)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = Color.White,
            modifier = Modifier.size(24.dp)
        )
    }
}

@Composable
fun ExifDetailItem(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, color = Color.White.copy(alpha = 0.5f), fontSize = 14.sp)
        Text(text = value, color = Color.White, fontWeight = FontWeight.Medium, fontSize = 14.sp)
    }
}

@Composable
fun CameraLogo(cameraName: String) {
    val name = cameraName.lowercase()
    val drawableRes = when {
        name.contains("iphone") || name.contains("apple") -> com.smugview.app.R.drawable.camera_apple
        name.contains("canon") -> com.smugview.app.R.drawable.camera_canon
        name.contains("nikon") -> com.smugview.app.R.drawable.camera_nikon
        name.contains("sony") -> com.smugview.app.R.drawable.camera_sony
        name.contains("fuji") -> com.smugview.app.R.drawable.camera_fuji
        name.contains("ez controller") || name.contains("noritsu") || name.contains("scanner") -> com.smugview.app.R.drawable.camera_scanner
        else -> null
    }

    if (drawableRes != null) {
        androidx.compose.foundation.Image(
            painter = androidx.compose.ui.res.painterResource(id = drawableRes),
            contentDescription = "Camera Image",
            modifier = Modifier.size(24.dp)
        )
    } else {
        Icon(
            imageVector = Icons.Default.CameraAlt,
            contentDescription = "Camera",
            tint = Color.White.copy(alpha = 0.7f),
            modifier = Modifier.size(24.dp)
        )
    }
}

@Composable
fun ExifCardItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    iconContent: @Composable (() -> Unit)? = null
) {
    Row(
        modifier = modifier
            .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(12.dp))
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (iconContent != null) {
            iconContent()
        } else {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = Color.White.copy(alpha = 0.7f),
                modifier = Modifier.size(20.dp)
            )
        }
        Column {
            Text(text = label, color = Color.White.copy(alpha = 0.5f), fontSize = 10.sp)
            Text(
                text = value,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * "Share picture" (design 3.10): the saved copy, else the X3 rendition, stripped of camera data and location
 * ([com.smugview.app.share.ShareFiles]), handed to the share sheet with the photo's web page as the text. [link] is null
 * when the photo has no page (the picture is still shared, with no text).
 */
fun sharePhoto(
    context: Context,
    coroutineScope: kotlinx.coroutines.CoroutineScope,
    viewModel: SmugViewModel,
    photo: AlbumImageData,
    link: String?
) {
    Toast.makeText(context, com.smugview.app.ui.text.UserMessages.SHARE_PREPARING, Toast.LENGTH_SHORT).show()

    coroutineScope.launch {
        try {
            val factory = (context.applicationContext as com.smugview.app.SmugViewApp).callFactory
            val file = com.smugview.app.share.ShareFiles(context.cacheDir, factory)
                .prepare(photo.imageKey, viewModel.savedFileOf(photo.imageKey), photo.thumbnailUrl)
            val fileUri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val description = photo.fileName?.takeIf { it.isNotBlank() }
                ?: photo.title?.takeIf { it.isNotBlank() }
                ?: "Photo ${photo.imageKey}"

            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "image/jpeg"
                putExtra(Intent.EXTRA_STREAM, fileUri)
                if (link != null) putExtra(Intent.EXTRA_TEXT, link)
                putExtra(Intent.EXTRA_SUBJECT, description)
                clipData = ClipData.newRawUri("", fileUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(intent, com.smugview.app.ui.text.UserMessages.SHARE_PICTURE).apply {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(chooser)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Toast.makeText(context, com.smugview.app.ui.text.UserMessages.shareFailed(shareProblem(e)), Toast.LENGTH_SHORT).show()
        }
    }
}

/** What a failed share says (design 3.5): a status from SmugMug is never "you went offline" (CLAUDE.md, offline means no answer). */
internal fun shareProblem(e: Throwable): com.smugview.app.ui.text.Problem {
    val photo = com.smugview.app.ui.text.Subject.Photo
    return when {
        e is com.smugview.app.share.ShareFiles.HttpFailure -> when (e.code) {
            429 -> com.smugview.app.ui.text.Problem.RateLimited(photo)
            in 500..599 -> com.smugview.app.ui.text.Problem.SmugMugTrouble(photo, e.code)
            404 -> com.smugview.app.ui.text.Problem.Gone(photo)
            else -> com.smugview.app.ui.text.Problem.Unexpected(photo, e.code.toString())
        }
        e is com.smugview.app.share.ShareFiles.NotAPictureException -> com.smugview.app.ui.text.Problem.Unexpected(photo, "unreadable")
        else -> com.smugview.app.ui.text.Problem.from(e, photo)
    }
}


/**
 * "Download photo" (design 3.11): the original into Pictures/SmugView, from the saved copy when there is one (so it works
 * offline), else from the CDN. The toast says what happened, and why when it did not.
 */
suspend fun downloadPhotoToGallery(context: Context, photo: AlbumImageData, viewModel: SmugViewModel) {
    val saved = viewModel.savedFileOf(photo.imageKey)
    var detailedPhoto = photo
    var detailsError: Throwable? = null
    if (saved == null && detailedPhoto.archivedUri == null) {
        Toast.makeText(context, "Fetching high-resolution image details...", Toast.LENGTH_SHORT).show()
        try {
            val result = viewModel.getImageDetails(photo.imageKey).first { it != null }
            if (result != null && result.isSuccess) detailedPhoto = result.getOrThrow() else detailsError = result?.exceptionOrNull()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            detailsError = e
        }
    }
    val sourceUrl = detailedPhoto.archivedUri ?: detailedPhoto.thumbnailUrl?.replace("/Th/", "/L/")?.replace("/th/", "/l/")
    try {
        if (saved == null && sourceUrl == null) throw detailsError ?: com.smugview.app.download.PhotoDownloader.NoSourceException()
        val factory = (context.applicationContext as com.smugview.app.SmugViewApp).callFactory
        val smaller = com.smugview.app.download.Renditions.fromThumbnail(detailedPhoto.thumbnailUrl)
            .ifEmpty { com.smugview.app.download.Renditions.fromOriginal(detailedPhoto.archivedUri) }
        val result = com.smugview.app.download.PhotoDownloader(context, factory)
            .download(photo.imageKey, detailedPhoto.fileName ?: photo.fileName, saved, sourceUrl, smaller)
        val said = if (result.original) com.smugview.app.ui.text.UserMessages.DOWNLOAD_SAVED else com.smugview.app.ui.text.UserMessages.DOWNLOAD_SAVED_SMALLER
        Toast.makeText(context, said, Toast.LENGTH_LONG).show()
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        Toast.makeText(context, com.smugview.app.download.downloadMessage(e), Toast.LENGTH_LONG).show()
    }
}

/**
 * The download button's action. Android 8 and 9 need the storage permission first: it is asked for once, the photo waits,
 * and a refusal says so (DOWNLOAD_PERMISSION). Android 10 and later need none.
 */
@Composable
fun rememberPhotoDownloadAction(viewModel: SmugViewModel): (AlbumImageData) -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var waiting by remember { mutableStateOf<AlbumImageData?>(null) }
    val askPermission = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted ->
        val photo = waiting
        waiting = null
        if (photo == null) return@rememberLauncherForActivityResult
        if (granted) scope.launch { downloadPhotoToGallery(context, photo, viewModel) }
        else Toast.makeText(context, com.smugview.app.ui.text.UserMessages.DOWNLOAD_PERMISSION, Toast.LENGTH_LONG).show()
    }
    return { photo ->
        val needsPermission = android.os.Build.VERSION.SDK_INT < 29 &&
            androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (needsPermission) {
            waiting = photo
            askPermission.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            scope.launch { downloadPhotoToGallery(context, photo, viewModel) }
        }
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun VideoPlayerView(
    videoUrl: String,
    isActive: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {}
) {
    val context = LocalContext.current
    var isPlaying by remember { mutableStateOf(true) }
    var currentPosition by remember { mutableStateOf(0L) }
    var duration by remember { mutableStateOf(0L) }
    var showControls by remember { mutableStateOf(true) }

    // Auto-hide controls after 3 seconds if playing
    LaunchedEffect(showControls, isPlaying) {
        if (showControls && isPlaying) {
            delay(3000)
            showControls = false
        }
    }

    val exoPlayer = remember(videoUrl) {
        val httpDataSourceFactory = androidx.media3.datasource.DefaultHttpDataSource.Factory()
            .setUserAgent("SmugView-Android-App/1.0")
            .setAllowCrossProtocolRedirects(true)
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(androidx.media3.exoplayer.source.DefaultMediaSourceFactory(httpDataSourceFactory))
            .build().apply {
                val mediaItem = MediaItem.fromUri(videoUrl)
                setMediaItem(mediaItem)
                prepare()
                playWhenReady = true
                repeatMode = Player.REPEAT_MODE_ONE
            }
    }

    // Keep position/duration updated
    LaunchedEffect(exoPlayer, isPlaying) {
        while (isPlaying) {
            currentPosition = exoPlayer.currentPosition
            duration = exoPlayer.duration.coerceAtLeast(0L)
            delay(500)
        }
    }

    // Sync play status when active page changes
    LaunchedEffect(isActive) {
        if (isActive) {
            exoPlayer.play()
            isPlaying = true
        } else {
            exoPlayer.pause()
            isPlaying = false
        }
    }

    DisposableEffect(exoPlayer) {
        onDispose {
            exoPlayer.release()
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                showControls = !showControls
                onClick()
            },
        contentAlignment = Alignment.Center
    ) {
        // The Video Player Surface
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exoPlayer
                    useController = false // We render our own beautiful Compose controls!
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // Custom Overlay Controls
        AnimatedVisibility(
            visible = showControls,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.4f)),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(32.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(32.dp))
                        .padding(horizontal = 24.dp, vertical = 12.dp)
                ) {
                    // Rewind 15s
                    IconButton(
                        onClick = {
                            val newPos = (exoPlayer.currentPosition - 15000).coerceAtLeast(0L)
                            exoPlayer.seekTo(newPos)
                            currentPosition = newPos
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.FastRewind,
                            contentDescription = "-15s",
                            tint = Color.White,
                            modifier = Modifier.size(36.dp)
                        )
                    }

                    // Play/Pause
                    IconButton(
                        onClick = {
                            if (exoPlayer.isPlaying) {
                                exoPlayer.pause()
                                isPlaying = false
                            } else {
                                exoPlayer.play()
                                isPlaying = true
                            }
                        }
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (isPlaying) "Pause" else "Play",
                            tint = Color.White,
                            modifier = Modifier.size(48.dp)
                        )
                    }

                    // Forward 15s
                    IconButton(
                        onClick = {
                            val newPos = (exoPlayer.currentPosition + 15000).coerceAtMost(exoPlayer.duration)
                            exoPlayer.seekTo(newPos)
                            currentPosition = newPos
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.FastForward,
                            contentDescription = "+15s",
                            tint = Color.White,
                            modifier = Modifier.size(36.dp)
                        )
                    }
                }

                // Progress Bar at the bottom of the video controls overlay
                if (duration > 0) {
                    val progress = currentPosition.toFloat() / duration.toFloat()
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 16.dp, start = 24.dp, end = 24.dp),
                        color = NeonBlue,
                        trackColor = Color.White.copy(alpha = 0.2f)
                    )
                }
            }
        }
    }
}

fun formatToLocalTime(dateStr: String?): String {
    if (dateStr.isNullOrEmpty()) return "Unknown Date"
    val trimmed = dateStr.trim()
    
    // 1. Try parsing EXIF standard format "yyyy:MM:dd HH:mm:ss"
    if (trimmed.matches(Regex("^\\d{4}:\\d{2}:\\d{2} \\d{2}:\\d{2}:\\d{2}$"))) {
        try {
            val formatter = java.time.format.DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss")
            val localDateTime = java.time.LocalDateTime.parse(trimmed, formatter)
            val outFormatter = java.time.format.DateTimeFormatter.ofPattern("MMM dd, yyyy\nhh:mm a")
            return localDateTime.format(outFormatter)
        } catch (e: Exception) {
            // fall through
        }
    }
    
    // 2. Try parsing ISO-8601 offset date-time (e.g. 2026-07-06T00:18:44Z)
    try {
        val zonedDateTime = java.time.ZonedDateTime.parse(trimmed)
        val localDateTime = zonedDateTime.withZoneSameInstant(java.time.ZoneId.systemDefault())
        val formatter = java.time.format.DateTimeFormatter.ofPattern("MMM dd, yyyy\nhh:mm a")
        return localDateTime.format(formatter)
    } catch (e: Exception) {
        // fall through
    }
    
    // 3. Try parsing simple ISO Local Date Time (e.g. 2026-07-06T00:18:44)
    try {
        val localDateTime = java.time.LocalDateTime.parse(trimmed)
        val formatter = java.time.format.DateTimeFormatter.ofPattern("MMM dd, yyyy\nhh:mm a")
        return localDateTime.format(formatter)
    } catch (e: Exception) {
        // fall through
    }
    
    // Fallback: clean up date string slightly and put on two lines
    return trimmed.replace("T", "\n").replace("Z", "")
}

/**
 * A media URL with the API key added. It never carries the gallery password: no endpoint honours a
 * `Password=` query parameter, and a password in a URL leaks into logs and caches (Phase 4 step 4-7,
 * R-23). Password galleries' CDN media URLs need no session at all (R-53).
 */
fun getAuthenticatedMediaUrl(url: String?): String {
    if (url.isNullOrEmpty()) return ""
    val apiKey = com.smugview.app.BuildConfig.SMUGMUG_API_KEY

    val uri = android.net.Uri.parse(url)
    val builder = uri.buildUpon()
    
    if (uri.getQueryParameter("APIKey") == null) {
        builder.appendQueryParameter("APIKey", apiKey)
    }

    return builder.build().toString()
}


/**
 * What the viewer shows while its album has no photo for it (6-7, R-45, design 3.5): a spinner while loading, the real cause
 * and the way out when the load failed (offline with nothing saved, gone, SmugMug trouble), and the password prompt when the
 * gallery is locked. Dismissing the prompt goes back: there is nothing behind it.
 */
@Composable
private fun ViewerWithoutPhoto(
    view: GridView,
    albumKey: String,
    targetImageKey: String,
    viewModel: SmugViewModel,
    onBackClick: () -> Unit
) {
    DarkSystemBars()
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .statusBarsPadding(),
        contentAlignment = Alignment.Center
    ) {
        when (view) {
            is GridView.Failed -> {
                val problem = view.problem.forSubject(Subject.Photo)
                val primary = UserMessages.primaryAction(problem)
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = UserMessages.heading(problem),
                        color = Color.White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = UserMessages.body(problem),
                        color = Color.White.copy(alpha = 0.6f),
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Row {
                        if (primary != ProblemAction.GoBack) {
                            Button(
                                onClick = onBackClick,
                                colors = ButtonDefaults.buttonColors(containerColor = SurfaceDark)
                            ) {
                                Text(UserMessages.BUTTON_GO_BACK, color = Color.White)
                            }
                            Spacer(modifier = Modifier.width(16.dp))
                        }
                        Button(
                            onClick = {
                                if (primary == ProblemAction.GoBack) onBackClick()
                                else viewModel.selectAlbum(albumKey, targetImageKey, force = true)
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = NeonBlue)
                        ) {
                            Text(primary.label)
                        }
                    }
                }
            }
            GridView.EmptyGallery, GridView.FilterEmpty -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = if (view == GridView.EmptyGallery) UserMessages.EMPTY_GALLERY else UserMessages.FILTER_EMPTY,
                        color = Color.White.copy(alpha = 0.7f),
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(onClick = onBackClick, colors = ButtonDefaults.buttonColors(containerColor = SurfaceDark)) {
                        Text(UserMessages.BUTTON_GO_BACK, color = Color.White)
                    }
                }
            }
            // A password prompt is open or about to be (or the load was interrupted): claim nothing, keep a way out.
            GridView.Blank -> Button(
                onClick = onBackClick,
                colors = ButtonDefaults.buttonColors(containerColor = SurfaceDark)
            ) {
                Text(UserMessages.BUTTON_GO_BACK, color = Color.White)
            }
            else -> CircularProgressIndicator(color = NeonBlue)
        }
    }
    PasswordPromptHost(viewModel, onDismiss = onBackClick)
}
