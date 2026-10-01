package com.smugview.app.ui.browser

import com.smugview.app.data.repository.UnlockManager
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


val featuredSitesList = listOf(
    FeaturedSite(
        nickname = "smugmugfilms",
        name = "Official SmugMug Films",
        description = "Curated highlights, community spotlights, and beautiful storytelling from SmugMug.",
        coverUrl = "https://images.unsplash.com/photo-1542038784456-1ea8e935640e?w=500&auto=format&fit=crop",
        previewUrls = listOf(
            "https://images.unsplash.com/photo-1542038784456-1ea8e935640e?w=200&auto=format&fit=crop",
            "https://images.unsplash.com/photo-1452784444945-3f422708fe5e?w=200&auto=format&fit=crop",
            "https://images.unsplash.com/photo-1492691527719-9d1e07e534b4?w=200&auto=format&fit=crop"
        )
    ),
    FeaturedSite(
        nickname = "Tutorial",
        name = "SmugMug Help & Tutorials",
        description = "Official training materials, sample galleries, and step-by-step guides from the SmugMug help team.",
        coverUrl = "https://images.unsplash.com/photo-1434030216411-0b793f4b4173?w=500&auto=format&fit=crop",
        previewUrls = listOf(
            "https://images.unsplash.com/photo-1434030216411-0b793f4b4173?w=200&auto=format&fit=crop",
            "https://images.unsplash.com/photo-1516321318423-f06f85e504b3?w=200&auto=format&fit=crop",
            "https://images.unsplash.com/photo-1456513080510-7bf3a84b82f8?w=200&auto=format&fit=crop"
        )
    ),
    FeaturedSite(
        nickname = "uphs",
        name = "Union Pacific Historical Society",
        description = "Preserving the history of the Union Pacific Railroad with archives of train depots, locomotives, and maps.",
        coverUrl = "https://images.unsplash.com/photo-1474487548417-781cb71495f3?w=500&auto=format&fit=crop",
        previewUrls = listOf(
            "https://images.unsplash.com/photo-1474487548417-781cb71495f3?w=200&auto=format&fit=crop",
            "https://images.unsplash.com/photo-1515165504660-f14d8f08a9f8?w=200&auto=format&fit=crop",
            "https://images.unsplash.com/photo-1532103054090-334e6e60ab29?w=200&auto=format&fit=crop"
        )
    ),
    FeaturedSite(
        nickname = "corvettemuseum",
        name = "National Corvette Museum",
        description = "Showcasing Corvette displays, drag racing, club events, and automotive museum highlights.",
        coverUrl = "https://images.unsplash.com/photo-1552519507-da3b142c6e3d?w=500&auto=format&fit=crop",
        previewUrls = listOf(
            "https://images.unsplash.com/photo-1552519507-da3b142c6e3d?w=200&auto=format&fit=crop",
            "https://images.unsplash.com/photo-1614162692292-7ac56d7f7f1e?w=200&auto=format&fit=crop",
            "https://images.unsplash.com/photo-1583121274602-3e2820c69888?w=200&auto=format&fit=crop"
        )
    ),
    FeaturedSite(
        nickname = "daemenuniversity",
        name = "Daemen University Galleries",
        description = "Official photo archives of campus events, graduation ceremonies, and student athletics at Daemen.",
        coverUrl = "https://images.unsplash.com/photo-1523050854058-8df90110c9f1?w=500&auto=format&fit=crop",
        previewUrls = listOf(
            "https://images.unsplash.com/photo-1523050854058-8df90110c9f1?w=200&auto=format&fit=crop",
            "https://images.unsplash.com/photo-1541339907198-e08756dedf3f?w=200&auto=format&fit=crop",
            "https://images.unsplash.com/photo-1524178232363-1fb2b075b655?w=200&auto=format&fit=crop"
        )
    )
)

val suggestionTagsList = listOf("Nature", "Wildlife", "Travel", "Landscape", "Sports", "Wedding", "Portraits", "Macro", "Astrophotography")
val suggestionNicknamesList = listOf("smugmugfilms", "Tutorial", "uphs", "corvettemuseum", "daemenuniversity")

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeTabView(
    viewModel: SmugViewModel,
    onNavigateToAlbum: (albumKey: String, albumTitle: String) -> Unit,
    onNavigateToPhotoDetail: (albumKey: String, imageKey: String) -> Unit,
    onNavigateToKeywordImages: () -> Unit
) {
    val nickname by viewModel.activeNickname.collectAsState()
    val activeUserProfile by viewModel.activeUserProfile.collectAsState()
    val previewAlbums by viewModel.previewAlbums.collectAsState()
    val focusManager = LocalFocusManager.current
    
    if (nickname.isNullOrEmpty()) {
        // --- SITE DISCOVERY DASHBOARD ---
        val recentSites by viewModel.recentSites.collectAsState()
        val sitePreview by viewModel.sitePreview.collectAsState()
        val globalSearchState by viewModel.globalSearchState.collectAsState()
        
        var nicknameQuery by remember { mutableStateOf("") }
        var isFocused by remember { mutableStateOf(false) }

        // Debounce live validation for direct matching (500ms delay)
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

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(24.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                // Header Title
                item {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "Explore SmugMug",
                            fontSize = 32.sp,
                            fontWeight = FontWeight.Black,
                            color = Color.White,
                            letterSpacing = 1.sp
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = "Discover professional portfolios or search by keyword",
                            fontSize = 14.sp,
                            color = Color.White.copy(alpha = 0.5f),
                            textAlign = TextAlign.Center
                        )
                    }
                }

                // Glowing Search Input Field with Suggestions
                item {
                    val keyboardController = LocalSoftwareKeyboardController.current
                    Column(modifier = Modifier.fillMaxWidth()) {
                        OutlinedTextField(
                            value = nicknameQuery,
                            onValueChange = { 
                                nicknameQuery = it 
                                if (it.isBlank()) {
                                    viewModel.clearGlobalSiteSearch()
                                }
                            },
                            placeholder = { Text("Search by nickname or keyword", color = Color.White.copy(alpha = 0.4f)) },
                            leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search", tint = NeonBlue) },
                            trailingIcon = {
                                if (nicknameQuery.isNotEmpty()) {
                                    IconButton(onClick = {
                                        nicknameQuery = ""
                                        viewModel.clearGlobalSiteSearch()
                                        focusManager.clearFocus()
                                    }) {
                                        Icon(Icons.Default.Close, contentDescription = "Clear", tint = Color.White.copy(alpha = 0.5f))
                                    }
                                }
                            },
                            singleLine = true,
                            shape = RoundedCornerShape(24.dp),
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Text,
                                imeAction = ImeAction.Search
                            ),
                            keyboardActions = KeyboardActions(
                                onSearch = {
                                    if (nicknameQuery.isNotEmpty()) {
                                        viewModel.searchPublicSites(nicknameQuery)
                                    }
                                    keyboardController?.hide()
                                    focusManager.clearFocus()
                                }
                            ),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedContainerColor = SurfaceDark,
                                unfocusedContainerColor = SurfaceDark,
                                focusedBorderColor = NeonBlue,
                                unfocusedBorderColor = Color.Transparent
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .onFocusChanged { isFocused = it.isFocused }
                                .border(
                                    width = 1.5.dp,
                                    brush = Brush.horizontalGradient(listOf(NeonBlue, GlowBorder)),
                                    shape = RoundedCornerShape(24.dp)
                                )
                        )

                        // Inline Autocomplete suggestion row when typing or focused
                        AnimatedVisibility(visible = isFocused || nicknameQuery.isNotEmpty()) {
                            Column(modifier = Modifier.padding(top = 8.dp)) {
                                val filteredTags = suggestionTagsList.filter { 
                                    it.contains(nicknameQuery, ignoreCase = true) 
                                }
                                val filteredNicknames = suggestionNicknamesList.filter {
                                    it.contains(nicknameQuery, ignoreCase = true)
                                }

                                if (filteredTags.isNotEmpty() || filteredNicknames.isNotEmpty()) {
                                    Text(
                                        text = "Suggestions",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = NeonBlue,
                                        modifier = Modifier.padding(start = 8.dp, bottom = 4.dp)
                                    )
                                    
                                    FlowRow(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp),
                                        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)
                                    ) {
                                        // Show Nicknames Suggestions first
                                        filteredNicknames.forEach { site ->
                                            SuggestionChip(
                                                onClick = {
                                                    nicknameQuery = site
                                                    viewModel.searchPublicSites(site)
                                                    viewModel.verifyAndPreviewNickname(site)
                                                    keyboardController?.hide()
                                                    focusManager.clearFocus()
                                                },
                                                label = { Text(site, color = Color.White) },
                                                colors = SuggestionChipDefaults.suggestionChipColors(
                                                    containerColor = SurfaceDark
                                                ),
                                                border = SuggestionChipDefaults.suggestionChipBorder(
                                                    enabled = true,
                                                    borderColor = GlowBorder.copy(alpha = 0.6f),
                                                    borderWidth = 1.dp
                                                )
                                            )
                                        }

                                        // Show Tag Suggestions
                                        filteredTags.forEach { tag ->
                                            SuggestionChip(
                                                onClick = {
                                                    nicknameQuery = tag
                                                    viewModel.searchPublicSites(tag)
                                                    keyboardController?.hide()
                                                    focusManager.clearFocus()
                                                },
                                                label = { Text(tag, color = Color.White) },
                                                colors = SuggestionChipDefaults.suggestionChipColors(
                                                    containerColor = SurfaceDark
                                                ),
                                                border = SuggestionChipDefaults.suggestionChipBorder(
                                                    enabled = true,
                                                    borderColor = NeonBlue.copy(alpha = 0.6f),
                                                    borderWidth = 1.dp
                                                )
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Global search state visual routing
                when (val state = globalSearchState) {
                    is GlobalSearchUiState.Idle -> {
                        // 1. Recently Explored Sites list
                        if (recentSites.isNotEmpty()) {
                            item {
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

                                    Spacer(modifier = Modifier.height(10.dp))

                                    LazyRow(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        items(recentSites, key = { it }) { site ->
                                            Box(
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(12.dp))
                                                    .background(SurfaceDark)
                                                    .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)), RoundedCornerShape(12.dp))
                                                    .clickable { 
                                                        nicknameQuery = site
                                                        viewModel.searchPublicSites(site)
                                                        viewModel.verifyAndPreviewNickname(site)
                                                    }
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
                        }

                        // 2. Popular Tags Suggestion Chips
                        item {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    text = "Popular Categories",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White.copy(alpha = 0.5f)
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    suggestionTagsList.take(6).forEach { tag ->
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(16.dp))
                                                .background(SurfaceDark)
                                                .border(BorderStroke(1.dp, NeonBlue.copy(alpha = 0.3f)), RoundedCornerShape(16.dp))
                                                .clickable {
                                                    nicknameQuery = tag
                                                    viewModel.searchPublicSites(tag)
                                                }
                                                .padding(horizontal = 14.dp, vertical = 8.dp)
                                        ) {
                                            Text(
                                                text = tag,
                                                color = Color.White,
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Medium
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // 3. Featured Portfolio Galleries (Carousel)
                        item {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    text = "Featured Photographers",
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = Color.White
                                )
                                Spacer(modifier = Modifier.height(14.dp))
                                LazyRow(
                                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    items(featuredSitesList) { site ->
                                        FeaturedSiteCard(
                                            site = site,
                                            onEnterSite = { viewModel.selectSite(site.nickname) }
                                        )
                                    }
                                }
                            }
                        }
                    }

                    is GlobalSearchUiState.Loading -> {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(250.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    CircularProgressIndicator(color = NeonBlue)
                                    Spacer(modifier = Modifier.height(16.dp))
                                    Text(
                                        text = "Searching public sites matching \"$nicknameQuery\"...",
                                        color = Color.White.copy(alpha = 0.5f),
                                        fontSize = 13.sp
                                    )
                                }
                            }
                        }
                    }

                    is GlobalSearchUiState.Error -> {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.1f))
                                    .border(1.dp, MaterialTheme.colorScheme.error, RoundedCornerShape(16.dp))
                                    .padding(24.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        text = state.message,
                                        color = MaterialTheme.colorScheme.error,
                                        fontWeight = FontWeight.Medium,
                                        fontSize = 14.sp
                                    )
                                    Spacer(modifier = Modifier.height(12.dp))
                                    Button(
                                        onClick = { viewModel.searchPublicSites(nicknameQuery) },
                                        colors = ButtonDefaults.buttonColors(containerColor = NeonBlue)
                                    ) {
                                        Text("Retry")
                                    }
                                }
                            }
                        }
                    }

                    is GlobalSearchUiState.Success -> {
                        // A. Direct Nickname Profile Match (if any resolved)
                        if (sitePreview != null) {
                            item {
                                sitePreview?.fold(
                                    onSuccess = { userData ->
                                        Column(modifier = Modifier.fillMaxWidth()) {
                                            Text(
                                                text = "Direct Profile Match",
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = GlowGreen,
                                                modifier = Modifier.padding(bottom = 10.dp)
                                            )
                                            Card(
                                                shape = RoundedCornerShape(24.dp),
                                                colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .border(BorderStroke(1.5.dp, GlowGreen.copy(alpha = 0.5f)), RoundedCornerShape(24.dp))
                                            ) {
                                                Column(modifier = Modifier.padding(20.dp)) {
                                                    Row(
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        modifier = Modifier.fillMaxWidth()
                                                    ) {
                                                        ProfileAvatar(
                                                            nickname = userData.nickName,
                                                            name = userData.name,
                                                            bioImageKey = userData.bioImageKey,
                                                            modifier = Modifier.size(56.dp),
                                                            textColor = Color.White,
                                                            fontSize = 20.sp
                                                        )
                                                        Spacer(modifier = Modifier.width(16.dp))
                                                        Column(modifier = Modifier.weight(1f)) {
                                                            Text(
                                                                text = userData.name.ifEmpty { userData.nickName },
                                                                fontWeight = FontWeight.Bold,
                                                                fontSize = 18.sp,
                                                                color = Color.White
                                                            )
                                                            Text(
                                                                text = "@${userData.nickName}",
                                                                fontSize = 13.sp,
                                                                color = NeonBlue
                                                            )
                                                        }
                                                    }
                                                    
                                                    Spacer(modifier = Modifier.height(16.dp))
                                                    
                                                    Button(
                                                        onClick = { viewModel.selectSite(userData.nickName) },
                                                        colors = ButtonDefaults.buttonColors(containerColor = GlowGreen),
                                                        modifier = Modifier.fillMaxWidth(),
                                                        shape = RoundedCornerShape(12.dp)
                                                    ) {
                                                        Text("Connect & Browse Site", color = Color.Black, fontWeight = FontWeight.Bold)
                                                    }
                                                }
                                            }
                                        }
                                    },
                                    onFailure = {
                                        // Ignore direct match failures, show global search results only
                                    }
                                )
                            }
                        }

                        // B. Grouped Discovered Sites
                        val discoveredSites = state.sites
                        if (discoveredSites.isEmpty()) {
                            item {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 48.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Icon(
                                            imageVector = Icons.Default.Photo,
                                            contentDescription = "No Results",
                                            tint = Color.White.copy(alpha = 0.1f),
                                            modifier = Modifier.size(72.dp)
                                        )
                                        Spacer(modifier = Modifier.height(16.dp))
                                        Text(
                                            text = "No public sites found matching \"$nicknameQuery\"",
                                            color = Color.White.copy(alpha = 0.4f),
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Medium,
                                            textAlign = TextAlign.Center
                                        )
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Text(
                                            text = "Try searching for Nature, Wildlife, or Travel",
                                            color = Color.White.copy(alpha = 0.3f),
                                            fontSize = 12.sp,
                                            textAlign = TextAlign.Center
                                        )
                                    }
                                }
                            }
                        } else {
                            item {
                                Text(
                                    text = "Discovered Portfolios (${discoveredSites.size})",
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White.copy(alpha = 0.5f)
                                )
                            }

                            items(discoveredSites, key = { it.nickname }) { site ->
                                DiscoveredSiteCard(
                                    site = site,
                                    onExploreSite = { viewModel.selectSite(site.nickname) }
                                )
                            }
                        }
                    }
                }
            }
        }
    } else {
        // --- ACTIVE SITE HUB PROFILE VIEW ---
        val recentImages by viewModel.activeSiteRecentImages.collectAsState()
        val albums by viewModel.activeSiteAlbums.collectAsState()
        val topKeywords by viewModel.activeSiteTopKeywords.collectAsState()
        val isDetailsLoading by viewModel.isActiveSiteDetailsLoading.collectAsState()

        val totalGalleriesOpt by viewModel.activeSiteTotalGalleries.collectAsState()
        val totalPhotosOpt by viewModel.activeSiteTotalPhotos.collectAsState()

        var showShareDialog by remember { mutableStateOf(false) }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Top
        ) {
            Spacer(modifier = Modifier.height(16.dp))
            
            // Active Profile Card (Slimmed Down with Stats)
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, NeonBlue.copy(alpha = 0.3f), RoundedCornerShape(16.dp))
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ProfileAvatar(
                            nickname = nickname!!,
                            name = nickname!!,
                            bioImageKey = activeUserProfile?.bioImageKey,
                            modifier = Modifier.size(54.dp),
                            textColor = Color.White,
                            fontSize = 22.sp
                        )
                        
                        Spacer(modifier = Modifier.width(16.dp))
                        
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = nickname!!.replaceFirstChar { it.uppercase() },
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp,
                                color = Color.White
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = activeUserProfile?.webUri ?: "Public Portfolio",
                                fontSize = 11.sp,
                                color = Color.White.copy(alpha = 0.4f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        IconButton(
                            onClick = { showShareDialog = true }
                        ) {
                            Icon(
                                imageVector = Icons.Default.Share,
                                contentDescription = "Share",
                                tint = NeonBlue
                            )
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(14.dp))
                    HorizontalDivider(color = Color.White.copy(alpha = 0.08f), thickness = 1.dp)
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    // Compute Last Active date modified
                    val lastActiveDate = remember(albums) {
                        val latestIso = albums.mapNotNull { it.dateModified }.maxOrNull()
                        if (latestIso != null) {
                            try {
                                latestIso.substringBefore("T")
                            } catch (e: Exception) {
                                "Recently"
                            }
                        } else {
                            "Recently"
                        }
                    }
                    
                    // Format galleries and photos
                    val totalGalleriesCount = totalGalleriesOpt ?: albums.size
                    val totalPhotosCount = totalPhotosOpt
                    
                    val galleriesVal = remember(totalGalleriesCount) {
                        java.text.NumberFormat.getIntegerInstance().format(totalGalleriesCount)
                    }
                    
                    val photosVal = remember(totalPhotosCount, albums) {
                        if (totalPhotosCount != null) {
                            java.text.NumberFormat.getIntegerInstance().format(totalPhotosCount)
                        } else {
                            var computed = 0
                            for (album in albums) {
                                computed += album.imageCount
                            }
                            if (computed > 0) {
                                java.text.NumberFormat.getIntegerInstance().format(computed)
                            } else {
                                "--"
                            }
                        }
                    }
                    
                    // Public Site Statistics Bar
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        StatItem(label = "Galleries", value = galleriesVal)
                        StatDivider()
                        StatItem(label = "Photos", value = photosVal)
                        StatDivider()
                        StatItem(label = "Last Active", value = lastActiveDate)
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(32.dp))
            
            // Featured Galleries Carousel
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Featured Galleries",
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 18.sp,
                    color = Color.White,
                    modifier = Modifier.align(Alignment.Start)
                )
                Spacer(modifier = Modifier.height(12.dp))
                
                if (isDetailsLoading) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(130.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(SurfaceDark.copy(alpha = 0.5f)),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = NeonBlue, modifier = Modifier.size(24.dp))
                    }
                } else if (albums.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(130.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(16.dp))
                            .background(SurfaceDark),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("No public galleries found.", color = Color.White.copy(alpha = 0.4f), fontSize = 13.sp)
                    }
                } else {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        items(albums, key = { it.albumKey }) { album ->
                            Card(
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                                modifier = Modifier
                                    .width(180.dp)
                                    .height(130.dp)
                                    .border(1.dp, NeonBlue.copy(alpha = 0.2f), RoundedCornerShape(16.dp))
                                    .clickable {
                                        val node = com.smugview.app.data.db.CachedNode(
                                            nodeId = album.albumKey,
                                            parentNodeId = "root",
                                            type = "Album",
                                            title = album.title,
                                            description = null,
                                            access = album.access,
                                            passwordHint = album.passwordHint,
                                            uri = "/api/v2/album/${album.albumKey}",
                                            childNodesUri = null,
                                            albumUri = "/api/v2/album/${album.albumKey}"
                                        )
                                        viewModel.checkAndNavigateToAlbum(node) { albumKey ->
                                            onNavigateToAlbum(albumKey, album.title)
                                        }
                                    }
                            ) {
                                Box(modifier = Modifier.fillMaxSize()) {
                                    if (album.coverUrl != null) {
                                        AsyncImage(
                                            model = album.coverUrl,
                                            contentDescription = album.title,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    } else {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .background(SurfaceDark),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = Icons.Filled.Folder,
                                                contentDescription = "Folder",
                                                tint = NeonBlue.copy(alpha = 0.6f),
                                                modifier = Modifier.size(40.dp)
                                            )
                                        }
                                    }
                                    
                                    val lock = rememberRowLock(viewModel, album.asRowNode())
                                    val isUnlocked = lock == UnlockManager.RowLock.Open
                                    val activeUpdates by viewModel.activeUpdateNodeIds.collectAsState()
                                    if (album.hasActiveUpdate(activeUpdates)) {
                                        Box(
                                            modifier = Modifier
                                                .align(Alignment.TopStart)
                                                .padding(8.dp)
                                                .size(10.dp)
                                                .border(2.dp, Color(0xFF00F0FF).copy(alpha = 0.4f), CircleShape)
                                                .clip(CircleShape)
                                                .background(Color(0xFF00F0FF))
                                        )
                                    }
                                    if (lock != UnlockManager.RowLock.None) {
                                        val lockIcon = if (isUnlocked) Icons.Default.LockOpen else Icons.Default.Lock
                                        val lockColor = if (isUnlocked) Color(0xFF00E5FF) else Color(0xFFFFB800)
                                        Icon(
                                            imageVector = lockIcon,
                                            contentDescription = if (isUnlocked) "Unlocked" else "Locked",
                                            tint = lockColor,
                                            modifier = Modifier
                                                .align(Alignment.TopEnd)
                                                .padding(8.dp)
                                                .size(18.dp)
                                        )
                                    }
                                    
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .align(Alignment.BottomStart)
                                            .background(
                                                Brush.verticalGradient(
                                                    listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))
                                                )
                                            )
                                            .padding(10.dp)
                                    ) {
                                        Text(
                                            text = album.title,
                                            color = Color.White,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
            
            // Recent Masterpieces Carousel
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Recent Masterpieces",
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 18.sp,
                    color = Color.White,
                    modifier = Modifier.align(Alignment.Start)
                )
                Spacer(modifier = Modifier.height(12.dp))
                
                if (isDetailsLoading) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(130.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(SurfaceDark.copy(alpha = 0.5f)),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = NeonBlue, modifier = Modifier.size(24.dp))
                    }
                } else if (recentImages.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(130.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(16.dp))
                            .background(SurfaceDark),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("No recent public uploads found.", color = Color.White.copy(alpha = 0.4f), fontSize = 13.sp)
                    }
                } else {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        itemsIndexed(recentImages) { index, photo ->
                            val albumKey = photo.uris?.imageAlbum?.substringAfterLast("/")?.substringBefore("!") ?: photo.uris?.album?.substringAfterLast("/")?.substringBefore("!") ?: ""
                            Card(
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                                modifier = Modifier
                                    .width(170.dp)
                                    .height(120.dp)
                                    .border(1.dp, NeonBlue.copy(alpha = 0.2f), RoundedCornerShape(16.dp))
                                    .clickable {
                                        if (com.smugview.app.BuildConfig.DEBUG) {
                                            android.util.Log.d(
                                                "BrowserScreen",
                                                "Recent masterpiece clicked: imageKey=${photo.imageKey}, imageAlbum=${photo.uris?.imageAlbum}, album=${photo.uris?.album}, albumKey=$albumKey"
                                            )
                                        }
                                        if (albumKey.isNotEmpty()) {
                                            onNavigateToPhotoDetail(albumKey, photo.imageKey)
                                        }
                                    }
                            ) {
                                Box(modifier = Modifier.fillMaxSize()) {
                                    AsyncImage(
                                        model = photo.thumbnailUrl,
                                        contentDescription = photo.title ?: "Recent photo",
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                    
                                    if (!photo.title.isNullOrEmpty()) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .align(Alignment.BottomStart)
                                                .background(
                                                    Brush.verticalGradient(
                                                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f))
                                                    )
                                                )
                                                .padding(8.dp)
                                        ) {
                                            Text(
                                                text = photo.title,
                                                color = Color.White,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(32.dp))
            
            // Signature Tags Cloud
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Signature Tags",
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 18.sp,
                    color = Color.White,
                    modifier = Modifier.align(Alignment.Start)
                )
                Spacer(modifier = Modifier.height(12.dp))
                
                if (isDetailsLoading) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(60.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(SurfaceDark.copy(alpha = 0.5f)),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = NeonBlue, modifier = Modifier.size(24.dp))
                    }
                } else if (topKeywords.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(60.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(16.dp))
                            .background(SurfaceDark),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("No signature tags found.", color = Color.White.copy(alpha = 0.4f), fontSize = 13.sp)
                    }
                } else {
                    @OptIn(ExperimentalLayoutApi::class)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        topKeywords.forEach { tag ->
                            Card(
                                shape = RoundedCornerShape(20.dp),
                                colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                                modifier = Modifier
                                    .border(1.dp, NeonBlue.copy(alpha = 0.3f), RoundedCornerShape(20.dp))
                                    .clickable {
                                        viewModel.clearSelectedTags()
                                        viewModel.selectTag(tag)
                                        viewModel.setActiveTab(BrowserTab.TagSearch)
                                        onNavigateToKeywordImages()
                                    }
                            ) {
                                Text(
                                    text = tag,
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        if (showShareDialog) {
            val shareUrl = activeUserProfile?.webUri ?: "https://${nickname}.smugmug.com"
            com.smugview.app.ui.component.QrShareDialog(
                title = nickname?.replaceFirstChar { it.uppercase() } ?: "Portfolio",
                url = shareUrl,
                onDismissRequest = { showShareDialog = false }
            )
        }
    }
}

@Composable
private fun StatItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            fontWeight = FontWeight.ExtraBold,
            fontSize = 16.sp,
            color = NeonBlue
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = label,
            fontSize = 10.sp,
            color = Color.White.copy(alpha = 0.4f),
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun StatDivider() {
    Box(
        modifier = Modifier
            .width(1.dp)
            .height(24.dp)
            .background(Color.White.copy(alpha = 0.08f))
    )
}


@Composable
fun FeaturedSiteCard(
    site: FeaturedSite,
    onEnterSite: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
        modifier = Modifier
            .width(280.dp)
            .height(350.dp)
            .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)), RoundedCornerShape(16.dp))
            .clickable { onEnterSite() }
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            AsyncImage(
                model = site.coverUrl,
                contentDescription = site.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.4f),
                                Color.Black.copy(alpha = 0.9f)
                            )
                        )
                    )
            )

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(NeonBlue.copy(alpha = 0.8f))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "Featured Portfolio",
                        color = Color.White,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Column {
                    Text(
                        text = site.name,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = site.description,
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 12.sp,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        site.previewUrls.forEach { url ->
                            AsyncImage(
                                model = url,
                                contentDescription = "Preview",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(50.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.15f)), RoundedCornerShape(8.dp))
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Button(
                        onClick = onEnterSite,
                        colors = ButtonDefaults.buttonColors(containerColor = NeonBlue),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("Explore Portfolio", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
fun DiscoveredSiteCard(
    site: com.smugview.app.data.repository.DiscoveredSite,
    onExploreSite: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
        modifier = Modifier
            .fillMaxWidth()
            .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)), RoundedCornerShape(16.dp))
            .clickable { onExploreSite() }
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(GlowBorder.copy(alpha = 0.2f))
                            .border(BorderStroke(1.dp, GlowBorder), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = site.nickname.take(2).uppercase(),
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = site.nickname.replaceFirstChar { it.uppercase() },
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        )
                        Text(
                            text = site.webUri ?: "https://${site.nickname}.smugmug.com",
                            color = Color.White.copy(alpha = 0.4f),
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Button(
                    onClick = onExploreSite,
                    colors = ButtonDefaults.buttonColors(containerColor = NeonBlue),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text("Explore", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            }

            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(site.previewPhotos) { img ->
                    AsyncImage(
                        model = img.thumbnailUrl ?: img.webUri,
                        contentDescription = img.title ?: "Preview",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(80.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .border(BorderStroke(0.5.dp, Color.White.copy(alpha = 0.15f)), RoundedCornerShape(8.dp))
                    )
                }
            }
        }
    }
}

