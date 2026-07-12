package com.smugview.app.ui.browser

import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.Popup
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.ui.text.style.TextAlign
import com.smugview.app.data.db.OfflineCollection
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.PhotoAlbum
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.LocalOffer
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.SortByAlpha
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import com.smugview.app.data.api.isVideo
import androidx.compose.animation.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.History
import com.smugview.app.ui.explorer.ProfilePreviewCard
import com.smugview.app.ui.explorer.ProfileAvatar
import com.smugview.app.ui.component.AddToCollectionsDialog
import androidx.compose.material3.*
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemContentType
import androidx.paging.compose.itemKey
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import com.smugview.app.data.db.CachedNode
import com.smugview.app.data.api.AlbumImageData
import com.smugview.app.ui.theme.DeepDarkBackground
import com.smugview.app.ui.theme.GlowBorder
import com.smugview.app.ui.theme.NeonBlue
import com.smugview.app.ui.theme.SoftRed
import com.smugview.app.ui.theme.SurfaceDark
import androidx.compose.foundation.BorderStroke
import com.smugview.app.ui.viewmodel.BrowserUiState
import com.smugview.app.ui.viewmodel.BrowserTab
import com.smugview.app.ui.viewmodel.SearchUiState
import com.smugview.app.ui.viewmodel.SplashUiState
import com.smugview.app.ui.viewmodel.SmugViewModel
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(
    onNavigateToAlbum: (albumKey: String, albumTitle: String) -> Unit,
    onNavigateToExplorer: () -> Unit,
    onNavigateToSearchPhotoDetail: (imageKey: String, index: Int) -> Unit,
    onNavigateToPhotoDetail: (albumKey: String, imageKey: String) -> Unit,
    onNavigateToKeywordImages: () -> Unit,
    viewModel: SmugViewModel = hiltViewModel()
) {
    val uiState by viewModel.browserState.collectAsState()
    val navStack = viewModel.folderNavigationStack
    val currentFolderId = viewModel.currentFolderId
    val activeTab by viewModel.activeTab.collectAsState()
    var selectedImageForDetail by remember { mutableStateOf<AlbumImageData?>(null) }

    // Handle system back click to navigate folders
    val cameFromSearch = viewModel.savedFolderStateBeforeSearch != null
    BackHandler(enabled = navStack.isNotEmpty() || cameFromSearch) {
        viewModel.navigateBack()
    }

    Scaffold(
        topBar = {
            if (activeTab != BrowserTab.Folders) {
                TopAppBar(
                    title = {
                        Column {
                            val titleText = when (activeTab) {
                                BrowserTab.Hub -> "Gallery Hub"
                                BrowserTab.Collections -> "Saved Collections"
                                BrowserTab.Search -> "Site Search"
                                BrowserTab.TagSearch -> "Tag Search"
                                else -> ""
                            }
                            Text(
                                text = titleText,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                    },
                    actions = {
                        val nickname by viewModel.activeNickname.collectAsState()
                        if (activeTab == BrowserTab.Hub && !nickname.isNullOrEmpty()) {
                            IconButton(onClick = {
                                viewModel.disconnectSite()
                                onNavigateToExplorer()
                            }) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ExitToApp,
                                    contentDescription = "Switch Site",
                                    tint = Color.White
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = DeepDarkBackground,
                        titleContentColor = Color.White
                    )
                )
            }
        },
        bottomBar = {
            val nickname by viewModel.activeNickname.collectAsState()
            val activeUserProfile by viewModel.activeUserProfile.collectAsState()
            val isSiteLoaded = !nickname.isNullOrEmpty()

            NavigationBar(
                containerColor = SurfaceDark,
                tonalElevation = 8.dp
            ) {
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Home, contentDescription = "Home", modifier = Modifier.size(20.dp)) },
                    label = { Text("Home", fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    selected = activeTab == BrowserTab.Folders,
                    enabled = isSiteLoaded,
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = NeonBlue,
                        selectedTextColor = NeonBlue,
                        unselectedIconColor = Color.White.copy(alpha = 0.6f),
                        unselectedTextColor = Color.White.copy(alpha = 0.6f),
                        indicatorColor = NeonBlue.copy(alpha = 0.1f)
                    ),
                    onClick = {
                        if (activeTab == BrowserTab.Folders) {
                            viewModel.navigateToHome()
                        } else {
                            viewModel.setActiveTab(BrowserTab.Folders)
                        }
                    }
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Search, contentDescription = "Search", modifier = Modifier.size(20.dp)) },
                    label = { Text("Search", fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    selected = activeTab == BrowserTab.Search,
                    enabled = isSiteLoaded,
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = NeonBlue,
                        selectedTextColor = NeonBlue,
                        unselectedIconColor = Color.White.copy(alpha = 0.6f),
                        unselectedTextColor = Color.White.copy(alpha = 0.6f),
                        indicatorColor = NeonBlue.copy(alpha = 0.1f)
                    ),
                    onClick = { viewModel.setActiveTab(BrowserTab.Search) }
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.LocalOffer, contentDescription = "Tag Search", modifier = Modifier.size(20.dp)) },
                    label = { Text("Tags", fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    selected = activeTab == BrowserTab.TagSearch,
                    enabled = isSiteLoaded,
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = NeonBlue,
                        selectedTextColor = NeonBlue,
                        unselectedIconColor = Color.White.copy(alpha = 0.6f),
                        unselectedTextColor = Color.White.copy(alpha = 0.6f),
                        indicatorColor = NeonBlue.copy(alpha = 0.1f)
                    ),
                    onClick = { viewModel.setActiveTab(BrowserTab.TagSearch) }
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.PhotoAlbum, contentDescription = "Collections", modifier = Modifier.size(20.dp)) },
                    label = { Text("Collections", fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    selected = activeTab == BrowserTab.Collections,
                    enabled = isSiteLoaded,
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = NeonBlue,
                        selectedTextColor = NeonBlue,
                        unselectedIconColor = Color.White.copy(alpha = 0.6f),
                        unselectedTextColor = Color.White.copy(alpha = 0.6f),
                        indicatorColor = NeonBlue.copy(alpha = 0.1f)
                    ),
                    onClick = { viewModel.setActiveTab(BrowserTab.Collections) }
                )
                NavigationBarItem(
                    icon = {
                        if (isSiteLoaded && !nickname.isNullOrEmpty()) {
                            ProfileAvatar(
                                nickname = nickname!!,
                                name = nickname!!,
                                bioImageKey = activeUserProfile?.bioImageKey,
                                modifier = Modifier.size(20.dp),
                                textColor = Color.White,
                                fontSize = 9.sp
                            )
                        } else {
                            Icon(Icons.Default.Hub, contentDescription = "Hub", modifier = Modifier.size(20.dp))
                        }
                    },
                    label = {
                        Text(
                            text = if (isSiteLoaded && !nickname.isNullOrEmpty()) {
                                nickname!!.replaceFirstChar { it.uppercase() }
                            } else {
                                "Hub"
                            },
                            fontSize = 10.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    selected = activeTab == BrowserTab.Hub,
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = NeonBlue,
                        selectedTextColor = NeonBlue,
                        unselectedIconColor = Color.White.copy(alpha = 0.6f),
                        unselectedTextColor = Color.White.copy(alpha = 0.6f),
                        indicatorColor = NeonBlue.copy(alpha = 0.1f)
                    ),
                    onClick = { viewModel.setActiveTab(BrowserTab.Hub) }
                )
            }
        },
        containerColor = DeepDarkBackground
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            if (activeTab == BrowserTab.Folders && navStack.isNotEmpty()) {
                BreadcrumbBar(
                    navStack = navStack,
                    onBreadcrumbClick = { index -> viewModel.navigateToStackFolder(index) }
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                when (activeTab) {
                    BrowserTab.Hub -> {
                        HomeTabView(viewModel = viewModel)
                    }
                    BrowserTab.Folders -> {
                        FoldersTabView(
                            uiState = uiState,
                            currentFolderId = currentFolderId,
                            onNavigateToAlbum = onNavigateToAlbum,
                            viewModel = viewModel
                        )
                    }
                    BrowserTab.Collections -> {
                        CollectionsTabView(
                            viewModel = viewModel,
                            onNavigateToAlbum = onNavigateToAlbum,
                            onImageClick = { albumKey, imageKey ->
                                onNavigateToPhotoDetail(albumKey, imageKey)
                            }
                        )
                    }
                    BrowserTab.Search -> {
                        SearchTabView(
                            viewModel = viewModel,
                            onImageClick = { index, image -> onNavigateToSearchPhotoDetail(image.imageKey, index) },
                            onNavigateToAlbum = onNavigateToAlbum
                        )
                    }
                    BrowserTab.TagSearch -> {
                        TagSearchTabView(
                            viewModel = viewModel,
                            onNavigateToKeywordImages = onNavigateToKeywordImages
                        )
                    }
                }

                // Secure Password Entry Dialog Overlay
                viewModel.passwordPromptNode?.let { node ->
                    PasswordPromptDialog(
                        node = node,
                        error = viewModel.passwordError,
                        onSubmit = { password -> 
                            viewModel.submitPassword(password) {
                                if (node.type != "Folder") {
                                    val albumKey = if (node.albumUri?.contains("/album/") == true) {
                                        node.albumUri.substringAfterLast("/").substringBefore("!")
                                    } else {
                                        node.nodeId
                                    }
                                    onNavigateToAlbum(albumKey, node.title)
                                }
                            }
                        },
                        onDismiss = { viewModel.dismissPasswordPrompt() }
                    )
                }

                // Immersive detailed image dialog overlay
                selectedImageForDetail?.let { image ->
                    Dialog(
                        onDismissRequest = { selectedImageForDetail = null },
                        properties = DialogProperties(usePlatformDefaultWidth = false)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black)
                        ) {
                            AsyncImage(
                                model = image.archivedUri ?: image.thumbnailUrl?.replace("/Th/", "/L/")?.replace("/th/", "/l/"),
                                contentDescription = image.title ?: image.caption,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxSize()
                            )

                            IconButton(
                                onClick = { selectedImageForDetail = null },
                                modifier = Modifier
                                    .align(Alignment.TopStart)
                                    .padding(16.dp)
                                    .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                            ) {
                                Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                            }

                            Column(
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .fillMaxWidth()
                                    .background(
                                        Brush.verticalGradient(
                                            colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.9f))
                                        )
                                    )
                                    .padding(24.dp)
                            ) {
                                Text(
                                    text = image.title ?: image.caption ?: "Untitled",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 18.sp,
                                    color = Color.White
                                )
                                if (!image.caption.isNullOrEmpty() && image.title != image.caption) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = image.caption,
                                        fontSize = 13.sp,
                                        color = Color.White.copy(alpha = 0.7f),
                                        maxLines = 3,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }

                                Spacer(modifier = Modifier.height(16.dp))

                                val exifState = viewModel.getImageExif(image.imageKey).collectAsState()
                                exifState.value?.fold(
                                    onSuccess = { exif ->
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                                        ) {
                                            val cam = exif.camera
                                            if (!cam.isNullOrEmpty()) {
                                                Text(
                                                    text = "Camera: $cam",
                                                    fontSize = 11.sp,
                                                    color = NeonBlue
                                                )
                                            }
                                        }
                                    },
                                    onFailure = {}
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun BrowserNodeItem(
    node: CachedNode,
    isUnlocked: Boolean,
    onClick: () -> Unit
) {
    val neonColors = listOf(
        Color(0xFF00E5FF), // Cyan/Blue
        Color(0xFF00E676), // Green
        Color(0xFFFF8F00), // Orange
        Color(0xFFFFEA00), // Yellow
        Color(0xFFF50057), // Pink
        Color(0xFFD500F9)  // Purple
    )
    val glowColor = neonColors[Math.abs(node.nodeId.hashCode()) % neonColors.size]
    val aspectRatio = if (Math.abs(node.nodeId.hashCode()) % 2 == 0) 0.85f else 1.15f

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(aspectRatio)
            .clip(RoundedCornerShape(16.dp))
            .border(1.5.dp, glowColor.copy(alpha = 0.45f), RoundedCornerShape(16.dp))
            .clickable { onClick() }
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Background Cover Image
            if (!node.highlightImageUrl.isNullOrEmpty()) {
                AsyncImage(
                    model = node.highlightImageUrl,
                    contentDescription = node.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                // Placeholder gradient
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.linearGradient(
                                colors = listOf(
                                    SurfaceDark,
                                    glowColor.copy(alpha = 0.15f)
                                )
                            )
                        )
                )
            }

            // Dark gradient overlay for typography readability
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.4f),
                                Color.Black.copy(alpha = 0.85f)
                            )
                        )
                    )
            )

            // Content layout
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // Top Row (Lock Badge)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    if (node.access == "Password" || node.access == "Inherited") {
                        val lockIcon = if (isUnlocked) Icons.Default.LockOpen else Icons.Default.Lock
                        val lockColor = if (isUnlocked) Color(0xFF00E5FF) else Color(0xFFFFB800)
                        Icon(
                            imageVector = lockIcon,
                            contentDescription = if (isUnlocked) "Unlocked" else "Locked",
                            tint = lockColor,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                // Bottom Typography
                Column {
                    Text(
                        text = node.title,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = Color.White,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    val subtitleText = when {
                        node.type == "Folder" -> {
                            if (!node.description.isNullOrEmpty()) node.description else null
                        }
                        !node.description.isNullOrEmpty() -> node.description
                        else -> "Album"
                    }

                    if (subtitleText != null) {
                        Text(
                            text = subtitleText,
                            fontSize = 12.sp,
                            color = Color.White.copy(alpha = 0.6f),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun PasswordPromptDialog(
    node: CachedNode,
    error: String?,
    onSubmit: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var password by remember { mutableStateOf("") }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = SurfaceDark,
            tonalElevation = 8.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = "Lock",
                    tint = Color(0xFFFFB800),
                    modifier = Modifier.size(40.dp)
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "Password Required",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Please enter password to unlock \"${node.title}\"",
                    fontSize = 13.sp,
                    color = Color.White.copy(alpha = 0.5f)
                )

                if (node.passwordHint != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Hint: ${node.passwordHint}",
                        fontSize = 12.sp,
                        color = NeonBlue
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password", color = Color.White.copy(alpha = 0.5f)) },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.None,
                        autoCorrect = false
                    ),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = NeonBlue,
                        unfocusedBorderColor = Color.White.copy(alpha = 0.3f)
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                if (error != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = error,
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 12.sp
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                Row(
                    horizontalArrangement = Arrangement.End,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Cancel", color = Color.White.copy(alpha = 0.5f))
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = { onSubmit(password) },
                        colors = ButtonDefaults.buttonColors(containerColor = NeonBlue)
                    ) {
                        Text("Unlock")
                    }
                }
            }
        }
    }
}

@Composable
fun FoldersTabView(
    uiState: BrowserUiState,
    currentFolderId: String?,
    onNavigateToAlbum: (albumKey: String, albumTitle: String) -> Unit,
    viewModel: SmugViewModel
) {
    val navStack = viewModel.folderNavigationStack
    val context = LocalContext.current
    val nickname by viewModel.activeNickname.collectAsState()
    val activeUserProfile by viewModel.activeUserProfile.collectAsState()

    when (uiState) {
        is BrowserUiState.Loading -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = NeonBlue)
            }
        }
        is BrowserUiState.Error -> {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(text = uiState.message, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(bottom = 16.dp))
                Button(
                    onClick = { currentFolderId?.let { viewModel.loadFolderContents(it, forceRefresh = true) } },
                    colors = ButtonDefaults.buttonColors(containerColor = NeonBlue)
                ) {
                    Text("Retry")
                }
            }
        }
        is BrowserUiState.Success -> {
            val staggeredGridState = rememberLazyStaggeredGridState()
            var showFolderBookmarkDialog by remember { mutableStateOf(false) }

            val scrollOffset = remember {
                derivedStateOf {
                    if (staggeredGridState.firstVisibleItemIndex == 0) {
                        staggeredGridState.firstVisibleItemScrollOffset
                    } else {
                        10000
                    }
                }
            }

            val configuration = LocalConfiguration.current
            val isLandscape = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
            val folderColumns = if (isLandscape) 4 else 2
            val headerHeight = if (isLandscape) (configuration.screenHeightDp.dp / 4) else (configuration.screenHeightDp.dp / 6)
            val density = LocalDensity.current
            val headerHeightPx = remember(headerHeight) { with(density) { headerHeight.toPx() } }

            val headerTranslationY = remember(scrollOffset.value, headerHeightPx) {
                if (headerHeightPx > 0f) {
                    (-scrollOffset.value * 0.5f).coerceAtLeast(-headerHeightPx)
                } else {
                    0f
                }
            }

            val headerAlpha = remember(scrollOffset.value, headerHeightPx) {
                if (headerHeightPx > 0f) {
                    val progress = (scrollOffset.value / headerHeightPx).coerceIn(0f, 1f)
                    1f - progress
                } else {
                    1f
                }
            }

            val folderTitle = navStack.lastOrNull()?.title ?: "Galleries"
            val coverUrl = navStack.lastOrNull()?.highlightImageUrl 
                ?: uiState.nodes.firstOrNull { !it.highlightImageUrl.isNullOrEmpty() }?.highlightImageUrl

            Box(modifier = Modifier.fillMaxSize()) {
                // 1. Grid Content
                if (uiState.nodes.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize().padding(top = headerHeight), contentAlignment = Alignment.Center) {
                        Text(text = "This folder is empty", color = Color.White.copy(alpha = 0.5f))
                    }
                } else {
                    LazyVerticalStaggeredGrid(
                        state = staggeredGridState,
                        columns = StaggeredGridCells.Fixed(folderColumns),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(top = headerHeight + 12.dp, bottom = 12.dp, start = 12.dp, end = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalItemSpacing = 12.dp
                    ) {
                        items(uiState.nodes, key = { it.nodeId }) { node ->
                            val isUnlocked = viewModel.isNodeUnlocked(node.nodeId)
                            BrowserNodeItem(
                                node = node,
                                isUnlocked = isUnlocked,
                                onClick = {
                                    if ((node.access == "Password" || node.access == "Inherited") && !isUnlocked) {
                                        viewModel.promptPassword(node)
                                    } else {
                                        if (node.type == "Folder") {
                                            viewModel.navigateToChildFolder(node)
                                        } else {
                                            val albumKey = if (node.albumUri?.contains("/album/") == true) {
                                                node.albumUri.substringAfterLast("/").substringBefore("!")
                                            } else {
                                                node.nodeId
                                            }
                                            // Do NOT call selectAlbum here — PhotoGridScreen's LaunchedEffect is the single load trigger.
                                            // Calling it here clears _rawPhotos and causes a concurrent double-load race condition.
                                            onNavigateToAlbum(albumKey, node.title)
                                        }
                                    }
                                }
                            )
                        }
                    }
                }

                // 2. Folder Header Banner (Parallax)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(headerHeight)
                        .graphicsLayer { 
                            translationY = headerTranslationY 
                            alpha = headerAlpha
                        }
                        .background(SurfaceDark)
                ) {
                    if (!coverUrl.isNullOrEmpty()) {
                        AsyncImage(
                            model = coverUrl,
                            contentDescription = folderTitle,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        // Default beautiful neon gradient
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    Brush.linearGradient(
                                        colors = listOf(SurfaceDark, NeonBlue.copy(alpha = 0.2f))
                                    )
                                )
                        )
                    }

                    // Scrim gradient overlay
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(
                                        Color.Black.copy(alpha = 0.6f),
                                        Color.Black.copy(alpha = 0.2f),
                                        Color.Black.copy(alpha = 0.7f)
                                    )
                                )
                            )
                    )

                    // Title with tooltip matching Album view
                    var showTooltip by remember { mutableStateOf(false) }
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(start = 16.dp, bottom = 12.dp, end = 120.dp)
                    ) {
                        Text(
                            text = folderTitle,
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 20.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.clickable(
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
                                    Text(text = folderTitle, color = Color.White, fontSize = 14.sp)
                                }
                            }
                        }
                    }
                }

                // 3. Static Sticky Header Buttons Row (always visible)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Back Button (shown if navStack is not empty or came from search)
                    if (navStack.isNotEmpty() || viewModel.savedFolderStateBeforeSearch != null) {
                        IconButton(
                            onClick = { viewModel.navigateBack() },
                            modifier = Modifier.background(Color.Black.copy(alpha = 0.3f), CircleShape)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint = Color.White
                            )
                        }
                    } else {
                        Spacer(modifier = Modifier.size(48.dp))
                    }

                    // Action buttons (Bookmark, Share, Refresh)
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Bookmark (hide on the root homepage)
                        if (navStack.isNotEmpty()) {
                            IconButton(
                                onClick = {
                                    showFolderBookmarkDialog = true
                                },
                                modifier = Modifier.background(Color.Black.copy(alpha = 0.3f), CircleShape)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.BookmarkBorder,
                                    contentDescription = "Bookmark Folder",
                                    tint = Color.White
                                )
                            }
                        }

                        // Share
                        IconButton(
                            onClick = {
                                val shareUrl = navStack.lastOrNull()?.webUri 
                                    ?: activeUserProfile?.webUri 
                                    ?: "https://${nickname}.smugmug.com"
                                val intent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, "Checkout '$folderTitle': $shareUrl")
                                }
                                context.startActivity(Intent.createChooser(intent, "Share"))
                            },
                            modifier = Modifier.background(Color.Black.copy(alpha = 0.3f), CircleShape)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Share,
                                contentDescription = "Share Folder",
                                tint = Color.White
                            )
                        }

                        // Search in current Folder
                        IconButton(
                            onClick = {
                                viewModel.setActiveTab(BrowserTab.Search)
                            },
                            modifier = Modifier.background(Color.Black.copy(alpha = 0.3f), CircleShape)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = "Search in Folder",
                                tint = Color.White
                            )
                        }

                        // Refresh
                        IconButton(
                            onClick = {
                                if (currentFolderId == "root" || currentFolderId == null) {
                                    viewModel.clearEntireCacheAndReload()
                                } else {
                                    currentFolderId?.let { viewModel.loadFolderContents(it, forceRefresh = true) }
                                }
                            },
                            modifier = Modifier.background(Color.Black.copy(alpha = 0.3f), CircleShape)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Refresh Folder",
                                tint = Color.White
                            )
                        }
                    }
                }
            }

            if (showFolderBookmarkDialog && currentFolderId != null) {
                AddToCollectionsDialog(
                    type = "Folder",
                    itemKey = currentFolderId,
                    title = folderTitle,
                    thumbnailUrl = coverUrl,
                    onDismissRequest = { showFolderBookmarkDialog = false },
                    viewModel = viewModel
                )
            }
        }
    }
}

@Composable
fun HomeTabView(
    viewModel: SmugViewModel
) {
    val nickname by viewModel.activeNickname.collectAsState()
    val activeUserProfile by viewModel.activeUserProfile.collectAsState()
    val previewAlbums by viewModel.previewAlbums.collectAsState()
    val focusManager = LocalFocusManager.current
    
    if (nickname.isNullOrEmpty()) {
        // --- SEARCH SITE SEARCHBOX VIEW ---
        val uiState by viewModel.splashState.collectAsState()
        val recentSites by viewModel.recentSites.collectAsState()
        val sitePreview by viewModel.sitePreview.collectAsState()
        var nicknameQuery by remember { mutableStateOf("") }

        // Debounce live validation (500ms delay)
        LaunchedEffect(nicknameQuery) {
            if (nicknameQuery.isBlank()) {
                viewModel.verifyAndPreviewNickname("")
                return@LaunchedEffect
            }
            delay(500)
            viewModel.verifyAndPreviewNickname(nicknameQuery)
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(DeepDarkBackground)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {
                    focusManager.clearFocus()
                }
        ) {
            // Aesthetic Top Radial Gradient
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(350.dp)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                NeonBlue.copy(alpha = 0.12f),
                                Color.Transparent
                            ),
                            radius = 800f
                        )
                    )
            )

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "Explore SmugMug",
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Black,
                    color = Color.White,
                    letterSpacing = 1.sp
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Enter a public site nickname to browse galleries",
                    fontSize = 14.sp,
                    color = Color.White.copy(alpha = 0.5f)
                )

                Spacer(modifier = Modifier.height(36.dp))

                // Glowing Text Input Field
                OutlinedTextField(
                    value = nicknameQuery,
                    onValueChange = { nicknameQuery = it },
                    placeholder = { Text("e.g. smugmug", color = Color.White.copy(alpha = 0.4f)) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search", tint = NeonBlue) },
                    singleLine = true,
                    shape = RoundedCornerShape(24.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedContainerColor = SurfaceDark,
                        unfocusedContainerColor = SurfaceDark,
                        focusedBorderColor = GlowBorder,
                        unfocusedBorderColor = Color.Transparent
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(
                            width = 1.5.dp,
                            brush = Brush.horizontalGradient(listOf(NeonBlue, GlowBorder)),
                            shape = RoundedCornerShape(24.dp)
                        )
                )

                Spacer(modifier = Modifier.height(20.dp))

                // Recently Explored Sites list
                if (recentSites.isNotEmpty()) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                imageVector = Icons.Default.History,
                                contentDescription = "History",
                                tint = Color.White.copy(alpha = 0.4f),
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Recently Explored",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White.copy(alpha = 0.4f)
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            items(recentSites) { site ->
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(SurfaceDark)
                                        .clickable { nicknameQuery = site }
                                        .padding(horizontal = 14.dp, vertical = 8.dp)
                                ) {
                                    Text(
                                        text = site,
                                        color = Color.White.copy(alpha = 0.8f),
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(40.dp))

                // Dynamic Live Profile Preview Card
                AnimatedVisibility(
                    visible = sitePreview != null,
                    enter = fadeIn() + slideInVertically { it / 2 },
                    exit = fadeOut() + slideOutVertically { it / 2 }
                ) {
                    sitePreview?.fold(
                        onSuccess = { userData ->
                            ProfilePreviewCard(
                                userData = userData,
                                previewAlbums = previewAlbums,
                                isLoading = uiState is SplashUiState.Loading,
                                onClickEnter = { viewModel.selectSite(userData.nickName) }
                            )
                        },
                        onFailure = {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f))
                                    .border(1.dp, MaterialTheme.colorScheme.error, RoundedCornerShape(16.dp))
                                    .padding(16.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "No public site matches \"$nicknameQuery\"",
                                    color = MaterialTheme.colorScheme.error,
                                    fontWeight = FontWeight.Medium,
                                    fontSize = 14.sp
                                )
                            }
                        }
                    )
                }
            }
        }
    } else {
        // --- ACTIVE SITE HUB PROFILE VIEW ---
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Top
        ) {
            Spacer(modifier = Modifier.height(16.dp))
            
            // Active Profile Card
            Card(
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.5.dp, NeonBlue.copy(alpha = 0.5f), RoundedCornerShape(24.dp))
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    ProfileAvatar(
                        nickname = nickname!!,
                        name = nickname!!,
                        bioImageKey = activeUserProfile?.bioImageKey,
                        modifier = Modifier.size(80.dp),
                        textColor = Color.White,
                        fontSize = 32.sp
                    )
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    Text(
                        text = nickname!!.replaceFirstChar { it.uppercase() },
                        fontWeight = FontWeight.Bold,
                        fontSize = 22.sp,
                        color = Color.White
                    )
                    
                    Text(
                        text = "Active Gallery Profile",
                        fontSize = 13.sp,
                        color = Color.White.copy(alpha = 0.5f)
                    )
                    
                    Spacer(modifier = Modifier.height(24.dp))
                    
                    Button(
                        onClick = { viewModel.disconnectSite() },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF50057)),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ExitToApp,
                            contentDescription = "Switch",
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Switch / Exit Site", fontWeight = FontWeight.Bold)
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(32.dp))
            
            // Quick Links
            Text(
                text = "Quick Actions",
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                color = Color.White,
                modifier = Modifier.align(Alignment.Start)
            )
            
            Spacer(modifier = Modifier.height(12.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Browse Galleries
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                    modifier = Modifier
                        .weight(1f)
                        .clickable { viewModel.setActiveTab(BrowserTab.Folders) }
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(Icons.Default.Folder, contentDescription = "Galleries", tint = NeonBlue, modifier = Modifier.size(28.dp))
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Browse Galleries", fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }

                // Saved Collections
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                    modifier = Modifier
                        .weight(1f)
                        .clickable { viewModel.setActiveTab(BrowserTab.Collections) }
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(Icons.Default.PhotoAlbum, contentDescription = "Collections", tint = NeonBlue, modifier = Modifier.size(28.dp))
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Saved Collections", fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                
                // Search Photos
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                    modifier = Modifier
                        .weight(1f)
                        .clickable { viewModel.setActiveTab(BrowserTab.Search) }
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(Icons.Default.Search, contentDescription = "Search", tint = NeonBlue, modifier = Modifier.size(28.dp))
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Search Photos", fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Button(
                onClick = { viewModel.navigateToHome() },
                colors = ButtonDefaults.buttonColors(containerColor = SurfaceDark),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(12.dp)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.Home, contentDescription = "Root", tint = Color.White, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Reset Navigation to Root", color = Color.White)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchTabView(
    viewModel: SmugViewModel,
    onImageClick: (Int, AlbumImageData) -> Unit,
    onNavigateToAlbum: (albumKey: String, albumTitle: String) -> Unit
) {
    val searchState by viewModel.searchState.collectAsState()
    val pagingFlow by viewModel.searchPhotosPagingFlow.collectAsState()
    val searchPhotosPagingItems = pagingFlow.collectAsLazyPagingItems()
    val isSearchPhotosLoading by viewModel.isSearchPhotosLoading.collectAsState()
    val activeScope by viewModel.searchScope.collectAsState()
    val searchHistory by viewModel.searchHistory.collectAsState()
    var searchInput by remember { mutableStateOf(viewModel.searchQuery) }
    val selectedResultTab = viewModel.searchResultTab
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    var isFocused by remember { mutableStateOf(false) }
    val photosLazyGridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
    val galleriesLazyGridState = androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState()
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // 1. Search Bar
        OutlinedTextField(
            value = searchInput,
            onValueChange = { searchInput = it },
            placeholder = { Text("Search photos across this site...", color = Color.White.copy(alpha = 0.4f)) },
            leadingIcon = {
                IconButton(onClick = {
                    viewModel.performSearch(searchInput)
                    keyboardController?.hide()
                    focusManager.clearFocus()
                }) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = "Search",
                        tint = NeonBlue
                    )
                }
            },
            trailingIcon = {
                if (searchInput.isNotEmpty()) {
                    IconButton(onClick = {
                        searchInput = ""
                        viewModel.performSearch("")
                    }) {
                        Icon(Icons.Default.Close, contentDescription = "Clear", tint = Color.White.copy(alpha = 0.5f))
                    }
                }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Text,
                imeAction = ImeAction.Search
            ),
            keyboardActions = KeyboardActions(
                onSearch = {
                    viewModel.performSearch(searchInput)
                    keyboardController?.hide()
                    focusManager.clearFocus()
                }
            ),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                focusedBorderColor = NeonBlue,
                unfocusedBorderColor = Color.White.copy(alpha = 0.2f),
                focusedContainerColor = SurfaceDark,
                unfocusedContainerColor = SurfaceDark
            ),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { isFocused = it.isFocused }
        )
        
        Spacer(modifier = Modifier.height(10.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // 2. Active Search Scope Badge & Force Refresh Button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .background(SurfaceDark.copy(alpha = 0.8f), RoundedCornerShape(20.dp))
                            .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(20.dp))
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = "Scope Info",
                            tint = NeonBlue,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Scope: ${activeScope.name}",
                            color = Color.White.copy(alpha = 0.9f),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                        if (activeScope.name != "Entire Site") {
                            Spacer(modifier = Modifier.width(8.dp))
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Clear Scope",
                                tint = Color.White.copy(alpha = 0.6f),
                                modifier = Modifier
                                    .size(14.dp)
                                    .clickable {
                                        viewModel.setSearchScope(com.smugview.app.ui.viewmodel.SearchScope("Entire Site"))
                                        if (searchInput.isNotEmpty()) {
                                            viewModel.performSearch(searchInput)
                                        }
                                    }
                            )
                        }
                    }

                    if (searchInput.isNotEmpty()) {
                        IconButton(
                            onClick = {
                                viewModel.performSearch(searchInput, forceRefresh = true)
                            },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Refresh Search",
                                tint = NeonBlue,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // 3. Results Box
        Box(
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentAlignment = Alignment.Center
        ) {
            when (val state = searchState) {
                is SearchUiState.Idle -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = "Search Idle",
                            tint = Color.White.copy(alpha = 0.15f),
                            modifier = Modifier.size(80.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Enter search terms to find photos",
                            color = Color.White.copy(alpha = 0.4f),
                            fontSize = 14.sp
                        )
                    }
                }
                is SearchUiState.Loading -> {
                    CircularProgressIndicator(color = NeonBlue)
                }
                is SearchUiState.Error -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(24.dp)
                    ) {
                        Text(text = state.message, color = MaterialTheme.colorScheme.error)
                    }
                }
                is SearchUiState.Success -> {
                    val totalResults = searchPhotosPagingItems.itemCount + state.galleries.size + state.folders.size
                    if (totalResults == 0) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                            modifier = Modifier.fillMaxSize()
                        ) {
                            Text(
                                text = if (isSearchPhotosLoading) "Loading photos..." else "Enter a word or phrase to search",
                                color = Color.White.copy(alpha = 0.4f),
                                fontWeight = FontWeight.Medium
                            )
                        }
                    } else {
                        Column(modifier = Modifier.fillMaxSize()) {
                            // Secondary indicator for tab selection
                            val tabs = listOf(
                                "Photos (${searchPhotosPagingItems.itemCount})",
                                "Galleries (${state.galleries.size})",
                                "Folders (${state.folders.size})"
                            )
                            
                            TabRow(
                                selectedTabIndex = selectedResultTab,
                                containerColor = Color.Transparent,
                                contentColor = Color.White,
                                indicator = { tabPositions ->
                                    TabRowDefaults.SecondaryIndicator(
                                        modifier = Modifier.tabIndicatorOffset(tabPositions[selectedResultTab]),
                                        color = NeonBlue
                                    )
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                tabs.forEachIndexed { index, title ->
                                    Tab(
                                        selected = selectedResultTab == index,
                                        onClick = { viewModel.searchResultTab = index },
                                        text = { Text(title, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1) }
                                    )
                                }
                            }
                            
                            Spacer(modifier = Modifier.height(12.dp))

                            Box(modifier = Modifier.weight(1f)) {
                                when (selectedResultTab) {
                                    0 -> { // Photos tab
                                        if (searchPhotosPagingItems.itemCount == 0) {
                                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                                Text(
                                                    text = if (isSearchPhotosLoading) "Loading photos..." else (state.photosError ?: "Enter a word or phrase to search"),
                                                    color = if (state.photosError != null) MaterialTheme.colorScheme.error else Color.White.copy(alpha = 0.4f),
                                                    textAlign = TextAlign.Center,
                                                    modifier = Modifier.padding(16.dp)
                                                )
                                            }
                                        } else {
                                            Column(modifier = Modifier.fillMaxSize()) {
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .padding(bottom = 8.dp),
                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Text(
                                                        text = "Sort Date: ${viewModel.searchPhotosSortOrder}",
                                                        color = Color.White.copy(alpha = 0.7f),
                                                        fontSize = 12.sp,
                                                        fontWeight = FontWeight.Medium
                                                    )
                                                    IconButton(
                                                        onClick = {
                                                            val newOrder = if (viewModel.searchPhotosSortOrder == "Ascending") "Descending" else "Ascending"
                                                            viewModel.updateSearchPhotosSortOrder(newOrder)
                                                            scope.launch {
                                                                try {
                                                                    if (newOrder == "Ascending") {
                                                                        val lastIndex = (searchPhotosPagingItems.itemCount - 1).coerceAtLeast(0)
                                                                        photosLazyGridState.scrollToItem(lastIndex)
                                                                    } else {
                                                                        photosLazyGridState.scrollToItem(0)
                                                                    }
                                                                } catch (e: Exception) {
                                                                    e.printStackTrace()
                                                                }
                                                            }
                                                        },
                                                        enabled = !isSearchPhotosLoading,
                                                        modifier = Modifier.size(24.dp)
                                                    ) {
                                                        Icon(
                                                            imageVector = if (viewModel.searchPhotosSortOrder == "Ascending") Icons.Default.SortByAlpha else Icons.Default.Sort,
                                                            contentDescription = "Toggle Sort Order",
                                                            tint = if (isSearchPhotosLoading) Color.Gray else NeonBlue,
                                                            modifier = Modifier.size(18.dp)
                                                        )
                                                    }
                                                }

                                                LazyVerticalGrid(
                                                    state = photosLazyGridState,
                                                    columns = GridCells.Fixed(3),
                                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                                    modifier = Modifier.fillMaxSize()
                                                ) {
                                                    items(
                                                        count = searchPhotosPagingItems.itemCount,
                                                        key = searchPhotosPagingItems.itemKey { it.imageKey }
                                                    ) { index ->
                                                        val image = searchPhotosPagingItems[index]
                                                        if (image != null) {
                                                            Box(
                                                                modifier = Modifier
                                                                    .aspectRatio(1f)
                                                                    .clip(RoundedCornerShape(8.dp))
                                                                    .clickable { onImageClick(index, image) }
                                                            ) {
                                                                AsyncImage(
                                                                    model = image.thumbnailUrl ?: image.archivedUri,
                                                                    contentDescription = image.title,
                                                                    contentScale = ContentScale.Crop,
                                                                    modifier = Modifier.fillMaxSize()
                                                                )
                                                                
                                                                if (image.format?.equals("Video", ignoreCase = true) == true || image.videoUrl != null) {
                                                                    Icon(
                                                                        imageVector = Icons.Default.PlayArrow,
                                                                        contentDescription = "Video",
                                                                        tint = Color.White,
                                                                        modifier = Modifier
                                                                            .align(Alignment.TopEnd)
                                                                            .padding(4.dp)
                                                                            .size(24.dp)
                                                                    )
                                                                }
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    1 -> { // Galleries tab
                                        if (state.galleries.isEmpty()) {
                                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                                Text("No galleries found", color = Color.White.copy(alpha = 0.4f))
                                            }
                                        } else {
                                            Column(modifier = Modifier.fillMaxSize()) {
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .padding(bottom = 8.dp),
                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Text(
                                                        text = "Order: ${viewModel.searchGallerySortOrder}",
                                                        color = Color.White.copy(alpha = 0.7f),
                                                        fontSize = 12.sp,
                                                        fontWeight = FontWeight.Medium
                                                    )
                                                    IconButton(
                                                        onClick = {
                                                            val newOrder = if (viewModel.searchGallerySortOrder == "Ascending") "Descending" else "Ascending"
                                                            viewModel.updateSearchGallerySortOrder(newOrder)
                                                            scope.launch {
                                                                try {
                                                                    if (newOrder == "Ascending") {
                                                                        val lastIndex = (state.galleries.size - 1).coerceAtLeast(0)
                                                                        galleriesLazyGridState.scrollToItem(lastIndex)
                                                                    } else {
                                                                        galleriesLazyGridState.scrollToItem(0)
                                                                    }
                                                                } catch (e: Exception) {
                                                                    e.printStackTrace()
                                                                }
                                                            }
                                                        },
                                                        modifier = Modifier.size(24.dp)
                                                    ) {
                                                        Icon(
                                                            imageVector = if (viewModel.searchGallerySortOrder == "Ascending") Icons.Default.SortByAlpha else Icons.Default.Sort,
                                                            contentDescription = "Toggle Sort Order",
                                                            tint = NeonBlue,
                                                            modifier = Modifier.size(18.dp)
                                                        )
                                                    }
                                                }
                                                // Render using standard lazy staggered vertical grid
                                                androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid(
                                                    state = galleriesLazyGridState,
                                                    columns = androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells.Fixed(2),
                                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                                    verticalItemSpacing = 12.dp,
                                                    modifier = Modifier.fillMaxSize()
                                                ) {
                                                    items(state.galleries, key = { it.nodeId }) { gallery ->
                                                        val isUnlocked = viewModel.isNodeUnlocked(gallery.nodeId)
                                                        BrowserNodeItem(
                                                            node = gallery,
                                                            isUnlocked = isUnlocked,
                                                            onClick = {
                                                                if ((gallery.access == "Password" || gallery.access == "Inherited") && !isUnlocked) {
                                                                    viewModel.promptPassword(gallery)
                                                                } else {
                                                                    val albumKey = gallery.albumUri?.substringAfterLast("/") ?: gallery.nodeId
                                                                    // Do NOT call selectAlbum here — PhotoGridScreen's LaunchedEffect is the single load trigger.
                                                                    onNavigateToAlbum(albumKey, gallery.title)
                                                                }
                                                            }
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    2 -> { // Folders tab
                                        if (state.folders.isEmpty()) {
                                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                                Text("No folders found", color = Color.White.copy(alpha = 0.4f))
                                            }
                                        } else {
                                            androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid(
                                                columns = androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells.Fixed(2),
                                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                                verticalItemSpacing = 12.dp,
                                                modifier = Modifier.fillMaxSize()
                                            ) {
                                                items(state.folders, key = { it.nodeId }) { folder ->
                                                    val isUnlocked = viewModel.isNodeUnlocked(folder.nodeId)
                                                    BrowserNodeItem(
                                                        node = folder,
                                                        isUnlocked = isUnlocked,
                                                        onClick = {
                                                            if ((folder.access == "Password" || folder.access == "Inherited") && !isUnlocked) {
                                                                viewModel.promptPassword(folder)
                                                            } else {
                                                                viewModel.navigateToFolderFromSearch(folder)
                                                            }
                                                        }
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
} // Closes the Column (line 1378) to allow history overlay inside parent Box

            if (isFocused && searchHistory.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF0F0F15))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            focusManager.clearFocus()
                        }
                        .padding(8.dp)
                ) {
                    Text(
                        text = "Recent Searches",
                        color = Color.White.copy(alpha = 0.5f),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                    searchHistory.forEach { history ->
                        key(history.query) {
                            val dismissState = rememberSwipeToDismissBoxState(
                                confirmValueChange = { value ->
                                    if (value == SwipeToDismissBoxValue.EndToStart || value == SwipeToDismissBoxValue.StartToEnd) {
                                        viewModel.deleteSearchQuery(history.query)
                                        true
                                    } else {
                                        false
                                    }
                                }
                            )
                            SwipeToDismissBox(
                                state = dismissState,
                                backgroundContent = {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .background(Color.Red.copy(alpha = 0.2f))
                                            .padding(horizontal = 16.dp),
                                        contentAlignment = Alignment.CenterEnd
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Delete,
                                            contentDescription = "Delete",
                                            tint = Color.Red
                                        )
                                    }
                                },
                                content = {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(Color(0xFF0F0F15))
                                            .clickable {
                                                searchInput = history.query
                                                viewModel.performSearch(history.query)
                                                keyboardController?.hide()
                                                focusManager.clearFocus()
                                            }
                                            .padding(horizontal = 8.dp, vertical = 12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.History,
                                            contentDescription = "History",
                                            tint = Color.White.copy(alpha = 0.5f),
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Text(
                                            text = history.query,
                                            color = Color.White,
                                            fontSize = 15.sp
                                        )
                                    }
                                }
                            )
                        }
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
                    Spacer(modifier = Modifier.height(8.dp))
                    TextButton(
                        onClick = {
                            viewModel.clearSearchHistory()
                        },
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    ) {
                        Text("Clear Search History", color = NeonBlue, fontSize = 14.sp)
                    }
                }
            }
        }
    }

@Composable
fun BreadcrumbBar(
    navStack: List<CachedNode>,
    onBreadcrumbClick: (index: Int) -> Unit
) {
    Surface(
        color = SurfaceDark,
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = Color.White.copy(alpha = 0.05f)
            )
    ) {
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Root Node Item (Home Icon)
            item {
                Icon(
                    imageVector = Icons.Default.Home,
                    contentDescription = "Home",
                    tint = NeonBlue,
                    modifier = Modifier
                        .size(16.dp)
                        .clickable { onBreadcrumbClick(-1) }
                )
            }

            // Stack Items (ancestors only, excluding the last item which is the current folder/gallery)
            val ancestors = if (navStack.isNotEmpty()) navStack.dropLast(1) else emptyList()
            itemsIndexed(ancestors) { index, node ->
                // Separator
                Text(
                    text = " / ",
                    fontSize = 11.sp,
                    color = Color.White.copy(alpha = 0.3f),
                    modifier = Modifier.padding(horizontal = 2.dp)
                )

                // Folder title
                Text(
                    text = node.title,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Normal,
                    color = Color.White.copy(alpha = 0.6f),
                    modifier = Modifier.clickable { onBreadcrumbClick(index) }
                )
            }
        }
    }
}

@Composable
fun CollectionsTabView(
    viewModel: SmugViewModel,
    onNavigateToAlbum: (albumKey: String, albumTitle: String) -> Unit,
    onImageClick: (albumKey: String, imageKey: String) -> Unit
) {
    val collections by viewModel.localCollections.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var newCollectionName by remember { mutableStateOf("") }
    var selectedCollectionForShortcuts by remember { mutableStateOf<OfflineCollection?>(null) }
    
    val neonColors = listOf(
        Color(0xFF00E5FF),
        Color(0xFF00E676),
        Color(0xFFFF8F00),
        Color(0xFFFFEA00),
        Color(0xFFF50057),
        Color(0xFFD500F9)
    )

    if (selectedCollectionForShortcuts != null) {
        val col = selectedCollectionForShortcuts!!
        // Reactively lookup active name in case it changed
        val activeColName = collections.find { it.id == col.id }?.name ?: col.name
        val bookmarks by viewModel.getBookmarksForCollection(col.id).collectAsState(initial = emptyList())
        var isRenaming by remember { mutableStateOf(false) }
        var renameInputText by remember { mutableStateOf(activeColName) }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            // Header Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = {
                        isRenaming = false
                        selectedCollectionForShortcuts = null
                    }
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                }

                if (isRenaming) {
                    TextField(
                        value = renameInputText,
                        onValueChange = { renameInputText = it },
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                        colors = TextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedContainerColor = Color.White.copy(alpha = 0.05f),
                            unfocusedContainerColor = Color.White.copy(alpha = 0.05f),
                            focusedIndicatorColor = NeonBlue,
                            unfocusedIndicatorColor = Color.Transparent
                        ),
                        singleLine = true
                    )
                    IconButton(
                        onClick = {
                            if (renameInputText.isNotBlank()) {
                                viewModel.renameCollection(col.id, renameInputText.trim())
                                isRenaming = false
                            }
                        }
                    ) {
                        Icon(Icons.Default.Check, contentDescription = "Save Name", tint = NeonBlue)
                    }
                    IconButton(onClick = { isRenaming = false }) {
                        Icon(Icons.Default.Close, contentDescription = "Cancel", tint = Color.White)
                    }
                } else {
                    Text(
                        text = activeColName,
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 8.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    
                    // Rename Button
                    IconButton(
                        onClick = {
                            renameInputText = activeColName
                            isRenaming = true
                        }
                    ) {
                        Icon(Icons.Default.Edit, contentDescription = "Rename", tint = Color.White.copy(alpha = 0.7f))
                    }
                    
                    // Delete Button
                    IconButton(
                        onClick = {
                            viewModel.deleteCollection(col.id)
                            selectedCollectionForShortcuts = null
                        }
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete Collection", tint = Color.Red.copy(alpha = 0.8f))
                    }
                }
            }

            Divider(color = Color.White.copy(alpha = 0.08f), modifier = Modifier.padding(bottom = 12.dp))

            if (bookmarks.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "This collection is empty.\nBookmark items to see them here.",
                        color = Color.White.copy(alpha = 0.4f),
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                val folders = remember(bookmarks) { bookmarks.filter { it.type == "Folder" } }
                val albums = remember(bookmarks) { bookmarks.filter { it.type == "Album" } }
                val images = remember(bookmarks) { bookmarks.filter { it.type == "Image" } }
                val imagesByAlbum = remember(images) { images.groupBy { it.albumKey } }
                
                val expandedGalleries = remember { mutableStateMapOf<String, Boolean>() }

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Folders
                    if (folders.isNotEmpty()) {
                        item {
                            Text(
                                text = "Folders",
                                color = NeonBlue,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(vertical = 4.dp)
                            )
                        }
                        items(folders) { f ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Color.White.copy(alpha = 0.04f), RoundedCornerShape(8.dp))
                                    .clickable {
                                        viewModel.setActiveTab(BrowserTab.Folders)
                                        viewModel.loadFolderContents(f.itemKey)
                                    }
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Folder,
                                        contentDescription = "Folder",
                                        tint = NeonBlue.copy(alpha = 0.8f),
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Column {
                                        Text(f.title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                        Text("Folder Shortcut", color = Color.White.copy(alpha = 0.4f), fontSize = 11.sp)
                                    }
                                }
                                IconButton(
                                    onClick = { viewModel.removeBookmark(col.id, "Folder", f.itemKey) },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Unsave",
                                        tint = Color.White.copy(alpha = 0.5f),
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }

                    // Albums
                    if (albums.isNotEmpty()) {
                        item {
                            Text(
                                text = "Galleries",
                                color = NeonBlue,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                            )
                        }
                        items(albums) { a ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Color.White.copy(alpha = 0.04f), RoundedCornerShape(8.dp))
                                    .clickable {
                                        // a is a CollectionBookmark — use albumKey field directly (already the resolved album key)
                                        val albumKey = a.albumKey.ifBlank { a.itemKey }
                                        // Do NOT call selectAlbum here — PhotoGridScreen's LaunchedEffect handles the load.
                                        onNavigateToAlbum(albumKey, a.title)
                                    }
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.PhotoAlbum,
                                        contentDescription = "Gallery",
                                        tint = NeonBlue.copy(alpha = 0.8f),
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Column {
                                        Text(a.title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                        Text("Gallery Shortcut", color = Color.White.copy(alpha = 0.4f), fontSize = 11.sp)
                                    }
                                }
                                IconButton(
                                    onClick = { viewModel.removeBookmark(col.id, "Album", a.itemKey) },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Unsave",
                                        tint = Color.White.copy(alpha = 0.5f),
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }

                    // Images Grouped Section
                    if (imagesByAlbum.isNotEmpty()) {
                        item {
                            Text(
                                text = "Photos By Gallery",
                                color = NeonBlue,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                            )
                        }
                        imagesByAlbum.forEach { (albumKey, albumImages) ->
                            val groupTitle = albumImages.firstOrNull()?.albumTitle?.takeIf { it.isNotEmpty() } ?: "Gallery Group"
                            val isExpanded = expandedGalleries[albumKey] ?: false

                            item(key = albumKey) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(Color.White.copy(alpha = 0.04f), RoundedCornerShape(8.dp))
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { expandedGalleries[albumKey] = !isExpanded }
                                            .padding(horizontal = 12.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Icon(
                                                imageVector = if (isExpanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowRight,
                                                contentDescription = if (isExpanded) "Collapse" else "Expand",
                                                tint = Color.White.copy(alpha = 0.6f),
                                                modifier = Modifier.size(20.dp)
                                            )
                                            Text(
                                                text = groupTitle,
                                                color = Color.White,
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.Medium,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = "${albumImages.size} item${if (albumImages.size > 1) "s" else ""}",
                                            color = Color.White.copy(alpha = 0.4f),
                                            fontSize = 12.sp
                                        )
                                    }

                                    if (isExpanded) {
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(start = 16.dp, end = 12.dp, bottom = 8.dp),
                                            verticalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            albumImages.forEach { img ->
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .background(Color.White.copy(alpha = 0.02f), RoundedCornerShape(6.dp))
                                                        .clickable {
                                                            onImageClick(albumKey, img.itemKey)
                                                        }
                                                        .padding(horizontal = 8.dp, vertical = 6.dp),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.SpaceBetween
                                                ) {
                                                    Row(
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                                        modifier = Modifier.weight(1f)
                                                    ) {
                                                        if (!img.thumbnailUrl.isNullOrEmpty()) {
                                                            AsyncImage(
                                                                model = img.thumbnailUrl,
                                                                contentDescription = img.title,
                                                                contentScale = ContentScale.Crop,
                                                                modifier = Modifier
                                                                    .size(36.dp)
                                                                    .clip(RoundedCornerShape(4.dp))
                                                            )
                                                        } else {
                                                            Box(
                                                                modifier = Modifier
                                                                    .size(36.dp)
                                                                    .background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(4.dp)),
                                                                contentAlignment = Alignment.Center
                                                            ) {
                                                                Icon(
                                                                    imageVector = Icons.Default.Photo,
                                                                    contentDescription = null,
                                                                    tint = Color.White.copy(alpha = 0.3f),
                                                                    modifier = Modifier.size(16.dp)
                                                                )
                                                            }
                                                        }
                                                        Column {
                                                            Text(
                                                                text = img.title.takeIf { !it.isNullOrBlank() } ?: "Photo ${img.itemKey}",
                                                                color = Color.White.copy(alpha = 0.9f),
                                                                fontSize = 13.sp,
                                                                maxLines = 1,
                                                                overflow = TextOverflow.Ellipsis
                                                            )
                                                            Text("Image Shortcut", color = Color.White.copy(alpha = 0.4f), fontSize = 10.sp)
                                                        }
                                                    }
                                                    Row(
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                                    ) {
                                                        IconButton(
                                                            onClick = {
                                                                val detailItem = AlbumImageData(
                                                                    imageKey = img.itemKey,
                                                                    title = img.title,
                                                                    caption = img.title,
                                                                    thumbnailUrl = img.thumbnailUrl,
                                                                    archivedUri = img.extraData ?: img.thumbnailUrl,
                                                                    date = null,
                                                                    dateTime = null,
                                                                    keywords = null,
                                                                    webUri = null,
                                                                    originalWidth = null,
                                                                    originalHeight = null,
                                                                    format = "JPG",
                                                                    videoUrl = null
                                                                )
                                                                com.smugview.app.ui.detail.sharePhoto(context, scope, detailItem)
                                                            },
                                                            modifier = Modifier.size(24.dp)
                                                        ) {
                                                            Icon(
                                                                imageVector = Icons.Default.Share,
                                                                contentDescription = "Share",
                                                                tint = Color.White.copy(alpha = 0.7f),
                                                                modifier = Modifier.size(14.dp)
                                                            )
                                                        }
                                                        IconButton(
                                                            onClick = {
                                                                val detailItem = AlbumImageData(
                                                                    imageKey = img.itemKey,
                                                                    title = img.title,
                                                                    caption = img.title,
                                                                    thumbnailUrl = img.thumbnailUrl,
                                                                    archivedUri = img.extraData ?: img.thumbnailUrl,
                                                                    date = null,
                                                                    dateTime = null,
                                                                    keywords = null,
                                                                    webUri = null,
                                                                    originalWidth = null,
                                                                    originalHeight = null,
                                                                    format = "JPG",
                                                                    videoUrl = null
                                                                )
                                                                scope.launch {
                                                                    try {
                                                                        com.smugview.app.ui.detail.downloadPhotoToGallery(context, detailItem, viewModel)
                                                                    } catch (e: Exception) {
                                                                        Toast.makeText(context, "Download failed: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                                                                    }
                                                                }
                                                            },
                                                            modifier = Modifier.size(24.dp)
                                                        ) {
                                                            Icon(
                                                                imageVector = Icons.Default.Download,
                                                                contentDescription = "Download",
                                                                tint = Color.White.copy(alpha = 0.7f),
                                                                modifier = Modifier.size(14.dp)
                                                            )
                                                        }
                                                        IconButton(
                                                            onClick = { viewModel.removeBookmark(col.id, "Image", img.itemKey) },
                                                            modifier = Modifier.size(24.dp)
                                                        ) {
                                                            Icon(
                                                                imageVector = Icons.Default.Close,
                                                                contentDescription = "Unsave",
                                                                tint = Color.White.copy(alpha = 0.5f),
                                                                modifier = Modifier.size(14.dp)
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    } else {
        // Standard Collections Grid View list
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            // Create new collection entry row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = newCollectionName,
                    onValueChange = { newCollectionName = it },
                    label = { Text("New Collection Name", color = Color.White.copy(alpha = 0.6f)) },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = NeonBlue,
                        unfocusedBorderColor = Color.White.copy(alpha = 0.2f),
                        focusedLabelColor = NeonBlue,
                        unfocusedLabelColor = Color.White.copy(alpha = 0.6f),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    )
                )
                Spacer(modifier = Modifier.width(12.dp))
                Button(
                    onClick = {
                        if (newCollectionName.isNotBlank()) {
                            viewModel.createCollection(newCollectionName.trim())
                            newCollectionName = ""
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = NeonBlue),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.height(56.dp)
                ) {
                    Text("Create", fontWeight = FontWeight.Bold)
                }
            }

            if (collections.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No saved collections for this site yet.",
                        color = Color.White.copy(alpha = 0.5f),
                        fontSize = 14.sp
                    )
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentPadding = PaddingValues(bottom = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    items(collections) { col ->
                        val glowColor = neonColors[Math.abs(col.id.hashCode()) % neonColors.size]
                        Card(
                            shape = RoundedCornerShape(20.dp),
                            colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(130.dp)
                                .border(1.5.dp, glowColor.copy(alpha = 0.4f), RoundedCornerShape(20.dp))
                                .clickable {
                                    selectedCollectionForShortcuts = col
                                }
                        ) {
                            Box(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                                // Delete Button top-right
                                IconButton(
                                    onClick = { viewModel.deleteCollection(col.id) },
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .size(28.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Delete Collection",
                                        tint = Color.White.copy(alpha = 0.5f),
                                        modifier = Modifier.size(16.dp)
                                    )
                                }

                                // Info details column
                                Column(
                                    modifier = Modifier.fillMaxSize(),
                                    verticalArrangement = Arrangement.SpaceBetween,
                                    horizontalAlignment = Alignment.Start
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.PhotoAlbum,
                                        contentDescription = null,
                                        tint = glowColor,
                                        modifier = Modifier.size(32.dp)
                                    )
                                    Column {
                                        Text(
                                            text = col.name,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 15.sp,
                                            color = Color.White,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = "Saved Collection",
                                            fontSize = 11.sp,
                                            color = Color.White.copy(alpha = 0.5f)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TagSearchTabView(
    viewModel: SmugViewModel,
    onNavigateToKeywordImages: () -> Unit
) {
    val activeScope by viewModel.searchScope.collectAsState()
    val isScanning by viewModel.isScanningTags.collectAsState()
    val scanProgress by viewModel.scanProgress.collectAsState()
    val allTags by viewModel.allScopeTags.collectAsState()
    val selectedTags by viewModel.selectedTags.collectAsState()
    val tagCloud by viewModel.tagCloudTags.collectAsState()
    val filteredPhotos by viewModel.tagFilteredPhotos.collectAsState()

    var tagSearchInput by remember { mutableStateOf(viewModel.tagSearchQuery) }
    val focusManager = LocalFocusManager.current
    var isFocused by remember { mutableStateOf(false) }

    val keyboardController = LocalSoftwareKeyboardController.current
    val suggestions = remember(tagSearchInput, allTags, selectedTags) {
        val query = tagSearchInput.trim().lowercase()
        if (query.isEmpty()) {
            emptyList()
        } else {
            allTags.keys.filter {
                it.lowercase().contains(query) && !selectedTags.containsKey(it)
            }.take(5)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // 1. Search Box & Scope
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .onFocusChanged { isFocused = it.isFocused }
            ) {
                OutlinedTextField(
                    value = tagSearchInput,
                    onValueChange = {
                        tagSearchInput = it
                        viewModel.tagSearchQuery = it
                    },
                    placeholder = { Text("Enter tags...", color = Color.White.copy(alpha = 0.4f)) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.LocalOffer,
                            contentDescription = "Tag",
                            tint = NeonBlue
                        )
                    },
                    trailingIcon = {
                        if (tagSearchInput.isNotEmpty()) {
                            IconButton(onClick = {
                                tagSearchInput = ""
                                viewModel.tagSearchQuery = ""
                            }) {
                                Icon(Icons.Default.Close, contentDescription = "Clear", tint = Color.White.copy(alpha = 0.5f))
                            }
                        }
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Text,
                        imeAction = ImeAction.Search
                    ),
                    keyboardActions = KeyboardActions(
                        onSearch = {
                            if (tagSearchInput.isNotBlank()) {
                                viewModel.selectTag(tagSearchInput.trim())
                                tagSearchInput = ""
                                viewModel.tagSearchQuery = ""
                                keyboardController?.hide()
                                focusManager.clearFocus()
                            }
                        }
                    ),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = NeonBlue,
                        unfocusedBorderColor = Color.White.copy(alpha = 0.2f),
                        focusedContainerColor = SurfaceDark,
                        unfocusedContainerColor = SurfaceDark
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            IconButton(
                onClick = onNavigateToKeywordImages,
                enabled = selectedTags.isNotEmpty(),
                modifier = Modifier
                    .background(
                        if (selectedTags.isNotEmpty()) SurfaceDark else SurfaceDark.copy(alpha = 0.5f),
                        RoundedCornerShape(12.dp)
                    )
                    .border(
                        1.dp,
                        if (selectedTags.isNotEmpty()) Color.White.copy(alpha = 0.1f) else Color.White.copy(alpha = 0.05f),
                        RoundedCornerShape(12.dp)
                    )
            ) {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = "Search Images for Tags",
                    tint = if (selectedTags.isNotEmpty()) NeonBlue else Color.White.copy(alpha = 0.3f)
                )
            }
        }

        // Inline Autocomplete Card
        if (isFocused && suggestions.isNotEmpty()) {
            Spacer(modifier = Modifier.height(4.dp))
            Card(
                colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.1f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(end = 56.dp)
                    .heightIn(max = 200.dp)
            ) {
                LazyColumn {
                    items(suggestions) { suggestion ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.selectTag(suggestion)
                                    tagSearchInput = ""
                                    viewModel.tagSearchQuery = ""
                                    focusManager.clearFocus()
                                }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.LocalOffer,
                                contentDescription = null,
                                tint = NeonBlue.copy(alpha = 0.7f),
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = suggestion,
                                color = Color.White,
                                fontSize = 14.sp
                            )
                        }
                        HorizontalDivider(color = Color.White.copy(alpha = 0.05f))
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (!isScanning && scanProgress.contains("cancelled", ignoreCase = true)) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = SoftRed.copy(alpha = 0.15f),
                border = BorderStroke(1.dp, SoftRed.copy(alpha = 0.4f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = "Info",
                        tint = SoftRed,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = scanProgress,
                        color = Color.White.copy(alpha = 0.9f),
                        fontSize = 12.sp,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Dismiss",
                        tint = Color.White.copy(alpha = 0.5f),
                        modifier = Modifier
                            .size(16.dp)
                            .clickable { viewModel.clearScanProgress() }
                    )
                }
            }
        }

        // Scanning State / Loader
        if (isScanning) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 24.dp)
            ) {
                CircularProgressIndicator(color = NeonBlue)
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = scanProgress,
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 14.sp
                )
            }
        } else {
            // Main Content Area (Cloud, Active Filters, Photos)
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                // 2. Active Filters Chip Row
                if (selectedTags.isNotEmpty()) {
                    item {
                        Text(
                            text = "Active Tag Filters:",
                            color = Color.White.copy(alpha = 0.6f),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        FlowRow(modifier = Modifier.fillMaxWidth()) {
                            val firstTag = selectedTags.keys.firstOrNull()
                            selectedTags.forEach { (tag, state) ->
                                val isFirst = firstTag == tag
                                val containerColor = if (isFirst) {
                                    NeonBlue.copy(alpha = 0.2f)
                                } else if (state == com.smugview.app.ui.viewmodel.SmugViewModel.TagFilterState.INCLUDED) {
                                    NeonBlue.copy(alpha = 0.15f)
                                } else {
                                    SoftRed.copy(alpha = 0.2f)
                                }
                                val textColor = if (isFirst || state == com.smugview.app.ui.viewmodel.SmugViewModel.TagFilterState.INCLUDED) NeonBlue else SoftRed
                                val prefix = if (isFirst) "[+] " else if (state == com.smugview.app.ui.viewmodel.SmugViewModel.TagFilterState.INCLUDED) "+ " else "- "
                                
                                Surface(
                                    shape = RoundedCornerShape(16.dp),
                                    color = containerColor,
                                    border = BorderStroke(1.dp, textColor.copy(alpha = 0.3f)),
                                    modifier = Modifier
                                        .padding(end = 6.dp, bottom = 6.dp)
                                        .clickable {
                                            if (!isFirst) {
                                                viewModel.toggleTagState(tag)
                                            }
                                        }
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                    ) {
                                        Text(text = "$prefix$tag", color = textColor, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription = "Remove",
                                            tint = textColor.copy(alpha = 0.7f),
                                            modifier = Modifier
                                                .size(14.dp)
                                                .clickable { viewModel.removeTag(tag) }
                                        )
                                    }
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                    }
                }

                // 3. Tag Cloud Options and Cloud
                if (allTags.isNotEmpty()) {
                    item {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "Top Keywords",
                                color = Color.White,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                            
                            // Top 25 / 50 / 100 / 250 selector
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                listOf(25, 50, 100, 250).forEach { limit ->
                                    val isSelected = viewModel.tagCloudLimit == limit
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = if (isSelected) NeonBlue.copy(alpha = 0.2f) else SurfaceDark,
                                        border = BorderStroke(1.dp, if (isSelected) NeonBlue else Color.White.copy(alpha = 0.1f)),
                                        modifier = Modifier
                                            .clickable { viewModel.tagCloudLimit = limit }
                                    ) {
                                        Text(
                                            text = "$limit",
                                            color = if (isSelected) NeonBlue else Color.White.copy(alpha = 0.6f),
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                        )
                                    }
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                    }

                    item {
                        val counts = tagCloud.map { it.second }
                        val maxCount = counts.maxOrNull() ?: 1
                        val minCount = counts.minOrNull() ?: 1

                        FlowRow(modifier = Modifier.fillMaxWidth()) {
                            tagCloud.forEach { (tag, count) ->
                                val isSelected = selectedTags.containsKey(tag)
                                val baseSize = 12
                                val maxSize = 24
                                val fontSize = remember(count, minCount, maxCount) {
                                    if (maxCount == minCount) {
                                        baseSize.sp
                                    } else {
                                        val scale = (count - minCount).toFloat() / (maxCount - minCount).toFloat()
                                        (baseSize + scale * (maxSize - baseSize)).sp
                                    }
                                }
                                val textColor = if (isSelected) NeonBlue else Color.White.copy(alpha = 0.8f)
                                val bg = if (isSelected) NeonBlue.copy(alpha = 0.1f) else Color.Transparent
                                
                                Box(
                                    modifier = Modifier
                                        .padding(end = 8.dp, bottom = 8.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(bg)
                                        .clickable {
                                            if (isSelected) {
                                                viewModel.removeTag(tag)
                                            } else {
                                                viewModel.selectTag(tag)
                                            }
                                        }
                                        .then(if (isSelected) Modifier.border(1.dp, NeonBlue.copy(alpha = 0.5f), RoundedCornerShape(8.dp)) else Modifier)
                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Text(
                                        text = tag,
                                        fontSize = fontSize,
                                        color = textColor,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                    }
                }

                if (allTags.isEmpty()) {
                    item {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 48.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.LocalOffer,
                                contentDescription = "Tags Empty",
                                tint = Color.White.copy(alpha = 0.15f),
                                modifier = Modifier.size(80.dp)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "Scan this folder scope to aggregate tags",
                                color = Color.White.copy(alpha = 0.4f),
                                fontSize = 14.sp,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Button(
                                onClick = { viewModel.triggerTagScopeScan(activeScope) },
                                colors = ButtonDefaults.buttonColors(containerColor = NeonBlue)
                            ) {
                                Text("Scan Scope Now")
                            }
                        }
                    }
                }
            }
        }
    }
}
