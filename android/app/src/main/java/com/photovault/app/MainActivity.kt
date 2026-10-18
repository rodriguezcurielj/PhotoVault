package com.photovault.app

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.lifecycle.lifecycleScope
import com.photovault.app.backup.BackupWorker
import com.photovault.app.data.Repo
import com.photovault.app.ui.Actions
import com.photovault.app.ui.App
import com.photovault.app.ui.PhotoVaultTheme
import kotlinx.coroutines.launch

/** Media permissions every screen needs; asked once at startup. */
fun mediaPermissions() = buildList {
    add(Manifest.permission.READ_MEDIA_IMAGES)
    add(Manifest.permission.READ_MEDIA_VIDEO)
    add(Manifest.permission.ACCESS_MEDIA_LOCATION)
    if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    if (Build.VERSION.SDK_INT < 33) add(Manifest.permission.READ_EXTERNAL_STORAGE)
}.toTypedArray()

class MainActivity : ComponentActivity() {
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        lifecycleScope.launch { Repo.refreshLocal() }
        BackupWorker.schedule(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val actions = Actions(this)
        permissions.launch(mediaPermissions())
        setContent {
            PhotoVaultTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { App(actions) }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch { Repo.refreshLocal(); Repo.syncRemote() }
    }
}
