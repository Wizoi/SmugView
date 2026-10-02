package com.smugview.app.ui.browser

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smugview.app.data.offline.GallerySummary
import com.smugview.app.data.offline.OfflineMessages
import com.smugview.app.data.offline.RowState
import com.smugview.app.ui.theme.NeonBlue

/**
 * What a collection row says about its saved copy, and the two ways out of a failure (phase 5 design 3 and 4, 5-9).
 * The words are computed by [com.smugview.app.data.offline.OfflineRowState] and unit-tested; this only draws them.
 * [state] is null for a row with nothing saved or planned (nothing is drawn).
 */
@Composable
internal fun OfflineRowLine(state: RowState?, onTryAgain: () -> Unit, onRemove: () -> Unit) {
    if (state == null) return
    val failed = state.kind == RowState.Kind.FAILED
    Column {
        Text(
            text = state.text,
            color = if (failed) MaterialTheme.colorScheme.error else Color.White.copy(alpha = 0.5f),
            fontSize = 11.sp
        )
        state.note?.let { Text(text = it, color = Color.White.copy(alpha = 0.4f), fontSize = 10.sp) }
        if (failed && (state.canTryAgain || state.canRemove)) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (state.canTryAgain) TextButton(onClick = onTryAgain) { Text(OfflineMessages.TRY_AGAIN, color = NeonBlue, fontSize = 12.sp) }
                if (state.canRemove) TextButton(onClick = onRemove) { Text(OfflineMessages.REMOVE, color = NeonBlue, fontSize = 12.sp) }
            }
        }
    }
}

/**
 * The "Keep offline" controls of a gallery shortcut (Q1): the switch, the progress or failure line, and the
 * "Change network setting" link while it waits for Wi-Fi (the rule is global, never per gallery).
 * [kept] is the gallery's summary, or null when it is not kept.
 */
@Composable
internal fun GalleryKeepOffline(
    kept: GallerySummary?,
    onKeepChange: (Boolean) -> Unit,
    onChangeNetworkSetting: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = kept?.keepLabel ?: OfflineMessages.KEEP_OFFLINE,
                color = Color.White.copy(alpha = 0.85f),
                fontSize = 13.sp
            )
            Switch(checked = kept != null, onCheckedChange = onKeepChange)
        }
        if (kept != null) {
            val failed = kept.kind == GallerySummary.Kind.FAILED
            Text(
                text = kept.text,
                color = if (failed) MaterialTheme.colorScheme.error else Color.White.copy(alpha = 0.55f),
                fontSize = 11.sp
            )
            kept.detail?.let { Text(text = it, color = MaterialTheme.colorScheme.error.copy(alpha = 0.85f), fontSize = 11.sp) }
            kept.action?.let { label ->
                TextButton(onClick = onChangeNetworkSetting) { Text(label, color = NeonBlue, fontSize = 12.sp) }
            }
        }
    }
}

/** Q8: asks before deleting a collection whose saved photos would go with it. [text] is the DELETE_CONFIRM line. */
@Composable
internal fun DeleteCollectionDialog(text: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Delete") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** Q10 (6-16): asks before an unsave, a row "Remove" or turning off Keep offline deletes a saved copy nothing else uses. */
@Composable
internal fun RemoveConfirmDialog(text: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Remove") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
