package com.photovault.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import com.photovault.app.backup.BackupWorker
import com.photovault.app.data.Repo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** "Share → Upload to PhotoVault": copies the files (the share permission ends with this screen) and uploads them in the background. */
class ShareActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Repo.prefs.token == null) {
            Toast.makeText(this, "Open PhotoVault and sign in first", Toast.LENGTH_LONG).show()
            finish(); return
        }
        val uris: List<Uri> = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java) ?: emptyList()
            else -> emptyList()
        }
        if (uris.isEmpty()) { finish(); return }
        lifecycleScope.launch {
            val copies = withContext(Dispatchers.IO) {
                val dir = File(cacheDir, "upload").apply { mkdirs() }
                uris.mapIndexedNotNull { i, uri ->
                    runCatching {
                        val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
                            ?: "shared_$i.${(contentResolver.getType(uri) ?: "image/jpeg").substringAfter('/').replace("jpeg", "jpg")}"
                        val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
                        val f = File(dir, "${System.currentTimeMillis()}${i}_$safe")
                        contentResolver.openInputStream(uri)!!.use { input -> f.outputStream().use { input.copyTo(it) } }
                        Uri.fromFile(f)
                    }.getOrNull()
                }
            }
            BackupWorker.uploadShared(this@ShareActivity, copies)
            Toast.makeText(this@ShareActivity, "Uploading ${copies.size} to PhotoVault", Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}
