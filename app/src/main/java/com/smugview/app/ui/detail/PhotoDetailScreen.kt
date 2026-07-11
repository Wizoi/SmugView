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

    if (lazyPhotos.itemCount == 0) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(color = NeonBlue)
        }
        return
    }

    val pagerState = rememberPagerState(
        initialPage = 0,
        pageCount = { lazyPhotos.itemCount }
    )

    var hasScrolledToTarget by remember { mutableStateOf(false) }
    LaunchedEffect(lazyPhotos.itemCount) {
        if (lazyPhotos.itemCount > 0) {
            var targetIndex = -1
            for (i in 0 until lazyPhotos.itemCount) {
                if (lazyPhotos[i]?.imageKey == targetImageKey) {
                    targetIndex = i
                    break
                }
            }
            if (targetIndex != -1) {
                if (!hasScrolledToTarget || pagerState.currentPage != targetIndex) {
                    if (!hasScrolledToTarget || lazyPhotos.itemCount > 1) {
                        pagerState.scrollToPage(targetIndex)
                        hasScrolledToTarget = true
                    }
                }
            }
        }
    }

    val currentPhoto = lazyPhotos[pagerState.currentPage]



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

    // Dynamic background color state extracted using Palette API
    var dominantColor by remember { mutableStateOf(Color.Black) }
    val animatedBgColor by animateColorAsState(
        targetValue = dominantColor.copy(alpha = 0.85f),
        animationSpec = tween(durationMillis = 500),
        label = "bgColor"
    )

    // Trigger Palette extraction when current photo changes
    LaunchedEffect(currentPhoto) {
        currentPhoto?.thumbnailUrl?.let { url ->
            val loader = context.imageLoader
            val req = ImageRequest.Builder(context)
                .data(url)
                .allowHardware(false) // Crucial for pixel reading
                .build()
            val result = loader.execute(req)
            if (result is SuccessResult) {
                val bitmap = (result.drawable as? BitmapDrawable)?.bitmap
                if (bitmap != null) {
                    withContext(Dispatchers.Default) {
                        val palette = Palette.from(bitmap).generate()
                        val dom = palette.getDominantColor(Color.Black.toArgb())
                        dominantColor = Color(dom)
                    }
                }
            }
        }
    }

    // Overlays & sheets state
    var showExifSheet by remember { mutableStateOf(false) }
    var showAddToCollectionDialog by remember { mutableStateOf(false) }
    var showControls by remember { mutableStateOf(false) }

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
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    if (photo.isVideo) {
                        val rawUrl = photo.videoUrl ?: photo.archivedUri ?: photo.thumbnailUrl ?: ""
                        val videoUrl = remember(rawUrl) {
                            getAuthenticatedMediaUrl(rawUrl, albumKey, viewModel)
                        }
                        val isActive = pagerState.currentPage == page
                        VideoPlayerView(
                            videoUrl = videoUrl,
                            isActive = isActive,
                            modifier = Modifier.fillMaxSize(),
                            onClick = { showControls = !showControls }
                        )
                    } else {
                        val pagerImageModel = remember(photo.thumbnailUrl, photo.archivedUri) {
                            photo.thumbnailUrl?.replace("/Th/", "/L/")
                                ?.replace("/th/", "/l/")
                                ?.replace("-Th.", "-L.")
                                ?.replace("-th.", "-l.")
                                ?: photo.archivedUri
                        }

                        var currentDetailUrl by remember(photo.thumbnailUrl, pagerImageModel) {
                            mutableStateOf(pagerImageModel)
                        }

                        AsyncImage(
                            model = currentDetailUrl,
                            contentDescription = photo.title ?: "Immersive photo details",
                            contentScale = ContentScale.Fit,
                            onError = {
                                currentDetailUrl = photo.archivedUri ?: photo.thumbnailUrl
                            },
                            modifier = Modifier
                                .fillMaxSize()
                                .combinedClickable(
                                    onClick = { showControls = !showControls },
                                    onLongClick = { showExifSheet = true },
                                    onDoubleClick = {
                                        // Default quick-action: Add to Favorites collection (if exists)
                                        val fav = localCollections.find { it.name.lowercase() == "favorites" }
                                        if (fav != null) {
                                            viewModel.addPhotoToCollection(photo, fav.id)
                                            Toast.makeText(context, "Added to Favorites!", Toast.LENGTH_SHORT).show()
                                        } else {
                                            showAddToCollectionDialog = true
                                        }
                                    }
                                )
                        )
                    }
                }
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
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
            ) {
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
                }

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

                    // Capsule Row container matching photo_viewer_rework capsule
                    Row(
                        modifier = Modifier
                            .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(32.dp))
                            .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(32.dp))
                            .padding(horizontal = 24.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(28.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Bookmark (Save) Button
                        IconButton(
                            onClick = {
                                val fav = localCollections.find { it.name.lowercase() == "favorites" }
                                if (fav != null) {
                                    viewModel.addPhotoToCollection(currentPhoto, fav.id)
                                    Toast.makeText(context, "Added to Favorites!", Toast.LENGTH_SHORT).show()
                                } else {
                                    showAddToCollectionDialog = true
                                }
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Default.BookmarkBorder,
                                contentDescription = "Save",
                                tint = Color.White,
                                modifier = Modifier.size(26.dp)
                            )
                        }

                        // Share Link (previous share icon)
                        IconButton(
                            onClick = {
                                sharePhoto(context, scope, currentPhoto)
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Default.Share,
                                contentDescription = "Share Link",
                                tint = Color.White,
                                modifier = Modifier.size(26.dp)
                            )
                        }

                        // Info (i) Button
                        IconButton(
                            onClick = { showExifSheet = true }
                        ) {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = "Info Details",
                                tint = Color.White,
                                modifier = Modifier.size(26.dp)
                            )
                        }
                    }

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

        // EXIF Bottom Sheet Overlay (Redesigned matching photo_viewer_mockup)
        if (showExifSheet && currentPhoto != null) {
            val exifState by viewModel.getImageExif(currentPhoto.imageKey).collectAsState()

            ModalBottomSheet(
                onDismissRequest = { showExifSheet = false },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                containerColor = SurfaceDark
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 24.dp, vertical = 16.dp)
                ) {
                    // Action Chips Rows
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp)
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            // Save (Bookmark) Action
                            AssistChip(
                                onClick = {
                                    val fav = localCollections.find { it.name.lowercase() == "favorites" }
                                    if (fav != null) {
                                        viewModel.addPhotoToCollection(currentPhoto, fav.id)
                                        Toast.makeText(context, "Added to Favorites!", Toast.LENGTH_SHORT).show()
                                    } else {
                                        showAddToCollectionDialog = true
                                    }
                                },
                                label = { Text("Save", color = Color.White) },
                                leadingIcon = { Icon(Icons.Default.BookmarkBorder, contentDescription = "Save", tint = Color.White, modifier = Modifier.size(16.dp)) },
                                colors = AssistChipDefaults.assistChipColors(containerColor = SurfaceDark),
                                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.2f)),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.weight(1f)
                            )

                            // Share Action
                            AssistChip(
                                onClick = { sharePhoto(context, scope, currentPhoto) },
                                label = { Text("Share", color = Color.White) },
                                leadingIcon = { Icon(Icons.Default.Share, contentDescription = "Share", tint = Color.White, modifier = Modifier.size(16.dp)) },
                                colors = AssistChipDefaults.assistChipColors(containerColor = SurfaceDark),
                                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.2f)),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.weight(1f)
                            )
                        }

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            // Download Action
                            AssistChip(
                                onClick = {
                                    scope.launch {
                                        downloadPhotoToGallery(context, currentPhoto, viewModel)
                                    }
                                },
                                label = { Text("Download", color = Color.White) },
                                leadingIcon = { Icon(Icons.Default.Download, contentDescription = "Download", tint = Color.White, modifier = Modifier.size(16.dp)) },
                                colors = AssistChipDefaults.assistChipColors(containerColor = SurfaceDark),
                                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.2f)),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.weight(1f)
                            )

                            // Gallery Action
                            AssistChip(
                                onClick = {
                                    onNavigateToGallery(albumKey, albumTitle)
                                },
                                label = { Text("Gallery", color = Color.White) },
                                leadingIcon = { Icon(Icons.Default.Collections, contentDescription = "Gallery", tint = Color.White, modifier = Modifier.size(16.dp)) },
                                colors = AssistChipDefaults.assistChipColors(containerColor = SurfaceDark),
                                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.2f)),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "PHOTO DETAILS",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White.copy(alpha = 0.6f),
                        modifier = Modifier.padding(bottom = 12.dp)
                    )

                    when (val result = exifState) {
                        null -> {
                            Box(modifier = Modifier.fillMaxWidth().height(100.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(color = NeonBlue)
                            }
                        }
                        else -> {
                            result.fold(
                                onSuccess = { exif ->
                                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                            // Camera
                                            val cam = exif.camera
                                            ExifCardItem(
                                                icon = Icons.Default.CameraAlt,
                                                label = "Camera:",
                                                value = cam ?: "Unknown",
                                                modifier = Modifier.weight(1f),
                                                iconContent = if (!cam.isNullOrEmpty()) {
                                                    { CameraLogo(exif.make ?: cam) }
                                                } else null
                                            )
                                            // Aperture
                                            ExifCardItem(
                                                icon = Icons.Default.Lens,
                                                label = "Aperture:",
                                                value = exif.aperture ?: "N/A",
                                                modifier = Modifier.weight(1f)
                                            )
                                        }
                                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                            // Shutter Speed
                                            ExifCardItem(
                                                icon = Icons.Default.Speed,
                                                label = "Shutter Speed:",
                                                value = exif.exposure ?: "N/A",
                                                modifier = Modifier.weight(1f)
                                            )
                                            // ISO
                                            ExifCardItem(
                                                icon = Icons.Default.LightMode,
                                                label = "ISO:",
                                                value = exif.iso?.toString() ?: "N/A",
                                                modifier = Modifier.weight(1f)
                                            )
                                        }
                                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                            // Focal Length
                                            ExifCardItem(
                                                icon = Icons.Default.ZoomIn,
                                                label = "Focal Length:",
                                                value = exif.focalLength ?: "N/A",
                                                modifier = Modifier.weight(1f)
                                            )
                                            // Date Taken
                                            ExifCardItem(
                                                icon = Icons.Default.Event,
                                                label = "Date Taken:",
                                                value = formatToLocalTime(exif.dateTimeCreated ?: exif.dateCreated ?: currentPhoto.dateTime ?: currentPhoto.date),
                                                modifier = Modifier.weight(1f)
                                            )
                                        }
                                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                            val fileName = currentPhoto.fileName ?: currentPhoto.title ?: "Unknown"
                                            ExifCardItem(
                                                icon = Icons.Default.InsertDriveFile,
                                                label = "File Name:",
                                                value = fileName,
                                                modifier = Modifier.fillMaxWidth()
                                            )
                                        }
                                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                            val dims = if (currentPhoto.originalWidth != null && currentPhoto.originalHeight != null) {
                                                "${currentPhoto.originalWidth} x ${currentPhoto.originalHeight}"
                                            } else "Unknown"
                                            ExifCardItem(
                                                icon = Icons.Default.AspectRatio,
                                                label = "Dimensions:",
                                                value = dims,
                                                modifier = Modifier.weight(1f)
                                            )
                                            val sizeBytes = currentPhoto.originalSize
                                            val sizeStr = if (sizeBytes != null) {
                                                if (sizeBytes > 1024 * 1024) String.format(java.util.Locale.US, "%.2f MB", sizeBytes / (1024.0 * 1024.0))
                                                else String.format(java.util.Locale.US, "%.2f KB", sizeBytes / 1024.0)
                                            } else "Unknown"
                                            ExifCardItem(
                                                icon = Icons.Default.DataUsage,
                                                label = "File Size:",
                                                value = sizeStr,
                                                modifier = Modifier.weight(1f)
                                            )
                                        }
                                    }
                                },
                                onFailure = { error ->
                                    Text(
                                        text = "Failed to load specifications: ${error.localizedMessage}",
                                        color = MaterialTheme.colorScheme.error,
                                        fontSize = 14.sp
                                    )
                                }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = "Tags",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White.copy(alpha = 0.5f),
                        modifier = Modifier.padding(bottom = 8.dp)
                    )


                    val tags = remember(currentPhoto, updatedKeywordsMap[currentPhoto.imageKey]) {
                        val keywordsStr = updatedKeywordsMap[currentPhoto.imageKey] ?: currentPhoto.keywordsString ?: ""
                        keywordsStr.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                    }

                    if (tags.isNotEmpty()) {
                        com.smugview.app.ui.grid.OptInFlowRow(
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            tags.forEach { tag ->
                                Box(
                                    modifier = Modifier
                                        .padding(4.dp)
                                        .background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(16.dp))
                                        .clickable {
                                            viewModel.selectSingleTag(tag)
                                            showExifSheet = false
                                            onNavigateToKeywordImages()
                                        }
                                        .padding(horizontal = 12.dp, vertical = 6.dp)
                                ) {
                                    Text(text = tag, color = Color.White, fontSize = 11.sp)
                                }
                            }
                        }
                    } else {
                        Text(text = "No tags available.", color = Color.White.copy(alpha = 0.3f), fontSize = 12.sp)
                    }

                    // Add Tag section removed as requested

                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
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

fun sharePhotoLink(context: Context, photo: AlbumImageData) {
    val shareUrl = photo.webUri ?: photo.archivedUri ?: return
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, shareUrl)
    }
    context.startActivity(Intent.createChooser(intent, "Share Photo Link"))
}

fun sharePhoto(context: Context, coroutineScope: kotlinx.coroutines.CoroutineScope, photo: AlbumImageData) {
    val imageUrl = photo.archivedUri ?: photo.thumbnailUrl ?: return
    val webLink = photo.webUri ?: photo.archivedUri ?: ""

    Toast.makeText(context, "Preparing image for sharing...", Toast.LENGTH_SHORT).show()

    coroutineScope.launch(Dispatchers.IO) {
        try {
            val url = URL(imageUrl)
            val connection = url.openConnection() as HttpURLConnection
            connection.connect()
            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val inputStream = connection.inputStream
                val sharedDir = File(context.cacheDir, "shared_images").apply { mkdirs() }
                val file = File(sharedDir, "shared_image_${photo.imageKey}.jpg")
                val outputStream = FileOutputStream(file)
                val buffer = ByteArray(4096)
                var bytesRead: Int
                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    outputStream.write(buffer, 0, bytesRead)
                }
                outputStream.flush()
                outputStream.close()
                inputStream.close()

                val fileUri = androidx.core.content.FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file
                )

                val imageDescription = photo.fileName?.takeIf { it.isNotBlank() }
                    ?: getFileNameFromUrl(imageUrl, photo.imageKey)

                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "image/jpeg"
                    putExtra(Intent.EXTRA_STREAM, fileUri)
                    putExtra(Intent.EXTRA_TEXT, webLink)
                    putExtra(Intent.EXTRA_SUBJECT, imageDescription)
                    clipData = ClipData.newRawUri("", fileUri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }

                withContext(Dispatchers.Main) {
                    val chooser = Intent.createChooser(intent, "Share Photo").apply {
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(chooser)
                }
            } else {
                withContext(Dispatchers.Main) {
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, webLink)
                    }
                    context.startActivity(Intent.createChooser(intent, "Share Link"))
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            withContext(Dispatchers.Main) {
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, webLink)
                }
                context.startActivity(Intent.createChooser(intent, "Share Link"))
            }
        }
    }
}

fun getFileNameFromUrl(url: String, fallbackKey: String): String {
    val segment = url.substringAfterLast('/').substringBefore('?')
    if (segment.isEmpty()) return "shared_image_$fallbackKey.jpg"
    val decodedSegment = try {
        java.net.URLDecoder.decode(segment, "UTF-8")
    } catch (e: Exception) {
        segment
    }
    return decodedSegment.replace(Regex("[\\\\/:*?\"<>|]"), "_")
}

fun getBaseNameFromUrl(url: String, fallbackKey: String): String {
    val segment = url.substringAfterLast('/').substringBefore('?')
    if (segment.isEmpty()) return "smugview_$fallbackKey"
    
    val decodedSegment = try {
        java.net.URLDecoder.decode(segment, "UTF-8")
    } catch (e: Exception) {
        segment
    }
    
    val baseName = if (decodedSegment.contains('.')) {
        decodedSegment.substringBeforeLast('.')
    } else {
        decodedSegment
    }
    
    return baseName.replace(Regex("[\\\\/:*?\"<>|]"), "_")
}

// Background utility to download files directly to user's public folder
suspend fun downloadPhotoToGallery(context: Context, photo: AlbumImageData, viewModel: SmugViewModel) {
    var detailedPhoto = photo
    if (detailedPhoto.archivedUri == null) {
        withContext(Dispatchers.Main) {
            Toast.makeText(context, "Fetching high-resolution image details...", Toast.LENGTH_SHORT).show()
        }
        try {
            val result = viewModel.getImageDetails(photo.imageKey).first { it != null }
            if (result != null && result.isSuccess) {
                detailedPhoto = result.getOrThrow()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    val downloadUrl = detailedPhoto.archivedUri ?: detailedPhoto.thumbnailUrl?.replace("/Th/", "/L/")?.replace("/th/", "/l/") ?: return
    withContext(Dispatchers.IO) {
        try {
            val url = URL(downloadUrl)
            val connection = url.openConnection() as HttpURLConnection
            connection.connect()

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val inputStream: java.io.InputStream = connection.inputStream
                val path = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val baseName = getBaseNameFromUrl(downloadUrl, detailedPhoto.imageKey)
                val file = File(path, "$baseName.jpg")
                val outputStream = FileOutputStream(file)

                val buffer = ByteArray(4096)
                var bytesRead: Int
                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    outputStream.write(buffer, 0, bytesRead)
                }
                outputStream.flush()
                outputStream.close()
                inputStream.close()

                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Saved to Downloads: ${file.name}", Toast.LENGTH_LONG).show()
                }
            } else {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Failed to download image", Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "Error: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
            }
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

fun getAuthenticatedMediaUrl(url: String?, albumKey: String, viewModel: SmugViewModel): String {
    if (url.isNullOrEmpty()) return ""
    val apiKey = com.smugview.app.BuildConfig.SMUGMUG_API_KEY
    val password = viewModel.getUnlockedPasswordSync(albumKey)
    
    val uri = android.net.Uri.parse(url)
    val builder = uri.buildUpon()
    
    if (uri.getQueryParameter("APIKey") == null) {
        builder.appendQueryParameter("APIKey", apiKey)
    }
    if (password != null && uri.getQueryParameter("Password") == null) {
        builder.appendQueryParameter("Password", password)
    }
    
    return builder.build().toString()
}

