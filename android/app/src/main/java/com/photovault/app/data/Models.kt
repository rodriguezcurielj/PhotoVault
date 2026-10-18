package com.photovault.app.data

import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/** A photo or video stored on this phone (MediaStore). */
data class LocalItem(
    val id: Long,
    val uri: Uri,
    val name: String,
    val mime: String,
    val isVideo: Boolean,
    val dateTaken: Long,
    val modified: Long,
    val size: Long,
    val width: Int,
    val height: Int,
    val duration: Long,
    val bucketId: String,
    val bucketName: String,
    val favorite: Boolean,
    val trashed: Boolean = false,
    val relativePath: String = "",
)

/** A photo or video on the PhotoVault server. */
data class RemoteAsset(
    val id: Long,
    val type: String,
    val name: String,
    val mime: String,
    val size: Long,
    val checksum: String,
    val takenAt: Long,
    val uploadedAt: Long,
    val width: Int,
    val height: Int,
    val duration: Double,
    val lat: Double?,
    val lon: Double?,
    val city: String?,
    val region: String?,
    val country: String?,
    val make: String?,
    val model: String?,
    val favorite: Boolean,
    val archived: Boolean,
    val locked: Boolean,
    val trashedAt: Long?,
    val description: String?,
    val deviceId: String?,
    val deviceAsset: String?,
) {
    val isVideo get() = type == "video"
    val place get() = listOfNotNull(city, region, country).joinToString(", ")

    companion object {
        private fun JSONObject.str(k: String) = if (isNull(k)) null else optString(k)
        private fun JSONObject.dbl(k: String) = if (isNull(k)) null else optDouble(k)
        fun from(o: JSONObject) = RemoteAsset(
            id = o.getLong("id"), type = o.optString("type", "image"), name = o.optString("name"), mime = o.optString("mime"),
            size = o.optLong("size"), checksum = o.optString("checksum"), takenAt = o.optLong("takenAt"), uploadedAt = o.optLong("uploadedAt"),
            width = o.optInt("width"), height = o.optInt("height"), duration = o.optDouble("duration", 0.0).let { if (it.isNaN()) 0.0 else it },
            lat = o.dbl("lat"), lon = o.dbl("lon"), city = o.str("city"), region = o.str("region"), country = o.str("country"),
            make = o.str("make"), model = o.str("model"), favorite = o.optBoolean("favorite"), archived = o.optBoolean("archived"),
            locked = o.optBoolean("locked"), trashedAt = if (o.isNull("trashedAt")) null else o.optLong("trashedAt"),
            description = o.str("description"), deviceId = o.str("deviceId"), deviceAsset = o.str("deviceAsset"),
        )
        fun list(a: JSONArray) = (0 until a.length()).map { from(a.getJSONObject(it)) }
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("type", type); put("name", name); put("mime", mime); put("size", size); put("checksum", checksum)
        put("takenAt", takenAt); put("uploadedAt", uploadedAt); put("width", width); put("height", height); put("duration", duration)
        put("lat", lat ?: JSONObject.NULL); put("lon", lon ?: JSONObject.NULL); put("city", city ?: JSONObject.NULL)
        put("region", region ?: JSONObject.NULL); put("country", country ?: JSONObject.NULL); put("make", make ?: JSONObject.NULL)
        put("model", model ?: JSONObject.NULL); put("favorite", favorite); put("archived", archived); put("locked", locked)
        put("trashedAt", trashedAt ?: JSONObject.NULL); put("description", description ?: JSONObject.NULL)
        put("deviceId", deviceId ?: JSONObject.NULL); put("deviceAsset", deviceAsset ?: JSONObject.NULL)
    }
}

/** One entry in a grid: on the phone, on the server, or both (backed up). */
data class MediaItem(val local: LocalItem?, val remote: RemoteAsset?) {
    val key: String = local?.let { "l${it.id}" } ?: "r${remote!!.id}"
    // The server reads the real capture time from EXIF (and keeps date edits), so prefer it.
    val date: Long get() = remote?.takenAt ?: local!!.dateTaken
    val isVideo: Boolean get() = local?.isVideo ?: remote!!.isVideo
    val favorite: Boolean get() = remote?.favorite ?: local!!.favorite
    val name: String get() = local?.name ?: remote!!.name
    val size: Long get() = local?.size ?: remote!!.size
    val width: Int get() = local?.width?.takeIf { it > 0 } ?: remote?.width ?: 0
    val height: Int get() = local?.height?.takeIf { it > 0 } ?: remote?.height ?: 0
    val durationMs: Long get() = local?.duration ?: ((remote?.duration ?: 0.0) * 1000).toLong()
    val backedUp: Boolean get() = local != null && remote != null
    val cloudOnly: Boolean get() = local == null
    val trashed: Boolean get() = (local?.trashed == true) || (remote?.trashedAt != null && local == null)
}

data class Album(val id: Long, val name: String, val count: Int, val cover: Long?, val shareToken: String?)
data class Place(val city: String, val region: String?, val country: String?, val count: Int, val cover: Long)
data class Memory(val title: String, val items: List<RemoteAsset>)
data class DeviceFolder(val bucketId: String, val name: String, val count: Int, val cover: LocalItem)
