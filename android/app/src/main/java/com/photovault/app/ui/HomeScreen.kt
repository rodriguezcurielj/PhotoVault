package com.photovault.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBackIos
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.photovault.app.backup.BackupWorker
import com.photovault.app.data.MediaItem
import com.photovault.app.data.Repo
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Calendar

/** Items for the main Library: everything except archived/trashed. */
fun timelineOf(all: List<MediaItem>) = all.filter { it.remote?.archived != true && !(it.local == null && it.remote?.trashedAt != null) }

private val TABS = listOf("Library" to Icons.Filled.PhotoLibrary, "For You" to Icons.Outlined.AutoAwesome, "Albums" to Icons.Filled.Collections, "Search" to Icons.Filled.Search)

@Composable
fun HomeScreen() {
    val app = LocalApp.current
    val ctx = LocalContext.current
    LaunchedEffect(Unit) {
        Repo.refreshLocal()
        Repo.syncRemote()
        Repo.refreshExtras()
        BackupWorker.runNow(ctx)
        // Keep the library fresh with uploads from other devices.
        while (true) { delay(60_000); Repo.syncRemote() }
    }
    Box(Modifier.fillMaxSize().background(Ios.background)) {
        when (app.tab) {
            0 -> LibraryTab()
            1 -> ForYouTab()
            2 -> AlbumsTab()
            else -> SearchTab()
        }
        if (!app.selectingHome) AppleTabBar(app.tab, { app.tab = it }, Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
fun AppleTabBar(selected: Int, onSelect: (Int) -> Unit, modifier: Modifier) {
    Column(modifier.fillMaxWidth().background(Ios.bar)) {
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(Ios.separator))
        Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(top = 6.dp, bottom = 4.dp)) {
            TABS.forEachIndexed { i, (label, icon) ->
                val tint = if (i == selected) Ios.blue else Ios.gray
                Column(Modifier.weight(1f).clickable(interactionSource = null, indication = null) { onSelect(i) }, horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(icon, label, tint = tint, modifier = Modifier.size(26.dp))
                    Text(label, color = tint, fontSize = 10.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

/** Big title over the photos with a fade, like the iOS 17/18 Photos app. */
@Composable
fun TitleOverlay(title: String, subtitle: String?, modifier: Modifier = Modifier, onPhotos: Boolean = true, onBack: (() -> Unit)? = null,
                 actions: @Composable () -> Unit = {}) {
    val textColor = if (onPhotos) Color.White else Ios.label
    Box(modifier.fillMaxWidth().background(
        if (onPhotos) Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.55f), Color.Black.copy(alpha = 0.25f), Color.Transparent))
        else Brush.verticalGradient(listOf(Ios.background, Ios.background.copy(alpha = 0.92f), Ios.background.copy(alpha = 0f))))
        .statusBarsPadding().padding(start = 16.dp, end = 12.dp, top = 6.dp, bottom = 22.dp)) {
        Column(Modifier.align(Alignment.CenterStart).padding(end = 150.dp)) {
            if (onBack != null) Row(Modifier.clickable(onClick = onBack).padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.AutoMirrored.Filled.ArrowBackIos, "Back", tint = if (onPhotos) Color.White else Ios.blue, modifier = Modifier.size(18.dp))
                Text("Back", color = if (onPhotos) Color.White else Ios.blue, fontSize = 17.sp)
            }
            Text(title, style = MaterialTheme.typography.headlineMedium, color = textColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.labelLarge, color = textColor.copy(alpha = 0.85f), maxLines = 1)
        }
        Row(Modifier.align(Alignment.CenterEnd), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) { actions() }
    }
}

/** Translucent capsule button ("Select", "Cancel", "…"). */
@Composable
fun Capsule(text: String? = null, icon: ImageVector? = null, onPhotos: Boolean = true, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(50),
        color = if (onPhotos) Color.Black.copy(alpha = 0.35f) else Ios.fill) {
        Box(Modifier.height(32.dp).padding(horizontal = if (text != null) 14.dp else 6.dp), contentAlignment = Alignment.Center) {
            val c = if (onPhotos) Color.White else Ios.blue
            if (text != null) Text(text, color = c, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            else if (icon != null) Icon(icon, null, tint = c, modifier = Modifier.size(20.dp))
        }
    }
}

// ---------------------------------------------------------------- Library tab
@Composable
fun LibraryTab() {
    val app = LocalApp.current
    val all by Repo.all.collectAsState()
    val items = remember(all) { timelineOf(all) }
    val selection = remember { Selection() }
    val state = app.gridState("photos")
    var mode by remember { mutableStateOf(2) } // 0 years, 1 months, 2 all photos
    val scope = rememberCoroutineScope()
    val display = remember(items) { items.asReversed() }
    val range by remember(display) { derivedStateOf { visibleRange(state, display) } }
    LaunchedEffect(selection.active) { app.selectingHome = selection.active }

    Box(Modifier.fillMaxSize()) {
        when (mode) {
            2 -> MediaGrid(items, selection, onOpen = { list, i -> app.push(Screen.Viewer(list, i)) }, state = state,
                empty = { EmptyState("No Photos or Videos", "Photos on this phone and on your Gallery server show up here.") })
            1 -> MonthCards(items) { firstOfMonth -> mode = 2; scope.launch { state.scrollToItem(display.indexOf(firstOfMonth).coerceAtLeast(0)) } }
            else -> YearCards(items) { firstOfYear -> mode = 1; scope.launch { state.scrollToItem(display.indexOf(firstOfYear).coerceAtLeast(0)) } }
        }
        TitleOverlay(
            title = if (selection.active) "${selection.keys.size} Selected" else when (mode) { 0 -> "Years"; 1 -> "Months"; else -> "Library" },
            subtitle = if (mode == 2 && !selection.active) range else null,
            onPhotos = mode == 2 && items.isNotEmpty(),
        ) {
            if (mode == 2 && items.isNotEmpty()) {
                if (selection.active) Capsule("Cancel", onPhotos = true) { selection.clear() }
                else {
                    BackupBadge()
                    Capsule("Select", onPhotos = true) { selection.mode = true }
                    ProfileButton { app.push(Screen.Settings) }
                }
            } else ProfileButton { app.push(Screen.Settings) }
        }
        if (selection.active) SelectionBottomBar(selection, items, Modifier.align(Alignment.BottomCenter))
        else ZoomSwitcher(mode, { mode = it }, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 62.dp))
    }
}

/** The "Years · Months · All Photos" pill above the tab bar. */
@Composable
private fun ZoomSwitcher(mode: Int, onMode: (Int) -> Unit, modifier: Modifier) {
    Surface(modifier, shape = RoundedCornerShape(50), color = Ios.bar, shadowElevation = 6.dp) {
        Row(Modifier.padding(3.dp)) {
            listOf("Years", "Months", "All Photos").forEachIndexed { i, label ->
                Box(Modifier.background(if (i == mode) Ios.fill else Color.Transparent, RoundedCornerShape(50))
                    .clickable(interactionSource = null, indication = null) { onMode(i) }.padding(horizontal = 16.dp, vertical = 7.dp)) {
                    Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Ios.label)
                }
            }
        }
    }
}

@Composable
private fun BackupBadge() {
    val app = LocalApp.current
    val status by Repo.backup.collectAsState()
    val online by Repo.online.collectAsState()
    Surface(onClick = { app.push(Screen.Settings) }, shape = CircleShape, color = Color.Black.copy(alpha = 0.35f)) {
        Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
            when {
                online == false -> Icon(Icons.Filled.CloudOff, "Offline", tint = Color(0xFFFF9F0A), modifier = Modifier.size(18.dp))
                status.running -> {
                    CircularProgressIndicator(progress = { if (status.pending == 0) 0f else status.done.toFloat() / status.pending },
                        modifier = Modifier.size(26.dp), strokeWidth = 2.dp, color = Color.White, trackColor = Color.White.copy(alpha = 0.25f))
                    Icon(Icons.Filled.CloudUpload, "Backing up", tint = Color.White, modifier = Modifier.size(12.dp))
                }
                else -> Icon(Icons.Filled.CloudDone, "Backed up", tint = Color.White, modifier = Modifier.size(18.dp))
            }
        }
    }
}

private data class Group(val label: String, val sub: String?, val cover: MediaItem, val first: MediaItem, val count: Int)

@Composable
private fun YearCards(items: List<MediaItem>, onOpen: (MediaItem) -> Unit) {
    val years = remember(items) {
        items.groupBy { Calendar.getInstance().apply { timeInMillis = it.date }.get(Calendar.YEAR) }.toSortedMap().map { (y, list) ->
            Group("$y", null, list.firstOrNull { it.favorite && !it.isVideo } ?: list[list.size / 2], list.last(), list.size)
        }
    }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = maxOf(0, years.size - 1))
    LazyColumn(state = listState, contentPadding = PaddingValues(top = 110.dp, bottom = 140.dp, start = 12.dp, end = 12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        items(years) { g -> BigCard(g, 1.1f) { onOpen(g.first) } }
    }
}

@Composable
private fun MonthCards(items: List<MediaItem>, onOpen: (MediaItem) -> Unit) {
    val months = remember(items) {
        items.groupBy { val c = Calendar.getInstance().apply { timeInMillis = it.date }; c.get(Calendar.YEAR) * 12 + c.get(Calendar.MONTH) }.toSortedMap().map { (_, list) ->
            val places = list.mapNotNull { it.remote?.city }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key }
            Group(monthLabel(list.first().date), places.take(2).joinToString(" & ").ifBlank { null },
                list.firstOrNull { it.favorite && !it.isVideo } ?: list.firstOrNull { !it.isVideo } ?: list.first(), list.last(), list.size)
        }
    }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = maxOf(0, months.size - 1))
    LazyColumn(state = listState, contentPadding = PaddingValues(top = 110.dp, bottom = 140.dp, start = 12.dp, end = 12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        items(months) { g -> BigCard(g, 1.4f) { onOpen(g.first) } }
    }
}

@Composable
private fun BigCard(g: Group, ratio: Float, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth().aspectRatio(ratio).clickable(onClick = onClick)) {
        RoundThumb(previewModel(g.cover), Modifier.fillMaxSize(), 16)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.45f), Color.Transparent, Color.Transparent)), RoundedCornerShape(16.dp)))
        Column(Modifier.padding(16.dp)) {
            Text(g.label, color = Color.White, style = MaterialTheme.typography.headlineSmall)
            if (g.sub != null) Text(g.sub, color = Color.White, style = MaterialTheme.typography.labelLarge)
        }
        Surface(shape = RoundedCornerShape(50), color = Color.Black.copy(alpha = 0.35f), modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp)) {
            Text("${g.count}", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
        }
    }
}

/** A sharper image for big cards than the grid thumbnail. */
fun previewModel(item: MediaItem): Any = item.remote?.let { Repo.api.previewUrl(it.id) } ?: thumbModel(item)

// ---------------------------------------------------------------- For You tab
@Composable
fun ForYouTab() {
    val app = LocalApp.current
    val memories by Repo.memories.collectAsState()
    val all by Repo.all.collectAsState()
    val featured = remember(all) { all.filter { it.favorite && !it.isVideo && it.remote?.trashedAt == null }.shuffled().take(6) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 110.dp)) {
        item { Text("For You", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.statusBarsPadding().padding(start = 16.dp, top = 12.dp)) }
        item { AppleSectionHeader("Memories") }
        if (memories.isEmpty()) item {
            Text("Memories from past years show up here — “1 year ago”, “2 years ago” and your recent favorites.", color = Ios.gray, modifier = Modifier.padding(horizontal = 16.dp))
        } else item {
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(memories) { m ->
                    val cover = m.items.firstOrNull { !it.isVideo } ?: m.items.first()
                    Box(Modifier.width(300.dp).height(400.dp).clickable { app.push(Screen.Story(m)) }) {
                        RoundThumb(Repo.api.previewUrl(cover.id), Modifier.fillMaxSize(), 16)
                        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Transparent, Color.Black.copy(alpha = 0.6f))), RoundedCornerShape(16.dp)))
                        Column(Modifier.align(Alignment.BottomStart).padding(18.dp)) {
                            Text(m.title, color = Color.White, style = MaterialTheme.typography.titleLarge)
                            Text(dateRange(m.items.first().takenAt, m.items.last().takenAt), color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
        }
        if (featured.isNotEmpty()) {
            item { AppleSectionHeader("Featured Photos") }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(featured, key = { it.key }) { f ->
                        Box(Modifier.size(200.dp).clickable { app.push(Screen.Viewer(featured, featured.indexOf(f))) }) { RoundThumb(previewModel(f), Modifier.fillMaxSize(), 14) }
                    }
                }
            }
        }
        item { AppleSectionHeader("Recently Added") }
        item {
            val recent = remember(all) { all.filter { it.remote?.trashedAt == null }.sortedByDescending { it.remote?.uploadedAt ?: ((it.local?.modified ?: 0) * 1000) }.take(12) }
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(recent, key = { it.key }) { r -> Box(Modifier.size(110.dp).clickable { app.push(Screen.Viewer(recent, recent.indexOf(r))) }) { RoundThumb(thumbModel(r), Modifier.fillMaxSize(), 8) } }
            }
        }
    }
}

@Composable
fun AppleSectionHeader(title: String, action: String? = null, onAction: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 26.dp, bottom = 10.dp), verticalAlignment = Alignment.Bottom) {
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        if (action != null) Text(action, color = Ios.blue, fontSize = 17.sp, modifier = Modifier.clickable(onClick = onAction))
    }
}

// ---------------------------------------------------------------- selection
/** Bottom bar while selecting: Share · "N Selected" · Trash · More, like Apple Photos. */
@Composable
fun SelectionBottomBar(selection: Selection, items: List<MediaItem>, modifier: Modifier = Modifier, albumId: Long? = null,
                       extra: (@Composable (List<MediaItem>, () -> Unit) -> Unit)? = null) {
    val app = LocalApp.current
    val chosen = remember(selection.keys, items) { selection.items(items) }
    var menu by remember { mutableStateOf(false) }
    var albumPicker by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var lockPrompt by remember { mutableStateOf(false) }
    var datePrompt by remember { mutableStateOf(false) }
    val allFav = chosen.isNotEmpty() && chosen.all { it.favorite }
    val n = chosen.size
    val photos = chosen.count { !it.isVideo }; val videos = n - photos
    val label = when { n == 0 -> "Select Items"; videos == 0 -> "$n Photo${if (n > 1) "s" else ""} Selected"; photos == 0 -> "$n Video${if (n > 1) "s" else ""} Selected"; else -> "$n Items Selected" }
    Column(modifier.fillMaxWidth().background(Ios.bar)) {
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(Ios.separator))
        Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 6.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(enabled = n > 0, onClick = { app.run { app.actions.share(chosen); null } }) { Icon(Icons.Outlined.IosShare, "Share", tint = if (n > 0) Ios.blue else Ios.gray) }
            Text(label, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            IconButton(enabled = n > 0, onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, "Delete", tint = if (n > 0) Ios.blue else Ios.gray) }
            Box {
                IconButton(enabled = n > 0, onClick = { menu = true }) { Icon(Icons.Filled.MoreHoriz, "More", tint = if (n > 0) Ios.blue else Ios.gray) }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem({ Text("Add to Album") }, { menu = false; albumPicker = true })
                    DropdownMenuItem({ Text("Get Link") }, { menu = false; app.run { app.actions.shareLink(chosen).also { selection.clear() } } })
                    DropdownMenuItem({ Text(if (allFav) "Unfavorite" else "Favorite") }, { menu = false; app.run { app.actions.favorite(chosen, !allFav).also { selection.clear() } } })
                    if (chosen.any { it.local != null && it.remote == null })
                        DropdownMenuItem({ Text("Back Up Now") }, { menu = false; app.run { app.actions.ensureRemote(chosen); selection.clear(); "Backed up" } })
                    if (chosen.any { it.cloudOnly })
                        DropdownMenuItem({ Text("Save to This Phone") }, { menu = false; app.run { app.actions.saveToDevice(chosen).also { selection.clear() } } })
                    DropdownMenuItem({ Text("Adjust Date & Time") }, { menu = false; datePrompt = true })
                    DropdownMenuItem({ Text("Archive") }, { menu = false; app.run { app.actions.archive(chosen, true).also { selection.clear() } } })
                    DropdownMenuItem({ Text("Hide") }, { menu = false; lockPrompt = true })
                    if (chosen.any { it.backedUp })
                        DropdownMenuItem({ Text("Remove from This Phone Only") }, { menu = false; app.run { app.actions.freeUpSpace(chosen.filter { it.backedUp }).also { selection.clear() } } })
                    if (albumId != null)
                        DropdownMenuItem({ Text("Remove from Album") }, { menu = false; app.run { Repo.api.removeFromAlbum(albumId, chosen.mapNotNull { it.remote?.id }); selection.clear(); Repo.refreshExtras(); "Removed from album" } })
                    extra?.invoke(chosen) { menu = false }
                }
            }
        }
    }
    if (albumPicker) AlbumPicker(onDismiss = { albumPicker = false }) { id, name ->
        albumPicker = false
        app.run { app.actions.addToAlbum(chosen, id, name).also { selection.clear() } }
    }
    if (confirmDelete) ConfirmDialog("Delete $n Item${if (n > 1) "s" else ""}?", "They'll be removed from this phone and your Gallery server, and kept in Recently Deleted for 30 days.", "Delete",
        { confirmDelete = false }) { app.run { app.actions.trash(chosen).also { selection.clear() } } }
    if (lockPrompt) TextPrompt("Enter your Gallery password", password = true, confirm = "Hide", onDismiss = { lockPrompt = false }) { pw ->
        lockPrompt = false
        app.run { app.actions.lock(chosen, pw).also { selection.clear() } }
    }
    if (datePrompt && chosen.isNotEmpty()) DateTimePrompt(chosen.first().date, onDismiss = { datePrompt = false }) { t ->
        datePrompt = false
        app.run { app.actions.setDate(chosen, t).also { selection.clear() } }
    }
}

@Composable
fun AlbumPicker(onDismiss: () -> Unit, onPick: (Long?, String?) -> Unit) {
    val albums by Repo.albums.collectAsState()
    var newAlbum by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { Repo.refreshExtras() }
    if (newAlbum) { TextPrompt("New Album", label = "Title", confirm = "Save", onDismiss = { newAlbum = false }) { onPick(null, it) }; return }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add to Album") },
        text = {
            LazyColumn {
                item {
                    Row(Modifier.fillMaxWidth().clickable { newAlbum = true }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(56.dp).background(Ios.fill, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) { Text("+", color = Ios.blue, style = MaterialTheme.typography.headlineSmall) }
                        Spacer(Modifier.width(14.dp)); Text("New Album…", color = Ios.blue)
                    }
                }
                items(albums) { al ->
                    Row(Modifier.fillMaxWidth().clickable { onPick(al.id, null) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        RoundThumb(al.cover?.let { Repo.api.thumbUrl(it) }, Modifier.size(56.dp), 10)
                        Spacer(Modifier.width(14.dp))
                        Column { Text(al.name); Text("${al.count}", style = MaterialTheme.typography.bodySmall, color = Ios.gray) }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
