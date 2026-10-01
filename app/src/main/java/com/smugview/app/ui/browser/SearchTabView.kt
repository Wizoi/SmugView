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
import com.smugview.app.ui.theme.GlowGreen
import androidx.compose.foundation.BorderStroke
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
    val isGalleriesFoldersLoading by viewModel.isGalleriesFoldersLoading.collectAsState()
    val activeScope by viewModel.searchScope.collectAsState()
    val searchHistory by viewModel.searchHistory.collectAsState()
    val activeUpdates by viewModel.activeUpdateNodeIds.collectAsState()
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
                                text = when {
                                    isSearchPhotosLoading && isGalleriesFoldersLoading -> "Loading results..."
                                    isSearchPhotosLoading -> "Loading photos..."
                                    isGalleriesFoldersLoading -> "Loading galleries and folders..."
                                    else -> "Enter a word or phrase to search"
                                },
                                color = Color.White.copy(alpha = 0.4f),
                                fontWeight = FontWeight.Medium
                            )
                        }
                    } else {
                        Column(modifier = Modifier.fillMaxSize()) {
                            // Secondary indicator for tab selection. Galleries/Folders show a
                            // pending "…" instead of "(0)" while isGalleriesFoldersLoading is true
                            // — otherwise, since photos (decoupled, can arrive first) make this tab
                            // row appear before galleries/folders are resolved, "(0)" reads as "no
                            // results" rather than "still loading" (verified: this looked exactly
                            // like a broken/empty search for as long as background indexing ran).
                            val tabs = listOf(
                                "Photos (${searchPhotosPagingItems.itemCount})",
                                if (isGalleriesFoldersLoading) "Galleries (…)" else "Galleries (${state.galleries.size})",
                                if (isGalleriesFoldersLoading) "Folders (…)" else "Folders (${state.folders.size})"
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
                                                if (isGalleriesFoldersLoading) {
                                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                                        CircularProgressIndicator(color = NeonBlue, modifier = Modifier.size(28.dp))
                                                        Spacer(modifier = Modifier.height(12.dp))
                                                        Text("Loading galleries...", color = Color.White.copy(alpha = 0.4f))
                                                    }
                                                } else {
                                                    Text("No galleries found", color = Color.White.copy(alpha = 0.4f))
                                                }
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
                                                        val lock = rememberRowLock(viewModel, gallery)
                                                        val hasActiveUpdate = activeUpdates.contains(gallery.nodeId)
                                                        BrowserNodeItem(
                                                            node = gallery,
                                                            lock = lock,
                                                            hasActiveUpdate = hasActiveUpdate,
                                                            onMarkAsViewed = { viewModel.markNodeAsViewed(gallery.nodeId) },
                                                            onClick = {
                                                                viewModel.checkAndNavigateToAlbum(gallery) { albumKey ->
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
                                                if (isGalleriesFoldersLoading) {
                                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                                        CircularProgressIndicator(color = NeonBlue, modifier = Modifier.size(28.dp))
                                                        Spacer(modifier = Modifier.height(12.dp))
                                                        Text("Loading folders...", color = Color.White.copy(alpha = 0.4f))
                                                    }
                                                } else {
                                                    Text("No folders found", color = Color.White.copy(alpha = 0.4f))
                                                }
                                            }
                                        } else {
                                            androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid(
                                                columns = androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells.Fixed(2),
                                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                                verticalItemSpacing = 12.dp,
                                                modifier = Modifier.fillMaxSize()
                                            ) {
                                                items(state.folders, key = { it.nodeId }) { folder ->
                                                    val lock = rememberRowLock(viewModel, folder)
                                                    val hasActiveUpdate = activeUpdates.contains(folder.nodeId)
                                                    BrowserNodeItem(
                                                        node = folder,
                                                        lock = lock,
                                                        hasActiveUpdate = hasActiveUpdate,
                                                        onMarkAsViewed = { viewModel.markNodeAsViewed(folder.nodeId) },
                                                        onClick = {
                                                            viewModel.navigateToFolderFromSearch(folder)
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
