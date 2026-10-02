package com.smugview.app.ui.grid

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.window.Popup
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.launch
import com.smugview.app.data.api.isVideo
import com.smugview.app.data.db.CachedNode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalConfiguration
import android.content.Intent
import android.widget.Toast
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import coil.compose.AsyncImage
import com.smugview.app.BuildConfig
import coil.imageLoader
import coil.request.ImageRequest
import com.smugview.app.ui.component.AddToCollectionsDialog
import com.smugview.app.data.api.AlbumImageData
import com.smugview.app.ui.theme.DeepDarkBackground
import com.smugview.app.ui.theme.GlowBorder
import com.smugview.app.ui.theme.NeonBlue
import com.smugview.app.ui.theme.SoftRed
import com.smugview.app.ui.theme.SurfaceDark
import com.smugview.app.ui.text.ProblemAction
import com.smugview.app.ui.text.UserMessages
import com.smugview.app.ui.viewmodel.SmugViewModel
import com.smugview.app.ui.browser.BreadcrumbBar

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PhotoGridScreen(
    albumKey: String,
    albumTitle: String,
    onNavigateToPhotoDetail: (imageKey: String) -> Unit,
    onBackClick: () -> Unit,
    onNavigateToFolder: () -> Unit,
    onNavigateToCastController: (String) -> Unit,
    viewModel: SmugViewModel = hiltViewModel()
) {
    LaunchedEffect(albumKey) {
        viewModel.selectAlbum(albumKey)
    }

    val lazyPhotos = viewModel.photosFlow.collectAsLazyPagingItems()

    var showFilterSheet by remember { mutableStateOf(false) }
    var showAlbumBookmarkDialog by remember { mutableStateOf(false) }
    var showAlbumShareDialog by remember { mutableStateOf(false) }
    val currentAlbumStyle = viewModel.currentAlbumStyle
    val sortBy by viewModel.sortBy.collectAsState()
    val album by viewModel.albumState.collectAsState()
    val filterType by viewModel.filterType.collectAsState()
    val includedTags by viewModel.includedTags.collectAsState()
    val excludedTags by viewModel.excludedTags.collectAsState()
    val notice by viewModel.albumNotice.collectAsState()
    // Design 6-4: one pure function decides what the screen shows (loading, failed, empty, filtered out, photos + banner).
    val gridView = GridView.of(
        rawCount = album?.photos?.size ?: 0,
        shownCount = lazyPhotos.itemCount,
        loading = album?.loading ?: true,
        problem = album?.problem,
        complete = album?.complete ?: false,
        notice = notice,
        expected = album?.expected,
        filtersActive = filterType != com.smugview.app.ui.viewmodel.GalleryFilterType.ALL ||
            includedTags.isNotEmpty() || excludedTags.isNotEmpty()
    )

    val staggeredGridState = rememberLazyStaggeredGridState()
    val gridState = rememberLazyGridState()

    // R-48: back to the top only when the sort or the filter CHANGED since the last time this ran. This effect also runs on
    // every return from the viewer (the screen is composed again), and that must keep the position the lazy states restored.
    val sortFilterKey = "$sortBy|$filterType"
    var appliedSortFilter by rememberSaveable { mutableStateOf(sortFilterKey) }
    LaunchedEffect(sortFilterKey) {
        if (sortFilterKey != appliedSortFilter) {
            staggeredGridState.scrollToItem(0)
            gridState.scrollToItem(0)
            appliedSortFilter = sortFilterKey
        }
    }

    val styleClean = currentAlbumStyle?.lowercase()?.replace(" ", "") ?: ""
    val isStaggered = remember(styleClean) {
        when {
            styleClean == "squares" || styleClean == "thumbnails" -> false
            else -> true
        }
    }

    val context = LocalContext.current
    LaunchedEffect(lazyPhotos.itemCount, currentAlbumStyle) {
        val imageLoader = context.imageLoader
        val preloadLimit = minOf(lazyPhotos.itemCount, 24)
        for (i in 0 until preloadLimit) {
            val photo = lazyPhotos[i]
            if (photo != null) {
                val targetSize = when {
                    styleClean == "squares" || styleClean == "thumbnails" -> "S"
                    else -> "M"
                }
                val url = photo.thumbnailUrl?.replace("/Th/", "/$targetSize/")
                    ?.replace("/th/", "/${targetSize.lowercase()}/")
                    ?.replace("-Th.", "-$targetSize.")
                    ?.replace("-th.", "-${targetSize.lowercase()}.")
                if (url != null) {
                    val request = ImageRequest.Builder(context)
                        .data(url)
                        .build()
                    imageLoader.enqueue(request)
                }
            }
        }
    }

    Scaffold(
        containerColor = DeepDarkBackground
    ) { paddingValues ->
        var coverUrl by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(albumKey, lazyPhotos.itemCount) {
            val node = viewModel.getNodeByAlbumKey(albumKey)
            if (node?.highlightImageUrl != null) {
                coverUrl = node.highlightImageUrl
            } else {
                // Read the list once, as an immutable snapshot. Not `itemCount` then `lazyPhotos[0]`: `get` reads the live
                // paging presenter, which a type filter that hides every photo empties before the count is published, and this
                // resumes from a Room lookup, so the two can disagree ("Index: 0, Size: 0").
                val photos = lazyPhotos.itemSnapshotList
                if (photos.isNotEmpty()) coverUrl = photos[0]?.thumbnailUrl
            }
        }

        val scrollOffset = remember(isStaggered) {
            derivedStateOf {
                if (isStaggered) {
                    if (staggeredGridState.firstVisibleItemIndex == 0) {
                        staggeredGridState.firstVisibleItemScrollOffset
                    } else {
                        10000
                    }
                } else {
                    if (gridState.firstVisibleItemIndex == 0) {
                        gridState.firstVisibleItemScrollOffset
                    } else {
                        10000
                    }
                }
            }
        }

        val configuration = LocalConfiguration.current
        val isLandscape = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val staggeredColumns = if (isLandscape) 4 else 2
        val gridColumns = if (isLandscape) 6 else 3
        val headerHeight = if (isLandscape) (configuration.screenHeightDp.dp / 4) else (configuration.screenHeightDp.dp / 6)
        val density = LocalDensity.current
        val headerHeightPx = remember(headerHeight) { with(density) { headerHeight.toPx() } }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            val folderNavStack = viewModel.folderNavigationStack
            val albumNode = remember(albumTitle) {
                com.smugview.app.data.db.CachedNode(
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
            val fullNavStack = remember(folderNavStack.toList(), albumTitle) {
                folderNavStack + albumNode
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
                    }
                }
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
            // 1. Grid Container (drawn bottom-most, so photos scroll behind the header banner and top buttons bar)
            Box(
                modifier = Modifier
                    .fillMaxSize()
            ) {
                // Main Grid or Loading/Empty States
                when (val view = gridView) {
                GridView.Loading -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                        CircularProgressIndicator(
                            color = NeonBlue,
                            modifier = Modifier.padding(top = 220.dp)
                        )
                    }
                }
                is GridView.Failed -> {
                    val problem = view.problem
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
                        // Q4 (6-11): a gallery SmugMug no longer has stays in the user's collections until the user says so.
                        val goneScope = rememberCoroutineScope()
                        var bookmarked by remember(albumKey, problem) { mutableStateOf(false) }
                        var removeQuestion by remember(albumKey, problem) { mutableStateOf<String?>(null) }
                        if (problem is com.smugview.app.ui.text.Problem.Gone) {
                            LaunchedEffect(albumKey, problem) { bookmarked = viewModel.isBookmarkedAnywhere("Album", albumKey) }
                        }
                        removeQuestion?.let { text ->
                            com.smugview.app.ui.browser.DeleteCollectionDialog(
                                text = text,
                                onConfirm = {
                                    viewModel.removeBookmarkGlobally(albumKey)
                                    bookmarked = false
                                    removeQuestion = null
                                },
                                onDismiss = { removeQuestion = null }
                            )
                        }
                        Spacer(modifier = Modifier.height(24.dp))
                        if (problem is com.smugview.app.ui.text.Problem.Gone && bookmarked) {
                            TextButton(
                                onClick = {
                                    goneScope.launch {
                                        val confirm = viewModel.removeGalleryConfirm(albumKey, albumTitle)
                                        if (confirm == null) {
                                            viewModel.removeBookmarkGlobally(albumKey)
                                            bookmarked = false
                                        } else {
                                            removeQuestion = confirm.text
                                        }
                                    }
                                }
                            ) {
                                Text(UserMessages.REMOVE_FROM_COLLECTIONS, color = SoftRed)
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                        }
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
                                // Try again and Enter password both reload; a locked gallery asks for its password again.
                                onClick = {
                                    if (primary == ProblemAction.GoBack) onBackClick()
                                    else viewModel.selectAlbum(albumKey, force = true)
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
                            fontSize = 16.sp,
                            color = Color.White.copy(alpha = 0.7f),
                            textAlign = TextAlign.Center
                        )
                        if (view == GridView.FilterEmpty) {
                            Spacer(modifier = Modifier.height(16.dp))
                            Button(
                                onClick = {
                                    viewModel.updateFilterType(com.smugview.app.ui.viewmodel.GalleryFilterType.ALL)
                                    viewModel.clearAllTags()
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = NeonBlue)
                            ) {
                                Text(UserMessages.BUTTON_SHOW_ALL)
                            }
                        }
                    }
                }
                GridView.Blank -> Box(modifier = Modifier.fillMaxSize())
                is GridView.Photos -> {
                    if (isStaggered) {
                        LazyVerticalStaggeredGrid(
                            state = staggeredGridState,
                            columns = StaggeredGridCells.Fixed(staggeredColumns),
                            contentPadding = PaddingValues(top = headerHeight, bottom = 4.dp, start = 4.dp, end = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalItemSpacing = 4.dp,
                            modifier = Modifier.fillMaxSize()
                        ) {
                            items(count = lazyPhotos.itemCount, key = { index -> lazyPhotos[index]?.imageKey ?: index }) { index ->
                                val photo = lazyPhotos[index]
                                if (photo != null) {
                                    PhotoGridItem(
                                        photo = photo,
                                        galleryStyle = currentAlbumStyle,
                                        onClick = { onNavigateToPhotoDetail(photo.imageKey) }
                                    )
                                }
                            }

                            // Append Loading State
                            if (lazyPhotos.loadState.append is LoadState.Loading) {
                                item {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(16.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        CircularProgressIndicator(color = NeonBlue, modifier = Modifier.size(24.dp))
                                    }
                                }
                            }
                        }
                    } else {
                        LazyVerticalGrid(
                            state = gridState,
                            columns = GridCells.Fixed(gridColumns),
                            contentPadding = PaddingValues(top = headerHeight, bottom = 4.dp, start = 4.dp, end = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            items(count = lazyPhotos.itemCount, key = { index -> lazyPhotos[index]?.imageKey ?: index }) { index ->
                                val photo = lazyPhotos[index]
                                if (photo != null) {
                                    PhotoGridItem(
                                        photo = photo,
                                        galleryStyle = currentAlbumStyle,
                                        onClick = { onNavigateToPhotoDetail(photo.imageKey) }
                                    )
                                }
                            }

                            // Append Loading State
                            if (lazyPhotos.loadState.append is LoadState.Loading) {
                                item {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(16.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        CircularProgressIndicator(color = NeonBlue, modifier = Modifier.size(24.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }


            // Tag Filtering Bottom Sheet
            if (showFilterSheet) {
                ModalBottomSheet(
                    onDismissRequest = { showFilterSheet = false },
                    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                    containerColor = SurfaceDark
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(horizontal = 24.dp, vertical = 16.dp)
                    ) {
                        Text(
                            text = "Sort & Filter",
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.height(20.dp))

                        // --- Sort Order Section (Date Taken only) ---
                        Text(
                            text = "Sort: Date Taken",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White.copy(alpha = 0.5f)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            val isAsc = sortBy == "date_asc"
                            FilterChip(
                                selected = isAsc,
                                onClick = { viewModel.updateSort("date_asc") },
                                label = { Text("Oldest First") },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = NeonBlue.copy(alpha = 0.2f),
                                    selectedLabelColor = NeonBlue,
                                    selectedLeadingIconColor = NeonBlue,
                                    containerColor = SurfaceDark,
                                    labelColor = Color.White.copy(alpha = 0.6f)
                                )
                            )
                            FilterChip(
                                selected = !isAsc,
                                onClick = { viewModel.updateSort("date_desc") },
                                label = { Text("Recent First") },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = NeonBlue.copy(alpha = 0.2f),
                                    selectedLabelColor = NeonBlue,
                                    selectedLeadingIconColor = NeonBlue,
                                    containerColor = SurfaceDark,
                                    labelColor = Color.White.copy(alpha = 0.6f)
                                )
                            )
                        }

                        Spacer(modifier = Modifier.height(20.dp))
                        HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
                        Spacer(modifier = Modifier.height(20.dp))

                        // --- Filter by Type Section ---
                        Text(
                            text = "Filter by Type",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White.copy(alpha = 0.5f)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            val filterAll = filterType == com.smugview.app.ui.viewmodel.GalleryFilterType.ALL
                            val filterImages = filterType == com.smugview.app.ui.viewmodel.GalleryFilterType.IMAGES
                            val filterVideos = filterType == com.smugview.app.ui.viewmodel.GalleryFilterType.VIDEOS

                            FilterChip(
                                selected = filterAll,
                                onClick = { viewModel.updateFilterType(com.smugview.app.ui.viewmodel.GalleryFilterType.ALL) },
                                label = { Text("All") },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = NeonBlue.copy(alpha = 0.2f),
                                    selectedLabelColor = NeonBlue,
                                    containerColor = SurfaceDark,
                                    labelColor = Color.White.copy(alpha = 0.6f)
                                )
                            )
                            FilterChip(
                                selected = filterImages,
                                onClick = { viewModel.updateFilterType(com.smugview.app.ui.viewmodel.GalleryFilterType.IMAGES) },
                                label = { Text("Images") },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = NeonBlue.copy(alpha = 0.2f),
                                    selectedLabelColor = NeonBlue,
                                    containerColor = SurfaceDark,
                                    labelColor = Color.White.copy(alpha = 0.6f)
                                )
                            )
                            FilterChip(
                                selected = filterVideos,
                                onClick = { viewModel.updateFilterType(com.smugview.app.ui.viewmodel.GalleryFilterType.VIDEOS) },
                                label = { Text("Videos") },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = NeonBlue.copy(alpha = 0.2f),
                                    selectedLabelColor = NeonBlue,
                                    containerColor = SurfaceDark,
                                    labelColor = Color.White.copy(alpha = 0.6f)
                                )
                            )
                        }

                        Spacer(modifier = Modifier.height(32.dp))

                        Button(
                            onClick = { showFilterSheet = false },
                            colors = ButtonDefaults.buttonColors(containerColor = NeonBlue),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Apply Filters")
                        }
                    }
                }
            }
        }

            // 2. Parallax Background Cover & Title Banner (drawn on top of the grid list, translated vertically)
            AlbumHeaderBanner(
                albumTitle = albumTitle,
                coverUrl = coverUrl,
                modifier = Modifier.graphicsLayer {
                    // Read in the layer, not in composition: the offset changes every scroll frame.
                    val offset = scrollOffset.value
                    translationY = (-offset * 0.5f).coerceAtLeast(-headerHeightPx)
                    alpha = 1f - (offset / headerHeightPx).coerceIn(0f, 1f)
                }
            )

            // 2b. The line at the bottom: the 5-9 offline notice (what is shown and why), or, when a later page failed,
            // PARTIAL with "Load the rest" (design 6-4). They share the slot; the notice wins because it says it all.
            (gridView as? GridView.Photos)?.banner?.let { banner ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.7f))
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = banner.text,
                        color = Color.White,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (banner is GridView.Banner.Partial) {
                        TextButton(onClick = { viewModel.selectAlbum(albumKey, force = true) }) {
                            Text(UserMessages.BUTTON_LOAD_THE_REST, color = NeonBlue, fontSize = 13.sp)
                        }
                    }
                }
            }

            // 3. Static Sticky Buttons Top Bar (always visible!)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Back Button (Top Left)
                IconButton(
                    onClick = onBackClick,
                    modifier = Modifier.background(Color.Black.copy(alpha = 0.3f), CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = Color.White
                    )
                }

                // Actions (Top Right: Search, Bookmark, Share, Cast, Filter)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Search in current Album
                    IconButton(
                        onClick = {
                            viewModel.setSearchScope(
                                com.smugview.app.ui.viewmodel.SearchScope(
                                    name = "Gallery: $albumTitle",
                                    nodeId = null,
                                    nodeUri = "/api/v2/album/$albumKey"
                                )
                            )
                            viewModel.setActiveTab(com.smugview.app.ui.viewmodel.BrowserTab.Search, updateScopeFromBrowsing = false)
                            onBackClick()
                        },
                        modifier = Modifier.background(Color.Black.copy(alpha = 0.3f), CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = "Search in Album",
                            tint = Color.White
                        )
                    }

                    // Bookmark
                    IconButton(
                        onClick = {
                            showAlbumBookmarkDialog = true
                        },
                        modifier = Modifier.background(Color.Black.copy(alpha = 0.3f), CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Default.BookmarkBorder,
                            contentDescription = "Bookmark Album",
                            tint = Color.White
                        )
                    }

                    // Share (opens the QR code dialog, which also offers the native share sheet)
                    IconButton(
                        onClick = { showAlbumShareDialog = true },
                        modifier = Modifier.background(Color.Black.copy(alpha = 0.3f), CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = "Share Album",
                            tint = Color.White
                        )
                    }

                    val activeDevice by viewModel.activeCastDevice.collectAsState()
                    val isCasting by viewModel.isCasting.collectAsState()
                    val discoveredDevices by viewModel.discoveredDevices.collectAsState()
                    val castedAlbumKey by viewModel.castedAlbumKeyFlow.collectAsState()
                    var showCastSelector by remember { mutableStateOf(false) }
                    var showRecastDialog by remember { mutableStateOf(false) }
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
                                        val urls = mutableListOf<String>()
                                        for (i in 0 until lazyPhotos.itemCount) {
                                            val photo = lazyPhotos[i]
                                            if (photo != null) {
                                                val url = if (photo.isVideo) {
                                                    photo.videoUrl ?: photo.thumbnailUrl?.replace("/Th/", "/X3/")
                                                } else {
                                                    photo.thumbnailUrl?.replace("/Th/", "/X3/")
                                                }
                                                if (url != null) {
                                                    urls.add(url)
                                                }
                                            }
                                        }
                                        viewModel.castSlideshow(urls)
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
                                
                                val urls = mutableListOf<String>()
                                for (i in 0 until lazyPhotos.itemCount) {
                                    val photo = lazyPhotos[i]
                                    if (photo != null) {
                                        val url = if (photo.isVideo) {
                                            photo.videoUrl ?: photo.thumbnailUrl?.replace("/Th/", "/X3/")
                                        } else {
                                            photo.thumbnailUrl?.replace("/Th/", "/X3/")
                                        }
                                        if (url != null) {
                                            urls.add(url)
                                        }
                                    }
                                }
                                viewModel.castSlideshow(urls)
                                onNavigateToCastController(albumTitle)
                            },
                            onGone = { viewModel.stopCastDiscovery() },
                            onDismiss = {
                                viewModel.stopCastDiscovery()
                                showCastSelector = false
                            }
                        )
                    }

                    // Filter (Sort Options)
                    IconButton(
                        onClick = { showFilterSheet = true },
                        modifier = Modifier.background(Color.Black.copy(alpha = 0.3f), CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Default.FilterList,
                            contentDescription = "Filters",
                            tint = if (filterType != com.smugview.app.ui.viewmodel.GalleryFilterType.ALL || sortBy != "date_desc") NeonBlue else Color.White
                        )
                    }
                }
            }
        }
    }

        if (showAlbumShareDialog) {
            val shareUrl = viewModel.currentAlbumWebUri?.ifEmpty { null }
                ?: "https://${viewModel.activeNickname.value}.smugmug.com"
            com.smugview.app.ui.component.QrShareDialog(
                title = albumTitle,
                url = shareUrl,
                onDismissRequest = { showAlbumShareDialog = false }
            )
        }

        if (showAlbumBookmarkDialog) {
            AddToCollectionsDialog(
                type = "Album",
                itemKey = albumKey,
                title = albumTitle,
                albumKey = albumKey,
                albumTitle = albumTitle,
                thumbnailUrl = coverUrl,
                onDismissRequest = { showAlbumBookmarkDialog = false },
                viewModel = viewModel
            )
        }

        com.smugview.app.ui.browser.PasswordPromptHost(viewModel, onDismiss = onBackClick)
    }
}

@Composable
fun PhotoGridItem(
    photo: AlbumImageData,
    galleryStyle: String?,
    onClick: () -> Unit
) {
    val styleClean = galleryStyle?.lowercase()?.replace(" ", "") ?: ""
    val isStaggered = when {
        styleClean == "squares" || styleClean == "thumbnails" -> false
        else -> true
    }

    val aspectRatio = if (isStaggered) {
        if (photo.originalWidth != null && photo.originalHeight != null && photo.originalHeight > 0) {
            photo.originalWidth.toFloat() / photo.originalHeight.toFloat()
        } else {
            when {
                styleClean.contains("landscape") -> 1.5f
                styleClean.contains("portrait") -> 0.7f
                else -> 1.2f
            }
        }
    } else {
        1f
    }

    val contentScale = if (isStaggered) ContentScale.Fit else ContentScale.Crop

    val optimizedImageUrl = remember(photo.thumbnailUrl, galleryStyle) {
        val targetSize = when {
            styleClean == "squares" || styleClean == "thumbnails" -> "S"
            else -> "M"
        }
        photo.thumbnailUrl?.replace("/Th/", "/$targetSize/")
            ?.replace("/th/", "/${targetSize.lowercase()}/")
            ?.replace("-Th.", "-$targetSize.")
            ?.replace("-th.", "-${targetSize.lowercase()}.")
    }

    var currentUrl by remember(photo.thumbnailUrl, optimizedImageUrl) {
        mutableStateOf(optimizedImageUrl ?: photo.thumbnailUrl)
    }

    Box(
        modifier = Modifier
            .aspectRatio(aspectRatio)
            .clip(RoundedCornerShape(8.dp))
            .background(SurfaceDark)
            .clickable { onClick() }
    ) {
        AsyncImage(
            model = currentUrl,
            contentDescription = photo.title ?: "Photo",
            contentScale = contentScale,
            onError = {
                currentUrl = photo.thumbnailUrl
            },
            modifier = Modifier.fillMaxSize()
        )

        if (photo.isVideo) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .size(24.dp)
                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = "Video",
                    tint = Color.White,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

// Simple FlowRow equivalent container using standard wrapping
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OptInFlowRow(
    modifier: Modifier = Modifier,
    content: @Composable FlowRowScope.() -> Unit
) {
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.Start,
        verticalArrangement = Arrangement.Top,
        maxItemsInEachRow = Int.MAX_VALUE,
        content = content
    )
}

@Composable
fun AlbumHeaderBanner(
    albumTitle: String,
    coverUrl: String?,
    modifier: Modifier = Modifier
) {
    val configuration = LocalConfiguration.current
    val headerHeight = configuration.screenHeightDp.dp / 6

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(headerHeight)
            .background(SurfaceDark)
    ) {
        // Cover Image
        if (!coverUrl.isNullOrEmpty()) {
            AsyncImage(
                model = coverUrl,
                contentDescription = "Album Cover",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }

        // Dark Contrast Scrim Overlay
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Black.copy(alpha = 0.6f),
                            Color.Black.copy(alpha = 0.3f),
                            Color.Black.copy(alpha = 0.7f)
                        )
                    )
                )
        )

        // Title text positioned at the bottom-left of the header with a clickable popup tooltip
        var showTooltip by remember { mutableStateOf(false) }

        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 16.dp, bottom = 12.dp, end = 16.dp)
        ) {
            Text(
                text = albumTitle,
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                fontSize = 20.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        showTooltip = true
                    }
            )

            if (showTooltip) {
                Popup(
                    alignment = Alignment.TopStart,
                    onDismissRequest = { showTooltip = false }
                ) {
                    Box(
                        modifier = Modifier
                            .background(Color.Black.copy(alpha = 0.9f), RoundedCornerShape(8.dp))
                            .border(1.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(8.dp))
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Text(text = albumTitle, color = Color.White, fontSize = 14.sp)
                    }
                }
            }
        }
    }
}
