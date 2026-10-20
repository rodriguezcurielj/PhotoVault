package com.photovault.app.ui

import android.app.PendingIntent
import android.app.WallpaperManager
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import com.photovault.app.data.LocalItem
import com.photovault.app.data.LocalMedia
import com.photovault.app.data.MediaItem
import com.photovault.app.data.RemoteAsset
import com.photovault.app.data.Repo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File

/**
 * Everything that changes photos: works on the phone copy (MediaStore, which may show a system
 * confirmation unless "Media management" access is granted) and the server copy together.
 */
class Actions(private val activity: ComponentActivity) {
    private var pending: CompletableDeferred<Boolean>? = null
    private val launcher = activity.registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        pending?.complete(it.resultCode == android.app.Activity.RESULT_OK)
    }
    private val resolver get() = activity.contentResolver
    private val api get() = Repo.api

    private suspend fun ask(pi: PendingIntent): Boolean = withContext(Dispatchers.Main) {
        val d = CompletableDeferred<Boolean>()
        pending = d
        launcher.launch(IntentSenderRequest.Builder(pi).build())
        d.await()
    }

    private suspend fun done() {
        Repo.refreshLocal()
        Repo.syncRemote()
    }

    /** Moves to trash on the phone and on the server (restorable for 30 days). */
    suspend fun trash(items: List<MediaItem>): String {
        val local = items.mapNotNull { it.local?.uri }
        if (local.isNotEmpty() && !ask(MediaStore.createTrashRequest(resolver, local, true))) return "Cancelled"
        val remote = items.mapNotNull { it.remote?.id }
        if (remote.isNotEmpty()) runCatching { api.bulk("trash", remote) }
        done()
        return "Moved ${items.size} to trash"
    }

    suspend fun restore(items: List<MediaItem>): String {
        val local = items.mapNotNull { it.local?.takeIf { l -> l.trashed }?.uri }
        if (local.isNotEmpty() && !ask(MediaStore.createTrashRequest(resolver, local, false))) return "Cancelled"
        val remote = items.mapNotNull { it.remote?.takeIf { r -> r.trashedAt != null }?.id }
        if (remote.isNotEmpty()) api.bulk("restore", remote)
        done()
        return "Restored ${items.size}"
    }

    suspend fun deleteForever(items: List<MediaItem>): String {
        val local = items.mapNotNull { it.local?.uri }
        if (local.isNotEmpty() && !ask(MediaStore.createDeleteRequest(resolver, local))) return "Cancelled"
        val remote = items.mapNotNull { it.remote?.id }
        if (remote.isNotEmpty()) { api.bulk("trash", remote); api.bulk("delete", remote) }
        done()
        return "Deleted ${items.size} forever"
    }

    suspend fun emptyTrash(): String {
        val local = Repo.localTrash.value.map { it.uri }
        if (local.isNotEmpty() && !ask(MediaStore.createDeleteRequest(resolver, local))) return "Cancelled"
        api.emptyTrash()
        done()
        return "Trash emptied"
    }

    suspend fun favorite(items: List<MediaItem>, on: Boolean): String {
        val remote = items.mapNotNull { it.remote?.id }
        if (remote.isNotEmpty()) runCatching { api.bulk(if (on) "favorite" else "unfavorite", remote) }
        val local = items.mapNotNull { it.local?.uri }
        if (local.isNotEmpty()) runCatching { ask(MediaStore.createFavoriteRequest(resolver, local, on)) }
        done()
        return if (on) "Added to favorites" else "Removed from favorites"
    }

    /** Server ids for these items, uploading any that aren't backed up yet. */
    suspend fun ensureRemote(items: List<MediaItem>, onProgress: (String) -> Unit = {}): List<Long> {
        val out = ArrayList<Long>()
        for ((i, it) in items.withIndex()) {
            val r = it.remote
            if (r != null) { out += r.id; continue }
            val l = it.local ?: continue
            onProgress("Uploading ${i + 1} of ${items.size}…")
            out += uploadOne(l).id
        }
        return out
    }

    suspend fun uploadOne(l: LocalItem): RemoteAsset = withContext(Dispatchers.IO) {
        val sum = LocalMedia.sha1(resolver, l.uri)
        val existing = sum?.let { api.check(listOf(it))[it] }
        val a = if (existing != null) Repo.remote.value[existing] ?: run { Repo.syncRemote(); Repo.remote.value[existing]!! }
        else api.upload(resolver, runCatching { MediaStore.setRequireOriginal(l.uri) }.getOrDefault(l.uri), l.name, l.mime, l.size, l.dateTaken, l.id.toString())
        Repo.markBacked(l, a, sum)
        a
    }

    suspend fun archive(items: List<MediaItem>, on: Boolean): String {
        val ids = ensureRemote(items)
        api.bulk(if (on) "archive" else "unarchive", ids)
        done()
        return if (on) "Archived ${ids.size}" else "Unarchived ${ids.size}"
    }

    /** Locked folder: backs up, hides on the server, and removes the phone copy. */
    suspend fun lock(items: List<MediaItem>, password: String): String {
        val ids = ensureRemote(items)
        api.bulk("lock", ids, unlock = password)
        val local = items.mapNotNull { it.local?.uri }
        if (local.isNotEmpty()) ask(MediaStore.createDeleteRequest(resolver, local))
        done()
        return "Moved ${ids.size} to the locked folder"
    }

    suspend fun unlock(ids: List<Long>, password: String): String {
        api.bulk("unlock", ids, unlock = password)
        done()
        return "Moved ${ids.size} back to Photos"
    }

    suspend fun addToAlbum(items: List<MediaItem>, albumId: Long?, newName: String?): String {
        val ids = ensureRemote(items)
        if (albumId != null) api.addToAlbum(albumId, ids) else api.createAlbum(newName ?: "New album", ids)
        Repo.refreshExtras()
        Repo.syncRemote()
        return if (albumId != null) "Added ${ids.size} to album" else "Album created"
    }

    /** Downloads a server copy into the phone's Pictures/PhotoVault folder. */
    suspend fun saveToDevice(items: List<MediaItem>): String = withContext(Dispatchers.IO) {
        var n = 0
        for (it in items) {
            val r = it.remote ?: continue
            if (it.local != null) continue
            val coll = if (r.isVideo) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) else MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, r.name)
                put(MediaStore.MediaColumns.MIME_TYPE, r.mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, (if (r.isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES) + "/PhotoVault")
                put(MediaStore.MediaColumns.DATE_TAKEN, r.takenAt)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(coll, values) ?: continue
            runCatching {
                api.client.newCall(Request.Builder().url(api.originalUrl(r.id)).build()).execute().use { res ->
                    resolver.openOutputStream(uri)!!.use { out -> res.body!!.byteStream().copyTo(out) }
                }
                resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                // It's already on the server, so don't upload it again.
                Repo.refreshLocal()
                Repo.local.value.firstOrNull { l -> l.uri == uri || l.id == LocalMedia.idFromUri(uri) }?.let { l -> Repo.markBacked(l, r, r.checksum) }
                n++
            }.onFailure { resolver.delete(uri, null, null) }
        }
        done()
        "Saved $n to this phone"
    }

    /** Makes a no-password link to just these items (uploading any that aren't backed up yet) and opens the share sheet with it. */
    suspend fun shareLink(items: List<MediaItem>): String? {
        val ids = ensureRemote(items)
        if (ids.isEmpty()) return "Nothing to share"
        val url = api.shareUrl(api.createLink(ids))
        activity.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url), "Share link"))
        return null
    }

    /** Downloads the newest app from the server and opens Android's installer. */
    suspend fun updateApp(): String? {
        val pm = activity.packageManager
        if (!pm.canRequestPackageInstalls()) {
            activity.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}")))
            return "Allow installing apps from Gallery, then tap Update again"
        }
        val f = withContext(Dispatchers.IO) {
            val dir = File(activity.cacheDir, "update").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
            File(dir, "Gallery.apk").also { f ->
                api.client.newCall(Request.Builder().url(api.apkUrl()).build()).execute().use { res ->
                    if (!res.isSuccessful) error("Couldn't download the update (${res.code})")
                    f.outputStream().use { res.body!!.byteStream().copyTo(it) }
                }
            }
        }
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.files", f)
        activity.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
        return null
    }

    /** Opens the Android share sheet. Server-only items are downloaded first. */
    suspend fun share(items: List<MediaItem>) {
        val uris = withContext(Dispatchers.IO) {
            val dir = File(activity.cacheDir, "shared").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
            items.mapNotNull { item ->
                item.local?.uri ?: item.remote?.let { r ->
                    val f = File(dir, r.name)
                    runCatching {
                        api.client.newCall(Request.Builder().url(api.originalUrl(r.id)).build()).execute().use { res ->
                            f.outputStream().use { res.body!!.byteStream().copyTo(it) }
                        }
                        FileProvider.getUriForFile(activity, "${activity.packageName}.files", f)
                    }.getOrNull()
                }
            }
        }
        if (uris.isEmpty()) return
        val mime = if (items.all { it.isVideo }) "video/*" else if (items.none { it.isVideo }) "image/*" else "*/*"
        val intent = if (uris.size == 1) Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
        else Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        intent.type = mime
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        activity.startActivity(Intent.createChooser(intent, null))
    }

    /** Deletes phone copies of everything that's safely on the server. */
    suspend fun freeUpSpace(items: List<MediaItem>): String {
        val uris = items.mapNotNull { it.local?.uri }
        if (uris.isEmpty()) return "Nothing to free up"
        var freed = 0L
        for (chunk in items.chunked(500)) {
            if (!ask(MediaStore.createDeleteRequest(resolver, chunk.mapNotNull { it.local?.uri }))) break
            freed += chunk.sumOf { it.local?.size ?: 0 }
        }
        done()
        return "Freed ${fmtBytes(freed)}"
    }

    fun setWallpaper(item: MediaItem) {
        val uri: Uri = item.local?.uri ?: return
        runCatching { activity.startActivity(WallpaperManager.getInstance(activity).getCropAndSetWallpaperIntent(uri)) }
            .onFailure {
                activity.startActivity(Intent.createChooser(Intent(Intent.ACTION_ATTACH_DATA).setDataAndType(uri, item.local.mime)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Use as"))
            }
    }

    fun openWith(item: MediaItem) {
        val uri = item.local?.uri ?: return
        activity.startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW).setDataAndType(uri, item.local.mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Open with"))
    }

    suspend fun setDate(items: List<MediaItem>, newFirst: Long): String {
        val ids = ensureRemote(items)
        val first = items.first().date
        api.bulk("setDate", ids, extra = org.json.JSONObject().put("shiftMs", newFirst - first))
        done()
        return "Date updated"
    }
}
