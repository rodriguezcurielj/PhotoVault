package com.photovault.app.ui

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.foundation.Canvas as ComposeCanvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.automirrored.filled.RotateLeft
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.exifinterface.media.ExifInterface
import com.photovault.app.data.LocalMedia
import com.photovault.app.data.MediaItem
import com.photovault.app.data.Repo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import kotlin.math.abs

data class EditState(
    val rot: Int = 0,
    val flip: Boolean = false,
    val crop: RectF? = null, // fractions of the rotated image
    val adj: Map<String, Float> = emptyMap(),
    val filter: Int = 0,
)

private val ADJUST = listOf("exposure" to "Exposure", "brightness" to "Brightness", "contrast" to "Contrast", "saturation" to "Saturation",
    "warmth" to "Warmth", "tint" to "Tint", "vignette" to "Vignette")
private val ASPECTS = listOf("Free" to 0f, "Original" to -1f, "Square" to 1f, "4:3" to 4f / 3, "3:4" to 3f / 4, "16:9" to 16f / 9, "9:16" to 9f / 16)

private fun scaleM(r: Float, g: Float, b: Float) = ColorMatrix().apply { setScale(r, g, b, 1f) }
private fun contrastM(k: Float) = ColorMatrix(floatArrayOf(k, 0f, 0f, 0f, 128 * (1 - k), 0f, k, 0f, 0f, 128 * (1 - k), 0f, 0f, k, 0f, 128 * (1 - k), 0f, 0f, 0f, 1f, 0f))
private fun offsetM(o: Float) = ColorMatrix(floatArrayOf(1f, 0f, 0f, 0f, o, 0f, 1f, 0f, 0f, o, 0f, 0f, 1f, 0f, o, 0f, 0f, 0f, 1f, 0f))
private fun satM(s: Float) = ColorMatrix().apply { setSaturation(s) }
private fun combine(vararg ms: ColorMatrix) = ColorMatrix().apply { ms.forEach { postConcat(it) } }
private val SEPIA = ColorMatrix(floatArrayOf(0.393f, 0.769f, 0.189f, 0f, 0f, 0.349f, 0.686f, 0.168f, 0f, 0f, 0.272f, 0.534f, 0.131f, 0f, 0f, 0f, 0f, 0f, 1f, 0f))

private val FILTERS: List<Pair<String, () -> ColorMatrix>> = listOf(
    "Original" to { ColorMatrix() },
    "Vivid" to { combine(satM(1.35f), contrastM(1.1f)) },
    "Warm" to { combine(scaleM(1.08f, 1f, 0.88f), satM(1.1f)) },
    "Cool" to { combine(scaleM(0.92f, 1f, 1.08f), offsetM(4f)) },
    "Pop" to { combine(satM(1.6f), contrastM(1.15f)) },
    "Fade" to { combine(contrastM(0.82f), satM(0.85f), offsetM(14f)) },
    "Vintage" to { combine(contrastM(0.92f), scaleM(1.06f, 1f, 0.86f), satM(0.75f), offsetM(10f)) },
    "Dramatic" to { combine(contrastM(1.35f), satM(1.1f), offsetM(-12f)) },
    "B&W" to { combine(satM(0f), contrastM(1.1f)) },
    "Noir" to { combine(satM(0f), contrastM(1.5f), offsetM(-14f)) },
    "Sepia" to { ColorMatrix(SEPIA) },
)

private fun colorMatrix(st: EditState): ColorMatrix {
    fun a(k: String) = st.adj[k] ?: 0f
    val ex = 1 + a("exposure") / 150f
    val warm = a("warmth") / 350f
    val tint = a("tint") / 350f
    return combine(
        scaleM(ex * (1 + warm), ex * (1 + tint), ex * (1 - warm)),
        offsetM(a("brightness") * 0.8f),
        contrastM(1 + a("contrast") / 120f),
        satM(1 + a("saturation") / 100f),
        FILTERS[st.filter].second(),
    )
}

/** Applies rotation, flip, crop, colour and vignette. */
fun renderEdit(src: Bitmap, st: EditState, showFull: Boolean): Bitmap {
    val m = Matrix().apply { postRotate(st.rot.toFloat()); if (st.flip) postScale(-1f, 1f) }
    val rotated = if (st.rot == 0 && !st.flip) src else Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    val c = if (!showFull && st.crop != null) st.crop else RectF(0f, 0f, 1f, 1f)
    val r = Rect((c.left * rotated.width).toInt(), (c.top * rotated.height).toInt(), (c.right * rotated.width).toInt(), (c.bottom * rotated.height).toInt())
    val out = Bitmap.createBitmap(maxOf(1, r.width()), maxOf(1, r.height()), Bitmap.Config.ARGB_8888)
    val canvas = Canvas(out)
    canvas.drawBitmap(rotated, r, Rect(0, 0, out.width, out.height), Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = ColorMatrixColorFilter(colorMatrix(st)) })
    val v = st.adj["vignette"] ?: 0f
    if (v != 0f) {
        val w = out.width.toFloat(); val h = out.height.toFloat()
        val edge = if (v > 0) android.graphics.Color.argb((v / 100f * 200).toInt(), 0, 0, 0) else android.graphics.Color.argb((-v / 100f * 200).toInt(), 255, 255, 255)
        canvas.drawRect(0f, 0f, w, h, Paint().apply {
            shader = RadialGradient(w / 2, h / 2, kotlin.math.hypot(w, h) / 2, intArrayOf(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT, edge), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        })
    }
    if (rotated !== src) rotated.recycle()
    return out
}

/** Decodes the photo (EXIF rotation applied) no larger than [maxSide]. */
private suspend fun decode(ctx: android.content.Context, item: MediaItem, maxSide: Int): Bitmap = withContext(Dispatchers.IO) {
    val source = item.local?.let { ImageDecoder.createSource(ctx.contentResolver, it.uri) } ?: run {
        val r = item.remote!!
        val f = File(ctx.cacheDir, "edit_${r.id}")
        if (!f.exists()) {
            val url = if (r.mime in listOf("image/jpeg", "image/png", "image/webp", "image/heic", "image/heif", "image/avif")) Repo.api.originalUrl(r.id) else Repo.api.previewUrl(r.id)
            Repo.api.client.newCall(Request.Builder().url(url).build()).execute().use { res -> f.outputStream().use { res.body!!.byteStream().copyTo(it) } }
        }
        ImageDecoder.createSource(f)
    }
    ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        val s = info.size
        val k = minOf(1f, maxSide.toFloat() / maxOf(s.width, s.height))
        if (k < 1f) decoder.setTargetSize((s.width * k).toInt(), (s.height * k).toInt())
    }
}

@Composable
fun EditorScreen(item: MediaItem) {
    val app = LocalApp.current
    val ctx = LocalContext.current
    val src by produceState<Bitmap?>(null, item.key) { value = runCatching { decode(ctx, item, 1600) }.getOrNull() }
    var st by remember { mutableStateOf(EditState()) }
    var tab by remember { mutableIntStateOf(0) }
    var adjKey by remember { mutableStateOf("exposure") }
    var aspect by remember { mutableStateOf(0f) }
    var saving by remember { mutableStateOf(false) }
    val preview by produceState<Bitmap?>(null, src, st, tab) {
        val s = src ?: return@produceState
        value = withContext(Dispatchers.Default) { renderEdit(s, st, showFull = tab == 0) }
    }
    val small by produceState<Bitmap?>(null, src) {
        val s = src ?: return@produceState
        value = withContext(Dispatchers.Default) {
            val k = 160f / maxOf(s.width, s.height)
            Bitmap.createScaledBitmap(s, maxOf(1, (s.width * k).toInt()), maxOf(1, (s.height * k).toInt()), true)
        }
    }

    Column(Modifier.fillMaxSize().background(Color(0xFF0C0C0D)).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { app.pop() }) { Text("Cancel", color = Color.White) }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { st = EditState(); aspect = 0f }) { Text("Reset", color = Color.White) }
            androidx.compose.material3.Button(enabled = !saving && src != null, onClick = {
                saving = true
                app.run {
                    try { saveEdited(ctx, item, st); app.pop(); "Saved as a copy" } finally { saving = false }
                }
            }) { Text(if (saving) "Saving…" else "Save copy") }
        }
        Box(Modifier.weight(1f).fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
            val bmp = preview
            if (bmp == null) CircularProgressIndicator()
            else if (tab == 0) CropArea(bmp, st.crop ?: RectF(0f, 0f, 1f, 1f), if (aspect == -1f) (src!!.width.toFloat() / src!!.height).let { if (st.rot % 180 != 0) 1 / it else it } else aspect) { st = st.copy(crop = it) }
            else Image(bmp.asImageBitmap(), null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        }
        TabRow(tab, containerColor = Color.Transparent, contentColor = Color.White) {
            listOf("Crop", "Adjust", "Filters").forEachIndexed { i, t -> Tab(tab == i, { tab = i }, text = { Text(t) }) }
        }
        Box(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
            when (tab) {
                0 -> Column {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                        IconButton(onClick = { st = st.copy(rot = (st.rot + 270) % 360, crop = null) }) { Icon(Icons.AutoMirrored.Filled.RotateLeft, "Rotate left", tint = Color.White) }
                        IconButton(onClick = { st = st.copy(rot = (st.rot + 90) % 360, crop = null) }) { Icon(Icons.AutoMirrored.Filled.RotateRight, "Rotate right", tint = Color.White) }
                        IconButton(onClick = { st = st.copy(flip = !st.flip, crop = null) }) { Icon(Icons.Filled.Flip, "Flip", tint = Color.White) }
                    }
                    LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        itemsIndexed(ASPECTS) { _, (label, value) ->
                            FilterChip(aspect == value, onClick = {
                                aspect = value
                                val b = preview ?: return@FilterChip
                                val ratio = if (value == -1f) b.width.toFloat() / b.height else value
                                st = st.copy(crop = if (value == 0f) st.crop else fitAspect(ratio, b.width.toFloat() / b.height))
                            }, label = { Text(label) })
                        }
                    }
                }
                1 -> Column {
                    LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        itemsIndexed(ADJUST) { _, (k, label) ->
                            FilterChip(adjKey == k, onClick = { adjKey = k }, label = { Text(label + ((st.adj[k] ?: 0f).toInt().takeIf { it != 0 }?.let { "  $it" } ?: "")) })
                        }
                    }
                    Slider(st.adj[adjKey] ?: 0f, { st = st.copy(adj = st.adj + (adjKey to it)) }, valueRange = -100f..100f, modifier = Modifier.padding(horizontal = 20.dp))
                }
                else -> LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    itemsIndexed(FILTERS) { i, (name, _) ->
                        val thumb = remember(small, i) { small?.let { renderEdit(it, EditState(filter = i), true) } }
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { st = st.copy(filter = i) }) {
                            Box(Modifier.size(72.dp).clip(RoundedCornerShape(10.dp)).border(2.dp, if (st.filter == i) MaterialTheme.colorScheme.primary else Color.Transparent, RoundedCornerShape(10.dp))) {
                                if (thumb != null) Image(thumb.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                            }
                            Text(name, color = Color.White, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                }
            }
        }
    }
}

/** Largest centred crop with the given pixel aspect ratio. */
private fun fitAspect(ratio: Float, imgRatio: Float): RectF {
    var w = 1f
    var h = imgRatio / ratio
    if (h > 1f) { h = 1f; w = ratio / imgRatio }
    return RectF((1 - w) / 2, (1 - h) / 2, (1 + w) / 2, (1 + h) / 2)
}

@Composable
private fun CropArea(bmp: Bitmap, crop: RectF, aspect: Float, onChange: (RectF) -> Unit) {
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val boxW = constraints.maxWidth.toFloat(); val boxH = constraints.maxHeight.toFloat()
        val k = minOf(boxW / bmp.width, boxH / bmp.height)
        val w = bmp.width * k; val h = bmp.height * k
        val wDp = with(density) { w.toDp() }; val hDp = with(density) { h.toDp() }
        Box(Modifier.size(wDp, hDp)) {
            Image(bmp.asImageBitmap(), null, modifier = Modifier.fillMaxSize())
            var handle by remember { mutableStateOf<String?>(null) }
            var rect by remember(crop) { mutableStateOf(crop) }
            ComposeCanvas(Modifier.fillMaxSize().pointerInput(w, h, aspect) {
                val touch = 40.dp.toPx()
                detectDragGestures(
                    onDragStart = { p ->
                        val r = rect
                        val corners = mapOf("tl" to Offset(r.left * w, r.top * h), "tr" to Offset(r.right * w, r.top * h), "bl" to Offset(r.left * w, r.bottom * h), "br" to Offset(r.right * w, r.bottom * h))
                        handle = corners.entries.firstOrNull { (it.value - p).getDistance() < touch }?.key
                            ?: if (p.x in r.left * w..r.right * w && p.y in r.top * h..r.bottom * h) "move" else null
                    },
                    onDragEnd = { handle = null; onChange(rect) },
                    onDragCancel = { handle = null },
                ) { change, d ->
                    change.consume()
                    val dx = d.x / w; val dy = d.y / h
                    val r = RectF(rect)
                    val min = 0.08f
                    when (handle) {
                        "move" -> { val mx = dx.coerceIn(-r.left, 1 - r.right); val my = dy.coerceIn(-r.top, 1 - r.bottom); r.offset(mx, my) }
                        "tl" -> { r.left = (r.left + dx).coerceIn(0f, r.right - min); r.top = (r.top + dy).coerceIn(0f, r.bottom - min) }
                        "tr" -> { r.right = (r.right + dx).coerceIn(r.left + min, 1f); r.top = (r.top + dy).coerceIn(0f, r.bottom - min) }
                        "bl" -> { r.left = (r.left + dx).coerceIn(0f, r.right - min); r.bottom = (r.bottom + dy).coerceIn(r.top + min, 1f) }
                        "br" -> { r.right = (r.right + dx).coerceIn(r.left + min, 1f); r.bottom = (r.bottom + dy).coerceIn(r.top + min, 1f) }
                        else -> return@detectDragGestures
                    }
                    if (aspect > 0 && handle != "move") {
                        // Keep the chosen shape: height follows width.
                        val wantH = (r.width() * w / aspect) / h
                        if (handle!!.startsWith("t")) r.top = (r.bottom - wantH).coerceAtLeast(0f) else r.bottom = (r.top + wantH).coerceAtMost(1f)
                        if (abs(r.height() - wantH) > 0.001f) { val fixW = r.height() * h * aspect / w; if (handle!!.endsWith("l")) r.left = r.right - fixW else r.right = r.left + fixW }
                    }
                    rect = r
                }
            }) {
                val l = rect.left * size.width; val t = rect.top * size.height; val rr = rect.right * size.width; val b = rect.bottom * size.height
                val shade = Color.Black.copy(alpha = 0.55f)
                drawRect(shade, Offset.Zero, Size(size.width, t))
                drawRect(shade, Offset(0f, b), Size(size.width, size.height - b))
                drawRect(shade, Offset(0f, t), Size(l, b - t))
                drawRect(shade, Offset(rr, t), Size(size.width - rr, b - t))
                drawRect(Color.White, Offset(l, t), Size(rr - l, b - t), style = Stroke(2.dp.toPx()))
                for (i in 1..2) {
                    drawLine(Color.White.copy(alpha = 0.4f), Offset(l + (rr - l) * i / 3, t), Offset(l + (rr - l) * i / 3, b))
                    drawLine(Color.White.copy(alpha = 0.4f), Offset(l, t + (b - t) * i / 3), Offset(rr, t + (b - t) * i / 3))
                }
                val hs = 6.dp.toPx()
                listOf(Offset(l, t), Offset(rr, t), Offset(l, b), Offset(rr, b)).forEach { drawCircle(Color.White, hs, it) }
            }
            LaunchedEffect(crop) { rect = crop }
        }
    }
}

/** Renders at full quality and saves next to the original in Pictures/PhotoVault (backup uploads it). */
private suspend fun saveEdited(ctx: android.content.Context, item: MediaItem, st: EditState) = withContext(Dispatchers.IO) {
    val full = decode(ctx, item, 6000)
    val out = renderEdit(full, st, showFull = false)
    full.recycle()
    val resolver = ctx.contentResolver
    val base = item.name.substringBeforeLast('.')
    val values = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, "${base}_edited_${System.currentTimeMillis() / 1000}.jpg")
        put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/PhotoVault")
        put(MediaStore.MediaColumns.DATE_TAKEN, item.date + 1000)
        put(MediaStore.MediaColumns.IS_PENDING, 1)
    }
    val uri = resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values) ?: error("Couldn't create the file")
    resolver.openOutputStream(uri)!!.use { out.compress(Bitmap.CompressFormat.JPEG, 95, it) }
    out.recycle()
    // Carry over the date, camera and location from the original.
    runCatching {
        val srcExif = item.local?.let { l -> LocalMedia.openOriginal(resolver, l.uri)?.use { ExifInterface(it) } }
        resolver.openFileDescriptor(uri, "rw")?.use { fd ->
            val e = ExifInterface(fd.fileDescriptor)
            val tags = listOf(ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_OFFSET_TIME_ORIGINAL, ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL,
                ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE_REF, ExifInterface.TAG_GPS_LONGITUDE, ExifInterface.TAG_GPS_LONGITUDE_REF, ExifInterface.TAG_GPS_ALTITUDE)
            if (srcExif != null) tags.forEach { t -> srcExif.getAttribute(t)?.let { e.setAttribute(t, it) } }
            else item.remote?.let { r -> if (r.lat != null && r.lon != null) e.setLatLong(r.lat, r.lon) }
            e.saveAttributes()
        }
    }
    resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
    Repo.refreshLocal()
}
