package com.smugview.spikeui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smugview.shared.api.NodeData
import com.smugview.shared.api.NodeListResponse
import kotlinx.serialization.json.Json

/**
 * iOS spike, Stage 0 step 4: the first shared screen. It reads the recorded `!children` answer of the owner's public site through the
 * shared kotlinx.serialization models and lists the folders and galleries, in the app's always-dark style. The same composable runs on
 * Android and on iOS (a `ComposeUIViewController`).
 */
@Composable
fun FolderListApp() {
    val nodes = remember {
        Json { ignoreUnknownKeys = true }.decodeFromString<NodeListResponse>(DemoData.ROOT_CHILDREN).response.nodes.orEmpty()
    }
    MaterialTheme(colorScheme = darkColorScheme()) {
        Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF090A0C)) {
            Column(modifier = Modifier.fillMaxSize().padding(start = 16.dp, end = 16.dp, top = 64.dp, bottom = 16.dp)) {
                Text("SmugView on iOS", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                Text("idzifamily: ${nodes.size} folders and galleries (shared Kotlin UI)", color = Color(0xFF00E5FF), fontSize = 13.sp)
                LazyColumn(modifier = Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(nodes, key = { it.nodeId }) { FolderRow(it) }
                }
            }
        }
    }
}

@Composable
private fun FolderRow(node: NodeData) {
    val locked = node.securityType == "Password"
    Row(
        modifier = Modifier.fillMaxWidth().background(Color(0xFF121418), RoundedCornerShape(12.dp)).padding(14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(node.name ?: "Untitled", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Text(if (node.type == "Album") "Gallery" else "Folder", color = Color.Gray, fontSize = 12.sp)
        }
        if (locked) Text("password", color = Color(0xFFFFB74D), fontSize = 12.sp)
    }
}
