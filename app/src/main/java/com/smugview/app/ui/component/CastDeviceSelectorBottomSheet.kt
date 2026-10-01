package com.smugview.app.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smugview.app.data.cast.CastDevice
import com.smugview.app.data.cast.CastType
import com.smugview.app.data.cast.ConnectionState
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CastDeviceSelectorBottomSheet(
    devices: List<CastDevice>,
    onDeviceSelected: (CastDevice) -> Unit,
    onDismiss: () -> Unit,
    /** Called when the sheet leaves the screen WITHOUT a device having been picked (dismissed, or its screen went away): nothing reads the list, so discovery stops. After a pick the manager stops it itself. */
    onGone: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val deepDarkBackground = Color(0xFF090A0C)
    val surfaceDark = Color(0xFF121418)
    val neonBlue = Color(0xFF00E5FF)
    
    // Step 6-8 (R-56): a sheet that closes without a pick must stop discovery (the collections screen never did).
    var picked by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        onDispose { if (!picked) onGone() }
    }

    // Simulate active scan progress indicator for 2 seconds when opened
    var isScanning by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        delay(2000)
        isScanning = false
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = deepDarkBackground,
        dragHandle = {
            BottomSheetDefaults.DragHandle(color = Color.Gray.copy(alpha = 0.5f))
        },
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 32.dp, start = 16.dp, end = 16.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Cast to Device",
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
                
                if (isScanning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = neonBlue
                    )
                } else {
                    IconButton(
                        onClick = { isScanning = true },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Rescan devices",
                            tint = Color.LightGray
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Check if connecting state is active on any device to debounce double-taps
            val isConnecting = devices.any { it.state == ConnectionState.CONNECTING }

            if (devices.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                        .background(surfaceDark, RoundedCornerShape(12.dp))
                        .border(1.dp, Color.Gray.copy(alpha = 0.2f), RoundedCornerShape(12.dp))
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Default.TvOff,
                            contentDescription = "No Cast devices found",
                            tint = Color.Gray,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "No devices detected",
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 15.sp
                        )
                        Text(
                            text = "Ensure your smart TV or Chromecast is connected to the same Wi-Fi network.",
                            color = Color.Gray,
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(devices) { device ->
                        DeviceItem(
                            device = device,
                            isConnectingGlobal = isConnecting,
                            onDeviceSelected = { device ->
                                picked = true
                                onDeviceSelected(device)
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun DeviceItem(
    device: CastDevice,
    isConnectingGlobal: Boolean,
    onDeviceSelected: (CastDevice) -> Unit
) {
    val surfaceDark = Color(0xFF121418)
    val neonBlue = Color(0xFF00E5FF)
    
    val borderStroke = if (device.state == ConnectionState.CONNECTED) {
        Modifier.border(1.dp, neonBlue, RoundedCornerShape(12.dp))
    } else {
        Modifier.border(1.dp, Color.Gray.copy(alpha = 0.2f), RoundedCornerShape(12.dp))
    }

    val icon = when (device.type) {
        CastType.GOOGLE -> Icons.Default.Cast
        CastType.ROKU -> Icons.Default.Tv
        CastType.AMAZON -> Icons.Default.DeveloperBoard
    }

    val typeLabel = when (device.type) {
        CastType.GOOGLE -> "Google Cast"
        CastType.ROKU -> "Roku"
        CastType.AMAZON -> "Amazon"
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(borderStroke)
            .background(surfaceDark, RoundedCornerShape(12.dp))
            .clickable(enabled = !isConnectingGlobal && device.state != ConnectionState.CONNECTED) {
                onDeviceSelected(device)
            }
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = "$typeLabel device icon",
            tint = if (device.state == ConnectionState.CONNECTED) neonBlue else Color.White,
            modifier = Modifier.size(28.dp)
        )
        
        Spacer(modifier = Modifier.width(16.dp))
        
        Column(
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = device.name,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp
            )
            Text(
                text = "$typeLabel • ${device.ipAddress}",
                color = Color.Gray,
                fontSize = 12.sp
            )
        }

        when (device.state) {
            ConnectionState.CONNECTING -> {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = neonBlue
                )
            }
            ConnectionState.CONNECTED -> {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = "Connected",
                    tint = neonBlue,
                    modifier = Modifier.size(18.dp)
                )
            }
            else -> {
                // Disconnected/Failed
            }
        }
    }
}
