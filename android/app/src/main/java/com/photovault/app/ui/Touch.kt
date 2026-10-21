package com.photovault.app.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import com.photovault.app.data.MediaItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Where each visible tile is on screen, so a finger position maps to the photo actually under it.
 * (The grid's own item offsets don't include the space kept for the title bar, which put the pick one row off.)
 */
class TileHits {
    // By photo, not by position in the list: while photos are being added (uploads, sync) positions shift,
    // and position-based bounds pointed at the wrong photo, often several rows away.
    private val bounds = HashMap<Any, androidx.compose.ui.geometry.Rect>()
    var gridOrigin = Offset.Zero
    fun place(key: Any, rootPos: Offset, size: androidx.compose.ui.geometry.Size) {
        bounds[key] = androidx.compose.ui.geometry.Rect(rootPos - gridOrigin, size)
    }
    /** The photo under [p] (its key), among the ones on screen. */
    fun keyAt(state: LazyGridState, p: Offset): String? =
        state.layoutInfo.visibleItemsInfo.firstOrNull { bounds[it.key]?.contains(p) == true }?.key as? String
}

/**
 * Apple-style touch on the photo grid (taps and pinches are handled elsewhere):
 * - touch and hold a photo, then let go → preview with quick actions ([onPeek])
 * - touch, hold and drag → start selecting and select every photo the finger passes over
 * - while selecting: swipe sideways across photos to select (or, starting on a selected one, deselect) a run
 * - dragging near the top or bottom edge scrolls the grid
 */
fun Modifier.gridTouch(state: LazyGridState, hits: TileHits, keys: () -> List<String>, selection: Selection, scope: CoroutineScope,
                       haptic: HapticFeedback, topEdge: Dp, bottomEdge: Dp, onHold: (Int?, Boolean) -> Unit, onPeek: (Int) -> Unit): Modifier =
    pointerInput(state, selection) {
        val slop = viewConfiguration.touchSlop
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val startKey = hits.keyAt(state, down.position) ?: return@awaitEachGesture
            val start = keys().indexOf(startKey).takeIf { it >= 0 } ?: return@awaitEachGesture
            var sideways = false
            // The photo starts shrinking right away so you can see the hold building up...
            if (!selection.active) onHold(start, false)
            // Tap, scroll, or hold? (null = held without moving)
            // A short hold (shorter than Android's usual long press) is enough.
            val ended = withTimeoutOrNull(HOLD_MS) {
                while (true) {
                    val ev = awaitPointerEvent(PointerEventPass.Initial)
                    val ch = ev.changes.firstOrNull { it.id == down.id }
                    if (ch == null || !ch.pressed || ev.changes.count { it.pressed } > 1) return@withTimeoutOrNull true
                    val d = ch.position - down.position
                    if (abs(d.x) > slop || abs(d.y) > slop) { sideways = selection.active && abs(d.x) > abs(d.y); return@withTimeoutOrNull true }
                }
                @Suppress("UNREACHABLE_CODE") true
            }
            if (ended != null) onHold(null, false)
            if (ended != null && !sideways) return@awaitEachGesture

            if (ended == null) {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                if (!selection.active) {
                    // ...and once it's held it gets a ring and a buzz: let go for the menu, or drag to select.
                    onHold(start, true)
                    while (true) {
                        val ev = awaitPointerEvent(PointerEventPass.Initial)
                        val ch = ev.changes.firstOrNull { it.id == down.id }
                        if (ch == null) { onHold(null, false); return@awaitEachGesture }
                        if (!ch.pressed) { ch.consume(); onHold(null, false); keys().indexOf(startKey).takeIf { it >= 0 }?.let(onPeek); return@awaitEachGesture }
                        ch.consume()
                        val d = ch.position - down.position
                        if (abs(d.x) > slop || abs(d.y) > slop) break
                    }
                    onHold(null, false)
                }
            }

            // Drag-select from the first photo to wherever the finger is (looked up fresh each time,
            // so photos added mid-drag don't throw the range off).
            val base = selection.keys
            val adding = startKey !in base
            var last = startKey
            fun selectTo(toKey: String) {
                val ks = keys()
                val a = ks.indexOf(startKey); val b = ks.indexOf(toKey)
                if (a < 0 || b < 0) return
                selection.keys = if (adding) base + ks.subList(minOf(a, b), maxOf(a, b) + 1) else base - ks.subList(minOf(a, b), maxOf(a, b) + 1).toSet()
                selection.mode = true
            }
            selectTo(startKey)
            var pos = down.position
            var speed = 0f
            var auto: Job? = null
            val top = topEdge.toPx(); val bottom = size.height - bottomEdge.toPx()
            while (true) {
                val ev = awaitPointerEvent(PointerEventPass.Initial)
                val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                ch.consume()
                if (!ch.pressed) break
                pos = ch.position
                hits.keyAt(state, pos)?.let { if (it != last) { last = it; selectTo(it); haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) } }
                speed = when { pos.y < top -> -(top - pos.y).coerceAtMost(200f) / 6f; pos.y > bottom -> (pos.y - bottom).coerceAtMost(200f) / 6f; else -> 0f }
                if (speed != 0f && auto?.isActive != true) auto = scope.launch {
                    while (speed != 0f) {
                        state.scrollBy(speed)
                        hits.keyAt(state, pos)?.let { if (it != last) { last = it; selectTo(it) } }
                        delay(16)
                    }
                }
            }
            speed = 0f
            auto?.cancel()
        }
    }

private const val HOLD_MS = 280L

/** Touch-and-hold preview of a photo with quick actions, like the iPhone's context menu. */
@Composable
fun PhotoPeek(item: MediaItem, onOpen: () -> Unit, onSelect: () -> Unit, onDismiss: () -> Unit) {
    val app = LocalApp.current
    var albumPicker by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val grow by animateFloatAsState(if (shown) 1f else 0.85f, spring(dampingRatio = 0.6f, stiffness = 500f), label = "peek")
    fun act(block: suspend () -> String?) { onDismiss(); app.run(block) }
    if (albumPicker) { AlbumPicker(onDismiss = { albumPicker = false; onDismiss() }) { id, name -> act { app.actions.addToAlbum(listOf(item), id, name) } }; return }
    if (confirmDelete) { ConfirmDialog("Delete Photo?", "It moves to Recently Deleted for 30 days.", "Delete", { confirmDelete = false; onDismiss() }) { act { app.actions.trash(listOf(item)) } }; return }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().clickable(interactionSource = null, indication = null, onClick = onDismiss), contentAlignment = Alignment.Center) {
            Column(Modifier.padding(24.dp).scale(grow), horizontalAlignment = Alignment.CenterHorizontally) {
                AsyncImage(model = item.local?.uri ?: previewModel(item), contentDescription = null, contentScale = ContentScale.Fit,
                    modifier = Modifier.heightIn(max = 420.dp).clip(RoundedCornerShape(14.dp)).clickable { onDismiss(); onOpen() })
                Spacer(Modifier.height(12.dp))
                Column(Modifier.width(250.dp).clip(RoundedCornerShape(14.dp)).background(Ios.cell)) {
                    PeekRow("Copy", Icons.Filled.ContentCopy) { act { app.actions.copy(item) } }
                    PeekRow("Share", Icons.Outlined.IosShare) { act { app.actions.share(listOf(item)); null } }
                    PeekRow(if (item.favorite) "Unfavorite" else "Favorite", if (item.favorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder) {
                        act { app.actions.favorite(listOf(item), !item.favorite) }
                    }
                    PeekRow("Add to Album", Icons.Filled.PhotoLibrary) { albumPicker = true }
                    PeekRow("Get Link", Icons.Filled.Link) { act { app.actions.shareLink(listOf(item)) } }
                    PeekRow("Select", Icons.Filled.CheckCircle) { onDismiss(); onSelect() }
                    PeekRow("Delete", Icons.Filled.Delete, Ios.red, divider = false) { confirmDelete = true }
                }
            }
        }
    }
}

@Composable
private fun PeekRow(label: String, icon: ImageVector, color: Color = Ios.label, divider: Boolean = true, onClick: () -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = color, fontSize = 17.sp)
            Icon(icon, null, tint = color, modifier = Modifier.size(20.dp))
        }
        if (divider) Box(Modifier.fillMaxWidth().height(0.5.dp).background(Ios.separator))
    }
}
