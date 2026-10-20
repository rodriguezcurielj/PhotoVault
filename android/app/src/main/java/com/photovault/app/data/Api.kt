package com.photovault.app.data

import android.content.ContentResolver
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import okio.source
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class ApiException(val code: Int, message: String) : IOException(message)

/** Talks to the PhotoVault server's JSON API. */
class Api(private val prefs: Prefs) {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .writeTimeout(10, TimeUnit.MINUTES)
        .addInterceptor { chain ->
            val req = chain.request()
            val token = prefs.token
            // Only send the login token to our own server.
            if (token != null && req.url.toString().startsWith(prefs.server)) {
                chain.proceed(req.newBuilder().header("Authorization", "Bearer $token").build())
            } else chain.proceed(req)
        }
        .build()

    private val json = "application/json".toMediaType()
    private fun url(path: String) = prefs.server + path

    fun thumbUrl(id: Long) = url("/api/assets/$id/thumb")
    fun previewUrl(id: Long) = url("/api/assets/$id/preview")
    fun originalUrl(id: Long) = url("/api/assets/$id/original")
    /** For players that can't send headers. */
    fun originalUrlWithToken(id: Long) = url("/api/assets/$id/original?token=${prefs.token}")
    fun shareUrl(token: String) = url("/s/$token")
    fun apkUrl() = url("/PhotoVault.apk")

    private suspend fun call(method: String, path: String, body: Any? = null, headers: Map<String, String> = emptyMap()): String =
        withContext(Dispatchers.IO) {
            val rb = when (body) {
                null -> if (method == "GET" || method == "DELETE") null else "{}".toRequestBody(json)
                is RequestBody -> body
                else -> body.toString().toRequestBody(json)
            }
            val req = Request.Builder().url(url(path)).method(method, rb).apply { headers.forEach { (k, v) -> header(k, v) } }.build()
            client.newCall(req).execute().use { res ->
                val text = res.body?.string() ?: ""
                if (!res.isSuccessful) {
                    val msg = runCatching { JSONObject(text).optString("error") }.getOrNull()?.takeIf { it.isNotBlank() } ?: "Server error ${res.code}"
                    throw ApiException(res.code, msg)
                }
                text
            }
        }

    /** null when the server answers as PhotoVault, otherwise what went wrong. */
    suspend fun health(server: String): String? = withContext(Dispatchers.IO) {
        try {
            client.newCall(Request.Builder().url(server.trimEnd('/') + "/api/health").build()).execute().use {
                if (it.isSuccessful && it.body?.string()?.contains("PhotoVault") == true) null else "That address answered, but it isn't PhotoVault (${it.code})"
            }
        } catch (e: Exception) {
            "Can't reach $server (${e.javaClass.simpleName}: ${e.message}). Check your internet connection."
        }
    }

    suspend fun login(username: String, password: String): String {
        val r = JSONObject(call("POST", "/api/login", JSONObject().put("username", username.trim()).put("password", password).put("device", android.os.Build.MODEL)))
        return r.getString("token")
    }

    suspend fun logout() { runCatching { call("POST", "/api/logout") } }

    data class Sync(val now: Long, val assets: List<RemoteAsset>, val deleted: List<Long>)

    suspend fun assets(since: Long): Sync {
        val o = JSONObject(call("GET", "/api/assets" + if (since > 0) "?since=$since" else ""))
        val del = o.getJSONArray("deleted")
        return Sync(o.getLong("now"), RemoteAsset.list(o.getJSONArray("assets")), (0 until del.length()).map { del.getLong(it) })
    }

    suspend fun lockedAssets(password: String): List<RemoteAsset> =
        RemoteAsset.list(JSONObject(call("GET", "/api/assets?locked=1", headers = mapOf("X-Unlock" to password))).getJSONArray("assets"))

    suspend fun bulk(action: String, ids: List<Long>, unlock: String? = null, extra: JSONObject? = null) {
        val body = (extra ?: JSONObject()).put("action", action).put("ids", JSONArray(ids))
        call("POST", "/api/assets/bulk", body, if (unlock != null) mapOf("X-Unlock" to unlock) else emptyMap())
    }

    suspend fun patch(id: Long, body: JSONObject): RemoteAsset = RemoteAsset.from(JSONObject(call("PATCH", "/api/assets/$id", body)))

    suspend fun emptyTrash() { call("POST", "/api/trash/empty") }

    /** checksum -> server id for the ones the server already has. */
    suspend fun check(checksums: List<String>): Map<String, Long> {
        val r = JSONObject(call("POST", "/api/assets/check", JSONObject().put("checksums", JSONArray(checksums)))).getJSONObject("existing")
        return r.keys().asSequence().associateWith { r.getLong(it) }
    }

    /** Streams one file from the phone to the server. Returns the server's copy. */
    suspend fun upload(
        resolver: ContentResolver, uri: Uri, name: String, mime: String, size: Long,
        lastModified: Long, deviceAsset: String?, editedFrom: Long? = null, albumId: Long? = null,
        onProgress: (Long) -> Unit = {},
    ): RemoteAsset {
        val fileBody = object : RequestBody() {
            override fun contentType(): MediaType = mime.ifBlank { "application/octet-stream" }.toMediaType()
            override fun contentLength() = if (size > 0) size else -1
            override fun writeTo(sink: BufferedSink) {
                val input = resolver.openInputStream(uri) ?: throw IOException("Can't read $name")
                input.source().use { src ->
                    var total = 0L
                    while (true) {
                        val n = src.read(sink.buffer, 256 * 1024)
                        if (n == -1L) break
                        total += n
                        sink.emitCompleteSegments()
                        onProgress(total)
                    }
                }
            }
        }
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("deviceId", prefs.deviceId)
            .addFormDataPart("lastModified", lastModified.toString())
            .apply {
                if (deviceAsset != null) addFormDataPart("deviceAsset", deviceAsset)
                if (editedFrom != null) addFormDataPart("editedFrom", editedFrom.toString())
                if (albumId != null) addFormDataPart("albumId", albumId.toString())
            }
            .addFormDataPart("files", name, fileBody)
            .build()
        val r = JSONObject(call("POST", "/api/upload", body)).getJSONArray("results").getJSONObject(0)
        if (r.has("error")) throw IOException(r.getString("error"))
        return RemoteAsset.from(r.getJSONObject("asset"))
    }

    suspend fun albums(): List<Album> {
        val a = JSONArray(call("GET", "/api/albums"))
        return (0 until a.length()).map { a.getJSONObject(it).toAlbum() }
    }
    private fun JSONObject.toAlbum() = Album(getLong("id"), optString("name"), optInt("count"),
        if (isNull("cover")) null else optLong("cover"), if (isNull("shareToken")) null else optString("shareToken"))

    suspend fun album(id: Long): Pair<Album, List<RemoteAsset>> {
        val o = JSONObject(call("GET", "/api/albums/$id"))
        return o.toAlbum() to RemoteAsset.list(o.getJSONArray("assets"))
    }
    suspend fun createAlbum(name: String, ids: List<Long>): Album =
        JSONObject(call("POST", "/api/albums", JSONObject().put("name", name).put("ids", JSONArray(ids)))).toAlbum()
    suspend fun addToAlbum(id: Long, ids: List<Long>) { call("POST", "/api/albums/$id/assets", JSONObject().put("ids", JSONArray(ids))) }
    suspend fun removeFromAlbum(id: Long, ids: List<Long>) { call("POST", "/api/albums/$id/remove", JSONObject().put("ids", JSONArray(ids))) }
    suspend fun renameAlbum(id: Long, name: String) { call("PATCH", "/api/albums/$id", JSONObject().put("name", name)) }
    suspend fun setAlbumCover(id: Long, cover: Long) { call("PATCH", "/api/albums/$id", JSONObject().put("cover", cover)) }
    suspend fun deleteAlbum(id: Long) { call("DELETE", "/api/albums/$id") }
    suspend fun shareAlbum(id: Long, enabled: Boolean): String? {
        val o = JSONObject(call("POST", "/api/albums/$id/share", JSONObject().put("enabled", enabled)))
        return if (o.isNull("shareToken")) null else o.getString("shareToken")
    }

    /** A no-password link to just these photos/videos. */
    suspend fun createLink(ids: List<Long>): String = JSONObject(call("POST", "/api/links", JSONObject().put("ids", JSONArray(ids)))).getString("token")
    /** versionCode of the APK the server has, or null if it doesn't say. */
    suspend fun serverAppVersion(): Int? = runCatching { JSONObject(call("GET", "/app-version.json")).getInt("versionCode") }.getOrNull()

    suspend fun memories(): List<Memory> {
        val a = JSONArray(call("GET", "/api/memories"))
        return (0 until a.length()).map { val o = a.getJSONObject(it); Memory(o.getString("title"), RemoteAsset.list(o.getJSONArray("assets"))) }
    }
    suspend fun places(): List<Place> {
        val a = JSONArray(call("GET", "/api/places"))
        return (0 until a.length()).map {
            val o = a.getJSONObject(it)
            Place(o.getString("city"), o.optString("region").ifBlank { null }, o.optString("country").ifBlank { null }, o.getInt("count"), o.getLong("cover"))
        }
    }
    suspend fun search(q: String): List<RemoteAsset> = RemoteAsset.list(JSONArray(call("GET", "/api/search?q=" + URLEncoder.encode(q, "UTF-8"))))
    suspend fun stats(): JSONObject = JSONObject(call("GET", "/api/stats"))
    suspend fun changePassword(current: String, new: String) { call("POST", "/api/password", JSONObject().put("current", current).put("password", new)) }
}
