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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudQueue
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
private sealed interface Entry {
    data class Month(val label: String) : Entry
    data class Day(val label: String, val key: Int, val keys: List<String>, val place: String?) : Entry
    data class Cell(val item: MediaItem, val index: Int) : Entry
}

class Selection {
    var keys by mutableStateOf(setOf<String>())
    val active get() = keys.isNotEmpty()
    fun toggle(k: String) { keys = if (k in keys) keys - k else keys + k }
    fun clear() { keys = emptySet() }
    fun items(all: List<MediaItem>) = all.filter { it.key in keys }
}

/**
 * Photo grid with day headers, pinch to change size, long-press to select, and a fast scroller.
 * [header] is shown above everything (e.g. memories).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MediaGrid(
    items: List<MediaItem>,
    selection: Selection,
    onOpen: (Int) -> Unit,
    modifier: Modifier = Modifier,
    groupByDay: Boolean = true,
    state: LazyGridState = rememberLazyGridState(),
    contentPadding: PaddingValues = PaddingValues(bottom = 96.dp),
    header: (@Composable () -> Unit)? = null,
    empty: @Composable () -> Unit = { EmptyState("Nothing here", "") },
) {
    var columns by remember { mutableStateOf(Repo.prefs.columns) }
    val entries = remember(items, groupByDay) {
        val out = ArrayList<Entry>(items.size + items.size / 4)
        if (!groupByDay) items.forEachIndexed { i, it -> out += Entry.Cell(it, i) }
        else {
            var lastMonth = ""
            var lastDay = -1
            items.forEachIndexed { i, it ->
                val m = monthLabel(it.date)
                if (m != lastMonth) { out += Entry.Month(m); lastMonth = m; lastDay = -1 }
                val d = dayKey(it.date)
                if (d != lastDay) {
                    var end = i
                    while (end < items.size && dayKey(items[end].date) == d) end++
                    val dayItems = items.subList(i, end)
                    out += Entry.Day(dayLabel(it.date), d, dayItems.map { x -> x.key }, dayItems.firstNotNullOfOrNull { x -> x.remote?.city })
                    lastDay = d
                }
                out += Entry.Cell(it, i)
            }
        }
        out
    }
    if (items.isEmpty() && header == null) { empty(); return }

    Box(modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            state = state,
            contentPadding = contentPadding,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier
                .fillMaxSize()
                // Pinch with two fingers to change how many photos fit in a row.
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        var zoom = 1f
                        do {
                            val ev = awaitPointerEvent(PointerEventPass.Initial)
                            if (ev.changes.count { it.pressed } >= 2) {
                                zoom *= ev.calculateZoom()
                                if (zoom > 1.3f && columns > 2) { columns--; zoom = 1f; Repo.prefs.columns = columns }
                                else if (zoom < 0.75f && columns < 8) { columns++; zoom = 1f; Repo.prefs.columns = columns }
                                ev.changes.forEach { it.consume() }
                            }
                        } while (ev.changes.any { it.pressed })
                    }
                },
        ) {
            if (header != null) item(span = { GridItemSpan(maxLineSpan) }, key = "header") { header() }
            if (items.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { empty() }
            for (e in entries) when (e) {
                is Entry.Month -> item(span = { GridItemSpan(maxLineSpan) }, key = "m" + e.label, contentType = "month") {
                    Text(e.label, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(start = 14.dp, top = 22.dp, bottom = 2.dp))
                }
                is Entry.Day -> item(span = { GridItemSpan(maxLineSpan) }, key = "d" + e.key, contentType = "day") {
                    val all = e.keys.all { it in selection.keys }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (selection.active) {
                            Icon(if (all) Icons.Filled.CheckCircle else Icons.Outlined.Circle, null,
                                tint = if (all) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(22.dp).clip(CircleShape).combinedClickable(onClick = {
                                    selection.keys = if (all) selection.keys - e.keys.toSet() else selection.keys + e.keys
                                }))
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(e.label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.weight(1f))
                        if (e.place != null) Text(e.place, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                is Entry.Cell -> item(key = e.item.key, contentType = "cell") {
                    Tile(e.item, e.item.key in selection.keys, selection.active,
                        onClick = { if (selection.active) selection.toggle(e.item.key) else onOpen(e.index) },
                        onLongClick = { selection.toggle(e.item.key) })
                }
            }
        }
        if (items.size > 60) FastScroller(state, entries.size, { idx ->
            // Find the month label for the item nearest this scroll position.
            var i = idx.coerceIn(0, entries.size - 1)
            while (i > 0 && entries[i] !is Entry.Cell) i--
            (entries.getOrNull(i) as? Entry.Cell)?.let { monthLabel(it.item.date) } ?: ""
        }, Modifier.align(Alignment.CenterEnd))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Tile(item: MediaItem, selected: Boolean, selecting: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val scale = if (selected) 0.86f else 1f
    Box(Modifier.aspectRatio(1f).background(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)
        .combinedClickable(onClick = onClick, onLongClick = onLongClick)) {
        AsyncImage(model = thumbModel(item), contentDescription = null, contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().scale(scale).clip(RoundedCornerShape(if (selected) 10.dp else 0.dp)))
        if (item.isVideo) Row(Modifier.align(Alignment.TopEnd).padding(5.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(fmtDuration(item.durationMs), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            Icon(Icons.Filled.PlayArrow, null, tint = Color.White, modifier = Modifier.size(16.dp))
        }
        if (item.favorite) Icon(Icons.Filled.Favorite, null, tint = Color.White, modifier = Modifier.align(Alignment.BottomStart).padding(5.dp).size(15.dp))
        if (item.cloudOnly) Icon(Icons.Filled.CloudQueue, null, tint = Color.White.copy(alpha = 0.9f), modifier = Modifier.align(Alignment.BottomEnd).padding(5.dp).size(15.dp))
        if (selecting) Icon(if (selected) Icons.Filled.CheckCircle else Icons.Outlined.Circle, null,
            tint = if (selected) MaterialTheme.colorScheme.primary else Color.White,
            modifier = Modifier.align(Alignment.TopStart).padding(6.dp).size(22.dp).background(if (selected) Color.White else Color.Transparent, CircleShape))
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
            Box(Modifier.align(Alignment.TopEnd).offset(y = yDp).padding(end = 2.dp).size(28.dp, 44.dp)
                .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(14.dp))
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
                })
            if (dragging) Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.align(Alignment.TopEnd).offset(x = (-44).dp, y = yDp + 6.dp)) {
                Text(labelAt((dragFraction * (count - 1)).toInt()), color = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp), style = MaterialTheme.typography.labelLarge)
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
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = if (password) KeyboardType.NumberPassword else KeyboardType.Text),
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
