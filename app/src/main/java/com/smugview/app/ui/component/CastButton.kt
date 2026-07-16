package com.smugview.app.ui.component

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.smugview.app.data.cast.ConnectionState

@Composable
fun CastButton(
    connectionState: ConnectionState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val scalePulse by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = if (connectionState == ConnectionState.CONNECTING) 1.15f else 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    val neonBlue = Color(0xFF00E5FF)
    val translucentDark = Color.Black.copy(alpha = 0.3f)
    
    val buttonColor = when (connectionState) {
        ConnectionState.CONNECTED -> neonBlue.copy(alpha = 0.2f)
        ConnectionState.CONNECTING -> neonBlue.copy(alpha = 0.1f)
        else -> translucentDark
    }

    val iconColor = when (connectionState) {
        ConnectionState.CONNECTED -> neonBlue
        ConnectionState.CONNECTING -> neonBlue.copy(alpha = 0.7f)
        else -> Color.White
    }

    val borderColor = when (connectionState) {
        ConnectionState.CONNECTED -> neonBlue
        ConnectionState.CONNECTING -> neonBlue.copy(alpha = 0.5f)
        else -> Color.Gray.copy(alpha = 0.3f)
    }

    val icon = if (connectionState == ConnectionState.CONNECTED) {
        Icons.Default.CastConnected
    } else {
        Icons.Default.Cast
    }

    val contentDesc = when (connectionState) {
        ConnectionState.CONNECTED -> "Casting active. Tap to view controls."
        ConnectionState.CONNECTING -> "Connecting to cast device."
        else -> "Cast screen. Tap to discover devices."
    }

    Box(
        modifier = modifier
            .size(48.dp) // Minimum touch target size
            .scale(scalePulse)
            .clip(CircleShape)
            .background(buttonColor)
            .border(1.dp, borderColor, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDesc,
            tint = iconColor,
            modifier = Modifier.size(24.dp)
        )
    }
}
