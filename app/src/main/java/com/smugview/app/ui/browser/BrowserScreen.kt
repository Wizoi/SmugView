package com.smugview.app.ui.browser

import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
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
import com.smugview.app.data.repository.UnlockManager
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material.icons.filled.Cast
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
import com.smugview.app.ui.component.ProfilePreviewCard
import com.smugview.app.ui.component.ProfileAvatar
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
import com.smugview.app.ui.theme.GlowGreen
import androidx.compose.foundation.BorderStroke
import com.smugview.app.ui.viewmodel.BackPolicy
import com.smugview.app.ui.viewmodel.BrowserUiState
import com.smugview.app.ui.viewmodel.BrowserTab
import com.smugview.app.ui.viewmodel.SearchUiState
import com.smugview.app.ui.viewmodel.SplashUiState
import com.smugview.app.ui.viewmodel.SmugViewModel
import com.smugview.app.ui.viewmodel.GlobalSearchUiState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun BrowserScreen(
    onNavigateToAlbum: (albumKey: String, albumTitle: String) -> Unit,
    onNavigateToExplorer: () -> Unit,
    onNavigateToSearchPhotoDetail: (imageKey: String, index: Int) -> Unit,
    onNavigateToPhotoDetail: (albumKey: String, imageKey: String) -> Unit,
    onNavigateToKeywordImages: () -> Unit,
    onNavigateToCastController: (title: String) -> Unit,
    viewModel: SmugViewModel = hiltViewModel()
) {
    val uiState by viewModel.browserState.collectAsState()
    val navStack = viewModel.folderNavigationStack
    val currentFolderId = viewModel.currentFolderId
    val activeTab by viewModel.activeTab.collectAsState()
    var selectedImageForDetail by remember { mutableStateOf<AlbumImageData?>(null) }

    // System back (R-14, owner Q2): on the Folders tab it pops the folder stack or returns to Search;
    // on every other tab the Android default applies. An open collection is closed by
    // CollectionsTabView's own handler, which owns that state, so it passes false here.
    val cameFromSearch = viewModel.savedFolderStateBeforeSearch != null
    BackHandler(enabled = BackPolicy.enabled(activeTab, navStack.size, cameFromSearch, openCollection = false)) {
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
                            TextButton(
                                onClick = {
                                    viewModel.disconnectSite()
                                    onNavigateToExplorer()
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ExitToApp,
                                    contentDescription = "Exit Site",
                                    tint = NeonBlue,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Exit Site",
                                    color = NeonBlue,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
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
                                bioImageThumbnailUrl = activeUserProfile?.bioImageThumbnailUrl,
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
                        HomeTabView(
                            viewModel = viewModel,
                            onNavigateToAlbum = onNavigateToAlbum,
                            onNavigateToPhotoDetail = onNavigateToPhotoDetail,
                            onNavigateToKeywordImages = onNavigateToKeywordImages
                        )
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
                            },
                            onNavigateToCastController = onNavigateToCastController
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
                PasswordPromptHost(viewModel)

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

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BrowserNodeItem(
    node: CachedNode,
    lock: UnlockManager.RowLock,
    hasActiveUpdate: Boolean,
    onMarkAsViewed: () -> Unit,
    onClick: () -> Unit
) {
    val isUnlocked = lock == UnlockManager.RowLock.Open
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
    var showMenu by remember { mutableStateOf(false) }

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(aspectRatio)
            .clip(RoundedCornerShape(16.dp))
            .border(1.5.dp, glowColor.copy(alpha = 0.45f), RoundedCornerShape(16.dp))
            .combinedClickable(
                onClick = onClick,
                onLongClick = { showMenu = true }
            )
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            DropdownMenu(
                expanded = showMenu,
                onDismissRequest = { showMenu = false },
                modifier = Modifier.background(SurfaceDark)
            ) {
                DropdownMenuItem(
                    text = { Text("Mark as Viewed", color = Color.White) },
                    onClick = {
                        showMenu = false
                        onMarkAsViewed()
                    }
                )
            }
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
                // Top Row (Active Update Dot & Lock Badge)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (hasActiveUpdate) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .border(2.dp, Color(0xFF00F0FF).copy(alpha = 0.4f), CircleShape)
                                .clip(CircleShape)
                                .background(Color(0xFF00F0FF))
                        )
                    } else {
                        Spacer(modifier = Modifier.size(10.dp))
                    }

                    if (lock != UnlockManager.RowLock.None) {
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
    onDismiss: () -> Unit,
    note: String? = null
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

                if (note != null) {
                    // PASSWORD_NOT_KEPT: not an error (the password works), so not the error colour.
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = note,
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 12.sp,
                        modifier = Modifier.testTag("passwordNotKeptNote")
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


/**
 * The lock a list row shows (design 7 Q7): Locked when [SmugViewModel.needsPassword] would prompt, Open
 * for an unlocked password root, nothing for a sub-folder under an unlocked root. Recomputed when any
 * root's access changes (a prompt succeeding or a saved password being rejected).
 */
@Composable
fun rememberRowLock(viewModel: SmugViewModel, node: CachedNode): UnlockManager.RowLock {
    val access by viewModel.passwordAccess.collectAsState()
    val saved by viewModel.unlockedNodeIds.collectAsState()
    val lock by produceState(UnlockManager.RowLock.None, node, access, saved) {
        value = viewModel.lockOf(node)
    }
    return lock
}

/**
 * The one place a password prompt is drawn (design 3.5, R-45). The Folders tab, the gallery grid and the photo viewer each
 * host it, so the prompt shows wherever the lock was met. [onDismiss] runs after the prompt is dismissed: a grid or a viewer
 * with nothing behind the prompt goes back.
 */
@Composable
fun PasswordPromptHost(viewModel: SmugViewModel, onDismiss: () -> Unit = {}) {
    viewModel.passwordPromptNode?.let { node ->
        PasswordPromptDialog(
            node = node,
            error = viewModel.passwordError,
            note = viewModel.passwordNotKeptNote,
            onSubmit = { password -> viewModel.submitPassword(password) },
            onDismiss = {
                viewModel.dismissPasswordPrompt()
                onDismiss()
            }
        )
    }
}
