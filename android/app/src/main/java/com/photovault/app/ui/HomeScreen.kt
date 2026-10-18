package com.photovault.app.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.LibraryAdd
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.background
import com.photovault.app.backup.BackupWorker
import com.photovault.app.data.MediaItem
import com.photovault.app.data.Repo
import kotlinx.coroutines.delay

/** Items for the main Photos timeline: everything except archived/trashed. */
fun timelineOf(all: List<MediaItem>) = all.filter { it.remote?.archived != true && !(it.local == null && it.remote?.trashedAt != null) }

@Composable
fun HomeScreen() {
    val app = LocalApp.current
    val tab = app.tab
    val ctx = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(Unit) {
        Repo.refreshLocal()
        Repo.syncRemote()
        Repo.refreshExtras()
        BackupWorker.runNow(ctx)
        // Keep the timeline fresh with uploads from other devices.
        while (true) { delay(60_000); Repo.syncRemote() }
    }
    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(tab == 0, { app.tab = 0 }, { Icon(Icons.Filled.Photo, null) }, label = { Text("Photos") })
                NavigationBarItem(tab == 1, { app.tab = 1 }, { Icon(Icons.Filled.Search, null) }, label = { Text("Search") })
                NavigationBarItem(tab == 2, { app.tab = 2 }, { Icon(Icons.AutoMirrored.Filled.LibraryBooks, null) }, label = { Text("Library") })
            }
        },
    ) { pad ->
        Box(Modifier.padding(bottom = pad.calculateBottomPadding())) {
            when (tab) {
                0 -> PhotosTab()
                1 -> SearchTab()
                else -> LibraryTab()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhotosTab() {
    val app = LocalApp.current
    val all by Repo.all.collectAsState()
    val items = remember(all) { timelineOf(all) }
    val selection = remember { Selection() }
    val memories by Repo.memories.collectAsState()

    Column(Modifier.fillMaxSize()) {
        if (selection.active) SelectionBar(selection, items)
        else TopAppBar(
            title = { Text("PhotoVault", fontWeight = FontWeight.SemiBold) },
            actions = {
                BackupIndicator()
                IconButton(onClick = { app.push(Screen.Settings) }) { Icon(Icons.Filled.Settings, "Settings") }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
        )
        MediaGrid(
            items = items,
            selection = selection,
            state = app.gridState("photos"),
            onOpen = { app.push(Screen.Viewer(items, it)) },
            header = if (memories.isNotEmpty()) ({ MemoriesRow(memories) }) else null,
            empty = { EmptyState("No photos yet", "Photos on this phone and on your PhotoVault server show up here.") },
        )
    }
}

@Composable
fun BackupIndicator() {
    val app = LocalApp.current
    val status by Repo.backup.collectAsState()
    val online by Repo.online.collectAsState()
    IconButton(onClick = { app.push(Screen.Settings) }) {
        when {
            online == false -> Icon(Icons.Filled.CloudOff, "Offline", tint = MaterialTheme.colorScheme.error)
            status.running -> Box(contentAlignment = Alignment.Center) {
                CircularProgressIndicator(progress = { if (status.pending == 0) 0f else status.done.toFloat() / status.pending }, modifier = Modifier.size(26.dp), strokeWidth = 2.5.dp)
                Icon(Icons.Filled.CloudUpload, "Backing up", modifier = Modifier.size(14.dp))
            }
            else -> Icon(Icons.Filled.CloudDone, "Backed up", tint = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun MemoriesRow(memories: List<com.photovault.app.data.Memory>) {
    val app = LocalApp.current
    LazyRow(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)) {
        items(memories) { m ->
            val cover = m.items.firstOrNull { !it.isVideo } ?: m.items.first()
            Box(Modifier.width(118.dp).height(168.dp).clickable { app.push(Screen.Story(m)) }) {
                RoundThumb(Repo.api.thumbUrl(cover.id), Modifier.fillMaxSize())
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f))), androidx.compose.foundation.shape.RoundedCornerShape(14.dp)))
                Text(m.title, color = Color.White, fontWeight = FontWeight.SemiBold, modifier = Modifier.align(Alignment.BottomStart).padding(10.dp))
            }
        }
    }
}

/** Top bar shown while photos are selected. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelectionBar(selection: Selection, items: List<MediaItem>, albumId: Long? = null, extra: (@Composable (List<MediaItem>, () -> Unit) -> Unit)? = null) {
    val app = LocalApp.current
    val chosen = remember(selection.keys, items) { selection.items(items) }
    var menu by remember { mutableStateOf(false) }
    var albumPicker by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var lockPrompt by remember { mutableStateOf(false) }
    var datePrompt by remember { mutableStateOf(false) }
    val allFav = chosen.isNotEmpty() && chosen.all { it.favorite }
    TopAppBar(
        navigationIcon = { IconButton(onClick = { selection.clear() }) { Icon(Icons.Filled.Close, "Clear") } },
        title = { Text("${selection.keys.size}") },
        actions = {
            IconButton(onClick = { app.run { app.actions.share(chosen); null } }) { Icon(Icons.Filled.Share, "Share") }
            IconButton(onClick = { albumPicker = true }) { Icon(Icons.Filled.LibraryAdd, "Add to album") }
            IconButton(onClick = { app.run { app.actions.favorite(chosen, !allFav).also { selection.clear() } } }) {
                Icon(if (allFav) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, "Favorite")
            }
            IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, "Delete") }
            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
            DropdownMenu(menu, { menu = false }) {
                if (chosen.any { it.local != null && it.remote == null })
                    DropdownMenuItem({ Text("Back up now") }, { menu = false; app.run { app.actions.ensureRemote(chosen); selection.clear(); "Backed up" } })
                if (chosen.any { it.cloudOnly })
                    DropdownMenuItem({ Text("Save to this phone") }, { menu = false; app.run { app.actions.saveToDevice(chosen).also { selection.clear() } } })
                DropdownMenuItem({ Text("Archive") }, { menu = false; app.run { app.actions.archive(chosen, true).also { selection.clear() } } })
                DropdownMenuItem({ Text("Move to locked folder") }, { menu = false; lockPrompt = true })
                DropdownMenuItem({ Text("Edit date & time") }, { menu = false; datePrompt = true })
                if (chosen.any { it.backedUp })
                    DropdownMenuItem({ Text("Delete from this phone only") }, { menu = false; app.run { app.actions.freeUpSpace(chosen.filter { it.backedUp }).also { selection.clear() } } })
                if (albumId != null)
                    DropdownMenuItem({ Text("Remove from album") }, { menu = false; app.run { Repo.api.removeFromAlbum(albumId, chosen.mapNotNull { it.remote?.id }); selection.clear(); Repo.refreshExtras(); "Removed from album" } })
                extra?.invoke(chosen) { menu = false }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    )
    if (albumPicker) AlbumPicker(onDismiss = { albumPicker = false }) { id, name ->
        albumPicker = false
        app.run { app.actions.addToAlbum(chosen, id, name).also { selection.clear() } }
    }
    if (confirmDelete) ConfirmDialog("Move ${chosen.size} to trash?", "They'll be removed from this phone and your PhotoVault server. You can restore them from Trash for 30 days.", "Move to trash",
        { confirmDelete = false }) { app.run { app.actions.trash(chosen).also { selection.clear() } } }
    if (lockPrompt) TextPrompt("Enter your PhotoVault password", password = true, confirm = "Move", onDismiss = { lockPrompt = false }) { pw ->
        lockPrompt = false
        app.run { app.actions.lock(chosen, pw).also { selection.clear() } }
    }
    if (datePrompt) DateTimePrompt(chosen.first().date, onDismiss = { datePrompt = false }) { t ->
        datePrompt = false
        app.run { app.actions.setDate(chosen, t).also { selection.clear() } }
    }
}

@Composable
fun AlbumPicker(onDismiss: () -> Unit, onPick: (Long?, String?) -> Unit) {
    val albums by Repo.albums.collectAsState()
    var newAlbum by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { Repo.refreshExtras() }
    if (newAlbum) { TextPrompt("New album", label = "Album name", confirm = "Create", onDismiss = { newAlbum = false }) { onPick(null, it) }; return }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add to album") },
        text = {
            androidx.compose.foundation.lazy.LazyColumn {
                item {
                    Row(Modifier.fillMaxWidth().clickable { newAlbum = true }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(48.dp).background(MaterialTheme.colorScheme.surfaceVariant, androidx.compose.foundation.shape.RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) { Text("+", style = MaterialTheme.typography.titleLarge) }
                        Spacer(Modifier.width(14.dp)); Text("New album")
                    }
                }
                items(albums) { al ->
                    Row(Modifier.fillMaxWidth().clickable { onPick(al.id, null) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        RoundThumb(al.cover?.let { Repo.api.thumbUrl(it) }, Modifier.size(48.dp), 10)
                        Spacer(Modifier.width(14.dp))
                        Column { Text(al.name); Text("${al.count} items", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
