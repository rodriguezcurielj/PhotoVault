package com.photovault.app.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.location.Geocoder
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.automirrored.filled.ArrowBackIos
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.exifinterface.media.ExifInterface
import androidx.media3.common.MediaItem as ExoItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import me.saket.telephoto.zoomable.ZoomSpec
import me.saket.telephoto.zoomable.coil3.ZoomableAsyncImage
import me.saket.telephoto.zoomable.rememberZoomableImageState
import me.saket.telephoto.zoomable.rememberZoomableState
import coil3.request.ImageRequest
import com.photovault.app.data.LocalMedia
import com.photovault.app.data.MediaItem
import com.photovault.app.data.Repo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@kotlin.OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewerScreen(s: Screen.Viewer) {
    val app = LocalApp.current
    val all by Repo.all.collectAsState()
    // Keep the list we were opened with, but refresh each item's state (favourite, backed up...).
    val byKey = remember(all) { all.associateBy { it.key } }
    var removed by remember { mutableStateOf(setOf<String>()) }
    val items = remember(s.items, byKey, removed) { s.items.filter { it.key !in removed }.map { byKey[it.key] ?: it } }
    if (items.isEmpty()) { LaunchedEffect(Unit) { app.pop() }; return }
    val pager = rememberPagerState(initialPage = s.start.coerceIn(0, items.size - 1)) { items.size }
    var chrome by remember { mutableStateOf(true) }
    var zoomed by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var albumPicker by remember { mutableStateOf(false) }
    var datePrompt by remember { mutableStateOf(false) }
    var lockPrompt by remember { mutableStateOf(false) }
    val current = items[pager.currentPage.coerceIn(0, items.size - 1)]
    val inTrash = current.trashed || current.remote?.trashedAt != null && current.local == null
    val locked = s.lockedPassword != null

    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val dark = isDark()
    // Apple Photos: white around the photo with the bars showing, black when you tap them away.
    val bg by androidx.compose.animation.animateColorAsState(if (chrome && !dark) Color.White else Color.Black, label = "bg")
    val fg = if (chrome && !dark) Color.Black else Color.White
    var dragY by remember { mutableFloatStateOf(0f) }
    val strip = androidx.compose.foundation.lazy.rememberLazyListState()
    LaunchedEffect(pager.currentPage) { strip.animateScrollToItem(maxOf(0, pager.currentPage - 4)) }
    // Fetch the next few photos ahead of time so swiping through them doesn't wait on the internet.
    val ctx = LocalContext.current
    LaunchedEffect(pager.currentPage) {
        val loader = coil3.SingletonImageLoader.get(ctx)
        for (d in listOf(1, 2, 3, -1, -2)) {
            val it = items.getOrNull(pager.currentPage + d) ?: continue
            if (it.local == null && it.remote != null && !it.isVideo) loader.enqueue(ImageRequest.Builder(ctx).data(Repo.api.previewUrl(it.remote.id)).build())
        }
    }
    val barBg = if (dark) Color.Black.copy(alpha = 0.55f) else Color.White.copy(alpha = 0.92f)

    Box(Modifier.fillMaxSize().background(bg.copy(alpha = 1f - (kotlin.math.abs(dragY) / 900f).coerceIn(0f, 0.7f)))
        // Swipe down to close, swipe up for info.
        .pointerInput(zoomed) {
            if (zoomed) return@pointerInput
            detectVerticalDragGestures(
                onDragEnd = { if (dragY > 220f) app.pop() else if (dragY < -160f) info = true; dragY = 0f },
                onDragCancel = { dragY = 0f },
            ) { _, dy -> dragY += dy }
        }) {
        HorizontalPager(pager, userScrollEnabled = !zoomed, beyondViewportPageCount = 1, key = { items[it].key },
            modifier = Modifier.fillMaxSize().graphicsLayer {
                translationY = dragY.coerceAtLeast(0f)
                val k = 1f - (dragY.coerceAtLeast(0f) / 3000f); scaleX = k; scaleY = k
            }) { page ->
            val item = items[page]
            if (item.isVideo) VideoPage(item, page == pager.currentPage) { chrome = !chrome }
            else ZoomableImage(item, onTap = { chrome = !chrome }, onZoom = { if (page == pager.currentPage) zoomed = it },
                onLongPress = { haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress); app.run { app.actions.copy(item) } })
        }
        AnimatedVisibility(chrome, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopCenter)) {
            Box(Modifier.fillMaxWidth().background(barBg).statusBarsPadding().padding(horizontal = 4.dp, vertical = 2.dp)) {
                IconButton(onClick = { app.pop() }, modifier = Modifier.align(Alignment.CenterStart)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBackIos, "Back", tint = Ios.blue, modifier = Modifier.size(20.dp))
                }
                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(dayLabel(current.date), color = fg, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, fontSize = 16.sp)
                    Text(SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(current.date)) + (current.remote?.city?.let { " · $it" } ?: ""),
                        color = Ios.gray, fontSize = 12.sp)
                }
                Row(Modifier.align(Alignment.CenterEnd), verticalAlignment = Alignment.CenterVertically) {
                    if (!current.isVideo && !inTrash && !locked) Text("Edit", color = Ios.blue, fontSize = 17.sp,
                        modifier = Modifier.clickable { app.push(Screen.Editor(current)) }.padding(horizontal = 8.dp, vertical = 10.dp))
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreHoriz, "More", tint = Ios.blue) }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem({ Text("Add to Album") }, { menu = false; albumPicker = true })
                            if (!locked) DropdownMenuItem({ Text("Get Link") }, { menu = false; app.run { app.actions.shareLink(listOf(current)) } })
                            if (s.albumId != null && current.remote != null) DropdownMenuItem({ Text("Make Key Photo") }, { menu = false; app.run { Repo.api.setAlbumCover(s.albumId, current.remote.id); Repo.refreshExtras(); "Key photo updated" } })
                            if (current.local != null && current.remote == null) DropdownMenuItem({ Text("Back Up Now") }, { menu = false; app.run { app.actions.ensureRemote(listOf(current)); "Backed up" } })
                            if (current.cloudOnly) DropdownMenuItem({ Text("Save to This Phone") }, { menu = false; app.run { app.actions.saveToDevice(listOf(current)) } })
                            if (current.local != null && !current.isVideo) DropdownMenuItem({ Text("Use as Wallpaper") }, { menu = false; app.actions.setWallpaper(current) })
                            if (current.local != null) DropdownMenuItem({ Text("Open With…") }, { menu = false; app.actions.openWith(current) })
                            DropdownMenuItem({ Text("Adjust Date & Time") }, { menu = false; datePrompt = true })
                            if (current.remote?.archived == true) DropdownMenuItem({ Text("Unarchive") }, { menu = false; app.run { app.actions.archive(listOf(current), false) } })
                            else if (!locked) DropdownMenuItem({ Text("Archive") }, { menu = false; app.run { app.actions.archive(listOf(current), true) } })
                            if (locked) DropdownMenuItem({ Text("Unhide") }, { menu = false; app.run { app.actions.unlock(listOf(current.remote!!.id), s.lockedPassword!!).also { removed = removed + current.key } } })
                            else DropdownMenuItem({ Text("Hide") }, { menu = false; lockPrompt = true })
                            if (current.backedUp) DropdownMenuItem({ Text("Remove from This Phone Only") }, { menu = false; app.run { app.actions.freeUpSpace(listOf(current)) } })
                        }
                    }
                }
            }
        }
        AnimatedVisibility(chrome, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomCenter)) {
            Column(Modifier.fillMaxWidth().background(barBg).navigationBarsPadding()) {
                // Filmstrip scrubber.
                if (items.size > 1) androidx.compose.foundation.lazy.LazyRow(state = strip, modifier = Modifier.fillMaxWidth().height(44.dp).padding(vertical = 4.dp),
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(1.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp)) {
                    items(items.size, key = { items[it].key }) { i ->
                        val on = i == pager.currentPage
                        Box(Modifier.width(if (on) 52.dp else 26.dp).fillMaxHeight().clickable { scope.launch { pager.scrollToPage(i) } }
                            .padding(horizontal = if (on) 4.dp else 0.dp)) {
                            AsyncImage(model = thumbModel(items[i]), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceAround, verticalAlignment = Alignment.CenterVertically) {
                    if (inTrash) {
                        Text("Delete", color = Ios.red, fontSize = 17.sp, modifier = Modifier.clickable { confirmDelete = true }.padding(12.dp))
                        Text("Recover", color = Ios.blue, fontSize = 17.sp, modifier = Modifier.clickable { app.run { app.actions.restore(listOf(current)) } }.padding(12.dp))
                    } else {
                        ViewerButton(Icons.Outlined.IosShare, "Share") { app.run { app.actions.share(listOf(current)); null } }
                        ViewerButton(if (current.favorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, "Favorite") { app.run { app.actions.favorite(listOf(current), !current.favorite); null } }
                        ViewerButton(Icons.Outlined.Info, "Info") { info = true }
                        ViewerButton(Icons.Outlined.Delete, "Delete") { confirmDelete = true }
                    }
                }
            }
        }
    }

    if (info) ModalBottomSheet(onDismissRequest = { info = false }) { InfoSheet(current) }
    if (confirmDelete) {
        if (inTrash) ConfirmDialog("Delete Forever?", "This can't be undone.", "Delete", { confirmDelete = false }) {
            app.run { app.actions.deleteForever(listOf(current)).also { removed = removed + current.key } }
        } else ConfirmDialog("Delete Photo?", "It will be removed from this phone and your Gallery server, and kept in Recently Deleted for 30 days.", "Delete", { confirmDelete = false }) {
            app.run { app.actions.trash(listOf(current)).also { if (!it.startsWith("Cancel")) removed = removed + current.key } }
        }
    }
    if (albumPicker) AlbumPicker({ albumPicker = false }) { id, name -> albumPicker = false; app.run { app.actions.addToAlbum(listOf(current), id, name) } }
    if (datePrompt) DateTimePrompt(current.date, { datePrompt = false }) { t -> datePrompt = false; app.run { app.actions.setDate(listOf(current), t) } }
    if (lockPrompt) TextPrompt("Enter your Gallery password", password = true, confirm = "Move", onDismiss = { lockPrompt = false }) { pw ->
        lockPrompt = false
        app.run { app.actions.lock(listOf(current), pw).also { removed = removed + current.key } }
    }
}

@Composable
private fun ViewerButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    IconButton(onClick = onClick) { Icon(icon, label, tint = Ios.blue, modifier = Modifier.size(26.dp)) }
}

/**
 * Pinch / double-tap zoom. Only takes over the touch when zoomed, so swiping still changes photos.
 * Shows a screen-sized image, and when zoomed in loads just the visible part at full resolution (like Photos),
 * instead of stretching one huge bitmap, which some phones draw as black.
 */
@Composable
fun ZoomableImage(item: MediaItem, onTap: () -> Unit, onZoom: (Boolean) -> Unit, onLongPress: () -> Unit = {}) {
    val zoom = rememberZoomableState(zoomSpec = ZoomSpec(maxZoomFactor = 3f))
    val state = rememberZoomableImageState(zoom)
    LaunchedEffect(zoom) { snapshotFlow { (zoom.zoomFraction ?: 0f) > 0.01f }.collect { onZoom(it) } }
    ZoomableAsyncImage(
        model = item.local?.uri ?: Repo.api.previewUrl(item.remote!!.id),
        contentDescription = null,
        state = state,
        modifier = Modifier.fillMaxSize(),
        onClick = { onTap() },
        onLongClick = { if ((zoom.zoomFraction ?: 0f) <= 0.01f) onLongPress() },
    )
}

@OptIn(UnstableApi::class)
@Composable
fun VideoPage(item: MediaItem, active: Boolean, onTap: () -> Unit) {
    val ctx = LocalContext.current
    if (!active) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            AsyncImage(model = thumbModel(item), contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        }
        return
    }
    val player = remember(item.key) {
        val http = DefaultHttpDataSource.Factory().setDefaultRequestProperties(mapOf("Authorization" to "Bearer ${Repo.prefs.token}"))
        val ds = androidx.media3.datasource.DefaultDataSource.Factory(ctx, http)
        ExoPlayer.Builder(ctx).setMediaSourceFactory(DefaultMediaSourceFactory(ds)).build().apply {
            val uri = item.local?.uri?.toString() ?: Repo.api.originalUrl(item.remote!!.id)
            setMediaItem(ExoItem.fromUri(uri))
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    AndroidView(factory = { PlayerView(it).apply { this.player = player; setShowNextButton(false); setShowPreviousButton(false); setControllerHideOnTouch(true); setOnClickListener { onTap() } } },
        modifier = Modifier.fillMaxSize())
}

@Composable
fun InfoSheet(item: MediaItem) {
    val app = LocalApp.current
    val ctx = LocalContext.current
    val r = item.remote
    // For phone-only photos read EXIF (camera, GPS) and look the place up with Android's geocoder.
    val exif by produceState<Triple<String?, Pair<Double, Double>?, String?>?>(null, item.key) {
        value = withContext(Dispatchers.IO) {
            val l = item.local ?: return@withContext null
            runCatching {
                LocalMedia.openOriginal(ctx.contentResolver, l.uri)?.use { s ->
                    val e = ExifInterface(s)
                    val cam = listOfNotNull(e.getAttribute(ExifInterface.TAG_MAKE), e.getAttribute(ExifInterface.TAG_MODEL)).joinToString(" ").ifBlank { null }
                    val ll = e.latLong?.let { it[0] to it[1] }
                    val lens = listOfNotNull(e.getAttribute(ExifInterface.TAG_F_NUMBER)?.let { "ƒ/$it" }, e.getAttribute(ExifInterface.TAG_EXPOSURE_TIME)?.toDoubleOrNull()?.let { if (it < 1) "1/${(1 / it).toInt()}" else "${it}s" },
                        e.getAttribute(ExifInterface.TAG_FOCAL_LENGTH)?.let { f -> f.split("/").let { if (it.size == 2) "%.1fmm".format(it[0].toDouble() / it[1].toDouble()) else f } },
                        e.getAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY)?.let { "ISO $it" }).joinToString("  ").ifBlank { null }
                    Triple(cam, ll, lens)
                }
            }.getOrNull()
        }
    }
    val latLon = r?.lat?.let { it to r.lon!! } ?: exif?.second
    val place by produceState(r?.place?.ifBlank { null }, latLon) {
        if (value == null && latLon != null) value = withContext(Dispatchers.IO) {
            runCatching {
                @Suppress("DEPRECATION")
                Geocoder(ctx).getFromLocation(latLon.first, latLon.second, 1)?.firstOrNull()?.let { a -> listOfNotNull(a.locality, a.adminArea, a.countryName).joinToString(", ") }
            }.getOrNull()
        }
    }
    var desc by remember(item.key) { mutableStateOf(r?.description ?: "") }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 32.dp)) {
        if (r != null) OutlinedTextField(desc, { desc = it }, placeholder = { Text("Add a description") }, modifier = Modifier.fillMaxWidth(),
            trailingIcon = { if (desc != (r.description ?: "")) androidx.compose.material3.TextButton(onClick = {
                app.run { Repo.updateRemote(Repo.api.patch(r.id, JSONObject().put("description", desc))); "Saved" }
            }) { Text("Save") } })
        InfoRow(Icons.Filled.DateRange, SimpleDateFormat("EEE, MMM d, yyyy", Locale.getDefault()).format(Date(item.date)), SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(item.date)))
        InfoRow(Icons.Filled.Image, item.name, listOfNotNull(
            if (item.width > 0) "${item.width} × ${item.height}" else null,
            if (item.width > 0 && !item.isVideo) "%.1f MP".format(item.width * item.height / 1e6) else null,
            fmtBytes(item.size), if (item.isVideo) fmtDuration(item.durationMs) else null).joinToString(" · "))
        val camera = exif?.first ?: listOfNotNull(r?.make, r?.model).joinToString(" ").ifBlank { null }
        if (camera != null) InfoRow(Icons.Filled.CameraAlt, camera, exif?.third)
        if (latLon != null) InfoRow(Icons.Filled.LocationOn, place ?: "Location", "%.5f, %.5f".format(latLon.first, latLon.second))
        InfoRow(if (item.cloudOnly) Icons.Filled.Cloud else Icons.Filled.PhoneAndroid,
            when { item.backedUp -> "On this phone · Backed up"; item.cloudOnly -> "Only on the server"; else -> "On this phone · Not backed up yet" },
            item.local?.let { "${it.relativePath}${it.name}" })
    }
}

@Composable
private fun InfoRow(icon: androidx.compose.ui.graphics.vector.ImageVector, main: String, sub: String?) {
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.Top) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(18.dp))
        Column {
            Text(main, style = MaterialTheme.typography.bodyLarge)
            if (!sub.isNullOrBlank()) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Android date picker, then time picker. */
@Composable
fun DateTimePrompt(initial: Long, onDismiss: () -> Unit, onPick: (Long) -> Unit) {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) {
        val c = Calendar.getInstance().apply { timeInMillis = initial }
        DatePickerDialog(ctx, { _, y, m, d ->
            TimePickerDialog(ctx, { _, hh, mm ->
                c.set(y, m, d, hh, mm)
                onPick(c.timeInMillis)
            }, c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), false).apply { setOnCancelListener { onDismiss() } }.show()
        }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).apply { setOnCancelListener { onDismiss() } }.show()
    }
}
