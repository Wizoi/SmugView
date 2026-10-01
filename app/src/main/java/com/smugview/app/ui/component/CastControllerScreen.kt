package com.smugview.app.ui.component

import androidx.compose.ui.platform.testTag
import com.smugview.app.ui.text.UserMessages
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.smugview.app.data.cast.CastDevice
import com.smugview.app.data.cast.ConnectionState

@Composable
fun CastControllerScreen(
    activeDevice: CastDevice,
    albumTitle: String,
    currentImageUri: String?,
    isSlideshowPlaying: Boolean,
    slideshowInterval: Int,
    volume: Float,
    isMuted: Boolean,
    isWebCompanionActive: Boolean = false,
    webCompanionUrl: String? = null,
    onPlayPauseToggle: () -> Unit,
    onNextClick: () -> Unit,
    onPrevClick: () -> Unit,
    onIntervalChange: (Int) -> Unit,
    onVolumeChange: (Float) -> Unit,
    onToggleMute: () -> Unit,
    onStopCasting: () -> Unit,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val deepDarkBackground = Color(0xFF090A0C)
    val surfaceDark = Color(0xFF121418)
    val neonBlue = Color(0xFF00E5FF)
    val softRed = Color(0xFFFF3366)

    // Debounce state to prevent double stop casting triggers
    var isTerminating by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(deepDarkBackground)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Top Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBackClick) {
                Icon(
                    imageVector = Icons.Default.ArrowBack,
                    contentDescription = "Back to gallery",
                    tint = Color.White
                )
            }
            
            Spacer(modifier = Modifier.width(8.dp))
            
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = "Now Casting",
                    color = Color.Gray,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = albumTitle.ifEmpty { "Active Album" },
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(neonBlue.copy(alpha = 0.1f))
                    .border(1.dp, neonBlue.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    text = "CASTING",
                    color = neonBlue,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Black
                )
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(24.dp))

        // TV / Target Indicator Card
        Card(
            colors = CardDefaults.cardColors(containerColor = surfaceDark),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Color.Gray.copy(alpha = 0.1f), RoundedCornerShape(12.dp))
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (activeDevice.state == ConnectionState.CONNECTING) Icons.Default.Cast else Icons.Default.CastConnected,
                    contentDescription = null,
                    tint = neonBlue,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(16.dp))
                Column {
                    Text(
                        text = activeDevice.name,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                    Text(
                        text = if (activeDevice.state == ConnectionState.CONNECTING) {
                            "Connecting via ${activeDevice.type.name.lowercase().capitalize()} • ${activeDevice.ipAddress}"
                        } else {
                            "Connected via ${activeDevice.type.name.lowercase().capitalize()} • ${activeDevice.ipAddress}"
                        },
                        color = Color.Gray,
                        fontSize = 12.sp
                    )
                }
            }
        }

        if (isWebCompanionActive) {
            Spacer(modifier = Modifier.height(16.dp))
            Card(
                colors = CardDefaults.cardColors(containerColor = surfaceDark),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, neonBlue.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "Web Companion Setup",
                        color = neonBlue,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    // No URL unless the server really is bound, and then the port it really got (R-58).
                    Text(
                        text = if (webCompanionUrl != null) UserMessages.castCompanion(webCompanionUrl) else UserMessages.CAST_COMPANION_FAILED,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        modifier = Modifier.fillMaxWidth().testTag("cast_companion_text")
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Image Preview Frame (Glassmorphism & Neon Glow)
        Box(
            modifier = Modifier
                .fillMaxWidth(0.85f)
                .aspectRatio(1.2f) // Fixed aspect ratio to avoid Cumulative Layout Shift (CLS)
                .clip(RoundedCornerShape(16.dp))
                .background(surfaceDark)
                .border(2.dp, neonBlue.copy(alpha = 0.3f), RoundedCornerShape(16.dp)),
            contentAlignment = Alignment.Center
        ) {
            if (currentImageUri != null) {
                AsyncImage(
                    model = currentImageUri,
                    contentDescription = "Preview of currently casted image",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (activeDevice.state == ConnectionState.CONNECTING) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(48.dp),
                            color = neonBlue,
                            strokeWidth = 3.dp
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Connecting to device...",
                            color = Color.Gray,
                            fontSize = 14.sp
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Image,
                            contentDescription = null,
                            tint = Color.Gray,
                            modifier = Modifier.size(64.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Select an image to cast",
                            color = Color.Gray,
                            fontSize = 14.sp
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.weight(0.5f))

        // Transport Controls
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onPrevClick,
                modifier = Modifier.size(56.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.SkipPrevious,
                    contentDescription = "Previous Photo",
                    tint = Color.White,
                    modifier = Modifier.size(36.dp)
                )
            }

            Spacer(modifier = Modifier.width(24.dp))

            IconButton(
                onClick = onPlayPauseToggle,
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(neonBlue.copy(alpha = 0.2f))
                    .border(1.dp, neonBlue, RoundedCornerShape(16.dp))
            ) {
                Icon(
                    imageVector = if (isSlideshowPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (isSlideshowPlaying) "Pause slideshow" else "Play slideshow",
                    tint = neonBlue,
                    modifier = Modifier.size(40.dp)
                )
            }

            Spacer(modifier = Modifier.width(24.dp))

            IconButton(
                onClick = onNextClick,
                modifier = Modifier.size(56.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.SkipNext,
                    contentDescription = "Next Photo",
                    tint = Color.White,
                    modifier = Modifier.size(36.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Slide Interval Slider (Only shown/enabled during slideshow playing or control)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Slideshow Speed",
                    color = Color.LightGray,
                    fontSize = 13.sp
                )
                Text(
                    text = "${slideshowInterval}s",
                    color = neonBlue,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            
            Slider(
                value = slideshowInterval.toFloat(),
                onValueChange = { onIntervalChange(it.toInt()) },
                valueRange = 2f..30f,
                steps = 28,
                colors = SliderDefaults.colors(
                    thumbColor = neonBlue,
                    activeTrackColor = neonBlue,
                    inactiveTrackColor = Color.Gray.copy(alpha = 0.3f)
                ),
                modifier = Modifier.fillMaxWidth()
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Volume Control Row/Slider
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = onToggleMute,
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            imageVector = if (isMuted || volume == 0f) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                            contentDescription = "Toggle Mute",
                            tint = Color.LightGray,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Device Volume",
                        color = Color.LightGray,
                        fontSize = 13.sp
                    )
                }
                Text(
                    text = "${(volume * 100).toInt()}%",
                    color = neonBlue,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            
            Slider(
                value = volume,
                onValueChange = onVolumeChange,
                valueRange = 0f..1f,
                colors = SliderDefaults.colors(
                    thumbColor = neonBlue,
                    activeTrackColor = neonBlue,
                    inactiveTrackColor = Color.Gray.copy(alpha = 0.3f)
                ),
                modifier = Modifier.fillMaxWidth()
            )
        }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Stop Casting Action Button
        Button(
            onClick = {
                if (!isTerminating) {
                    isTerminating = true
                    onStopCasting()
                }
            },
            colors = ButtonDefaults.buttonColors(containerColor = softRed),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.CastConnected,
                    contentDescription = null,
                    tint = Color.White
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Stop Casting",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}
