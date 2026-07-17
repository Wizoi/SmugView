package com.smugview.app.ui.detail

import android.graphics.drawable.BitmapDrawable
import android.widget.Toast
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.DataUsage
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Iso
import androidx.compose.material.icons.filled.Lens
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.palette.graphics.Palette
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.smugview.app.data.api.AlbumImageData
import com.smugview.app.data.db.OfflineCollection
import com.smugview.app.data.api.isVideo
import com.smugview.app.ui.theme.NeonBlue
import com.smugview.app.ui.theme.SurfaceDark
import com.smugview.app.ui.viewmodel.SmugViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Shared building blocks for the three immersive photo-detail screens
 * (PhotoDetailScreen / SearchPhotoDetailScreen / KeywordPhotoDetailScreen), which were previously
 * ~90% copy-pasted. Each screen still owns its own data source (album paging vs search paging vs
 * tag-filtered list) and its own top bar (breadcrumb+cast vs "Jump to Gallery"); everything below
 * — the pager page body, the palette background, the action capsule, and the EXIF sheet — is shared.
 *
 * Behavior is preserved; the only intentional change is unifying the EXIF sheet's slightly-drifted
 * labels into one canonical layout.
 */

/** Palette-derived, animated radial-gradient background color for the current photo. */
@Composable
fun rememberDominantBackgroundColor(
    imageUrl: String?,
    defaultColor: Color = Color.Black,
    targetAlpha: Float = 0.85f,
    durationMs: Int = 500
): Color {
    val context = LocalContext.current
    var dominantColor by remember { mutableStateOf(defaultColor) }
    LaunchedEffect(imageUrl) {
        imageUrl?.let { url ->
            withContext(Dispatchers.IO) {
                try {
                    val req = ImageRequest.Builder(context)
                        .data(url)
                        .allowHardware(false) // required for pixel reading
                        .build()
                    val result = context.imageLoader.execute(req)
                    if (result is SuccessResult) {
                        (result.drawable as? BitmapDrawable)?.bitmap?.let { bmp ->
                            dominantColor = Color(Palette.from(bmp).generate().getDominantColor(defaultColor.toArgb()))
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }
    val animated by animateColorAsState(
        targetValue = dominantColor.copy(alpha = targetAlpha),
        animationSpec = tween(durationMillis = durationMs),
        label = "bgColor"
    )
    return animated
}

/**
 * One page of the immersive pager: a video (with authenticated URL) or a large image with the
 * standard tap-to-toggle-controls / long-press-EXIF / double-tap-favorite gestures.
 *
 * @param fallbackAlbumKey album key to authenticate video URLs when the photo doesn't carry one
 *   (PhotoDetailScreen passes its album key; the search/keyword screens pass "").
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ImmersivePhotoPage(
    photo: AlbumImageData,
    isActive: Boolean,
    fallbackAlbumKey: String,
    localCollections: List<OfflineCollection>,
    viewModel: SmugViewModel,
    onToggleControls: () -> Unit,
    onLongPress: () -> Unit,
    onRequestAddToCollection: () -> Unit
) {
    val context = LocalContext.current
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (photo.isVideo) {
            val rawUrl = photo.videoUrl ?: photo.archivedUri ?: photo.thumbnailUrl ?: ""
            val pageDetailed by viewModel.getImageDetails(photo.imageKey).collectAsState()
            val photoAlbumKey = pageDetailed?.getOrNull()?.uris?.album?.substringAfterLast("/")
                ?: photo.uris?.album?.substringAfterLast("/")
                ?: fallbackAlbumKey
            val videoUrl = remember(rawUrl, photoAlbumKey) {
                getAuthenticatedMediaUrl(rawUrl, photoAlbumKey, viewModel)
            }
            VideoPlayerView(
                videoUrl = videoUrl,
                isActive = isActive,
                modifier = Modifier.fillMaxSize(),
                onClick = onToggleControls
            )
        } else {
            val pagerImageModel = remember(photo.thumbnailUrl, photo.archivedUri) {
                photo.thumbnailUrl?.replace("/Th/", "/L/")
                    ?.replace("/th/", "/l/")
                    ?.replace("-Th.", "-L.")
                    ?.replace("-th.", "-l.")
                    ?: photo.archivedUri
            }
            var currentDetailUrl by remember(photo.thumbnailUrl, pagerImageModel) {
                mutableStateOf(pagerImageModel)
            }
            AsyncImage(
                model = currentDetailUrl,
                contentDescription = photo.title ?: "Immersive photo details",
                contentScale = ContentScale.Fit,
                onError = { currentDetailUrl = photo.archivedUri ?: photo.thumbnailUrl },
                modifier = Modifier
                    .fillMaxSize()
                    .combinedClickable(
                        onClick = onToggleControls,
                        onLongClick = onLongPress,
                        onDoubleClick = {
                            val fav = localCollections.find { it.name.lowercase() == "favorites" }
                            if (fav != null) {
                                viewModel.addPhotoToCollection(photo, fav.id)
                                Toast.makeText(context, "Added to Favorites!", Toast.LENGTH_SHORT).show()
                            } else {
                                onRequestAddToCollection()
                            }
                        }
                    )
            )
        }
    }
}

/** The rounded action capsule: Save (favorite) / Share / Download / Info. Identical across screens. */
@Composable
fun PhotoActionCapsule(
    photo: AlbumImageData,
    localCollections: List<OfflineCollection>,
    viewModel: SmugViewModel,
    scope: CoroutineScope,
    onShowExif: () -> Unit,
    onRequestAddToCollection: () -> Unit
) {
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(32.dp))
            .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(32.dp))
            .padding(horizontal = 24.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(28.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = {
            val fav = localCollections.find { it.name.lowercase() == "favorites" }
            if (fav != null) {
                viewModel.addPhotoToCollection(photo, fav.id)
                Toast.makeText(context, "Added to Favorites!", Toast.LENGTH_SHORT).show()
            } else {
                onRequestAddToCollection()
            }
        }) {
            Icon(Icons.Default.BookmarkBorder, "Save", tint = Color.White, modifier = Modifier.size(26.dp))
        }
        IconButton(onClick = {
            if (photo.webUri != null || photo.archivedUri != null) {
                sharePhoto(context, scope, photo)
            } else {
                Toast.makeText(context, "Loading photo details, please wait...", Toast.LENGTH_SHORT).show()
            }
        }) {
            Icon(Icons.Default.Share, "Share Link", tint = Color.White, modifier = Modifier.size(26.dp))
        }
        IconButton(onClick = {
            scope.launch { downloadPhotoToGallery(context, photo, viewModel) }
        }) {
            Icon(Icons.Default.Download, "Download Photo", tint = Color.White, modifier = Modifier.size(26.dp))
        }
        IconButton(onClick = onShowExif) {
            Icon(Icons.Default.Info, "Info Details", tint = Color.White, modifier = Modifier.size(26.dp))
        }
    }
}

/**
 * The EXIF / metadata bottom sheet, unified across all three screens (their labels/layouts had
 * drifted). Falls back from [detailedPhoto] to [currentPhoto] for each field.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PhotoExifSheet(
    currentPhoto: AlbumImageData,
    detailedPhoto: AlbumImageData?,
    viewModel: SmugViewModel,
    onDismiss: () -> Unit,
    onNavigateToKeywordImages: () -> Unit
) {
    val exifState by viewModel.getImageExif(currentPhoto.imageKey).collectAsState()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = SurfaceDark
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 16.dp)
        ) {
            when (val result = exifState) {
                null -> {
                    Box(modifier = Modifier.fillMaxWidth().height(100.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = NeonBlue)
                    }
                }
                else -> {
                    val data = result.getOrNull()
                    if (data == null) {
                        Text("Metadata unavailable for this photo", color = Color.White.copy(alpha = 0.5f))
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                                val cam = data.camera
                                ExifCardItem(
                                    icon = Icons.Default.CameraAlt,
                                    label = "Camera Model",
                                    value = cam ?: "Unknown",
                                    modifier = Modifier.weight(1f),
                                    iconContent = if (!cam.isNullOrEmpty()) { { CameraLogo(data.make ?: cam) } } else null
                                )
                                ExifCardItem(Icons.Default.Lens, "Lens / Focal", data.focalLength ?: "Unknown", Modifier.weight(1f))
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                                ExifCardItem(Icons.Default.LightMode, "Aperture", data.aperture ?: "Unknown", Modifier.weight(1f))
                                ExifCardItem(Icons.Default.Speed, "Exposure Time", data.exposure ?: "Unknown", Modifier.weight(1f))
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                                ExifCardItem(Icons.Default.Iso, "ISO Speed", data.iso?.toString() ?: "Unknown", Modifier.weight(1f))
                                val w = detailedPhoto?.originalWidth ?: currentPhoto.originalWidth
                                val h = detailedPhoto?.originalHeight ?: currentPhoto.originalHeight
                                ExifCardItem(
                                    Icons.Default.ZoomIn, "Original Dimensions",
                                    if (w != null && h != null) "$w x $h" else "Unknown", Modifier.weight(1f)
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                                ExifCardItem(
                                    Icons.Default.Event, "Date Taken",
                                    formatToLocalTime(data.dateTimeCreated ?: data.dateCreated ?: currentPhoto.date ?: currentPhoto.dateTime),
                                    Modifier.weight(1f)
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                                val fileName = detailedPhoto?.fileName ?: currentPhoto.fileName ?: currentPhoto.title ?: "Unknown"
                                ExifCardItem(Icons.Default.InsertDriveFile, "File Name", fileName, Modifier.fillMaxWidth())
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                                val sizeStr = formatPhotoFileSize(detailedPhoto?.originalSize ?: currentPhoto.originalSize)
                                ExifCardItem(Icons.Default.DataUsage, "File Size", sizeStr, Modifier.weight(1f))
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Tags",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White.copy(alpha = 0.5f),
                modifier = Modifier.padding(bottom = 8.dp)
            )
            val tags = remember(currentPhoto, detailedPhoto) {
                val keywordsStr = detailedPhoto?.keywordsString ?: currentPhoto.keywordsString ?: ""
                keywordsStr.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            }
            if (tags.isNotEmpty()) {
                com.smugview.app.ui.grid.OptInFlowRow(modifier = Modifier.fillMaxWidth()) {
                    tags.forEach { tag ->
                        Box(
                            modifier = Modifier
                                .padding(4.dp)
                                .background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(16.dp))
                                .clickable {
                                    viewModel.selectSingleTag(tag)
                                    onDismiss()
                                    onNavigateToKeywordImages()
                                }
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text(text = tag, color = Color.White, fontSize = 11.sp)
                        }
                    }
                }
            } else {
                Text("No tags available.", color = Color.White.copy(alpha = 0.3f), fontSize = 12.sp)
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

/** Builds the "Taken with <camera>, <focal>, ..." caption from an ExifData result, or "". */
@Composable
fun rememberCameraDetails(imageKey: String, viewModel: SmugViewModel): String {
    val exifState by viewModel.getImageExif(imageKey).collectAsState()
    return remember(exifState) {
        exifState?.getOrNull()?.let { exif ->
            listOf(
                exif.camera ?: "",
                exif.focalLength ?: "",
                exif.aperture ?: "",
                exif.exposure ?: "",
                exif.iso?.let { "ISO $it" } ?: ""
            ).filter { it.isNotEmpty() }
                .joinToString(", ")
                .let { if (it.isNotEmpty()) "Taken with $it" else "" }
        } ?: ""
    }
}
