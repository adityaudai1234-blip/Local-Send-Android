package com.example.presentation.storage

import android.text.format.DateFormat
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.transfer.ReceivedFileCategory
import com.example.data.transfer.ReceivedFileEntry
import com.example.data.transfer.StorageManager
import com.example.domain.model.TransferItem
import com.example.presentation.components.EmptyStateView
import com.example.presentation.components.FilePreviewDialog
import com.example.presentation.components.FilePreviewThumbnail
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Date

enum class ReceivedFileSort(val label: String) {
    DATE_DESC("Newest"),
    NAME_ASC("Name"),
    SIZE_DESC("Largest")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceivedFilesScreen(
    storageManager: StorageManager,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onFileDeleted: (String) -> Unit = {}
) {
    BackHandler(onBack = onBack)

    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var allEntries by remember { mutableStateOf<List<ReceivedFileEntry>>(emptyList()) }
    var selectedCategory by remember { mutableStateOf(ReceivedFileCategory.ALL) }
    var searchQuery by remember { mutableStateOf("") }
    var sortOption by remember { mutableStateOf(ReceivedFileSort.DATE_DESC) }
    var showSortMenu by remember { mutableStateOf(false) }
    var previewEntry by remember { mutableStateOf<ReceivedFileEntry?>(null) }
    var pendingDelete by remember { mutableStateOf<ReceivedFileEntry?>(null) }

    fun reloadFiles() {
        scope.launch {
            allEntries = withContext(Dispatchers.IO) {
                storageManager.listReceivedFileEntries()
            }
        }
    }

    LaunchedEffect(Unit) {
        reloadFiles()
    }

    val filteredEntries = remember(allEntries, selectedCategory, searchQuery, sortOption) {
        val base = allEntries.filter { entry ->
            val catMatch = selectedCategory == ReceivedFileCategory.ALL || entry.category == selectedCategory
            val q = searchQuery.trim()
            val textMatch = q.isBlank() ||
                entry.name.contains(q, ignoreCase = true) ||
                entry.relativeFolder.contains(q, ignoreCase = true)
            catMatch && textMatch
        }
        when (sortOption) {
            ReceivedFileSort.DATE_DESC -> base.sortedByDescending { it.lastModified }
            ReceivedFileSort.NAME_ASC -> base.sortedBy { it.name.lowercase() }
            ReceivedFileSort.SIZE_DESC -> base.sortedByDescending { it.size }
        }
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .testTag("received_files_screen"),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Received Files",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "${filteredEntries.size} item(s) • ${TransferItem.formatBytes(filteredEntries.sumOf { it.size })}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag("received_files_back_button")
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { showSortMenu = true }) {
                            Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "Sort files")
                        }
                        DropdownMenu(
                            expanded = showSortMenu,
                            onDismissRequest = { showSortMenu = false }
                        ) {
                            ReceivedFileSort.entries.forEach { opt ->
                                DropdownMenuItem(
                                    text = { Text("Sort by ${opt.label}") },
                                    onClick = {
                                        sortOption = opt
                                        showSortMenu = false
                                    }
                                )
                            }
                        }
                    }
                    IconButton(onClick = { reloadFiles() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh received files")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Search received files or folders…") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("received_files_search_input")
            )

            Spacer(modifier = Modifier.height(10.dp))

            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(ReceivedFileCategory.entries) { category ->
                    val count = if (category == ReceivedFileCategory.ALL) {
                        allEntries.size
                    } else {
                        allEntries.count { it.category == category }
                    }
                    FilterChip(
                        selected = selectedCategory == category,
                        onClick = { selectedCategory = category },
                        label = { Text("${category.label} ($count)") },
                        modifier = Modifier.testTag("category_chip_${category.name}")
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            if (filteredEntries.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    EmptyStateView(
                        icon = Icons.Default.FolderOpen,
                        title = if (allEntries.isEmpty()) "No received files yet" else "No matching files",
                        description = if (allEntries.isEmpty()) {
                            "Incoming files saved to Downloads/LocalDrop will be organized and browsable here."
                        } else {
                            "Try clearing the search query or selecting All categories."
                        }
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(filteredEntries, key = { it.file.absolutePath }) { entry ->
                        ReceivedBrowserRow(
                            entry = entry,
                            onPreview = { previewEntry = entry },
                            onShare = { storageManager.shareFile(entry.file) },
                            onDelete = { pendingDelete = entry }
                        )
                    }
                    item { Spacer(modifier = Modifier.height(16.dp)) }
                }
            }
        }
    }

    previewEntry?.let { entry ->
        FilePreviewDialog(
            fileName = entry.name,
            fileSizeBytes = entry.size,
            mimeType = entry.mimeType,
            relativeFolder = entry.relativeFolder.takeIf { it.isNotBlank() },
            file = entry.file,
            onOpen = { storageManager.openFile(entry.file) },
            onShare = { storageManager.shareFile(entry.file) },
            onDismiss = { previewEntry = null }
        )
    }

    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete File?") },
            text = { Text("Permanently delete '${target.name}' from device storage?") },
            confirmButton = {
                Button(
                    onClick = {
                        pendingDelete = null
                        scope.launch {
                            val deleted = withContext(Dispatchers.IO) {
                                storageManager.deleteFile(target.file)
                            }
                            if (deleted) {
                                onFileDeleted(target.file.absolutePath)
                            }
                            reloadFiles()
                            snackbarHostState.showSnackbar(
                                if (deleted) "Deleted ${target.name}" else "Could not delete file"
                            )
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun ReceivedBrowserRow(
    entry: ReceivedFileEntry,
    onPreview: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onPreview)
            .testTag("received_browser_item_${entry.name}"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilePreviewThumbnail(
                fileName = entry.name,
                mimeType = entry.mimeType,
                file = entry.file,
                size = 48.dp
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val dateStr = DateFormat.format("MMM d, HH:mm", Date(entry.lastModified)).toString()
                Text(
                    text = "${TransferItem.formatBytes(entry.size)} • ${entry.category.label} • $dateStr",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (entry.relativeFolder.isNotBlank()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Folder,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = entry.relativeFolder,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            IconButton(
                onClick = onShare,
                modifier = Modifier.size(48.dp)
            ) {
                Icon(Icons.Default.Share, contentDescription = "Share ${entry.name}", tint = MaterialTheme.colorScheme.primary)
            }
            IconButton(
                onClick = onDelete,
                modifier = Modifier.size(48.dp)
            ) {
                Icon(Icons.Default.DeleteOutline, contentDescription = "Delete ${entry.name}", tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}
