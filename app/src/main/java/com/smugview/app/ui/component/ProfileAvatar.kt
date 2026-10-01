package com.smugview.app.ui.component

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
                bioImageThumbnailUrl = userData.bioImageThumbnailUrl,
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
                                // No cover: leave the tile empty (the old fallback asked a dead avatar URL, L1).
                                if (album.thumbnailUrl != null) {
                                    AsyncImage(
                                        model = album.thumbnailUrl,
                                        contentDescription = album.title,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
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

/**
 * What the avatar asks the image loader for (design 3.7): the BioImage's path-bearing `/Th/` thumbnail resized
 * to `/S/` (`-Th.` -> `-S.` in the file name too, checked live: both sizes answer 206), or null for "show the
 * initial letter". The old avatar tried `secure.smugmug.com/users/{nick}-avatar.jpg` and
 * `{nick}.smugmug.com/bioimage`, both 404 on 5 of 5 live sites (L1), so neither is ever requested.
 */
fun avatarModel(bioImageThumbnailUrl: String?): String? {
    val url = bioImageThumbnailUrl?.takeIf { it.isNotBlank() } ?: return null
    return url.replace("/Th/", "/S/").replace("/th/", "/s/").replace("-Th.", "-S.").replace("-th.", "-s.")
}

@Composable
fun ProfileAvatar(
    nickname: String,
    name: String,
    bioImageThumbnailUrl: String?,
    modifier: Modifier = Modifier,
    textColor: Color = NeonBlue,
    fontSize: TextUnit = 28.sp,
    shape: androidx.compose.ui.graphics.Shape = CircleShape,
    contentScale: ContentScale = ContentScale.Crop
) {
    val model = avatarModel(bioImageThumbnailUrl)
    // A BioImage that fails to load (offline, removed) falls back to the letter, never to another host.
    var failed by remember(nickname, model) { mutableStateOf(false) }

    Box(
        modifier = modifier
            .clip(shape)
            .background(SurfaceDark),
        contentAlignment = Alignment.Center
    ) {
        if (model != null && !failed) {
            AsyncImage(
                model = model,
                contentDescription = "Avatar",
                contentScale = contentScale,
                modifier = Modifier.fillMaxSize(),
                onError = { failed = true }
            )
        } else {
            Text(
                text = name.take(1).uppercase(),
                color = textColor,
                fontSize = fontSize,
                fontWeight = FontWeight.Black
            )
        }
    }
}
