package com.smugview.app.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smugview.app.ui.WelcomeHelp
import com.smugview.app.ui.theme.NeonBlue
import com.smugview.app.ui.theme.SurfaceDark

/**
 * "How to use SmugView": a few short tips, the way to send feedback in the bottom row (the support page, which has the email address and the issue tracker),
 * and the privacy policy. Shown once to a new user and from the Help button after that. Two taps at most to leave: Done, or tap outside.
 */
@Composable
fun HelpDialog(onDismiss: () -> Unit) {
    val links = LocalUriHandler.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("How to use SmugView", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier.widthIn(max = 440.dp).heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                TIPS.forEach { Text(it, color = Color.White.copy(alpha = 0.85f), fontSize = 14.sp) }
                OutlinedButton(
                    onClick = { links.openUri(WelcomeHelp.PRIVACY_URL) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                ) { Text("Privacy policy") }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done", color = NeonBlue, fontWeight = FontWeight.Bold) } },
        // Always visible, not below the tips: the feedback link is the point of the Help entry (the support page has the email and the issue tracker).
        dismissButton = { TextButton(onClick = { links.openUri(WelcomeHelp.SUPPORT_URL) }) { Text("Send feedback", color = Color.White.copy(alpha = 0.85f)) } },
        containerColor = SurfaceDark,
        shape = RoundedCornerShape(16.dp)
    )
}

private val TIPS = listOf(
    "Pick a public site: tap one of the suggestions, or type a SmugMug nickname and tap Connect & Browse Site. No account is needed.",
    "Open a gallery, then a photo. Swipe to move between photos. The i button shows the camera details.",
    "Search and Tags find photos across the whole site. Tags can include or leave out words.",
    "Share sends a link, a QR code, or the picture at the size you choose. The bookmark keeps a photo in your own collection, and the download button saves it to this device.",
    "A lock marks a password folder. Enter its password once and this device remembers it.",
    "Blue dots mark galleries that changed since you last looked. The check mark in your site's tab clears them all."
)
