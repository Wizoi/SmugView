package com.smugview.app.ui.browser

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smugview.app.data.offline.OfflineMessages
import com.smugview.app.data.offline.OfflineNetworkRule
import com.smugview.app.data.offline.OfflineStore
import com.smugview.app.ui.theme.NeonBlue
import com.smugview.app.ui.viewmodel.SmugViewModel

/** The Collections top bar button that opens the one network setting for kept galleries (addendum 2.2). */
@Composable
internal fun NetworkSettingsAction(onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(Icons.Default.Settings, contentDescription = OfflineMessages.SETTINGS_ICON, tint = NeonBlue)
    }
}

/**
 * Which network kept galleries save on. Choosing a row stores it at once (no Save button); [onClose] only closes.
 * [waiting] feeds the size line under "Wi-Fi or mobile data", so the cost is read before it is chosen.
 */
@Composable
internal fun NetworkSettingDialog(
    rule: OfflineNetworkRule,
    waiting: OfflineStore.GalleryWaiting,
    onChoose: (OfflineNetworkRule) -> Unit,
    onClose: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(OfflineMessages.NETWORK_TITLE) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.selectableGroup()) {
                    NetworkRow(OfflineMessages.NETWORK_WIFI_ONLY, OfflineMessages.NETWORK_WIFI_ONLY_HINT, rule == OfflineNetworkRule.WIFI_ONLY) {
                        onChoose(OfflineNetworkRule.WIFI_ONLY)
                    }
                    NetworkRow(
                        OfflineMessages.NETWORK_ANY,
                        OfflineMessages.networkAnyHint(waiting.bytes, waiting.unlisted),
                        rule == OfflineNetworkRule.WIFI_AND_MOBILE
                    ) { onChoose(OfflineNetworkRule.WIFI_AND_MOBILE) }
                }
                Text(OfflineMessages.NETWORK_PHOTOS_NOTE, color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text(OfflineMessages.CLOSE) } }
    )
}

@Composable
private fun NetworkRow(label: String, hint: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().selectable(selected = selected, onClick = onSelect, role = Role.RadioButton).padding(vertical = 6.dp),
        verticalAlignment = Alignment.Top
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(Modifier.padding(start = 12.dp)) {
            Text(label, fontSize = 15.sp)
            Text(hint, color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
        }
    }
}

/** The dialog over the view model: the stored rule, the size waiting now (read again after each change), and the write. */
@Composable
internal fun NetworkSettingHost(viewModel: SmugViewModel, onClose: () -> Unit) {
    val rule by viewModel.offlineNetworkRule.collectAsState()
    val waiting by produceState(OfflineStore.GalleryWaiting(0L, false), rule) { value = viewModel.galleryWaiting() }
    NetworkSettingDialog(rule = rule, waiting = waiting, onChoose = { viewModel.setOfflineNetworkRule(it) }, onClose = onClose)
}
