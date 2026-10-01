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
