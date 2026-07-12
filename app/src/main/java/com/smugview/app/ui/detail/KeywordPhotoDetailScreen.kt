package com.smugview.app.ui.detail

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Environment
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.DataUsage
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Lens
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Iso
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.palette.graphics.Palette
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.smugview.app.data.api.AlbumImageData
import com.smugview.app.data.api.isVideo
import com.smugview.app.ui.component.AddToCollectionsDialog
import com.smugview.app.ui.theme.DeepDarkBackground
import com.smugview.app.ui.theme.NeonBlue
import com.smugview.app.ui.theme.SurfaceDark
import com.smugview.app.ui.theme.SurfaceGlass
import com.smugview.app.ui.viewmodel.SmugViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun KeywordPhotoDetailScreen(
    targetImageKey: String,
    initialIndex: Int,
    viewModel: SmugViewModel,
    onBackClick: () -> Unit,
    onNavigateToKeywordImages: () -> Unit,
    onNavigateToGallery: (albumKey: String, imageKey: String) -> Unit = { _, _ -> }
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val keywordPhotos by viewModel.tagFilteredPhotos.collectAsState()
    val localCollections by viewModel.localCollections.collectAsState(initial = emptyList())
    val updatedKeywordsMap = remember { mutableStateMapOf<String, String>() }

    // 1. Initial State Resolution
    val totalCount = keywordPhotos.size
    val pagerState = rememberPagerState(
        initialPage = if (initialIndex in 0 until totalCount) initialIndex else 0,
        pageCount = { totalCount }
    )

    val currentPhoto = remember(keywordPhotos, pagerState.currentPage) {
        if (pagerState.currentPage in keywordPhotos.indices) {
            keywordPhotos[pagerState.currentPage]
        } else null
    }

    val detailedPhotoState = remember(currentPhoto?.imageKey) {
        currentPhoto?.imageKey?.let { viewModel.getImageDetails(it) }
            ?: kotlinx.coroutines.flow.MutableStateFlow(null)
    }.collectAsState()
    val detailedPhoto = detailedPhotoState.value?.getOrNull()

    // 2. Fetch Large Image Details on demand and preload neighboring images
    LaunchedEffect(pagerState.currentPage, keywordPhotos.size) {
        val imageLoader = context.imageLoader
        val prevIndex = pagerState.currentPage - 1
        val nextIndex = pagerState.currentPage + 1
        listOf(pagerState.currentPage, prevIndex, nextIndex).forEach { index ->
            if (index in keywordPhotos.indices) {
                val photo = keywordPhotos[index]
                if (photo != null) {
                    viewModel.getImageDetails(photo.imageKey)

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

    // 3. Adaptive Aesthetic Palette Background Generation
    var dominantColor by remember { mutableStateOf(Color.DarkGray) }
    val animatedBgColor by animateColorAsState(
        targetValue = dominantColor.copy(alpha = 0.25f),
        animationSpec = tween(durationMillis = 650),
        label = "BgColorAnimation"
    )

    LaunchedEffect(currentPhoto?.thumbnailUrl) {
        currentPhoto?.thumbnailUrl?.let { url ->
            withContext(Dispatchers.IO) {
                try {
                    val loader = context.imageLoader
                    val req = ImageRequest.Builder(context)
                        .data(url)
                        .allowHardware(false)
                        .build()
                    val result = loader.execute(req)
                    if (result is SuccessResult) {
                        val bitmap = (result.drawable as? BitmapDrawable)?.bitmap
                        if (bitmap != null) {
                            Palette.from(bitmap).generate().let { palette ->
                                dominantColor = Color(palette.getDominantColor(Color.DarkGray.toArgb()))
                            }
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
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
        // Dynamic Radial Gradient Overlay
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
            val photo = if (page in keywordPhotos.indices) keywordPhotos[page] else null
            if (photo != null) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    if (photo.isVideo) {
                        val rawUrl = photo.videoUrl ?: photo.archivedUri ?: photo.thumbnailUrl ?: ""
                        val pageDetailedState by viewModel.getImageDetails(photo.imageKey).collectAsState()
                        val pageDetailed = pageDetailedState?.getOrNull()
                        val photoAlbumKey = pageDetailed?.uris?.album?.substringAfterLast("/")
                            ?: photo.uris?.album?.substringAfterLast("/")
                            ?: ""
                        val videoUrl = remember(rawUrl, photoAlbumKey) {
                            getAuthenticatedMediaUrl(rawUrl, photoAlbumKey, viewModel)
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

        // Navigation Top Bar
        AnimatedVisibility(
            visible = showControls,
            enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(16.dp),
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

                // Jump to Gallery Button
                if (currentPhoto != null) {
                    Button(
                        onClick = {
                            val photoAlbumKey = detailedPhoto?.uris?.album?.substringAfterLast("/")
                                ?: detailedPhoto?.uris?.imageAlbum?.substringAfterLast("/")
                                ?: currentPhoto.uris?.album?.substringAfterLast("/")
                                ?: currentPhoto.uris?.imageAlbum?.substringAfterLast("/")
                                ?: ""
                            if (photoAlbumKey.isNotEmpty()) {
                                onNavigateToGallery(photoAlbumKey, currentPhoto.imageKey)
                            } else {
                                val reason = if (detailedPhoto == null) "Loading details..." else "Album key could not be resolved."
                                Toast.makeText(context, "Gallery Navigation Failed: $reason", Toast.LENGTH_SHORT).show()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = SurfaceGlass),
                        shape = RoundedCornerShape(16.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.FolderOpen,
                            contentDescription = "Jump to Gallery",
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Jump to Gallery",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        // Floating Action Capsule & Text Details Block
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

                    Row(
                        modifier = Modifier
                            .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(32.dp))
                            .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(32.dp))
                            .padding(horizontal = 24.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(28.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
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

                        IconButton(
                            onClick = {
                                if (currentPhoto.webUri != null || currentPhoto.archivedUri != null) {
                                    sharePhoto(context, scope, currentPhoto)
                                } else {
                                    Toast.makeText(context, "Loading photo details, please wait...", Toast.LENGTH_SHORT).show()
                                }
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Default.Share,
                                contentDescription = "Share Link",
                                tint = Color.White,
                                modifier = Modifier.size(26.dp)
                            )
                        }

                        IconButton(
                            onClick = {
                                if (currentPhoto != null) {
                                    scope.launch {
                                        downloadPhotoToGallery(context, currentPhoto, viewModel)
                                    }
                                }
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Default.Download,
                                contentDescription = "Download Photo",
                                tint = Color.White,
                                modifier = Modifier.size(26.dp)
                            )
                        }

                        IconButton(onClick = { showExifSheet = true }) {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = "Metadata Specs",
                                tint = Color.White,
                                modifier = Modifier.size(26.dp)
                            )
                        }
                    }

                    if (cameraDetails.isNotEmpty()) {
                        Text(
                            text = cameraDetails,
                            color = Color.White.copy(alpha = 0.5f),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier
                                .padding(top = 10.dp)
                                .background(Color.Black.copy(alpha = 0.4f), RoundedCornerShape(4.dp))
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                }
            }
        }

        // EXIF Bottom Sheet Overlay
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
                            .padding(vertical = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
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
                                onClick = {
                                    if (currentPhoto.webUri != null || currentPhoto.archivedUri != null) {
                                        sharePhoto(context, scope, currentPhoto)
                                    } else {
                                        Toast.makeText(context, "Loading photo details, please wait...", Toast.LENGTH_SHORT).show()
                                    }
                                },
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
                                    val photoAlbumKey = detailedPhoto?.uris?.album?.substringAfterLast("/")
                                        ?: detailedPhoto?.uris?.imageAlbum?.substringAfterLast("/")
                                        ?: currentPhoto?.uris?.album?.substringAfterLast("/")
                                        ?: currentPhoto?.uris?.imageAlbum?.substringAfterLast("/")
                                        ?: ""
                                    if (photoAlbumKey.isNotEmpty()) {
                                        onNavigateToGallery(photoAlbumKey, currentPhoto.imageKey)
                                    } else {
                                        Toast.makeText(context, "Loading photo details, please wait...", Toast.LENGTH_SHORT).show()
                                    }
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

                    Text(text = "EXIF Metadata", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(16.dp))

                    when (val exifData = exifState) {
                        null -> {
                            Box(modifier = Modifier.fillMaxWidth().height(100.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(color = NeonBlue)
                            }
                        }
                        else -> {
                            val data = exifData.getOrNull()
                            if (data == null) {
                                Text(text = "Metadata unavailable for this photo", color = Color.White.copy(alpha = 0.5f))
                            } else {
                                Column(
                                    verticalArrangement = Arrangement.spacedBy(10.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        val cam = data.camera
                                        ExifCardItem(
                                            icon = Icons.Default.CameraAlt,
                                            label = "Camera Model",
                                            value = cam ?: "Unknown",
                                            modifier = Modifier.weight(1f),
                                            iconContent = if (!cam.isNullOrEmpty()) {
                                                { CameraLogo(data.make ?: cam) }
                                            } else null
                                        )
                                        ExifCardItem(
                                            icon = Icons.Default.Lens,
                                            label = "Lens / Focal",
                                            value = data.focalLength ?: "Unknown",
                                            modifier = Modifier.weight(1f)
                                        )
                                    }

                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        ExifCardItem(
                                            icon = Icons.Default.LightMode,
                                            label = "Aperture",
                                            value = data.aperture ?: "Unknown",
                                            modifier = Modifier.weight(1f)
                                        )
                                        ExifCardItem(
                                            icon = Icons.Default.Speed,
                                            label = "Exposure Time",
                                            value = data.exposure ?: "Unknown",
                                            modifier = Modifier.weight(1f)
                                        )
                                    }

                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        ExifCardItem(
                                            icon = Icons.Default.Iso,
                                            label = "ISO Speed",
                                            value = data.iso?.toString() ?: "Unknown",
                                            modifier = Modifier.weight(1f)
                                        )
                                        ExifCardItem(
                                            icon = Icons.Default.ZoomIn,
                                            label = "Original Dimensions",
                                            value = run {
                                                val w = detailedPhoto?.originalWidth ?: currentPhoto.originalWidth
                                                val h = detailedPhoto?.originalHeight ?: currentPhoto.originalHeight
                                                if (w != null && h != null) "$w x $h" else "Unknown"
                                            },
                                            modifier = Modifier.weight(1f)
                                        )
                                    }

                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        ExifCardItem(
                                            icon = Icons.Default.Event,
                                            label = "Date Taken",
                                            value = formatToLocalTime(data.dateTimeCreated ?: data.dateCreated ?: currentPhoto.date ?: currentPhoto.dateTime),
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    HorizontalDivider(color = Color.White.copy(alpha = 0.1f), modifier = Modifier.padding(vertical = 12.dp))

                    // Metadata details
                    Text(
                        text = "Metadata Specs",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White.copy(alpha = 0.5f),
                        modifier = Modifier.padding(bottom = 8.dp)
                    )

                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        val fileName = detailedPhoto?.fileName ?: currentPhoto.fileName ?: currentPhoto.title ?: "Unknown"
                        if (fileName.isNotEmpty()) {
                            ExifCardItem(
                                icon = Icons.Default.InsertDriveFile,
                                label = "File Name",
                                value = fileName,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            val sizeBytes = detailedPhoto?.originalSize ?: currentPhoto.originalSize
                            val sizeStr = if (sizeBytes != null) {
                                if (sizeBytes > 1024 * 1024) String.format(java.util.Locale.US, "%.2f MB", sizeBytes / (1024.0 * 1024.0))
                                else String.format(java.util.Locale.US, "%.2f KB", sizeBytes / 1024.0)
                            } else "Unknown"
                            ExifCardItem(
                                icon = Icons.Default.DataUsage,
                                label = "File Size",
                                value = sizeStr,
                                modifier = Modifier.weight(1f)
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Tags",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White.copy(alpha = 0.5f),
                            modifier = Modifier.padding(bottom = 8.dp)
                        )

                        val tags = remember(currentPhoto, detailedPhoto, updatedKeywordsMap[currentPhoto.imageKey]) {
                            val keywordsStr = updatedKeywordsMap[currentPhoto.imageKey]
                                ?: detailedPhoto?.keywordsString
                                ?: currentPhoto.keywordsString
                                ?: ""
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
                                                // Clear selected tags, select ONLY this clicked tag, and navigate to keyword images search page
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
                    }
                }
            }
        }

        // Add to Collection Dialog overlay
        if (showAddToCollectionDialog && currentPhoto != null) {
            val photoAlbumKey = currentPhoto.uris?.album?.substringAfterLast("/") ?: ""
            AddToCollectionsDialog(
                type = "Image",
                itemKey = currentPhoto.imageKey,
                title = currentPhoto.title ?: currentPhoto.caption ?: "Untitled Photo",
                albumKey = photoAlbumKey,
                albumTitle = "Gallery",
                thumbnailUrl = currentPhoto.thumbnailUrl,
                imageUrl = currentPhoto.archivedUri ?: currentPhoto.thumbnailUrl,
                onDismissRequest = { showAddToCollectionDialog = false },
                viewModel = viewModel
            )
        }
    }
}
