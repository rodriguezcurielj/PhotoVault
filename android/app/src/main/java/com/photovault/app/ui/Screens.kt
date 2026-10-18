package com.photovault.app.ui

import android.annotation.SuppressLint
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import coil3.compose.AsyncImage
import com.photovault.app.data.MediaItem
import com.photovault.app.data.Repo
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.launch
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
            if (item.name.contains("screenshot", true) || l?.bucketName?.contains("screenshot", true) == true) "screenshot screenshots" else null,
            if (item.name.contains("selfie", true) || item.name.contains("front", true)) "selfie selfies" else null,
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SearchTab() {
    val app = LocalApp.current
    val all by Repo.all.collectAsState()
    var q by remember { mutableStateOf("") }
    val places = remember(all) {
        all.mapNotNull { it.remote }.filter { it.city != null && it.trashedAt == null && !it.archived }.groupBy { it.city!! }
            .map { (city, list) -> Triple(city, list.size, list.first()) }.sortedByDescending { it.second }
    }
    val years = remember(all) { all.groupBy { Calendar.getInstance().apply { timeInMillis = it.date }.get(Calendar.YEAR) }.toSortedMap(compareByDescending { it }) }
    val cameras = remember(all) { all.mapNotNull { it.remote?.model }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(12) }
    LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            TextField(q, { q = it }, placeholder = { Text("Search places, dates, “beach 2024”…") },
                leadingIcon = { Icon(Icons.Filled.Search, null) }, singleLine = true, shape = RoundedCornerShape(28.dp),
                colors = TextFieldDefaults.colors(focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { if (q.isNotBlank()) app.push(Screen.Grid("“$q”", "search:$q")) }),
                modifier = Modifier.fillMaxWidth().padding(12.dp))
        }
        item {
            FlowRow(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("Favorites") { app.push(Screen.Grid("Favorites", "favorites")) }
                Pill("Videos") { app.push(Screen.Grid("Videos", "videos")) }
                Pill("Screenshots") { app.push(Screen.Grid("Screenshots", "screenshots")) }
                Pill("Selfies") { app.push(Screen.Grid("Selfies", "search:selfie")) }
                Pill("Recently added") { app.push(Screen.Grid("Recently added", "recent")) }
                Pill("Map") { app.push(Screen.Map) }
            }
        }
        if (places.isNotEmpty()) {
            item { SectionTitle("Places") }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(places.take(40)) { (city, count, cover) ->
                        Column(Modifier.width(120.dp).clickable { app.push(Screen.Grid(city, "place:$city")) }) {
                            RoundThumb(Repo.api.thumbUrl(cover.id), Modifier.size(120.dp))
                            Text(city, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                            Text("$count", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        if (years.isNotEmpty()) {
            item { SectionTitle("Years") }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(years.entries.toList()) { (y, list) ->
                        Box(Modifier.size(120.dp).clickable { app.push(Screen.Grid("$y", "search:$y")) }) {
                            RoundThumb(thumbModel(list.first()), Modifier.fillMaxSize())
                            Text("$y", color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium, modifier = Modifier.align(Alignment.BottomStart).padding(10.dp))
                        }
                    }
                }
            }
        }
        if (cameras.isNotEmpty()) {
            item { SectionTitle("Cameras") }
            item {
                FlowRow(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    cameras.forEach { (m, n) -> Pill("$m · $n") { app.push(Screen.Grid(m, "search:$m")) } }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- library
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryTab() {
    val app = LocalApp.current
    val albums by Repo.albums.collectAsState()
    var newAlbum by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { Repo.refreshExtras() }
    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("Library", fontWeight = FontWeight.SemiBold) }, actions = {
            BackupIndicator()
            IconButton(onClick = { app.push(Screen.Settings) }) { Icon(Icons.Filled.Settings, "Settings") }
        }, colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background))
        LazyVerticalGrid(GridCells.Adaptive(160.dp), contentPadding = PaddingValues(12.dp, 0.dp, 12.dp, 32.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        LibButton(Icons.Filled.Favorite, "Favorites", Modifier.weight(1f)) { app.push(Screen.Grid("Favorites", "favorites")) }
                        LibButton(Icons.Filled.Videocam, "Videos", Modifier.weight(1f)) { app.push(Screen.Grid("Videos", "videos")) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        LibButton(Icons.Filled.Archive, "Archive", Modifier.weight(1f)) { app.push(Screen.Grid("Archive", "archive")) }
                        LibButton(Icons.Filled.Lock, "Locked folder", Modifier.weight(1f)) { app.push(Screen.Locked) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        LibButton(Icons.Filled.Delete, "Trash", Modifier.weight(1f)) { app.push(Screen.Trash) }
                        LibButton(Icons.Filled.Map, "Map", Modifier.weight(1f)) { app.push(Screen.Map) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        LibButton(Icons.Filled.PhoneAndroid, "On this device", Modifier.weight(1f)) { app.push(Screen.Folders) }
                        LibButton(Icons.Filled.CleaningServices, "Free up space", Modifier.weight(1f)) { app.push(Screen.FreeUp) }
                    }
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 12.dp)) {
                    Text("Albums", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    androidx.compose.material3.TextButton(onClick = { newAlbum = true }) { Icon(Icons.Filled.Add, null); Spacer(Modifier.width(4.dp)); Text("New album") }
                }
            }
            if (albums.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { EmptyState("No albums yet", "Select photos and tap “Add to album”.") }
            items(albums, key = { it.id }) { al ->
                Column(Modifier.clickable { app.push(Screen.Album(al.id)) }) {
                    RoundThumb(al.cover?.let { Repo.api.thumbUrl(it) }, Modifier.fillMaxWidth().aspectRatio(1f))
                    Text(al.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                    Text("${al.count} items" + if (al.shareToken != null) " · Shared" else "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    if (newAlbum) TextPrompt("New album", label = "Album name", confirm = "Create", onDismiss = { newAlbum = false }) { name ->
        newAlbum = false
        app.run { val al = Repo.api.createAlbum(name, emptyList()); Repo.refreshExtras(); app.push(Screen.Album(al.id)); null }
    }
}

@Composable
private fun LibButton(icon: ImageVector, label: String, modifier: Modifier, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = modifier) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Text(label, style = MaterialTheme.typography.titleSmall)
        }
    }
}

// ---------------------------------------------------------------- generic grids
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackTopBar(title: String, actions: @Composable () -> Unit = {}) {
    val app = LocalApp.current
    TopAppBar(title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = { IconButton(onClick = { app.pop() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
        actions = { actions() }, colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background))
}

@Composable
fun GridScreen(s: Screen.Grid) {
    val app = LocalApp.current
    val all by Repo.all.collectAsState()
    val selection = remember { Selection() }
    val live = { it: MediaItem -> it.remote?.trashedAt == null || it.local != null }
    val items = remember(all, s.kind) {
        when {
            s.kind == "favorites" -> all.filter { it.favorite && live(it) }
            s.kind == "videos" -> all.filter { it.isVideo && live(it) && it.remote?.archived != true }
            s.kind == "archive" -> all.filter { it.remote?.archived == true && it.remote.trashedAt == null }
            s.kind == "recent" -> all.filter(live).sortedByDescending { it.remote?.uploadedAt ?: ((it.local?.modified ?: 0) * 1000) }.take(500)
            s.kind == "screenshots" -> all.filter { live(it) && (it.local?.bucketName?.contains("screenshot", true) == true || it.name.contains("screenshot", true)) }
            s.kind.startsWith("place:") -> all.filter { live(it) && it.remote?.city == s.kind.removePrefix("place:") }
            s.kind.startsWith("folder:") -> all.filter { it.local?.bucketId == s.kind.removePrefix("folder:") }
            s.kind.startsWith("search:") -> searchItems(all, s.kind.removePrefix("search:"))
            else -> emptyList()
        }
    }
    Column(Modifier.fillMaxSize()) {
        if (selection.active) SelectionBar(selection, items) else BackTopBar(s.title)
        MediaGrid(items, selection, onOpen = { app.push(Screen.Viewer(items, it)) }, state = app.gridState(s),
            groupByDay = s.kind != "recent",
            empty = { EmptyState(if (s.kind.startsWith("search:")) "No results" else "Nothing here yet",
                if (s.kind.startsWith("search:")) "Try a place, a year, a month, a camera, “videos” or “favorites”." else "") })
    }
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
    val selection = remember { Selection() }
    var menu by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    Column(Modifier.fillMaxSize()) {
        if (selection.active) SelectionBar(selection, items, albumId = id)
        else BackTopBar(album?.name ?: "") {
            IconButton(onClick = {
                app.run {
                    val token = album?.shareToken ?: Repo.api.shareAlbum(id, true)!!
                    reload++
                    ctx.startActivity(android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(android.content.Intent.EXTRA_TEXT, Repo.api.shareUrl(token)), "Share album link"))
                    null
                }
            }) { Icon(Icons.Filled.Share, "Share link") }
            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem({ Text("Slideshow") }, { menu = false; if (assets.isNotEmpty()) app.push(Screen.Story(com.photovault.app.data.Memory(album?.name ?: "", assets))) })
                DropdownMenuItem({ Text("Rename") }, { menu = false; rename = true })
                if (album?.shareToken != null) DropdownMenuItem({ Text("Turn off shared link") }, { menu = false; app.run { Repo.api.shareAlbum(id, false); reload++; "Link turned off" } })
                DropdownMenuItem({ Text("Save all to this phone") }, { menu = false; app.run { app.actions.saveToDevice(items) } })
                DropdownMenuItem({ Text("Delete album") }, { menu = false; confirmDelete = true })
            }
        }
        if (album != null) Text("${items.size} items" + if (album?.shareToken != null) " · Anyone with the link can view" else "",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp))
        MediaGrid(items, selection, onOpen = { app.push(Screen.Viewer(items, it, albumId = id)) }, state = app.gridState("album$id"),
            empty = { EmptyState("This album is empty", "Select photos in Photos and tap “Add to album”.") })
    }
    if (rename) TextPrompt("Rename album", album?.name ?: "", confirm = "Save", onDismiss = { rename = false }) { name ->
        rename = false; app.run { Repo.api.renameAlbum(id, name); Repo.refreshExtras(); reload++; "Renamed" }
    }
    if (confirmDelete) ConfirmDialog("Delete album?", "The photos stay in your library.", "Delete", { confirmDelete = false }) {
        app.run { Repo.api.deleteAlbum(id); Repo.refreshExtras(); app.pop(); "Album deleted" }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrashScreen() {
    val app = LocalApp.current
    val all by Repo.all.collectAsState()
    val localTrash by Repo.localTrash.collectAsState()
    val items = remember(all, localTrash) {
        (localTrash.map { MediaItem(it, null) } + all.filter { it.local == null && it.remote?.trashedAt != null }).sortedByDescending { it.date }
    }
    val selection = remember { Selection() }
    var confirmEmpty by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { Repo.refreshLocal() }
    Column(Modifier.fillMaxSize()) {
        if (selection.active) TopAppBar(
            navigationIcon = { IconButton(onClick = { selection.clear() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) } },
            title = { Text("${selection.keys.size}") },
            actions = {
                androidx.compose.material3.TextButton(onClick = { val c = selection.items(items); app.run { app.actions.restore(c).also { selection.clear() } } }) { Text("Restore") }
                androidx.compose.material3.TextButton(onClick = { val c = selection.items(items); app.run { app.actions.deleteForever(c).also { selection.clear() } } }) { Text("Delete") }
            })
        else BackTopBar("Trash") { if (items.isNotEmpty()) androidx.compose.material3.TextButton(onClick = { confirmEmpty = true }) { Text("Empty") } }
        Text("Items are deleted forever after 30 days.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp))
        MediaGrid(items, selection, groupByDay = false, onOpen = { app.push(Screen.Viewer(items, it)) }, empty = { EmptyState("Trash is empty", "") })
    }
    if (confirmEmpty) ConfirmDialog("Empty trash?", "${items.size} items will be deleted forever.", "Empty trash", { confirmEmpty = false }) { app.run { app.actions.emptyTrash() } }
}

@Composable
fun LockedScreen() {
    val app = LocalApp.current
    var password by remember { mutableStateOf<String?>(null) }
    var items by remember { mutableStateOf<List<MediaItem>?>(null) }
    val selection = remember { Selection() }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(password, reload) {
        val pw = password ?: return@LaunchedEffect
        runCatching { Repo.api.lockedAssets(pw) }.onSuccess { list -> items = list.map { MediaItem(null, it) } }
            .onFailure { password = null; app.toast(it.message ?: "Wrong password") }
    }
    Column(Modifier.fillMaxSize()) {
        if (selection.active) SelectionBar(selection, items ?: emptyList(), extra = { chosen, close ->
            DropdownMenuItem({ Text("Move out of locked folder") }, { close(); app.run { app.actions.unlock(chosen.mapNotNull { it.remote?.id }, password!!).also { selection.clear(); reload++ } } })
        })
        else BackTopBar("Locked folder")
        val list = items
        if (password == null || list == null) {
            Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Filled.Lock, null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
                Text("Photos here are hidden from Photos, search, albums and the map, and removed from this phone.", modifier = Modifier.padding(vertical = 16.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (password == null) TextPrompt("Enter your PhotoVault password", password = true, confirm = "Unlock", onDismiss = { app.pop() }) { password = it }
        } else MediaGrid(list, selection, onOpen = { app.push(Screen.Viewer(list, it, lockedPassword = password)) },
            empty = { EmptyState("Nothing locked", "Select photos and choose “Move to locked folder”.") })
    }
}

@Composable
fun FoldersScreen() {
    val app = LocalApp.current
    val local by Repo.local.collectAsState()
    var excluded by remember { mutableStateOf(Repo.prefs.excludedBuckets) }
    val folders = remember(local) {
        local.groupBy { it.bucketId }.map { (id, list) -> com.photovault.app.data.DeviceFolder(id, list.first().bucketName, list.size, list.first()) }.sortedByDescending { it.count }
    }
    Column(Modifier.fillMaxSize()) {
        BackTopBar("On this device")
        LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) {
            item { Text("Switch a folder off to stop backing it up.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp, 0.dp, 16.dp, 8.dp)) }
            items(folders, key = { it.bucketId }) { f ->
                Row(Modifier.fillMaxWidth().clickable { app.push(Screen.Grid(f.name, "folder:${f.bucketId}")) }.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    RoundThumb(com.photovault.app.LocalThumb(f.cover.uri), Modifier.size(56.dp), 10)
                    Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                        Text(f.name); Text("${f.count} items", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(f.bucketId !in excluded, { on ->
                        excluded = if (on) excluded - f.bucketId else excluded + f.bucketId
                        Repo.prefs.excludedBuckets = excluded
                    })
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
    Column(Modifier.fillMaxSize()) {
        BackTopBar("Free up space")
        Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Filled.CleaningServices, null, modifier = Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
            Text(if (backed.isEmpty()) "Nothing to free up yet" else "Free up ${fmtBytes(bytes)}", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 16.dp))
            Text("${backed.size} photos and videos on this phone are safely backed up to PhotoVault. Removing them from the phone keeps them in PhotoVault, where you can still see them here.",
                textAlign = androidx.compose.ui.text.style.TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 16.dp))
            Button(enabled = backed.isNotEmpty(), onClick = { app.run { app.actions.freeUpSpace(backed) } }) { Text("Free up ${fmtBytes(bytes)}") }
        }
    }
}

// ---------------------------------------------------------------- map (Leaflet in a WebView)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun MapScreen() {
    val app = LocalApp.current
    val all by Repo.all.collectAsState()
    val geo = remember(all) { all.filter { it.remote?.lat != null && it.remote.trashedAt == null && !it.remote.archived } }
    val data = remember(geo) {
        JSONArray().apply {
            geo.forEachIndexed { i, it -> put(JSONObject().put("i", i).put("lat", it.remote!!.lat).put("lon", it.remote.lon).put("t", Repo.api.thumbUrl(it.remote.id) + "?token=" + Repo.prefs.token)) }
        }.toString()
    }
    Column(Modifier.fillMaxSize()) {
        BackTopBar("Map · ${geo.size} photos")
        if (geo.isEmpty()) { EmptyState("No photos with a location yet", "Photos backed up with GPS show up here."); return@Column }
        AndroidView(factory = { ctx ->
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
                        post { app.push(Screen.Viewer(idx.mapNotNull { geo.getOrNull(it) }, 0)) }
                    }
                }, "Android")
                loadDataWithBaseURL("https://photovault.local/", mapHtml(data), "text/html", "utf-8", null)
            }
        }, modifier = Modifier.fillMaxSize())
    }
}

private fun mapHtml(data: String) = """
<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
<link rel="stylesheet" href="https://cdnjs.cloudflare.com/ajax/libs/leaflet/1.9.4/leaflet.min.css">
<link rel="stylesheet" href="https://cdnjs.cloudflare.com/ajax/libs/leaflet.markercluster/1.5.3/MarkerCluster.min.css">
<style>html,body,#m{margin:0;height:100%} .t{width:48px;height:48px;border-radius:9px;border:2px solid #fff;box-shadow:0 2px 6px rgba(0,0,0,.4);object-fit:cover}
.c{position:absolute;right:-8px;top:-8px;background:#2f6fde;color:#fff;border-radius:10px;padding:1px 6px;font:600 11px sans-serif}</style></head>
<body><div id="m"></div>
<script src="https://cdnjs.cloudflare.com/ajax/libs/leaflet/1.9.4/leaflet.min.js"></script>
<script src="https://cdnjs.cloudflare.com/ajax/libs/leaflet.markercluster/1.5.3/leaflet.markercluster.min.js"></script>
<script>
const pts = $data;
const map = L.map('m', {zoomControl:false}).setView([20,0],2);
L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png',{maxZoom:19,attribution:'© OpenStreetMap'}).addTo(map);
const icon = (t, n) => L.divIcon({html:'<img class="t" src="'+t+'">'+(n?'<span class="c">'+n+'</span>':''), className:'', iconSize:[48,48]});
const cl = L.markerClusterGroup({iconCreateFunction: c => icon(c.getAllChildMarkers()[0].p.t, c.getChildCount()), zoomToBoundsOnClick:false});
pts.forEach(p => { const m = L.marker([p.lat,p.lon],{icon:icon(p.t)}); m.p = p; m.on('click',()=>Android.open(String(p.i))); cl.addLayer(m); });
cl.on('clusterclick', e => { if (map.getZoom() >= 15) Android.open(e.layer.getAllChildMarkers().map(m=>m.p.i).join(',')); else e.layer.zoomToBounds({padding:[30,30]}); });
map.addLayer(cl); map.fitBounds(cl.getBounds(), {padding:[30,30], maxZoom:12});
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
        AsyncImage(Repo.api.previewUrl(items[index].id), null, contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = zoom.value; scaleY = zoom.value })
        Column(Modifier.statusBarsPadding().padding(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                items.take(30).forEachIndexed { i, _ ->
                    LinearProgressIndicator(progress = { if (i < index) 1f else if (i == index) progress.value else 0f },
                        modifier = Modifier.weight(1f).height(3.dp).clip(RoundedCornerShape(2.dp)), color = Color.White, trackColor = Color.White.copy(alpha = 0.3f))
                }
            }
            Text(memory.title, color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp))
        }
    }
}
