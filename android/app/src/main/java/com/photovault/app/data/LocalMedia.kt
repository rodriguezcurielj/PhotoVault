package com.photovault.app.data

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import java.io.InputStream
import java.security.MessageDigest

/** Reads the phone's photos and videos from MediaStore. */
object LocalMedia {
    private val FILES: Uri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
    private val PROJECTION = arrayOf(
        MediaStore.MediaColumns._ID, MediaStore.Files.FileColumns.MEDIA_TYPE, MediaStore.MediaColumns.DISPLAY_NAME,
        MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.DATE_TAKEN, MediaStore.MediaColumns.DATE_MODIFIED,
        MediaStore.MediaColumns.DATE_ADDED, MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.WIDTH,
        MediaStore.MediaColumns.HEIGHT, MediaStore.MediaColumns.DURATION, MediaStore.MediaColumns.BUCKET_ID,
        MediaStore.MediaColumns.BUCKET_DISPLAY_NAME, MediaStore.MediaColumns.IS_FAVORITE, MediaStore.MediaColumns.ORIENTATION,
        MediaStore.MediaColumns.RELATIVE_PATH,
    )
    private const val SELECTION = "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE}, ${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO})"

    fun contentUri(id: Long, isVideo: Boolean): Uri = ContentUris.withAppendedId(
        if (isVideo) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL) else MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL), id)

    fun query(context: Context, trashed: Boolean = false): List<LocalItem> {
        val args = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, SELECTION)
            putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, "${MediaStore.MediaColumns.DATE_TAKEN} DESC")
            if (trashed) putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_ONLY)
        }
        val out = ArrayList<LocalItem>()
        runCatching {
            context.contentResolver.query(FILES, PROJECTION, args, null)?.use { c -> while (c.moveToNext()) out += read(c, trashed) }
        }
        return out
    }

    private fun read(c: Cursor, trashed: Boolean): LocalItem {
        val id = c.getLong(0)
        val isVideo = c.getInt(1) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
        val taken = c.getLong(4).takeIf { it > 0 } ?: (c.getLong(5) * 1000).takeIf { it > 0 } ?: (c.getLong(6) * 1000)
        var w = c.getInt(8)
        var h = c.getInt(9)
        if (c.getInt(14) % 180 == 90) { val t = w; w = h; h = t }
        return LocalItem(
            id = id, uri = contentUri(id, isVideo), name = c.getString(2) ?: "", mime = c.getString(3) ?: "",
            isVideo = isVideo, dateTaken = taken, modified = c.getLong(5), size = c.getLong(7), width = w, height = h,
            duration = c.getLong(10), bucketId = c.getString(11) ?: "", bucketName = c.getString(12) ?: "",
            favorite = c.getInt(13) == 1, trashed = trashed, relativePath = c.getString(15) ?: "",
        )
    }

    /** Opens the original file, including GPS (needs ACCESS_MEDIA_LOCATION). */
    fun openOriginal(resolver: ContentResolver, uri: Uri): InputStream? =
        runCatching { resolver.openInputStream(MediaStore.setRequireOriginal(uri)) }.getOrNull() ?: resolver.openInputStream(uri)

    fun sha1(resolver: ContentResolver, uri: Uri): String? = runCatching {
        val md = MessageDigest.getInstance("SHA-1")
        openOriginal(resolver, uri)?.use { input ->
            val buf = ByteArray(256 * 1024)
            while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        } ?: return null
        md.digest().joinToString("") { "%02x".format(it) }
    }.getOrNull()

    /** Pulls the MediaStore id out of content://media/.../123 links other apps send us. */
    fun idFromUri(uri: Uri): Long? =
        if (uri.authority == MediaStore.AUTHORITY) uri.lastPathSegment?.toLongOrNull() else null
}
