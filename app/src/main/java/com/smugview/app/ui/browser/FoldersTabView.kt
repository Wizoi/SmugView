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
import com.smugview.app.ui.viewmodel.BrowserUiState
import com.smugview.app.ui.text.ProblemAction
import com.smugview.app.ui.text.UserMessages
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
    val activeUpdates by viewModel.activeUpdateNodeIds.collectAsState()
    val siteHeaderImageUrl by viewModel.siteHeaderImageUrl.collectAsState()

    when (uiState) {
        is BrowserUiState.Loading -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = NeonBlue)
            }
        }
        is BrowserUiState.Error -> {
            // Design 3.3 (N2, R-47): the real cause and the way out. With no folder open the failure is the site's own
            // (its profile did not load), so Try again opens the site again; inside a folder it lists that folder again.
            val problem = uiState.problem
            val siteAttempt by viewModel.siteAttempt.collectAsState()
            val siteName = (siteAttempt ?: nickname)?.replaceFirstChar { it.uppercase() }
            val goesBack = UserMessages.primaryAction(problem) == ProblemAction.GoBack
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
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = UserMessages.body(problem, siteName),
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
                if (!goesBack) {
                    Button(
                        onClick = {
                            val open = currentFolderId
                            if (open == null) viewModel.retryActiveSite() else viewModel.loadFolderContents(open, forceRefresh = true)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = NeonBlue)
                    ) {
                        Text(UserMessages.BUTTON_TRY_AGAIN)
                    }
                }
                if (goesBack || navStack.isNotEmpty()) {
                    TextButton(onClick = {
                        // Inside a folder: back to its parent. At the site root there is no parent: leave the site.
                        if (navStack.isEmpty()) viewModel.disconnectSite() else viewModel.navigateBackFolder()
                    }) {
                        Text(UserMessages.BUTTON_GO_BACK)
                    }
                }
            }
        }
        is BrowserUiState.Success -> {
            val staggeredGridState = rememberLazyStaggeredGridState()
            var showFolderBookmarkDialog by remember { mutableStateOf(false) }
            var showShareDialog by remember { mutableStateOf(false) }

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

            val isRoot = navStack.isEmpty()
            // At the site root, title the header with the site's name (fall back to the nickname)
            // instead of a generic "Galleries".
            val siteName = activeUserProfile?.name?.takeIf { it.isNotBlank() }
                ?: nickname?.replaceFirstChar { it.uppercase() }
                ?: "Galleries"
            val folderTitle = navStack.lastOrNull()?.title ?: siteName
            // At the site root, use the site's own configured header image (its HighlightImage),
            // not an arbitrary child album's cover — that's what the website itself shows as its
            // header, and is what should appear on the homepage.
            val coverUrl = navStack.lastOrNull()?.highlightImageUrl
                ?: siteHeaderImageUrl
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
                            val lock = rememberRowLock(viewModel, node)
                            val hasActiveUpdate = activeUpdates.contains(node.nodeId)
                            BrowserNodeItem(
                                node = node,
                                lock = lock,
                                hasActiveUpdate = hasActiveUpdate,
                                onMarkAsViewed = { viewModel.markNodeAsViewed(node.nodeId) },
                                onClick = {
                                    if (node.type == "Folder") {
                                        // The navigator asks needsPassword and prompts for the password root.
                                        viewModel.navigateToChildFolder(node)
                                    } else {
                                        // Use checkAndNavigateToAlbum to ensure pre-flight checks are run
                                        viewModel.checkAndNavigateToAlbum(node) { albumKey ->
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
                            // Read in the layer, not in composition: the offset changes every scroll frame.
                            val offset = scrollOffset.value
                            translationY = if (headerHeightPx > 0f) (-offset * 0.5f).coerceAtLeast(-headerHeightPx) else 0f
                            alpha = if (headerHeightPx > 0f) 1f - (offset / headerHeightPx).coerceIn(0f, 1f) else 1f
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
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // At the site root, show the site logo to the left of the name.
                            if (isRoot && nickname != null) {
                                ProfileAvatar(
                                    nickname = nickname!!,
                                    name = siteName,
                                    bioImageThumbnailUrl = activeUserProfile?.bioImageThumbnailUrl,
                                    modifier = Modifier.size(40.dp),
                                    textColor = Color.White,
                                    fontSize = 18.sp
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                            }
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
                        }

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

                    // Action buttons (Search, Bookmark, Share, Refresh)
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
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

                        // Share (opens the QR code dialog, which also offers the native share sheet)
                        IconButton(
                            onClick = { showShareDialog = true },
                            modifier = Modifier.background(Color.Black.copy(alpha = 0.3f), CircleShape)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Share,
                                contentDescription = "Share Folder",
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

            if (showShareDialog) {
                val shareUrl = navStack.lastOrNull()?.webUri
                    ?: activeUserProfile?.webUri
                    ?: "https://${nickname}.smugmug.com"
                com.smugview.app.ui.component.QrShareDialog(
                    title = folderTitle,
                    url = shareUrl,
                    onDismissRequest = { showShareDialog = false }
                )
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

data class FeaturedSite(
    val nickname: String,
    val name: String,
    val description: String,
    val coverUrl: String,
    val previewUrls: List<String>
)
