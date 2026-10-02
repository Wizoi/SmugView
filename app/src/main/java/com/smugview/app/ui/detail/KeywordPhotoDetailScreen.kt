package com.smugview.app.ui.detail

import android.content.res.Configuration
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.imageLoader
import coil.request.ImageRequest
import com.smugview.app.ui.component.AddToCollectionsDialog
import com.smugview.app.ui.theme.SurfaceGlass
import com.smugview.app.ui.viewmodel.SmugViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

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

    DisposableEffect(Unit) {
        viewModel.setViewingDetail(true)
        onDispose { viewModel.setViewingDetail(false) }
    }

    val totalCount = keywordPhotos.size
    val pagerState = rememberPagerState(
        initialPage = if (initialIndex in 0 until totalCount) initialIndex else 0,
        pageCount = { totalCount }
    )
    val currentPhoto = remember(keywordPhotos, pagerState.currentPage) {
        keywordPhotos.getOrNull(pagerState.currentPage)
    }

    val detailedPhotoState = remember(currentPhoto?.imageKey) {
        currentPhoto?.imageKey?.let { viewModel.getImageDetails(it) }
            ?: kotlinx.coroutines.flow.MutableStateFlow(null)
    }.collectAsState()
    val detailedPhoto = detailedPhotoState.value?.getOrNull()

    LaunchedEffect(pagerState.currentPage, keywordPhotos.size) {
        val imageLoader = context.imageLoader
        listOf(pagerState.currentPage, pagerState.currentPage - 1, pagerState.currentPage + 1).forEach { index ->
            keywordPhotos.getOrNull(index)?.let { photo ->
                viewModel.getImageDetails(photo.imageKey)
                val url = photo.thumbnailUrl?.replace("/Th/", "/L/")?.replace("/th/", "/l/")
                    ?.replace("-Th.", "-L.")?.replace("-th.", "-l.") ?: photo.archivedUri
                if (url != null) imageLoader.enqueue(ImageRequest.Builder(context).data(url).build())
            }
        }
    }

    val animatedBgColor = rememberDominantBackgroundColor(
        currentPhoto?.thumbnailUrl, defaultColor = Color.DarkGray, targetAlpha = 0.25f, durationMs = 650
    )

    var showExifSheet by remember { mutableStateOf(false) }
    var showAddToCollectionDialog by remember { mutableStateOf(false) }
    var showControls by remember { mutableStateOf(false) }
    var isResolvingAlbum by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        Box(
            modifier = Modifier.fillMaxSize().background(
                Brush.radialGradient(colors = listOf(animatedBgColor, Color.Black), radius = 1200f)
            )
        )

        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize(), pageSpacing = 16.dp) { page ->
            val photo = keywordPhotos.getOrNull(page)
            if (photo != null) {
                ImmersivePhotoPage(
                    photo = photo,
                    isActive = pagerState.currentPage == page,
                    fallbackAlbumKey = "",
                    localCollections = localCollections,
                    viewModel = viewModel,
                    onToggleControls = { showControls = !showControls },
                    onLongPress = { showExifSheet = true },
                    onRequestAddToCollection = { showAddToCollectionDialog = true }
                )
            }
        }

        // Top bar: Back + Jump to Gallery
        AnimatedVisibility(
            visible = showControls,
            enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBackClick, modifier = Modifier.clip(CircleShape).background(SurfaceGlass)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                }
                if (currentPhoto != null) {
                    Button(
                        onClick = {
                            val immediate = detailedPhoto?.uris?.album?.substringAfterLast("/")
                                ?: detailedPhoto?.uris?.imageAlbum?.substringAfterLast("/")
                                ?: currentPhoto.uris?.album?.substringAfterLast("/")
                                ?: currentPhoto.uris?.imageAlbum?.substringAfterLast("/")
                                ?: ""
                            if (immediate.isNotEmpty()) {
                                onNavigateToGallery(immediate, currentPhoto.imageKey)
                            } else if (!isResolvingAlbum) {
                                // Keyword photos come from image!search, which omits the per-image
                                // album reference — so the album is only known once the full image
                                // details load. Resolve it on demand instead of failing the tap.
                                val imageKey = currentPhoto.imageKey
                                scope.launch {
                                    isResolvingAlbum = true
                                    val resolved = withTimeoutOrNull(8000) {
                                        viewModel.getImageDetails(imageKey)
                                            .map { result ->
                                                val d = result?.getOrNull()
                                                d?.uris?.album?.substringAfterLast("/")
                                                    ?: d?.uris?.imageAlbum?.substringAfterLast("/") ?: ""
                                            }
                                            .first { it.isNotEmpty() }
                                    }
                                    isResolvingAlbum = false
                                    if (!resolved.isNullOrEmpty()) {
                                        onNavigateToGallery(resolved, imageKey)
                                    } else {
                                        Toast.makeText(context, "Couldn't find this photo's gallery. Please try again.", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = SurfaceGlass),
                        shape = RoundedCornerShape(16.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        if (isResolvingAlbum) {
                            CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                        } else {
                            Icon(Icons.Default.FolderOpen, "Jump to Gallery", tint = Color.White, modifier = Modifier.size(18.dp))
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Jump to Gallery", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // Bottom block: title + action capsule + camera caption
        val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
        if (!isLandscape && currentPhoto != null) {
            val cameraDetails = rememberCameraDetails(currentPhoto.imageKey, viewModel)
            AnimatedVisibility(
                visible = showControls,
                enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
                modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter)
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 16.dp, start = 16.dp, end = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    val photoTitle = (currentPhoto.title ?: currentPhoto.caption ?: "").trim()
                    if (photoTitle.isNotEmpty()) {
                        Text(
                            text = photoTitle, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp,
                            textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(bottom = 12.dp)
                                .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                                .padding(horizontal = 16.dp, vertical = 6.dp)
                        )
                    }
                    PhotoActionCapsule(
                        photo = currentPhoto, localCollections = localCollections, viewModel = viewModel, scope = scope,
                        onShowExif = { showExifSheet = true },
                        onRequestAddToCollection = { showAddToCollectionDialog = true }
                    )
                    if (cameraDetails.isNotEmpty()) {
                        Text(
                            text = cameraDetails, color = Color.White.copy(alpha = 0.5f), fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(top = 10.dp)
                                .background(Color.Black.copy(alpha = 0.4f), RoundedCornerShape(4.dp))
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                }
            }
        }

        if (showExifSheet && currentPhoto != null) {
            PhotoExifSheet(
                currentPhoto = currentPhoto, detailedPhoto = detailedPhoto, viewModel = viewModel,
                onDismiss = { showExifSheet = false }, onNavigateToKeywordImages = onNavigateToKeywordImages
            )
        }

        if (showAddToCollectionDialog && currentPhoto != null) {
            AddToCollectionsDialog(
                type = "Image", itemKey = currentPhoto.imageKey,
                title = currentPhoto.title ?: currentPhoto.caption ?: "Untitled Photo",
                albumKeyResolver = { viewModel.albumKeyFor(currentPhoto) }, albumTitle = "Gallery",
                thumbnailUrl = currentPhoto.thumbnailUrl, imageUrl = currentPhoto.archivedUri ?: currentPhoto.thumbnailUrl,
                onDismissRequest = { showAddToCollectionDialog = false }, viewModel = viewModel
            )
        }
    }
}
