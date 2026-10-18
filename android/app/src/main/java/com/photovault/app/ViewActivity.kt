package com.photovault.app

import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.lifecycleScope
import com.photovault.app.data.LocalItem
import com.photovault.app.data.LocalMedia
import com.photovault.app.data.MediaItem
import com.photovault.app.data.Repo
import com.photovault.app.ui.Actions
import com.photovault.app.ui.App
import com.photovault.app.ui.PhotoVaultTheme
import com.photovault.app.ui.Screen
import com.photovault.app.ui.timelineOf
import kotlinx.coroutines.launch

/**
 * Opens photos/videos sent from other apps (Files, the camera's thumbnail, chats...).
 * If the photo is in the phone's gallery you can swipe to its neighbours, like the stock gallery.
 */
class ViewActivity : ComponentActivity() {
    private var screen by mutableStateOf<Screen?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val actions = Actions(this)
        val uri = intent.data ?: run { finish(); return }
        lifecycleScope.launch {
            Repo.refreshLocal()
            val id = LocalMedia.idFromUri(uri)
            val timeline = timelineOf(Repo.all.value.ifEmpty { Repo.local.value.map { MediaItem(it, null) } })
            val index = if (id != null) timeline.indexOfFirst { it.local?.id == id } else -1
            screen = if (index >= 0) Screen.Viewer(timeline, index) else Screen.Viewer(listOf(MediaItem(single(uri), null)), 0)
        }
        setContent {
            PhotoVaultTheme {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    screen?.let { App(actions, initial = it, onExit = { finish() }) }
                }
            }
        }
    }

    private fun single(uri: Uri): LocalItem {
        var name = uri.lastPathSegment ?: "Photo"
        var size = 0L
        runCatching {
            contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) { name = c.getString(0) ?: name; size = c.getLong(1) }
            }
        }
        val mime = intent.type ?: contentResolver.getType(uri) ?: "image/*"
        return LocalItem(id = -1, uri = uri, name = name, mime = mime, isVideo = mime.startsWith("video"), dateTaken = System.currentTimeMillis(),
            modified = 0, size = size, width = 0, height = 0, duration = 0, bucketId = "", bucketName = "", favorite = false)
    }
}
