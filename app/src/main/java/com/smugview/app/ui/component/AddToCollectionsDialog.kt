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
    onDismissRequest: () -> Unit,
    viewModel: SmugViewModel
) {
    val context = LocalContext.current
    val localCollections by viewModel.localCollections.collectAsState()
    
    // Track local bookmark checkbox states
    val bookmarkStates = remember(localCollections, itemKey) {
        mutableStateMapOf<Long, Boolean>()
    }
    
    // Fetch checked status for each collection
    LaunchedEffect(localCollections, itemKey) {
        localCollections.forEach { col ->
            val isInitiallyBookmarked = viewModel.isBookmarked(col.id, type, itemKey)
            if (isInitiallyBookmarked) {
                bookmarkStates[col.id] = true
            } else if (viewModel.lastSelectedCollectionIds.contains(col.id)) {
                bookmarkStates[col.id] = true
                viewModel.addBookmark(
                    collectionId = col.id,
                    type = type,
                    itemKey = itemKey,
                    title = title,
                    albumKey = albumKey,
                    albumTitle = albumTitle,
                    thumbnailUrl = thumbnailUrl,
                    imageUrl = imageUrl
                )
            } else {
                bookmarkStates[col.id] = false
            }
        }
    }

    var newCollectionName by remember { mutableStateOf("") }

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
                                val isChecked = bookmarkStates[col.id] ?: false
                                val toggleBookmark = { checked: Boolean ->
                                    if (checked) {
                                        viewModel.addBookmark(
                                            collectionId = col.id,
                                            type = type,
                                            itemKey = itemKey,
                                            title = title,
                                            albumKey = albumKey,
                                            albumTitle = albumTitle,
                                            thumbnailUrl = thumbnailUrl,
                                            imageUrl = imageUrl
                                        )
                                        bookmarkStates[col.id] = true
                                        viewModel.lastSelectedCollectionIds = viewModel.lastSelectedCollectionIds + col.id
                                        Toast.makeText(context, "Saved to ${col.name}!", Toast.LENGTH_SHORT).show()
                                    } else {
                                        viewModel.removeBookmark(col.id, type, itemKey)
                                        bookmarkStates[col.id] = false
                                        viewModel.lastSelectedCollectionIds = viewModel.lastSelectedCollectionIds - col.id
                                        Toast.makeText(context, "Removed from ${col.name}", Toast.LENGTH_SHORT).show()
                                    }
                                }

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
