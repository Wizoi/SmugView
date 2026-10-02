package com.smugview.app.ui.component

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smugview.app.ui.browser.RemoveConfirmDialog
import kotlinx.coroutines.launch
import com.smugview.app.ui.theme.NeonBlue
import com.smugview.app.ui.theme.SurfaceDark
import com.smugview.app.ui.viewmodel.SmugViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddToCollectionsDialog(
    type: String, // "Folder", "Album", or "Image"
    itemKey: String, // nodeId or imageKey
    title: String,
    albumKey: String = "",
    albumTitle: String = "",
    thumbnailUrl: String? = null,
    imageUrl: String? = null,
    albumKeyResolver: (suspend () -> String?)? = null,
    onDismissRequest: () -> Unit,
    viewModel: SmugViewModel
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val localCollections by viewModel.localCollections.collectAsState()

    // What is ticked now, and what was bookmarked when the dialog opened. Opening writes nothing (6-16, Q9);
    // "Save" writes the ticked set, "Cancel" writes nothing.
    val ticked = remember(itemKey) { mutableStateMapOf<Long, Boolean>() }
    val wasBookmarked = remember(itemKey) { mutableStateMapOf<Long, Boolean>() }

    LaunchedEffect(localCollections, itemKey) {
        localCollections.forEach { col ->
            if (wasBookmarked.containsKey(col.id)) return@forEach
            val bookmarked = viewModel.isBookmarked(col.id, type, itemKey)
            wasBookmarked[col.id] = bookmarked
            ticked[col.id] = bookmarked || viewModel.lastSelectedCollectionIds.contains(col.id)
        }
    }

    var newCollectionName by remember { mutableStateOf("") }
    var pendingRemoval by remember { mutableStateOf<String?>(null) }

    fun commit() {
        scope.launch(kotlinx.coroutines.Dispatchers.Main) {
            val key = albumKey.ifBlank { albumKeyResolver?.invoke().orEmpty() }
            val added = localCollections.filter { ticked[it.id] == true && wasBookmarked[it.id] != true }
            val removed = localCollections.filter { ticked[it.id] != true && wasBookmarked[it.id] == true }
            added.forEach { col ->
                viewModel.addBookmark(
                    collectionId = col.id, type = type, itemKey = itemKey, title = title,
                    albumKey = key, albumTitle = albumTitle, thumbnailUrl = thumbnailUrl, imageUrl = imageUrl
                )
                Toast.makeText(context, "Saved to ${col.name}!", Toast.LENGTH_SHORT).show()
            }
            removed.forEach { col ->
                viewModel.removeBookmark(col.id, type, itemKey)
                Toast.makeText(context, "Removed from ${col.name}", Toast.LENGTH_SHORT).show()
            }
            viewModel.lastSelectedCollectionIds = localCollections.filter { ticked[it.id] == true }.map { it.id }.toSet()
            onDismissRequest()
        }
    }

    pendingRemoval?.let { text ->
        RemoveConfirmDialog(
            text = text,
            onConfirm = { pendingRemoval = null; commit() },
            onDismiss = { pendingRemoval = null }
        )
    }

    val onSave: () -> Unit = {
        scope.launch(kotlinx.coroutines.Dispatchers.Main) {
            val removed = localCollections.filter { ticked[it.id] != true && wasBookmarked[it.id] == true }
            val confirm = if (type == "Image" && removed.isNotEmpty()) {
                viewModel.removeImageConfirm(removed, itemKey, title, dropsPhotoRow = false, dropsBookmark = true)
            } else null
            if (confirm == null) commit() else pendingRemoval = confirm.text
        }
    }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = {
            Text(
                text = "Save $type to Collection",
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Selected Item: $title",
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 13.sp,
                    maxLines = 1,
                    modifier = Modifier.padding(bottom = 4.dp)
                )

                // List of collections
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 240.dp)
                ) {
                    if (localCollections.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "No collections created yet.",
                                color = Color.White.copy(alpha = 0.4f),
                                fontSize = 14.sp
                            )
                        }
                    } else {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            localCollections.forEach { col ->
                                val isChecked = ticked[col.id] ?: false
                                val toggleBookmark = { checked: Boolean -> ticked[col.id] = checked }
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(Color.White.copy(alpha = 0.04f), RoundedCornerShape(8.dp))
                                        .clickable { toggleBookmark(!isChecked) }
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Folder,
                                            contentDescription = "Collection",
                                            tint = NeonBlue.copy(alpha = 0.8f),
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Text(
                                            text = col.name,
                                            color = Color.White,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Medium
                                        )
                                    }
                                    
                                    Checkbox(
                                        checked = isChecked,
                                        onCheckedChange = { toggleBookmark(it) },
                                        colors = CheckboxDefaults.colors(
                                            checkedColor = NeonBlue,
                                            uncheckedColor = Color.White.copy(alpha = 0.3f),
                                            checkmarkColor = Color.Black
                                        )
                                    )
                                }
                            }
                        }
                    }
                }

                Divider(color = Color.White.copy(alpha = 0.1f))

                // Create new collection inline input
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TextField(
                        value = newCollectionName,
                        onValueChange = { newCollectionName = it },
                        placeholder = { Text("New Collection Name...", color = Color.White.copy(alpha = 0.3f), fontSize = 13.sp) },
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                        shape = RoundedCornerShape(8.dp),
                        colors = TextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedContainerColor = Color.White.copy(alpha = 0.05f),
                            unfocusedContainerColor = Color.White.copy(alpha = 0.05f),
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent
                        ),
                        singleLine = true,
                        textStyle = LocalTextStyle.current.copy(fontSize = 13.sp)
                    )

                    IconButton(
                        onClick = {
                            if (newCollectionName.trim().isNotEmpty()) {
                                viewModel.createCollection(newCollectionName.trim())
                                Toast.makeText(context, "Collection created!", Toast.LENGTH_SHORT).show()
                                newCollectionName = ""
                            }
                        },
                        modifier = Modifier
                            .size(48.dp)
                            .background(NeonBlue, RoundedCornerShape(8.dp))
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Create",
                            tint = Color.Black
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onSave,
                colors = ButtonDefaults.textButtonColors(contentColor = NeonBlue)
            ) {
                Text("Save", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismissRequest,
                colors = ButtonDefaults.textButtonColors(contentColor = Color.White.copy(alpha = 0.7f))
            ) {
                Text("Cancel")
            }
        },
        containerColor = SurfaceDark,
        shape = RoundedCornerShape(16.dp)
    )
}
