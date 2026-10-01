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


@Composable
fun CollectionsTabView(
    viewModel: SmugViewModel,
    onNavigateToAlbum: (albumKey: String, albumTitle: String) -> Unit,
    onImageClick: (albumKey: String, imageKey: String) -> Unit,
    onNavigateToCastController: (title: String) -> Unit
) {
    val collections by viewModel.localCollections.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var newCollectionName by remember { mutableStateOf("") }
    var selectedCollectionForShortcuts by remember { mutableStateOf<OfflineCollection?>(null) }

    val activeDevice by viewModel.activeCastDevice.collectAsState()
    val isCasting by viewModel.isCasting.collectAsState()
    val discoveredDevices by viewModel.discoveredDevices.collectAsState()
    var showCastSelector by remember { mutableStateOf(false) }
    var pendingCastCollectionId by remember { mutableStateOf<Long?>(null) }
    var pendingCastCollectionName by remember { mutableStateOf("") }
    
    // System back closes an open collection (R-14, owner Q2); with none open it falls through.
    BackHandler(enabled = BackPolicy.enabled(BrowserTab.Collections, 0, false, selectedCollectionForShortcuts != null)) {
        selectedCollectionForShortcuts = null
    }

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
                        items(folders, key = { com.smugview.app.ui.navigation.CollectionRowKeys.folder(it.itemKey) }) { f ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Color.White.copy(alpha = 0.04f), RoundedCornerShape(8.dp))
                                    .clickable {
                                        viewModel.openFolderShortcut(f.itemKey)
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
                        items(albums, key = { com.smugview.app.ui.navigation.CollectionRowKeys.album(it.itemKey) }) { a ->
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

                            item(key = com.smugview.app.ui.navigation.CollectionRowKeys.photoGroup(albumKey)) {
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
                    items(collections, key = { it.id }) { col ->
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
                                // Action buttons top-right (Cast & Delete)
                                Row(
                                    modifier = Modifier.align(Alignment.TopEnd),
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    IconButton(
                                        onClick = {
                                            if (isCasting) {
                                                viewModel.castCollection(col.id)
                                                onNavigateToCastController(col.name)
                                            } else {
                                                pendingCastCollectionId = col.id
                                                pendingCastCollectionName = col.name
                                                viewModel.startCastDiscovery()
                                                showCastSelector = true
                                            }
                                        },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.Cast,
                                            contentDescription = "Cast Collection",
                                            tint = if (isCasting) NeonBlue else Color.White.copy(alpha = 0.5f),
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }

                                    IconButton(
                                        onClick = { viewModel.deleteCollection(col.id) },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription = "Delete Collection",
                                            tint = Color.White.copy(alpha = 0.5f),
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
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

        if (showCastSelector) {
            com.smugview.app.ui.component.CastDeviceSelectorBottomSheet(
                devices = discoveredDevices,
                onDeviceSelected = { device ->
                    viewModel.connectToCastDevice(device)
                    showCastSelector = false
                    
                    pendingCastCollectionId?.let { colId ->
                        scope.launch {
                            delay(1200)
                            viewModel.castCollection(colId)
                            onNavigateToCastController(pendingCastCollectionName)
                            pendingCastCollectionId = null
                        }
                    }
                },
                onDismiss = {
                    showCastSelector = false
                    pendingCastCollectionId = null
                }
            )
        }
    }
}
