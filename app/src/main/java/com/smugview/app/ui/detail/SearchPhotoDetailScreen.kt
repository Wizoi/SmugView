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
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.paging.compose.collectAsLazyPagingItems
import coil.imageLoader
import coil.request.ImageRequest
import com.smugview.app.ui.component.AddToCollectionsDialog
import com.smugview.app.ui.theme.DarkSystemBars
import com.smugview.app.ui.theme.SurfaceGlass
import com.smugview.app.ui.viewmodel.SmugViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SearchPhotoDetailScreen(
    targetImageKey: String,
    initialIndex: Int,
    onBackClick: () -> Unit,
    onNavigateToPhotoDetail: (albumKey: String, imageKey: String) -> Unit,
    onNavigateToGallery: (albumKey: String, imageKey: String) -> Unit,
    onNavigateToKeywordImages: () -> Unit,
    viewModel: SmugViewModel = hiltViewModel()
) {
    val pagingFlow by viewModel.searchPhotosPagingFlow.collectAsState()
    val searchPhotos = pagingFlow.collectAsLazyPagingItems()
    val localCollections by viewModel.localCollections.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    if (searchPhotos.itemCount == 0) {
        Box(
            modifier = Modifier.fillMaxSize().background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            Text("No search results to display", color = Color.White.copy(alpha = 0.5f))
        }
        return
    }

    val pagerState = rememberPagerState(initialPage = initialIndex, pageCount = { searchPhotos.itemCount })
    val currentPhoto = if (pagerState.currentPage < searchPhotos.itemCount) searchPhotos[pagerState.currentPage] else null

    val detailedPhotoState = remember(currentPhoto?.imageKey) {
        currentPhoto?.imageKey?.let { viewModel.getImageDetails(it) }
            ?: kotlinx.coroutines.flow.MutableStateFlow(null)
    }.collectAsState()
    val detailedPhoto = detailedPhotoState.value?.getOrNull()

    // Preload current + neighbor images and their full metadata.
    LaunchedEffect(pagerState.currentPage, searchPhotos.itemCount) {
        val imageLoader = context.imageLoader
        listOf(pagerState.currentPage, pagerState.currentPage - 1, pagerState.currentPage + 1).forEach { index ->
            if (index in 0 until searchPhotos.itemCount) {
                searchPhotos[index]?.let { photo ->
                    viewModel.getImageDetails(photo.imageKey)
                    val url = photo.thumbnailUrl?.replace("/Th/", "/L/")?.replace("/th/", "/l/")
                        ?.replace("-Th.", "-L.")?.replace("-th.", "-l.") ?: photo.archivedUri
                    if (url != null) imageLoader.enqueue(ImageRequest.Builder(context).data(url).build())
                }
            }
        }
    }

    val animatedBgColor = rememberDominantBackgroundColor(currentPhoto?.thumbnailUrl)

    var showExifSheet by remember { mutableStateOf(false) }
    var showAddToCollectionDialog by remember { mutableStateOf(false) }
    var showControls by remember { mutableStateOf(false) }
    var isResolvingAlbum by remember { mutableStateOf(false) }

    DarkSystemBars()

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        Box(
            modifier = Modifier.fillMaxSize().background(
                Brush.radialGradient(colors = listOf(animatedBgColor, Color.Black), radius = 1200f)
            )
        )

        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize(), pageSpacing = 16.dp) { page ->
            val photo = if (page < searchPhotos.itemCount) searchPhotos[page] else null
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
                                // Search photos come from image!search, which omits the per-image
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

        // Bottom block: title + action capsule + footer
        val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
        if (!isLandscape && currentPhoto != null) {
            val nickname by viewModel.activeNickname.collectAsState()
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
                    Spacer(modifier = Modifier.height(16.dp))
                    val footerText = if (photoTitle.isNotEmpty()) {
                        "Image ${pagerState.currentPage + 1} of ${searchPhotos.itemCount} • $photoTitle • Photo by ${nickname ?: "Owner"}"
                    } else {
                        "Image ${pagerState.currentPage + 1} of ${searchPhotos.itemCount} • Photo by ${nickname ?: "Owner"}"
                    }
                    Text(footerText, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (cameraDetails.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(cameraDetails, color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp,
                            textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
