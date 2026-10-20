package com.photovault.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.photovault.app.LocalThumb
import com.photovault.app.data.MediaItem
import com.photovault.app.data.Repo
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

// ---------------------------------------------------------------- formatting
fun fmtBytes(b: Long): String = when {
    b >= 1_000_000_000 -> "%.1f GB".format(b / 1e9)
    b >= 1_000_000 -> "%.1f MB".format(b / 1e6)
    else -> "${maxOf(1, b / 1000)} KB"
}
fun fmtDuration(ms: Long): String { val s = ms / 1000; return "%d:%02d".format(s / 60, s % 60) }
private fun sameDay(a: Calendar, b: Calendar) = a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
fun dayLabel(t: Long): String {
    val c = Calendar.getInstance().apply { timeInMillis = t }
    val now = Calendar.getInstance()
    val y = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
    return when {
        sameDay(c, now) -> "Today"
        sameDay(c, y) -> "Yesterday"
        c.get(Calendar.YEAR) == now.get(Calendar.YEAR) -> SimpleDateFormat("EEE, MMM d", Locale.getDefault()).format(Date(t))
        else -> SimpleDateFormat("EEE, MMM d, yyyy", Locale.getDefault()).format(Date(t))
    }
}
fun monthLabel(t: Long): String = SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(Date(t))
fun dayKey(t: Long): Int { val c = Calendar.getInstance().apply { timeInMillis = t }; return c.get(Calendar.YEAR) * 1000 + c.get(Calendar.DAY_OF_YEAR) }

/** What to load for a grid tile. */
fun thumbModel(item: MediaItem): Any = item.local?.let { LocalThumb(it.uri) } ?: Repo.api.thumbUrl(item.remote!!.id)

// ---------------------------------------------------------------- grid
class Selection {
    var keys by mutableStateOf(setOf<String>())
    /** "Select" mode, like Apple Photos: on before anything is picked. */
    var mode by mutableStateOf(false)
    val active get() = mode || keys.isNotEmpty()
    fun toggle(k: String) { keys = if (k in keys) keys - k else keys + k; mode = true }
    fun clear() { keys = emptySet(); mode = false }
    fun items(all: List<MediaItem>) = all.filter { it.key in keys }
}

// Grids that have already jumped to the newest photo once.
private val positioned = java.util.Collections.newSetFromMap(java.util.WeakHashMap<LazyGridState, Boolean>())

/** "Oct 12 – 17, 2026" for a span of time. */
fun dateRange(a: Long, b: Long): String {
    val from = minOf(a, b)
    val to = maxOf(a, b)
    val f = Calendar.getInstance().apply { timeInMillis = from }
    val t = Calendar.getInstance().apply { timeInMillis = to }
    val loc = Locale.getDefault()
    fun fmt(p: String, x: Long) = SimpleDateFormat(p, loc).format(Date(x))
    return when {
        dayKey(from) == dayKey(to) -> fmt("MMM d, yyyy", from)
        f.get(Calendar.YEAR) != t.get(Calendar.YEAR) -> fmt("MMM yyyy", from) + " – " + fmt("MMM yyyy", to)
        f.get(Calendar.MONTH) != t.get(Calendar.MONTH) -> fmt("MMM d", from) + " – " + fmt("MMM d, yyyy", to)
        else -> fmt("MMM d", from) + " – " + fmt("d, yyyy", to)
    }
}

/** Date range of the photos on screen, for the big title overlay. */
fun visibleRange(state: LazyGridState, display: List<MediaItem>): String? {
    val info = state.layoutInfo.visibleItemsInfo
    if (info.isEmpty() || display.isEmpty()) return null
    val first = display.getOrNull(info.first().index) ?: return null
    val last = display.getOrNull(info.last().index) ?: return null
    return dateRange(first.date, last.date)
}

/**
 * Apple Photos–style grid: oldest at the top, opens scrolled to the newest at the bottom,
 * square tiles with hairline gaps, pinch to change the number of columns, tap "Select" or long-press to pick.
 * [items] come newest-first; [onOpen] gets the on-screen (oldest-first) list.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MediaGrid(
    items: List<MediaItem>,
    selection: Selection,
    onOpen: (List<MediaItem>, Int) -> Unit,
    modifier: Modifier = Modifier,
    state: LazyGridState = rememberLazyGridState(),
    contentPadding: PaddingValues = PaddingValues(top = 120.dp, bottom = 140.dp),
    newestAtBottom: Boolean = true,
    empty: @Composable () -> Unit = { EmptyState("No Photos", "") },
) {
    var columns by remember { mutableStateOf(Repo.prefs.columns) }
    val display = remember(items, newestAtBottom) { if (newestAtBottom) items.asReversed() else items }
    LaunchedEffect(display.isNotEmpty()) {
        if (display.isNotEmpty() && newestAtBottom && state !in positioned) {
            positioned += state
            state.scrollToItem(display.size - 1)
        }
    }
    if (items.isEmpty()) { Box(modifier.fillMaxSize().padding(contentPadding)) { empty() }; return }

    Box(modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            state = state,
            contentPadding = contentPadding,
            horizontalArrangement = Arrangement.spacedBy(1.5.dp),
            verticalArrangement = Arrangement.spacedBy(1.5.dp),
            modifier = Modifier
                .fillMaxSize()
                // Pinch with two fingers to zoom the grid in or out (1, 3, 5 or 9 across).
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        var zoom = 1f
                        do {
                            val ev = awaitPointerEvent(PointerEventPass.Initial)
                            if (ev.changes.count { it.pressed } >= 2) {
                                zoom *= ev.calculateZoom()
                                if (zoom > 1.3f && columns > 1) {
                                    columns = when { columns > 5 -> 5; columns > 3 -> 3; else -> 1 }
                                    zoom = 1f; Repo.prefs.columns = columns
                                } else if (zoom < 0.75f && columns < 9) {
                                    columns = when { columns < 3 -> 3; columns < 5 -> 5; else -> 9 }
                                    zoom = 1f; Repo.prefs.columns = columns
                                }
                                ev.changes.forEach { it.consume() }
                            }
                        } while (ev.changes.any { it.pressed })
                    }
                },
        ) {
            items(display.size, key = { display[it].key }, contentType = { "cell" }) { i ->
                val item = display[i]
                Tile(item, item.key in selection.keys, selection.active, fit = columns == 1,
                    onClick = { if (selection.active) selection.toggle(item.key) else onOpen(display, i) },
                    onLongClick = { selection.toggle(item.key) })
            }
        }
        if (display.size > 60) FastScroller(state, display.size, { idx -> monthLabel(display[idx.coerceIn(0, display.size - 1)].date) },
            Modifier.align(Alignment.CenterEnd).padding(contentPadding))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Tile(item: MediaItem, selected: Boolean, selecting: Boolean, fit: Boolean = false, onClick: () -> Unit, onLongClick: () -> Unit) {
    Box(Modifier.aspectRatio(1f).background(MaterialTheme.colorScheme.surfaceVariant)
        .combinedClickable(onClick = onClick, onLongClick = onLongClick)) {
        AsyncImage(model = thumbModel(item), contentDescription = null, contentScale = if (fit) ContentScale.Fit else ContentScale.Crop,
            modifier = Modifier.fillMaxSize())
        if (selected) Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = 0.28f)))
        val shadow = TextStyle(shadow = Shadow(Color.Black.copy(alpha = 0.6f), blurRadius = 4f))
        if (item.isVideo) Text(fmtDuration(item.durationMs), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, style = shadow,
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 5.dp, bottom = 3.dp))
        if (item.favorite) Icon(Icons.Filled.Favorite, null, tint = Color.White, modifier = Modifier.align(Alignment.BottomStart).padding(4.dp).size(13.dp))
        if (!selecting) Box(Modifier.align(Alignment.TopEnd).padding(4.dp).size(18.dp).background(Color.Black.copy(alpha = 0.35f), CircleShape),
            contentAlignment = Alignment.Center) {
            if (item.remote != null) Icon(Icons.Filled.CloudDone, "Uploaded", tint = Color.White, modifier = Modifier.size(12.dp))
            else Icon(Icons.Filled.CloudOff, "Not uploaded", tint = Color(0xFFFF9F0A), modifier = Modifier.size(12.dp))
        }
        if (selected) Box(Modifier.align(Alignment.BottomEnd).padding(5.dp).size(22.dp).background(Color.White, CircleShape).padding(1.5.dp)
            .background(Ios.blue, CircleShape), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Check, null, tint = Color.White, modifier = Modifier.size(14.dp))
        }
    }
}

/** Drag the handle on the right edge to jump through years of photos. */
@Composable
private fun FastScroller(state: LazyGridState, count: Int, labelAt: (Int) -> String, modifier: Modifier) {
    val scope = rememberCoroutineScope()
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    val fraction by remember { derivedStateOf { if (count == 0) 0f else state.firstVisibleItemIndex.toFloat() / count } }
    val scrolling = state.isScrollInProgress || dragging
    BoxWithConstraints(modifier.fillMaxSize().width(40.dp)) {
        val h = constraints.maxHeight.toFloat()
        val handleH = with(LocalDensity.current) { 44.dp.toPx() }
        val f = if (dragging) dragFraction else fraction
        val y = ((h - handleH) * f).coerceIn(0f, h - handleH)
        val yDp = with(LocalDensity.current) { y.toDp() }
        if (scrolling) {
            Box(Modifier.align(Alignment.TopEnd).offset(y = yDp).padding(end = 2.dp).size(26.dp, 44.dp)
                .background(Ios.bar, RoundedCornerShape(13.dp))
                .pointerInput(count) {
                    detectVerticalDragGestures(
                        onDragStart = { dragging = true; dragFraction = fraction },
                        onDragEnd = { dragging = false },
                        onDragCancel = { dragging = false },
                    ) { change, dy ->
                        change.consume()
                        dragFraction = (dragFraction + dy / (h - handleH)).coerceIn(0f, 1f)
                        scope.launch { state.scrollToItem((dragFraction * (count - 1)).toInt()) }
                    }
                }, contentAlignment = Alignment.Center) { Text("⇕", color = Ios.gray) }
            if (dragging) Surface(shape = RoundedCornerShape(20.dp), color = Ios.bar,
                modifier = Modifier.align(Alignment.TopEnd).offset(x = (-40).dp, y = yDp + 6.dp)) {
                Text(labelAt((dragFraction * (count - 1)).toInt()), modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp), style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

// ---------------------------------------------------------------- small pieces
@Composable
fun EmptyState(title: String, body: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 80.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        if (body.isNotBlank()) Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

@Composable
fun TextPrompt(title: String, initial: String = "", label: String = "", confirm: String = "OK", password: Boolean = false,
               onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(text, { text = it }, label = { if (label.isNotBlank()) Text(label) }, singleLine = true,
                visualTransformation = if (password) androidx.compose.ui.text.input.PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = if (password) KeyboardType.Password else KeyboardType.Text),
                modifier = Modifier.fillMaxWidth().focusRequester(focus))
            LaunchedEffect(Unit) { focus.requestFocus() }
        },
        confirmButton = { TextButton(onClick = { onConfirm(text) }, enabled = text.isNotBlank()) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun ConfirmDialog(title: String, body: String, confirm: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = { Text(body) },
        confirmButton = { TextButton(onClick = { onDismiss(); onConfirm() }) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable
fun RoundThumb(model: Any?, modifier: Modifier = Modifier, corner: Int = 14) {
    Box(modifier.clip(RoundedCornerShape(corner.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
        if (model != null) AsyncImage(model = model, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) =
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = modifier.padding(start = 16.dp, top = 20.dp, bottom = 8.dp))

@Composable
fun Pill(text: String, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.border(0.dp, Color.Transparent, RoundedCornerShape(50))) {
        Text(text, modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp), style = MaterialTheme.typography.labelLarge)
    }
}
