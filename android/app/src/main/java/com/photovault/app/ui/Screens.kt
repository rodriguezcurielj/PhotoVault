package com.photovault.app.ui

import android.annotation.SuppressLint
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.CameraFront
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Screenshot
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import coil3.compose.AsyncImage
import com.photovault.app.data.MediaItem
import com.photovault.app.data.Repo
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

// ---------------------------------------------------------------- search
private val MONTHS = listOf("january", "february", "march", "april", "may", "june", "july", "august", "september", "october", "november", "december")

/** Matches every word against the name, folder, place, camera, description, dates and type. */
fun searchItems(all: List<MediaItem>, q: String): List<MediaItem> {
    val words = q.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
    if (words.isEmpty()) return emptyList()
    val cal = Calendar.getInstance()
    return all.filter { it.remote?.trashedAt == null || it.local != null }.filter { item ->
        val r = item.remote
        val l = item.local
        val text = listOfNotNull(item.name, l?.bucketName, l?.relativePath, r?.city, r?.region, r?.country, r?.make, r?.model, r?.description,
            if (item.isVideo) "video videos" else "photo photos", if (item.favorite) "favorite favorites" else null,
            if (isScreenshot(item)) "screenshot screenshots" else null,
            if (isSelfie(item)) "selfie selfies" else null,
            if (r?.archived == true) "archive archived" else null).joinToString(" ").lowercase()
        cal.timeInMillis = item.date
        words.all { w ->
            when {
                w.length == 4 && w.all(Char::isDigit) -> cal.get(Calendar.YEAR) == w.toInt()
                w.length >= 3 && MONTHS.any { it.startsWith(w) } && !text.contains(w) -> MONTHS[cal.get(Calendar.MONTH)].startsWith(w)
                else -> text.contains(w)
            }
        }
    }
}
fun isScreenshot(i: MediaItem) = i.name.contains("screenshot", true) || i.local?.bucketName?.contains("screenshot", true) == true
fun isSelfie(i: MediaItem) = i.name.contains("selfie", true) || i.name.contains("front", true) || i.local?.bucketName?.contains("selfie", true) == true

@Composable
fun SearchTab() {
    val app = LocalApp.current
    val all by Repo.all.collectAsState()
    var q by remember { mutableStateOf("") }
    val live = remember(all) { all.filter { it.remote?.trashedAt == null || it.local != null } }
    val places = remember(live) {
        live.filter { it.remote?.city != null && !it.remote.archived }.groupBy { it.remote!!.city!! }
            .map { (city, list) -> Triple(city, list.size, list.first()) }.sortedByDescending { it.second }
    }
    val results = remember(q, live) { if (q.length >= 2) searchItems(live, q) else emptyList() }
    val years = remember(live) { live.groupBy { Calendar.getInstance().apply { timeInMillis = it.date }.get(Calendar.YEAR) }.toSortedMap(compareByDescending { it }) }
    LazyColumn(Modifier.fillMaxSize().background(Ios.background), contentPadding = PaddingValues(bottom = 110.dp)) {
        item { Text("Search", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.statusBarsPadding().padding(start = 16.dp, top = 12.dp)) }
        item {
            TextField(q, { q = it }, placeholder = { Text("Places, Dates, Cameras, and More", color = Ios.gray) },
                leadingIcon = { Icon(Icons.Filled.Search, null, tint = Ios.gray) }, singleLine = true, shape = RoundedCornerShape(10.dp),
                colors = TextFieldDefaults.colors(focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                    focusedContainerColor = Ios.fill, unfocusedContainerColor = Ios.fill),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { if (q.isNotBlank()) app.push(Screen.Grid(q, "search:$q")) }),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).height(52.dp))
        }
        if (q.length >= 2) {
            item {
                Row(Modifier.fillMaxWidth().clickable { app.push(Screen.Grid(q, "search:$q")) }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${results.size} Results", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    Text("See All", color = Ios.blue)
                }
            }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(results.take(30), key = { it.key }) { r -> Box(Modifier.size(110.dp).clickable { app.push(Screen.Viewer(results, results.indexOf(r))) }) { RoundThumb(thumbModel(r), Modifier.fillMaxSize(), 6) } }
                }
            }
        } else {
            if (places.isNotEmpty()) {
                item { AppleSectionHeader("Places", "See All") { app.push(Screen.Map) } }
                item {
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(places.take(30)) { (city, count, cover) ->
                            Column(Modifier.width(150.dp).clickable { app.push(Screen.Grid(city, "place:$city")) }) {
                                RoundThumb(previewModel(cover), Modifier.size(150.dp), 12)
                                Text(city, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                                Text("$count", style = MaterialTheme.typography.bodySmall, color = Ios.gray)
                            }
                        }
                    }
                }
            }
            item { AppleSectionHeader("Categories") }
            item {
                val cats = listOf(
                    Triple("Videos", "videos", live.firstOrNull { it.isVideo }), Triple("Favorites", "favorites", live.firstOrNull { it.favorite }),
                    Triple("Selfies", "search:selfie", live.firstOrNull(::isSelfie)), Triple("Screenshots", "screenshots", live.firstOrNull(::isScreenshot)),
                    Triple("Recently Added", "recent", live.maxByOrNull { it.remote?.uploadedAt ?: 0 }),
                )
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(cats) { (label, kind, cover) -> LabeledTile(label, cover?.let { thumbModel(it) }) { app.push(Screen.Grid(label, kind)) } }
                }
            }
            if (years.isNotEmpty()) {
                item { AppleSectionHeader("Moments") }
                item {
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(years.entries.toList()) { (y, list) -> LabeledTile("$y", previewModel(list.first())) { app.push(Screen.Grid("$y", "search:$y")) } }
                    }
                }
            }
        }
    }
}

@Composable
private fun LabeledTile(label: String, model: Any?, onClick: () -> Unit) {
    Box(Modifier.size(150.dp).clickable(onClick = onClick)) {
        RoundThumb(model, Modifier.fillMaxSize(), 12)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f))), RoundedCornerShape(12.dp)))
        Text(label, color = Color.White, style = MaterialTheme.typography.titleSmall, modifier = Modifier.align(Alignment.BottomStart).padding(10.dp))
    }
}

// ---------------------------------------------------------------- Albums tab (iOS grouped style)
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun AlbumsTab() {
    val app = LocalApp.current
    val albums by Repo.albums.collectAsState()
    val all by Repo.all.collectAsState()
    val localTrash by Repo.localTrash.collectAsState()
    val local by Repo.local.collectAsState()
    var newAlbum by remember { mutableStateOf(false) }
    var renameAlbum by remember { mutableStateOf<com.photovault.app.data.Album?>(null) }
    var deleteAlbum by remember { mutableStateOf<com.photovault.app.data.Album?>(null) }
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val seenRetap = remember { androidx.compose.runtime.mutableIntStateOf(app.retap) }
    LaunchedEffect(app.retap) { if (app.retap != seenRetap.intValue) { seenRetap.intValue = app.retap; listState.animateScrollToItem(0) } }
    LaunchedEffect(Unit) { Repo.refreshExtras() }
    val live = remember(all) { all.filter { it.remote?.trashedAt == null || it.local != null } }
    val favorites = remember(live) { live.filter { it.favorite } }
    val recents = remember(live) { timelineOf(live) }
    LazyColumn(Modifier.fillMaxSize().background(Ios.grouped), state = listState, contentPadding = PaddingValues(bottom = 110.dp)) {
        item {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 16.dp, end = 8.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Albums", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                androidx.compose.material3.IconButton(onClick = { newAlbum = true }) { Icon(Icons.Filled.Add, "New album", tint = Ios.blue) }
            }
        }
        item { AppleSectionHeader("My Albums") }
        item {
            data class Card(val title: String, val count: Int, val cover: Any?, val album: com.photovault.app.data.Album? = null, val open: () -> Unit)
            val cards = listOf(
                Card("Recents", recents.size, recents.firstOrNull()?.let { thumbModel(it) }) { app.tab = 0 },
                Card("Favorites", favorites.size, favorites.firstOrNull()?.let { thumbModel(it) }) { app.push(Screen.Grid("Favorites", "favorites")) },
            ) + albums.map { al -> Card(al.name, al.count, al.cover?.let { Repo.api.thumbUrl(it) }, al) { app.push(Screen.Album(al.id)) } }
            val rows = if (cards.size > 2) 2 else 1
            LazyHorizontalGrid(GridCells.Fixed(rows), Modifier.height((235 * rows).dp),
                contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(cards) { c ->
                    // Touch and hold an album for Rename / Share Link / Delete, like iOS.
                    var menu by remember { mutableStateOf(false) }
                    Box {
                        Column(Modifier.width(170.dp).combinedClickable(onClick = c.open, onLongClick = if (c.album == null) null else ({
                            haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress); menu = true }))) {
                            RoundThumb(c.cover, Modifier.size(170.dp), 8)
                            Text(c.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 5.dp))
                            Text("${c.count}", style = MaterialTheme.typography.bodyMedium, color = Ios.gray)
                        }
                        val al = c.album
                        if (al != null) androidx.compose.material3.DropdownMenu(menu, { menu = false }) {
                            androidx.compose.material3.DropdownMenuItem({ Text("Rename") }, { menu = false; renameAlbum = al })
                            androidx.compose.material3.DropdownMenuItem({ Text("Share Link") }, {
                                menu = false
                                app.run {
                                    val token = al.shareToken ?: Repo.api.shareAlbum(al.id, true)!!
                                    Repo.refreshExtras()
                                    ctx.startActivity(android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
                                        .putExtra(android.content.Intent.EXTRA_TEXT, Repo.api.shareUrl(token)), "Share album link"))
                                    null
                                }
                            })
                            androidx.compose.material3.DropdownMenuItem({ Text("Delete Album", color = Ios.red) }, { menu = false; deleteAlbum = al })
                        }
                    }
                }
            }
        }
        val shared = albums.filter { it.shareToken != null }
        if (shared.isNotEmpty()) {
            item { AppleSectionHeader("Shared Albums") }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(shared) { al ->
                        Column(Modifier.width(170.dp).clickable { app.push(Screen.Album(al.id)) }) {
                            RoundThumb(al.cover?.let { Repo.api.thumbUrl(it) }, Modifier.size(170.dp), 8)
                            Text(al.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, modifier = Modifier.padding(top = 5.dp))
                            Text("${al.count}", style = MaterialTheme.typography.bodyMedium, color = Ios.gray)
                        }
                    }
                }
            }
        }
        item { AppleSectionHeader("Places") }
        item {
            val geo = live.firstOrNull { it.remote?.lat != null }
            Box(Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(170.dp).clip(RoundedCornerShape(12.dp)).clickable { app.push(Screen.Map) }) {
                RoundThumb(geo?.let { previewModel(it) }, Modifier.fillMaxSize(), 12)
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.5f)))))
                Row(Modifier.align(Alignment.BottomStart).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Map, null, tint = Color.White); Spacer(Modifier.width(6.dp))
                    Text("Map · ${live.count { it.remote?.lat != null }} Photos", color = Color.White, style = MaterialTheme.typography.titleSmall)
                }
            }
        }
        item { AppleSectionHeader("Media Types") }
        item {
            GroupedList(listOf(
                Row3(Icons.Filled.Videocam, "Videos", live.count { it.isVideo }) { app.push(Screen.Grid("Videos", "videos")) },
                Row3(Icons.Filled.CameraFront, "Selfies", live.count(::isSelfie)) { app.push(Screen.Grid("Selfies", "search:selfie")) },
                Row3(Icons.Filled.Screenshot, "Screenshots", live.count(::isScreenshot)) { app.push(Screen.Grid("Screenshots", "screenshots")) },
            ))
        }
        item { AppleSectionHeader("Utilities") }
        item {
            GroupedList(listOf(
                Row3(Icons.Filled.FolderOpen, "On This Phone", local.size) { app.push(Screen.Folders) },
                Row3(Icons.Filled.Archive, "Archive", all.count { it.remote?.archived == true && it.remote.trashedAt == null }) { app.push(Screen.Grid("Archive", "archive")) },
                Row3(Icons.Filled.VisibilityOff, "Hidden", null, locked = true) { app.push(Screen.Locked) },
                Row3(Icons.Filled.Delete, "Recently Deleted", localTrash.size + all.count { it.local == null && it.remote?.trashedAt != null }) { app.push(Screen.Trash) },
                Row3(Icons.Filled.CleaningServices, "Free Up Space", null) { app.push(Screen.FreeUp) },
                Row3(Icons.Filled.CloudDownload, "Import from Google / Amazon Photos", null) { app.push(Screen.Import) },
            ))
        }
    }
    renameAlbum?.let { al ->
        TextPrompt("Rename Album", al.name, confirm = "Save", onDismiss = { renameAlbum = null }) { name ->
            renameAlbum = null; app.run { Repo.api.renameAlbum(al.id, name); Repo.refreshExtras(); null }
        }
    }
    deleteAlbum?.let { al ->
        ConfirmDialog("Delete “${al.name}”?", "The photos stay in your library.", "Delete", { deleteAlbum = null }) {
            app.run { Repo.api.deleteAlbum(al.id); Repo.refreshExtras(); "Album deleted" }
        }
    }
    if (newAlbum) TextPrompt("New Album", label = "Title", confirm = "Save", onDismiss = { newAlbum = false }) { name ->
        newAlbum = false
        app.run { val al = Repo.api.createAlbum(name, emptyList()); Repo.refreshExtras(); app.push(Screen.Album(al.id)); null }
    }
}

data class Row3(val icon: ImageVector, val title: String, val count: Int?, val locked: Boolean = false, val onClick: () -> Unit)

/** iOS inset grouped list: rounded card, blue icons, counts and chevrons. */
@Composable
fun GroupedList(rows: List<Row3>) {
    Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Ios.cell)) {
        rows.forEachIndexed { i, r ->
            Row(Modifier.fillMaxWidth().clickable(onClick = r.onClick).padding(start = 14.dp, end = 8.dp).height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(r.icon, null, tint = Ios.blue, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(14.dp))
                Text(r.title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                if (r.locked) Text("🔒", color = Ios.gray)
                if (r.count != null) Text("${r.count}", color = Ios.gray, style = MaterialTheme.typography.bodyLarge)
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Ios.separator)
            }
            if (i < rows.lastIndex) Box(Modifier.padding(start = 50.dp).fillMaxWidth().height(0.5.dp).background(Ios.separator))
        }
    }
}

// ---------------------------------------------------------------- photo pages (album, favorites, place, search...)
/** A full-screen grid with the big title over the photos and the selection bar, used by every sub-page. */
@Composable
fun PhotoPage(title: String, subtitle: String?, items: List<MediaItem>, stateKey: Any, albumId: Long? = null,
              empty: @Composable () -> Unit = { EmptyState("No Photos", "") },
              extraSelect: (@Composable (List<MediaItem>, () -> Unit) -> Unit)? = null,
              selectionBar: (@Composable (Selection) -> Unit)? = null,
              onOpen: ((List<MediaItem>, Int) -> Unit)? = null,
              menu: (@Composable (() -> Unit) -> Unit)? = null) {
    val app = LocalApp.current
    val selection = remember { Selection() }
    val state = app.gridState(stateKey)
    val display = remember(items) { items.asReversed() }
    val range by remember(display) { derivedStateOf { visibleRange(state, display) } }
    var showMenu by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().background(Ios.background)) {
        MediaGrid(items, selection, state = state, contentPadding = PaddingValues(top = 130.dp, bottom = 90.dp), empty = empty,
            onOpen = onOpen ?: { list, i -> app.push(Screen.Viewer(list, i, albumId = albumId)) })
        val onPhotos = items.isNotEmpty()
        TitleOverlay(if (selection.active) "${selection.keys.size} Selected" else title, if (selection.active) null else subtitle ?: range,
            onPhotos = onPhotos, onBack = { app.pop() }) {
            if (items.isNotEmpty()) {
                if (selection.active) Capsule("Cancel", onPhotos = onPhotos) { selection.clear() }
                else Capsule("Select", onPhotos = onPhotos) { selection.mode = true }
            }
            if (menu != null && !selection.active) Box {
                Capsule(icon = Icons.Filled.MoreHoriz, onPhotos = onPhotos) { showMenu = true }
                DropdownMenu(showMenu, { showMenu = false }) { menu { showMenu = false } }
            }
        }
        if (selection.active) {
            if (selectionBar != null) Box(Modifier.align(Alignment.BottomCenter)) { selectionBar(selection) }
            else SelectionBottomBar(selection, items, Modifier.align(Alignment.BottomCenter), albumId = albumId, extra = extraSelect)
        }
    }
}

@Composable
fun GridScreen(s: Screen.Grid) {
    val all by Repo.all.collectAsState()
    val live = { it: MediaItem -> it.remote?.trashedAt == null || it.local != null }
    val items = remember(all, s.kind) {
        when {
            s.kind == "favorites" -> all.filter { it.favorite && live(it) }
            s.kind == "videos" -> all.filter { it.isVideo && live(it) && it.remote?.archived != true }
            s.kind == "archive" -> all.filter { it.remote?.archived == true && it.remote.trashedAt == null }
            s.kind == "recent" -> all.filter(live).sortedByDescending { it.remote?.uploadedAt ?: ((it.local?.modified ?: 0) * 1000) }.take(500)
            s.kind == "screenshots" -> all.filter { live(it) && isScreenshot(it) }
            s.kind.startsWith("place:") -> all.filter { live(it) && it.remote?.city == s.kind.removePrefix("place:") }
            s.kind.startsWith("folder:") -> all.filter { it.local?.bucketId == s.kind.removePrefix("folder:") }
            s.kind.startsWith("search:") -> searchItems(all, s.kind.removePrefix("search:"))
            else -> emptyList()
        }
    }
    PhotoPage(s.title, null, items, s,
        empty = { EmptyState(if (s.kind.startsWith("search:")) "No Results" else "No Photos", if (s.kind.startsWith("search:")) "Try a place, a year, a month, a camera, “videos” or “favorites”." else "") })
}

@Composable
fun AlbumScreen(id: Long) {
    val app = LocalApp.current
    val all by Repo.all.collectAsState()
    var album by remember { mutableStateOf<com.photovault.app.data.Album?>(null) }
    var assets by remember { mutableStateOf(listOf<com.photovault.app.data.RemoteAsset>()) }
    var reload by remember { mutableIntStateOf(0) }
    val albums by Repo.albums.collectAsState()
    LaunchedEffect(id, reload, albums) {
        runCatching { Repo.api.album(id) }.onSuccess { album = it.first; assets = it.second }.onFailure { app.toast(it.message ?: "Couldn't open album") }
    }
    val byRemote = remember(all) { all.filter { it.remote != null }.associateBy { it.remote!!.id } }
    val items = remember(assets, byRemote) { assets.map { byRemote[it.id] ?: MediaItem(null, it) } }
    var rename by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    fun shareLink() = app.run {
        val token = album?.shareToken ?: Repo.api.shareAlbum(id, true)!!
        reload++
        ctx.startActivity(android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
            .putExtra(android.content.Intent.EXTRA_TEXT, Repo.api.shareUrl(token)), "Share album link"))
        null
    }
    PhotoPage(album?.name ?: "", null, items, "album$id", albumId = id,
        empty = { EmptyState("No Photos", "Select photos in Library and choose Add to Album.") },
        menu = { close ->
            DropdownMenuItem({ Text("Share Link") }, { close(); shareLink() }, leadingIcon = { Icon(Icons.Outlined.IosShare, null) })
            DropdownMenuItem({ Text("Slideshow") }, { close(); if (assets.isNotEmpty()) app.push(Screen.Story(com.photovault.app.data.Memory(album?.name ?: "", assets))) })
            DropdownMenuItem({ Text("Rename Album") }, { close(); rename = true })
            if (album?.shareToken != null) DropdownMenuItem({ Text("Stop Sharing") }, { close(); app.run { Repo.api.shareAlbum(id, false); reload++; "Link turned off" } })
            DropdownMenuItem({ Text("Save All to This Phone") }, { close(); app.run { app.actions.saveToDevice(items) } })
            DropdownMenuItem({ Text("Delete Album", color = Ios.red) }, { close(); confirmDelete = true })
        })
    if (rename) TextPrompt("Rename Album", album?.name ?: "", confirm = "Save", onDismiss = { rename = false }) { name ->
        rename = false; app.run { Repo.api.renameAlbum(id, name); Repo.refreshExtras(); reload++; null }
    }
    if (confirmDelete) ConfirmDialog("Delete Album?", "The photos stay in your library.", "Delete", { confirmDelete = false }) {
        app.run { Repo.api.deleteAlbum(id); Repo.refreshExtras(); app.pop(); "Album deleted" }
    }
}

@Composable
fun TrashScreen() {
    val app = LocalApp.current
    val all by Repo.all.collectAsState()
    val localTrash by Repo.localTrash.collectAsState()
    val items = remember(all, localTrash) {
        (localTrash.map { MediaItem(it, null) } + all.filter { it.local == null && it.remote?.trashedAt != null }).sortedByDescending { it.date }
    }
    var confirmEmpty by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { Repo.refreshLocal() }
    PhotoPage("Recently Deleted", "Deleted forever after 30 days", items, "trash",
        empty = { EmptyState("No Photos or Videos", "Deleted items stay here for 30 days.") },
        selectionBar = { selection ->
            val chosen = selection.items(items)
            Row(Modifier.fillMaxWidth().background(Ios.bar).navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text("Delete", color = if (chosen.isEmpty()) Ios.gray else Ios.red, fontSize = 17.sp, modifier = Modifier.clickable(enabled = chosen.isNotEmpty()) {
                    app.run { app.actions.deleteForever(chosen).also { selection.clear() } }
                })
                Spacer(Modifier.weight(1f))
                Text("Recover", color = if (chosen.isEmpty()) Ios.gray else Ios.blue, fontSize = 17.sp, modifier = Modifier.clickable(enabled = chosen.isNotEmpty()) {
                    app.run { app.actions.restore(chosen).also { selection.clear() } }
                })
            }
        },
        menu = if (items.isEmpty()) null else ({ close -> DropdownMenuItem({ Text("Delete All", color = Ios.red) }, { close(); confirmEmpty = true }) }))
    if (confirmEmpty) ConfirmDialog("Delete All?", "${items.size} items will be deleted forever.", "Delete All", { confirmEmpty = false }) { app.run { app.actions.emptyTrash() } }
}

@Composable
fun LockedScreen() {
    val app = LocalApp.current
    var password by remember { mutableStateOf<String?>(null) }
    var items by remember { mutableStateOf<List<MediaItem>?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(password, reload) {
        val pw = password ?: return@LaunchedEffect
        runCatching { Repo.api.lockedAssets(pw) }.onSuccess { list -> items = list.map { MediaItem(null, it) } }
            .onFailure { password = null; app.toast(it.message ?: "Wrong password") }
    }
    val list = items
    if (password == null || list == null) {
        Column(Modifier.fillMaxSize().background(Ios.background).statusBarsPadding().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(Icons.Filled.VisibilityOff, null, modifier = Modifier.size(56.dp), tint = Ios.blue)
            Text("Hidden", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 12.dp))
            Text("Hidden photos don't appear in your Library, albums, search or the map, and are removed from this phone.", textAlign = TextAlign.Center,
                color = Ios.gray, modifier = Modifier.padding(vertical = 12.dp))
        }
        if (password == null) TextPrompt("Enter your Gallery password", password = true, confirm = "View", onDismiss = { app.pop() }) { password = it }
        return
    }
    PhotoPage("Hidden", null, list, "locked",
        empty = { EmptyState("No Hidden Photos", "Select photos and choose Hide.") },
        onOpen = { l, i -> app.push(Screen.Viewer(l, i, lockedPassword = password)) },
        extraSelect = { chosen, close ->
            DropdownMenuItem({ Text("Unhide") }, { close(); app.run { app.actions.unlock(chosen.mapNotNull { it.remote?.id }, password!!).also { reload++ } } })
        })
}

@Composable
fun FoldersScreen() {
    val app = LocalApp.current
    val local by Repo.local.collectAsState()
    var excluded by remember { mutableStateOf(Repo.prefs.excludedBuckets) }
    val folders = remember(local) {
        local.groupBy { it.bucketId }.map { (id, list) -> com.photovault.app.data.DeviceFolder(id, list.first().bucketName, list.size, list.first()) }.sortedByDescending { it.count }
    }
    LazyColumn(Modifier.fillMaxSize().background(Ios.grouped), contentPadding = PaddingValues(bottom = 40.dp)) {
        item { TitleOverlay("On This Phone", "Turn a folder off to stop backing it up", onPhotos = false, onBack = { app.pop() }) }
        item {
            Column(Modifier.padding(horizontal = 16.dp).clip(RoundedCornerShape(10.dp)).background(Ios.cell)) {
                folders.forEachIndexed { i, f ->
                    Row(Modifier.fillMaxWidth().clickable { app.push(Screen.Grid(f.name, "folder:${f.bucketId}")) }.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        RoundThumb(com.photovault.app.LocalThumb(f.cover.uri), Modifier.size(54.dp), 6)
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Text(f.name, style = MaterialTheme.typography.bodyLarge); Text("${f.count}", style = MaterialTheme.typography.bodySmall, color = Ios.gray)
                        }
                        Switch(f.bucketId !in excluded, { on ->
                            excluded = if (on) excluded - f.bucketId else excluded + f.bucketId
                            Repo.prefs.excludedBuckets = excluded
                        })
                    }
                    if (i < folders.lastIndex) Box(Modifier.padding(start = 78.dp).fillMaxWidth().height(0.5.dp).background(Ios.separator))
                }
            }
        }
    }
}

@Composable
fun FreeUpScreen() {
    val app = LocalApp.current
    val all by Repo.all.collectAsState()
    val backed = remember(all) { all.filter { it.backedUp && it.remote?.trashedAt == null } }
    val bytes = backed.sumOf { it.local!!.size }
    Column(Modifier.fillMaxSize().background(Ios.grouped)) {
        TitleOverlay("Free Up Space", null, onPhotos = false, onBack = { app.pop() })
        Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Filled.CleaningServices, null, modifier = Modifier.size(56.dp), tint = Ios.blue)
            Text(if (backed.isEmpty()) "Nothing to Free Up Yet" else fmtBytes(bytes), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 16.dp))
            Text("${backed.size} photos and videos on this phone are safely backed up to Gallery. Removing them from the phone keeps them in Gallery, where you can still see them here.",
                textAlign = TextAlign.Center, color = Ios.gray, modifier = Modifier.padding(vertical = 16.dp))
            Button(enabled = backed.isNotEmpty(), onClick = { app.run { app.actions.freeUpSpace(backed) } }) { Text("Free Up ${fmtBytes(bytes)}") }
        }
    }
}

// ---------------------------------------------------------------- map (Leaflet in a WebView)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun MapScreen() {
    val app = LocalApp.current
    val all by Repo.all.collectAsState()
    val dark = isDark()
    val geo = remember(all) { all.filter { it.remote?.lat != null && it.remote.trashedAt == null && !it.remote.archived } }
    val data = remember(geo) {
        JSONArray().apply {
            geo.forEachIndexed { i, it -> put(JSONObject().put("i", i).put("lat", it.remote!!.lat).put("lon", it.remote.lon).put("t", Repo.api.thumbUrl(it.remote.id) + "?token=" + Repo.prefs.token)) }
        }.toString()
    }
    Box(Modifier.fillMaxSize().background(Ios.background)) {
        if (geo.isEmpty()) Box(Modifier.fillMaxSize().padding(top = 120.dp)) { EmptyState("No Places Yet", "Photos with a location show up here once they're backed up.") }
        else AndroidView(factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                // Thumbnails come from your server, which may be plain http on a home network.
                settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                webViewClient = WebViewClient()
                webChromeClient = object : android.webkit.WebChromeClient() {
                    override fun onConsoleMessage(m: android.webkit.ConsoleMessage): Boolean {
                        android.util.Log.i("PhotoVaultMap", "${m.message()} @${m.lineNumber()}"); return true
                    }
                }
                addJavascriptInterface(object {
                    @JavascriptInterface fun open(indexes: String) {
                        val idx = indexes.split(",").mapNotNull { it.toIntOrNull() }
                        post { app.push(Screen.Viewer(idx.mapNotNull { geo.getOrNull(it) }.sortedBy { it.date }, 0)) }
                    }
                }, "Android")
                loadDataWithBaseURL(Repo.prefs.server + "/", mapHtml(data, dark), "text/html", "utf-8", null)
            }
        }, modifier = Modifier.fillMaxSize())
        TitleOverlay("Places", "${geo.size} Photos", onPhotos = false, onBack = { app.pop() })
    }
}

/** OpenStreetMap tiles served (and cached) by your Gallery server, rounded photo pins with a count badge. */
private fun mapHtml(data: String, dark: Boolean) = """
<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
<link rel="stylesheet" href="https://cdnjs.cloudflare.com/ajax/libs/leaflet/1.9.4/leaflet.min.css">
<link rel="stylesheet" href="https://cdnjs.cloudflare.com/ajax/libs/leaflet.markercluster/1.5.3/MarkerCluster.min.css">
<style>#m{position:fixed;inset:0} html,body,#m{margin:0;background:${if (dark) "#1c1c1e" else "#f2f2f7"}}
.t{width:56px;height:56px;border-radius:12px;border:3px solid #fff;box-shadow:0 3px 10px rgba(0,0,0,.35);object-fit:cover;background:#ddd;display:block}
.p{position:relative}.p:after{content:'';position:absolute;left:50%;bottom:-7px;margin-left:-7px;border:7px solid transparent;border-bottom:0;border-top-color:#fff}
.c{position:absolute;right:-9px;top:-9px;background:#007aff;color:#fff;border-radius:11px;padding:2px 7px;font:600 12px -apple-system,Roboto,sans-serif;border:2px solid #fff}
.leaflet-control-attribution{font-size:9px}${if (dark) ".leaflet-tile-pane{filter:invert(1) hue-rotate(180deg) brightness(.85) contrast(.9)}" else ""}</style></head>
<body><div id="m"></div>
<script>window.L_DISABLE_3D = true; // plain positioning: some WebViews skip drawing 3D layers</script>
<script src="https://cdnjs.cloudflare.com/ajax/libs/leaflet/1.9.4/leaflet.min.js"></script>
<script src="https://cdnjs.cloudflare.com/ajax/libs/leaflet.markercluster/1.5.3/leaflet.markercluster.min.js"></script>
<script>
const pts = $data;
const map = L.map('m', {zoomControl:false}).setView([20,0],2);
L.tileLayer('/tiles/{z}/{x}/{y}.png', {maxZoom:19, attribution:'© OpenStreetMap'}).addTo(map);
const icon = (t, n) => L.divIcon({html:'<div class="p"><img class="t" src="'+t+'">'+(n>1?'<span class="c">'+n+'</span>':'')+'</div>', className:'', iconSize:[56,63], iconAnchor:[28,63]});
const cl = L.markerClusterGroup({iconCreateFunction: c => icon(c.getAllChildMarkers()[0].p.t, c.getChildCount()), zoomToBoundsOnClick:false, showCoverageOnHover:false, maxClusterRadius:70});
pts.forEach(p => { const m = L.marker([p.lat,p.lon],{icon:icon(p.t,1)}); m.p = p; m.on('click',()=>Android.open(String(p.i))); cl.addLayer(m); });
cl.on('clusterclick', e => { if (map.getZoom() >= 15) Android.open(e.layer.getAllChildMarkers().map(m=>m.p.i).join(',')); else e.layer.zoomToBounds({padding:[40,40]}); });
map.addLayer(cl);
// The WebView can start at zero size; re-measure once it has its real size, then frame all the photos.
const frame = () => {  map.invalidateSize(); if (innerHeight > 0) map.fitBounds(cl.getBounds(), {padding:[60,60], maxZoom:12}); };
addEventListener('resize', () => map.invalidateSize());
frame(); setTimeout(frame, 300); setTimeout(frame, 1200);
</script></body></html>
"""

// ---------------------------------------------------------------- memories slideshow
@Composable
fun StoryScreen(memory: com.photovault.app.data.Memory) {
    val app = LocalApp.current
    val items = remember(memory) { memory.items.filter { !it.isVideo }.ifEmpty { memory.items } }
    var index by remember { mutableIntStateOf(0) }
    val progress = remember { Animatable(0f) }
    val zoom = remember { Animatable(1f) }
    LaunchedEffect(index) {
        progress.snapTo(0f); zoom.snapTo(1f)
        kotlinx.coroutines.coroutineScope {
            launch { zoom.animateTo(1.08f, tween(5000, easing = LinearEasing)) }
            progress.animateTo(1f, tween(5000, easing = LinearEasing))
        }
        if (index < items.size - 1) index++ else app.pop()
    }
    Box(Modifier.fillMaxSize().background(Color.Black).pointerInput(Unit) {
        detectTapGestures { p -> if (p.x < size.width / 3) { if (index > 0) index-- } else if (index < items.size - 1) index++ else app.pop() }
    }) {
        AsyncImage(Repo.api.previewUrl(items[index].id), null, contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = zoom.value; scaleY = zoom.value })
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.45f), Color.Transparent, Color.Transparent))))
        Column(Modifier.statusBarsPadding().padding(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                items.take(30).forEachIndexed { i, _ ->
                    LinearProgressIndicator(progress = { if (i < index) 1f else if (i == index) progress.value else 0f },
                        modifier = Modifier.weight(1f).height(2.5.dp).clip(RoundedCornerShape(2.dp)), color = Color.White, trackColor = Color.White.copy(alpha = 0.35f))
                }
            }
            Text(memory.title, color = Color.White, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 14.dp, start = 6.dp))
            Text(dateRange(items.first().takenAt, items.last().takenAt), color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 6.dp))
        }
    }
}
