package com.smugview.app.ui.component

import android.content.Intent
import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.smugview.app.share.ShareContent
import com.smugview.app.ui.text.UserMessages
import com.smugview.app.ui.theme.NeonBlue
import com.smugview.app.ui.theme.SurfaceDark
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The single sharing surface for a folder/gallery/photo link: shows a scannable QR code for the
 * same web URL the app would otherwise hand to the native share sheet, so someone standing nearby
 * can scan it to open that SmugMug page — plus a fallback into that native share sheet for
 * anything else (messaging apps, email, etc.). This dialog IS the existing "Share" action's
 * destination; it's not a second button anywhere.
 *
 * A blank, non-`https` or CDN [url] (design 3.10) shows [UserMessages.SHARE_NO_LINK] instead of a code, and its
 * buttons are off. The code is drawn on `Dispatchers.Default`.
 */
@Composable
fun QrShareDialog(
    title: String,
    url: String,
    onDismissRequest: () -> Unit,
    // A photo also offers its picture (stripped of camera data) next to the link.
    onSharePicture: ((android.content.Context) -> Unit)? = null
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val hasLink = ShareContent.isShareable(url)

    val qrBitmap by produceState<Bitmap?>(initialValue = null, url) {
        value = if (hasLink) withContext(Dispatchers.Default) { generateQrCodeBitmap(url) } else null
    }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = {
            Text(
                text = title,
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(220.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.White),
                    contentAlignment = Alignment.Center
                ) {
                    val bitmap = qrBitmap
                    when {
                        !hasLink -> Text(
                            text = UserMessages.SHARE_NO_LINK,
                            color = Color.Black,
                            fontSize = 14.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(16.dp)
                        )
                        bitmap != null -> Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = "QR code for $title",
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(12.dp)
                        )
                        else -> CircularProgressIndicator(color = NeonBlue)
                    }
                }

                if (hasLink) {
                    Text(
                        text = UserMessages.SHARE_CAPTION,
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center
                    )

                    Text(
                        text = url,
                        color = Color.White.copy(alpha = 0.5f),
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        enabled = hasLink,
                        onClick = {
                            clipboardManager.setText(AnnotatedString(url))
                            Toast.makeText(context, "Link copied", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Copy", fontSize = 13.sp)
                    }

                    Button(
                        enabled = hasLink,
                        onClick = {
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, "Checkout '$title': $url")
                            }
                            context.startActivity(Intent.createChooser(intent, UserMessages.SHARE_LINK))
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = NeonBlue, contentColor = Color.Black)
                    ) {
                        Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(UserMessages.SHARE_LINK, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                }

                if (onSharePicture != null) {
                    OutlinedButton(
                        onClick = { onSharePicture(context) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                    ) {
                        Text(UserMessages.SHARE_PICTURE, fontSize = 13.sp)
                    }
                    Text(
                        text = UserMessages.SHARE_STRIPPED,
                        color = Color.White.copy(alpha = 0.5f),
                        fontSize = 11.sp,
                        textAlign = TextAlign.Center
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDismissRequest,
                colors = ButtonDefaults.textButtonColors(contentColor = NeonBlue)
            ) {
                Text("Done", fontWeight = FontWeight.Bold)
            }
        },
        containerColor = SurfaceDark,
        shape = RoundedCornerShape(16.dp)
    )
}

/** Renders [url] as a black-on-white QR bitmap, or null if it couldn't be encoded (e.g. blank). */
private fun generateQrCodeBitmap(url: String, sizePx: Int = 512): Bitmap? {
    if (url.isBlank()) return null
    return try {
        val hints = mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 0)
        val bitMatrix = QRCodeWriter().encode(url, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
        val pixels = IntArray(sizePx * sizePx) { i ->
            if (bitMatrix[i % sizePx, i / sizePx]) android.graphics.Color.BLACK else android.graphics.Color.WHITE
        }
        Bitmap.createBitmap(pixels, sizePx, sizePx, Bitmap.Config.RGB_565)
    } catch (e: Exception) {
        null
    }
}
