package com.smugview.app.ui.explorer

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LocalOffer
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import coil.compose.AsyncImage
import com.smugview.app.data.api.AlbumImageData
import com.smugview.app.data.api.isVideo
import com.smugview.app.ui.theme.DeepDarkBackground
import com.smugview.app.ui.theme.NeonBlue
import com.smugview.app.ui.theme.SurfaceDark
import com.smugview.app.ui.viewmodel.SmugViewModel
import com.smugview.app.ui.viewmodel.SmugViewModel.TagFilterState

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun KeywordImagesScreen(
    onBackClick: () -> Unit,
    onNavigateToPhotoDetail: (String, Int) -> Unit,
    viewModel: SmugViewModel
) {
    val selectedTags by viewModel.selectedTags.collectAsState()
    val allTags by viewModel.allScopeTags.collectAsState()
    val filteredPhotos by viewModel.tagFilteredPhotos.collectAsState()
    val isScanning by viewModel.isScanningTags.collectAsState()

    var tagInput by remember { mutableStateOf("") }
    val focusManager = LocalFocusManager.current
    var isFocused by remember { mutableStateOf(false) }
    val keyboardController = LocalSoftwareKeyboardController.current

    val suggestions = remember(tagInput, allTags, selectedTags) {
        val query = tagInput.trim().lowercase()
        if (query.isEmpty()) {
            emptyList()
        } else {
            allTags.keys.filter {
                it.contains(query) && !selectedTags.containsKey(it)
            }.take(5)
        }
    }

    Scaffold(
        containerColor = DeepDarkBackground,
        topBar = {
            TopAppBar(
                title = { Text("Keyword Search", color = Color.White, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = DeepDarkBackground)
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp)
        ) {
            // Autocomplete Textbox
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { isFocused = it.isFocused }
            ) {
                OutlinedTextField(
                    value = tagInput,
                    onValueChange = { tagInput = it },
                    placeholder = { Text("Add tag filter...", color = Color.White.copy(alpha = 0.4f)) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.LocalOffer,
                            contentDescription = "Tag",
                            tint = NeonBlue
                        )
                    },
                    trailingIcon = {
                        if (tagInput.isNotEmpty()) {
                            IconButton(onClick = { tagInput = "" }) {
                                Icon(Icons.Default.Close, contentDescription = "Clear", tint = Color.White.copy(alpha = 0.5f))
                            }
                        }
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Text,
                        imeAction = ImeAction.Search
                    ),
                    keyboardActions = KeyboardActions(
                        onSearch = {
                            if (tagInput.isNotBlank()) {
                                viewModel.selectTag(tagInput.trim())
                                tagInput = ""
                                keyboardController?.hide()
                                focusManager.clearFocus()
                            }
                        }
                    ),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = NeonBlue,
                        unfocusedBorderColor = Color.White.copy(alpha = 0.2f),
                        focusedContainerColor = SurfaceDark,
                        unfocusedContainerColor = SurfaceDark
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                // Autocomplete Popup Card
                if (isFocused && suggestions.isNotEmpty()) {
                    Popup(
                        alignment = Alignment.BottomStart,
                        onDismissRequest = { isFocused = false }
                    ) {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.1f)),
                            modifier = Modifier
                                .width(300.dp)
                                .padding(top = 4.dp)
                                .heightIn(max = 200.dp)
                        ) {
                            LazyColumn {
                                items(suggestions.size) { index ->
                                    val suggestion = suggestions[index]
                                    Text(
                                        text = suggestion,
                                        color = Color.White,
                                        fontSize = 14.sp,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                viewModel.selectTag(suggestion)
                                                tagInput = ""
                                                focusManager.clearFocus()
                                            }
                                            .padding(horizontal = 16.dp, vertical = 12.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Pills (chips) for active keywords at the top
            if (selectedTags.isNotEmpty()) {
                FlowRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    selectedTags.forEach { (tag, state) ->
                        val containerColor = if (state == TagFilterState.INCLUDED) {
                            NeonBlue.copy(alpha = 0.15f)
                        } else {
                            Color.Red.copy(alpha = 0.15f)
                        }
                        val textColor = if (state == TagFilterState.INCLUDED) NeonBlue else Color.Red
                        val prefix = if (state == TagFilterState.INCLUDED) "+ " else "- "

                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = containerColor,
                            border = BorderStroke(1.dp, textColor.copy(alpha = 0.3f)),
                            modifier = Modifier.clickable {
                                viewModel.toggleTagState(tag)
                            }
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = "$prefix$tag",
                                    color = textColor,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Remove",
                                    tint = textColor.copy(alpha = 0.7f),
                                    modifier = Modifier
                                        .size(14.dp)
                                        .clickable { viewModel.removeTag(tag) }
                                )
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            // Loader or Results Grid
            if (isScanning) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = NeonBlue)
                }
            } else {
                if (filteredPhotos.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (selectedTags.isEmpty()) "Add keywords above to find images." else "No images found for selected keywords.",
                            color = Color.White.copy(alpha = 0.5f),
                            fontSize = 15.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                } else {
                    Text(
                        text = "Results (${filteredPhotos.size}):",
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )

                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    ) {
                        itemsIndexed(filteredPhotos) { index, photo ->
                            Box(
                                modifier = Modifier
                                    .aspectRatio(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(SurfaceDark)
                                    .clickable {
                                        onNavigateToPhotoDetail(photo.imageKey, index)
                                    }
                            ) {
                                AsyncImage(
                                    model = photo.thumbnailUrl,
                                    contentDescription = photo.title ?: photo.caption,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                                if (photo.isVideo) {
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .padding(6.dp)
                                            .size(20.dp)
                                            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(10.dp)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.PlayArrow,
                                            contentDescription = "Video",
                                            tint = Color.White,
                                            modifier = Modifier.size(12.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
