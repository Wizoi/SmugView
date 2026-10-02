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
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.palette.graphics.Palette
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.smugview.app.data.api.AlbumImageData
import com.smugview.app.data.api.ImageSizeDetailsPayload
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
 * Max pan distance (in px, from center) that keeps a ContentScale.Fit-fitted image's edges from
 * being dragged past the viewport, given the image's real aspect ratio and current zoom scale.
 * Returns Offset.Zero (no panning) if the aspect ratio isn't known yet.
 */
private fun maxPanFor(aspectRatio: Float?, box: IntSize, scale: Float): Offset {
    if (aspectRatio == null || box.width <= 0 || box.height <= 0) return Offset.Zero
    val boxAr = box.width.toFloat() / box.height.toFloat()
    val fittedW: Float
    val fittedH: Float
    if (boxAr > aspectRatio) {
        fittedH = box.height.toFloat(); fittedW = fittedH * aspectRatio
    } else {
        fittedW = box.width.toFloat(); fittedH = fittedW / aspectRatio
    }
    return Offset(
        ((fittedW * scale - box.width).coerceAtLeast(0f)) / 2f,
        ((fittedH * scale - box.height).coerceAtLeast(0f)) / 2f
    )
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
            val videoUrl = remember(rawUrl) { getAuthenticatedMediaUrl(rawUrl) }
            VideoPlayerView(
                videoUrl = videoUrl,
                isActive = isActive,
                modifier = Modifier.fillMaxSize(),
                onClick = onToggleControls
            )
        } else {
            // 5-9 (Q2): a saved copy opens with no network. The page's own localUri (placeholder, offline gallery)
            // or the app-wide map of saved files (live: a photo saved while this page is open).
            val savedFiles by viewModel.localFiles.collectAsState()
            val localUri = photo.localUri ?: savedFiles[photo.imageKey]
            val pagerImageModel = remember(photo.thumbnailUrl, photo.archivedUri, localUri) {
                viewerImageModel(photo.thumbnailUrl, photo.archivedUri, localUri)
            }
            var currentDetailUrl by remember(photo.thumbnailUrl, pagerImageModel) {
                mutableStateOf(pagerImageModel)
            }
            // Instant guess shown as tier 1 while the real ImageSizeDetails call is in flight.
            val fallbackHalfwayUrl = remember(photo.archivedUri, photo.thumbnailUrl, localUri) {
                zoomFallbackUrl(photo.thumbnailUrl, photo.archivedUri, localUri)
            }

            var scale by remember(photo.imageKey) { mutableStateOf(1f) }
            var offset by remember(photo.imageKey) { mutableStateOf(Offset.Zero) }
            var boxSizePx by remember(photo.imageKey) { mutableStateOf(IntSize.Zero) }
            var sizeDetailsFlow by remember(photo.imageKey) {
                mutableStateOf<kotlinx.coroutines.flow.StateFlow<Result<ImageSizeDetailsPayload>?>?>(null)
            }
            var committedOriginalPx by remember(photo.imageKey) { mutableStateOf(0) }
            // Live pinch focal point (2-finger average position), tracked by a non-consuming observer
            // so the zoom can pivot around the fingers instead of the box's fixed center.
            var pinchCentroid by remember(photo.imageKey) { mutableStateOf<Offset?>(null) }
            // Real decoded aspect ratio, captured from whichever tier loads first, used to bound panning
            // to the photo's actual displayed edges (independent of API metadata availability/timing).
            var contentAspectRatio by remember(photo.imageKey) { mutableStateOf<Float?>(null) }

            // Reset the zoom whenever this page stops being the active pager page.
            LaunchedEffect(isActive) {
                if (!isActive) {
                    scale = 1f
                    offset = Offset.Zero
                    sizeDetailsFlow = null
                    committedOriginalPx = 0
                    pinchCentroid = null
                }
            }

            val isZoomed = scale > 1.01f

            // Kick off the real (accurate) size lookup on first pinch, once, per photo.
            val sizeDetailsUri = if (localUri != null) null else photo.uris?.imageSizeDetails // the saved original needs no size lookup
            LaunchedEffect(isZoomed, isActive) {
                if (isZoomed && isActive && sizeDetailsUri != null && sizeDetailsFlow == null) {
                    sizeDetailsFlow = viewModel.getImageSizeDetails(photo.imageKey, sizeDetailsUri)
                }
            }
            val sizeDetails = sizeDetailsFlow?.collectAsState()?.value?.getOrNull()

            // Tier 1 ("halfway"): the largest non-original rendition SmugMug actually generated.
            val halfwayEntry = sizeDetails?.bestHalfway
            val halfwayUrl = halfwayEntry?.url ?: fallbackHalfwayUrl
            val loadHalfway = isZoomed && isActive && halfwayUrl != null

            // Max zoom is derived from the real original resolution once known, so the user can zoom
            // to native pixel resolution and no further. Falls back to a flat cap until that resolves.
            val maxScale = remember(sizeDetails, boxSizePx) {
                val originalWidth = sizeDetails?.original?.width
                if (originalWidth != null && boxSizePx.width > 0) {
                    (originalWidth.toFloat() / boxSizePx.width).coerceIn(1f, 10f)
                } else {
                    5f
                }
            }

            // Tier 2 ("largest"): swap to the true original once zoom exceeds what tier 1 can show.
            val originalEntry = sizeDetails?.original
            val neededPx = boxSizePx.width * scale
            val loadOriginal = loadHalfway && halfwayEntry != null && neededPx > halfwayEntry.width.toFloat()
            val originalRequestUrl = originalEntry?.url ?: photo.archivedUri
            val targetOriginalPx = originalEntry?.width?.let { ow -> neededPx.toInt().coerceAtMost(ow) } ?: neededPx.toInt()
            LaunchedEffect(targetOriginalPx, loadOriginal) {
                if (loadOriginal && (committedOriginalPx == 0 || targetOriginalPx > committedOriginalPx * 1.2f)) {
                    committedOriginalPx = targetOriginalPx
                }
            }

            val transformState = rememberTransformableState { zoomChange, panChange, _ ->
                val newScale = (scale * zoomChange).coerceIn(1f, maxScale)
                val boxCenter = Offset(boxSizePx.width / 2f, boxSizePx.height / 2f)
                val focal = pinchCentroid ?: boxCenter
                // graphicsLayer scales around boxCenter by default; solve for the offset that keeps the
                // content point under `focal` visually fixed as scale changes, so zoom pivots at the
                // fingers instead of always shrinking/growing from the screen center.
                val zoomCompensated = focal - boxCenter - (focal - boxCenter - offset) * zoomChange
                val raw = if (newScale > 1f) zoomCompensated + panChange else Offset.Zero
                val bound = maxPanFor(contentAspectRatio, boxSizePx, newScale)
                scale = newScale
                offset = Offset(raw.x.coerceIn(-bound.x, bound.x), raw.y.coerceIn(-bound.y, bound.y))
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .onSizeChanged { boxSizePx = it }
                    // Pan is only consumed while zoomed, so a 1x page still swipes to the next photo.
                    .transformable(state = transformState, canPan = { scale > 1f })
                    // Non-consuming observer: just watches raw pointer positions to track the pinch's
                    // focal point for the transformable callback above. Never calls .consume(), so it
                    // can't interfere with transformable's or the tap detector's own handling.
                    .pointerInput(photo.imageKey) {
                        awaitEachGesture {
                            do {
                                val event = awaitPointerEvent()
                                val active = event.changes.filter { it.pressed }
                                pinchCentroid = if (active.size >= 2) {
                                    var sum = Offset.Zero
                                    for (c in active) sum += c.position
                                    sum / active.size.toFloat()
                                } else null
                            } while (event.changes.any { it.pressed })
                            pinchCentroid = null
                        }
                    }
                    .pointerInput(photo.imageKey) {
                        detectTapGestures(
                            onTap = { onToggleControls() },
                            onLongPress = { onLongPress() },
                            onDoubleTap = {
                                val fav = localCollections.find { it.name.lowercase() == "favorites" }
                                if (fav != null) {
                                    viewModel.addPhotoToCollection(photo, fav.id)
                                    Toast.makeText(context, "Added to Favorites!", Toast.LENGTH_SHORT).show()
                                } else {
                                    onRequestAddToCollection()
                                }
                            }
                        )
                    }
            ) {
                val zoomModifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    }
                AsyncImage(
                    model = currentDetailUrl,
                    contentDescription = photo.title ?: "Immersive photo details",
                    contentScale = ContentScale.Fit,
                    onSuccess = { state ->
                        val d = state.result.drawable
                        if (d.intrinsicWidth > 0 && d.intrinsicHeight > 0) {
                            contentAspectRatio = d.intrinsicWidth.toFloat() / d.intrinsicHeight.toFloat()
                        }
                    },
                    onError = {
                        // A saved file that will not decode falls back to the network rendition, then the original.
                        currentDetailUrl = if (localUri != null && currentDetailUrl == localUri) {
                            viewerImageModel(photo.thumbnailUrl, photo.archivedUri, null)
                        } else {
                            photo.archivedUri ?: photo.thumbnailUrl
                        }
                    },
                    modifier = zoomModifier
                )
                if (loadHalfway) {
                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(halfwayUrl)
                            .size(if (localUri != null) 4096 else halfwayEntry?.width ?: 2560)
                            .crossfade(true)
                            .build(),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = zoomModifier
                    )
                }
                if (loadOriginal && committedOriginalPx > 0 && originalRequestUrl != null) {
                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(originalRequestUrl)
                            .size(committedOriginalPx)
                            .crossfade(true)
                            .build(),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = zoomModifier
                    )
                }
            }
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
    var showShareDialog by remember { mutableStateOf(false) }
    // The web page of this photo (6-14): its own, or its gallery's from the index. Null once resolved = no link to share.
    var shareLink by remember(photo.imageKey, photo.webUri) { mutableStateOf<String?>(null) }
    var linkResolved by remember(photo.imageKey, photo.webUri) { mutableStateOf(false) }
    LaunchedEffect(photo.imageKey, photo.webUri, photo.thumbnailUrl) {
        shareLink = viewModel.shareLinkFor(photo)
        linkResolved = true
    }
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
            when {
                !linkResolved -> Toast.makeText(context, "Loading photo details, please wait...", Toast.LENGTH_SHORT).show()
                shareLink == null -> Toast.makeText(context, com.smugview.app.ui.text.UserMessages.SHARE_NO_LINK, Toast.LENGTH_LONG).show()
                else -> showShareDialog = true
            }
        }) {
            Icon(
                Icons.Default.Share, "Share Link",
                tint = Color.White.copy(alpha = if (linkResolved && shareLink == null) 0.35f else 1f),
                modifier = Modifier.size(26.dp)
            )
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

    if (showShareDialog) {
        com.smugview.app.ui.component.QrShareDialog(
            title = photo.title?.takeIf { it.isNotBlank() } ?: (photo.fileName ?: "Photo"),
            url = shareLink.orEmpty(),
            onDismissRequest = { showShareDialog = false },
            onSharePicture = { ctx -> sharePhoto(ctx, scope, viewModel, photo, shareLink) }
        )
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

/**
 * The instant tier-1 guess for the pinch zoom while the real `ImageSizeDetails` call is in flight (R-29,
 * design 3.5): the X3 size derived from the thumbnail URL. The original (`ArchivedUri`) is only a last
 * resort for a photo with no thumbnail: it used to come first, so every zoom downloaded the original
 * before the real tiers arrived. A photo too small to have an X3 fails to load it and `onError` falls
 * back to `ArchivedUri`.
 *
 * 5-9 (Q2): a saved copy on this phone wins over everything ([localUri]): it is the original, needs no network
 * and no size lookup.
 */
internal fun zoomFallbackUrl(thumbnailUrl: String?, archivedUri: String?, localUri: String? = null): String? =
    localUri
        ?: thumbnailUrl?.replace("/Th/", "/X3/")?.replace("/th/", "/x3/")
            ?.replace("-Th.", "-X3.")?.replace("-th.", "-x3.")
        ?: archivedUri

/**
 * The picture a still opens with (5-9, Q2): the saved copy when there is one, otherwise the large rendition derived
 * from the thumbnail, otherwise the original.
 */
internal fun viewerImageModel(thumbnailUrl: String?, archivedUri: String?, localUri: String?): String? =
    localUri
        ?: thumbnailUrl?.replace("/Th/", "/L/")?.replace("/th/", "/l/")?.replace("-Th.", "-L.")?.replace("-th.", "-l.")
        ?: archivedUri
