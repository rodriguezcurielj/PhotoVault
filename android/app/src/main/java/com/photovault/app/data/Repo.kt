package com.photovault.app.data

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File

data class BackupStatus(val running: Boolean = false, val pending: Int = 0, val done: Int = 0, val current: String? = null, val error: String? = null)

/** App-wide data: phone items, server items, and the merged timeline. */
object Repo {
    lateinit var app: Context
    lateinit var prefs: Prefs
    lateinit var api: Api
    lateinit var backupDb: BackupDb
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val local = MutableStateFlow<List<LocalItem>>(emptyList())
    val localTrash = MutableStateFlow<List<LocalItem>>(emptyList())
    val remote = MutableStateFlow<Map<Long, RemoteAsset>>(emptyMap())
    val backed = MutableStateFlow<Map<Long, BackupDb.Entry>>(emptyMap())
    val online = MutableStateFlow<Boolean?>(null)
    val backup = MutableStateFlow(BackupStatus())
    val albums = MutableStateFlow<List<Album>>(emptyList())
    val memories = MutableStateFlow<List<Memory>>(emptyList())
    val loggedIn = MutableStateFlow(false)

    /** Every item, phone + server, merged (includes archived and trashed server items). */
    val all: StateFlow<List<MediaItem>> = combine(local, remote, backed) { l, r, b -> merge(l, r, b) }
        .flowOn(Dispatchers.Default)
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    fun init(context: Context) {
        app = context.applicationContext
        prefs = Prefs(app)
        api = Api(prefs)
        backupDb = BackupDb(app)
        loggedIn.value = prefs.token != null
        loadCache()
        backed.value = backupDb.all()
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) = refreshLocalSoon()
        }
        app.contentResolver.registerContentObserver(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL), true, observer)
    }

    private fun merge(l: List<LocalItem>, r: Map<Long, RemoteAsset>, b: Map<Long, BackupDb.Entry>): List<MediaItem> {
        val myDevice = prefs.deviceId
        val byDeviceAsset = HashMap<String, RemoteAsset>()
        for (a in r.values) if (a.deviceId == myDevice && a.deviceAsset != null) byDeviceAsset[a.deviceAsset] = a
        val claimed = HashSet<Long>()
        val out = ArrayList<MediaItem>(l.size + r.size)
        for (item in l) {
            val rem = b[item.id]?.remoteId?.let { r[it] } ?: byDeviceAsset[item.id.toString()]
            if (rem != null) claimed += rem.id
            out += MediaItem(item, rem)
        }
        for (a in r.values) if (a.id !in claimed) out += MediaItem(null, a)
        out.sortByDescending { it.date }
        return out
    }

    // ---------------- phone
    private var localJob: Job? = null
    fun refreshLocalSoon() {
        localJob?.cancel()
        localJob = scope.launch { delay(800); refreshLocal() }
    }
    suspend fun refreshLocal() = withContext(Dispatchers.IO) {
        local.value = LocalMedia.query(app)
        localTrash.value = LocalMedia.query(app, trashed = true)
    }

    // ---------------- server
    private val syncLock = Mutex()
    suspend fun syncRemote(full: Boolean = false) = syncLock.withLock {
        if (prefs.token == null) return@withLock
        try {
            val since = if (full) 0 else prefs.lastSync
            val s = api.assets(since)
            val map = if (since == 0L) HashMap() else HashMap(remote.value)
            for (a in s.assets) map[a.id] = a
            for (id in s.deleted) map.remove(id)
            if (s.deleted.isNotEmpty()) {
                backupDb.forgetRemote(s.deleted.filter { id -> map[id] == null })
                backed.value = backupDb.all()
            }
            remote.value = map
            prefs.lastSync = s.now
            online.value = true
            saveCache()
        } catch (e: ApiException) {
            if (e.code == 401) logoutLocal()
            online.value = false
        } catch (e: Exception) {
            online.value = false
        }
    }

    suspend fun refreshExtras() {
        runCatching { albums.value = api.albums() }
        runCatching { memories.value = api.memories() }
    }

    fun updateRemote(a: RemoteAsset) { remote.value = remote.value + (a.id to a) }

    fun logoutLocal() {
        prefs.token = null
        prefs.lastSync = 0
        remote.value = emptyMap()
        albums.value = emptyList()
        memories.value = emptyList()
        loggedIn.value = false
        File(app.filesDir, "remote.json").delete()
        backupDb.clear()
        backed.value = emptyMap()
    }

    private fun loadCache() {
        runCatching {
            val f = File(app.filesDir, "remote.json")
            if (f.exists()) remote.value = RemoteAsset.list(JSONArray(f.readText())).associateBy { it.id }
        }
    }
    private fun saveCache() {
        runCatching {
            val arr = JSONArray()
            remote.value.values.forEach { arr.put(it.toJson()) }
            File(app.filesDir, "remote.json.tmp").apply { writeText(arr.toString()); renameTo(File(app.filesDir, "remote.json")) }
        }
    }

    fun markBacked(item: LocalItem, a: RemoteAsset, checksum: String?) {
        backupDb.mark(item, a.id, checksum)
        backed.value = backed.value + (item.id to BackupDb.Entry(a.id, item.size, item.modified))
        updateRemote(a)
    }
}
