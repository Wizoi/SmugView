package com.smugview.app.ui.explorer

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.TextUnit
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.smugview.app.data.api.UserData
import com.smugview.app.ui.theme.DeepDarkBackground
import com.smugview.app.ui.theme.GlowBorder
import com.smugview.app.ui.theme.NeonBlue
import com.smugview.app.ui.theme.SurfaceDark
import com.smugview.app.ui.theme.SurfaceGlass
import com.smugview.app.ui.viewmodel.SplashUiState
import com.smugview.app.ui.viewmodel.SmugViewModel
import kotlinx.coroutines.delay
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.text.style.TextOverflow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SiteExplorerScreen(
    onNavigateToBrowser: () -> Unit,
    viewModel: SmugViewModel = hiltViewModel()
) {
    val uiState by viewModel.splashState.collectAsState()
    val recentSites by viewModel.recentSites.collectAsState()
    val sitePreview by viewModel.sitePreview.collectAsState()
    val previewAlbums by viewModel.previewAlbums.collectAsState()
    val focusManager = LocalFocusManager.current

    var nicknameQuery by remember { mutableStateOf("") }

    // Navigation trigger on success
    LaunchedEffect(uiState) {
        if (uiState is SplashUiState.Success) {
            onNavigateToBrowser()
        }
    }

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
                .height(400.dp)
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
                .padding(24.dp)
                .navigationBarsPadding()
                .statusBarsPadding(),
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
}

@Composable
fun ProfilePreviewCard(
    userData: UserData,
    previewAlbums: List<com.smugview.app.data.api.AlbumPreview> = emptyList(),
    isLoading: Boolean,
    onClickEnter: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceGlass),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(24.dp))
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            ProfileAvatar(
                nickname = userData.nickName,
                name = userData.name,
                bioImageKey = userData.bioImageKey,
                modifier = Modifier.size(80.dp),
                shape = CircleShape,
                contentScale = ContentScale.Crop
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = userData.name,
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp,
                color = Color.White
            )

            Text(
                text = "@" + userData.nickName,
                fontSize = 13.sp,
                color = Color.White.copy(alpha = 0.5f)
            )

            if (previewAlbums.isNotEmpty()) {
                Spacer(modifier = Modifier.height(20.dp))
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Public Galleries Preview",
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        color = Color.White.copy(alpha = 0.5f)
                    )
                    
                    TextButton(
                        onClick = onClickEnter,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        modifier = Modifier.height(28.dp),
                        colors = ButtonDefaults.textButtonColors(contentColor = NeonBlue)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text("Browse", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Icon(
                                imageVector = Icons.Default.ChevronRight,
                                contentDescription = "Browse",
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(previewAlbums) { album ->
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.width(80.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(64.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(SurfaceDark)
                            ) {
                                AsyncImage(
                                    model = album.thumbnailUrl ?: "https://secure.smugmug.com/users/${userData.nickName}-avatar.jpg",
                                    contentDescription = album.title,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = album.title ?: "",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Medium,
                                color = Color.White.copy(alpha = 0.7f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = onClickEnter,
                colors = ButtonDefaults.buttonColors(containerColor = NeonBlue),
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth(),
                enabled = !isLoading
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier.padding(vertical = 4.dp)
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            color = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    } else {
                        Text(
                            text = "Explore Portfolio",
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            imageVector = Icons.Default.ChevronRight,
                            contentDescription = "Enter",
                            tint = Color.White
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ProfileAvatar(
    nickname: String,
    name: String,
    bioImageKey: String?,
    modifier: Modifier = Modifier,
    textColor: Color = NeonBlue,
    fontSize: TextUnit = 28.sp,
    shape: androidx.compose.ui.graphics.Shape = CircleShape,
    contentScale: ContentScale = ContentScale.Crop
) {
    // Prioritize loading the square avatar (loadStep = 0)
    // 0: secure.smugmug.com/users/{nickname}-avatar.jpg
    // 1: photos.smugmug.com/photos/i-{bioImageKey}-M.jpg (BioImage)
    // 2: nickname.smugmug.com/bioimage redirect
    // 3: Text fallback
    var loadStep by remember(nickname, bioImageKey) {
        mutableStateOf(0)
    }

    Box(
        modifier = modifier
            .clip(shape)
            .background(SurfaceDark),
        contentAlignment = Alignment.Center
    ) {
        when (loadStep) {
            0 -> {
                AsyncImage(
                    model = "https://secure.smugmug.com/users/${nickname}-avatar.jpg",
                    contentDescription = "Avatar",
                    contentScale = contentScale,
                    modifier = Modifier.fillMaxSize(),
                    onError = {
                        loadStep = if (bioImageKey != null) 1 else 2
                    }
                )
            }
            1 -> {
                AsyncImage(
                    model = "https://photos.smugmug.com/photos/i-${bioImageKey}/0/M/i-${bioImageKey}-M.jpg",
                    contentDescription = "BioImage API",
                    contentScale = contentScale,
                    modifier = Modifier.fillMaxSize(),
                    onError = {
                        loadStep = 2
                    }
                )
            }
            2 -> {
                AsyncImage(
                    model = "https://${nickname}.smugmug.com/bioimage",
                    contentDescription = "BioImage Redirect",
                    contentScale = contentScale,
                    modifier = Modifier.fillMaxSize(),
                    onError = {
                        loadStep = 3
                    }
                )
            }
            else -> {
                Text(
                    text = name.take(1).uppercase(),
                    color = textColor,
                    fontSize = fontSize,
                    fontWeight = FontWeight.Black
                )
            }
        }
    }
}
