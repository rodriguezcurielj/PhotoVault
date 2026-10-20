package com.photovault.app.ui

import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.photovault.app.data.Repo
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Upload progress lives outside the screen so leaving it doesn't lose track. */
private object ZipUpload {
    var name by mutableStateOf<String?>(null)
    var sent by mutableStateOf(0L)
    var size by mutableStateOf(0L)
    var message by mutableStateOf<String?>(null)
}

/** Import from Google Photos (Takeout) or Amazon Photos: upload their .zip, the server unpacks it. */
@Composable
fun ImportScreen() {
    val app = LocalApp.current
    val ctx = LocalContext.current
    var server by androidx.compose.runtime.remember { mutableStateOf<String?>(null) }
    var wasRunning by androidx.compose.runtime.remember { mutableStateOf(false) }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty() || ZipUpload.name != null) return@rememberLauncherForActivityResult
        app.scope.launch {
            for (uri in uris) {
                var name = "import.zip"; var size = 0L
                ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                    if (c.moveToFirst()) { name = c.getString(0) ?: name; size = c.getLong(1) }
                }
                if (!name.endsWith(".zip", ignoreCase = true)) { ZipUpload.message = "$name isn't a .zip file"; continue }
                ZipUpload.name = name; ZipUpload.size = size; ZipUpload.sent = 0
                ZipUpload.message = try {
                    Repo.api.importZip(ctx.contentResolver, uri, name, size) { ZipUpload.sent = it }
                    "$name uploaded, importing now"
                } catch (e: Exception) { "$name failed: ${e.message}" }
            }
            ZipUpload.name = null
        }
    }

    // Check the server's import progress every few seconds while this screen is open.
    LaunchedEffect(Unit) {
        while (true) {
            runCatching { Repo.api.importStatus() }.onSuccess { s ->
                val running = s.optBoolean("running")
                val counts = "${s.optInt("added")} new, ${s.optInt("duplicates")} already here" + (if (s.optInt("failed") > 0) ", ${s.optInt("failed")} failed" else "")
                server = when {
                    running -> "Importing ${s.optString("file")}: ${s.optInt("done")} of ${s.optInt("total").takeIf { it > 0 } ?: "â€¦"} â€” $counts"
                    !s.isNull("finishedAt") -> "Last import finished: $counts"
                    else -> null
                }
                if (wasRunning && !running) Repo.syncRemote() // show the new photos
                wasRunning = running
            }
            delay(3000)
        }
    }

    fun open(url: String) = runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }

    Column(Modifier.fillMaxSize().background(Ios.grouped)) {
        TitleOverlay("Import", null, onPhotos = false, onBack = { app.pop() })
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).navigationBarsPadding()) {
            Card {
                Button(enabled = ZipUpload.name == null, modifier = Modifier.fillMaxWidth(),
                    onClick = { pick.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/x-zip")) }) { Text("Choose .zip Files") }
                ZipUpload.name?.let { n ->
                    Text("Uploading $n" + if (ZipUpload.size > 0) " Â· ${fmtBytes(ZipUpload.sent)} of ${fmtBytes(ZipUpload.size)}" else "", modifier = Modifier.padding(top = 10.dp))
                    if (ZipUpload.size > 0) LinearProgressIndicator(progress = { ZipUpload.sent.toFloat() / ZipUpload.size }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                    Text("Keep Gallery open until the upload finishes.", color = Ios.gray, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
                }
                ZipUpload.message?.let { Text(it, modifier = Modifier.padding(top = 10.dp)) }
                server?.let { Text(it, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 10.dp)) }
                Text("Photos you already have are skipped.", color = Ios.gray, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
            }

            Card {
                Heading("Google Photos")
                Step("1. Open Google Takeout, tap â€œDeselect allâ€, then tick only Google Photos.")
                Step("2. Next step â†’ File type .zip, size 50 GB â†’ Create export. Google emails you when itâ€™s ready.")
                Step("3. Download the zip(s), then tap Choose .zip Files above.")
                Step("Dates and locations from Googleâ€™s files are kept.", gray = true)
                OutlinedButton(onClick = { open("https://takeout.google.com/") }, modifier = Modifier.padding(top = 6.dp)) { Text("Open Google Takeout") }
            }

            Card {
                Heading("Amazon Photos")
                Step("1. In Amazon Photos on the web, select the photos you want.")
                Step("2. Tap â‹¯ â†’ Download. Amazon gives you a .zip.")
                Step("3. Tap Choose .zip Files above and pick it.")
                OutlinedButton(onClick = { open("https://www.amazon.com/photos/all") }, modifier = Modifier.padding(top = 6.dp)) { Text("Open Amazon Photos") }
            }

            Card {
                Heading("A few at a time")
                Step("In the Google Photos or Amazon Photos app, select photos â†’ Share â†’ Upload to Gallery.")
                Step("Big exports go faster from a computer: on the Gallery website, use Import, or copy the zips into D:\\PhotoVault\\data\\import on the server PC.", gray = true)
            }
        }
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 12.dp).background(Ios.cell, RoundedCornerShape(12.dp)).padding(16.dp)) { content() }
}

@Composable
private fun Heading(text: String) = Text(text, fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.padding(bottom = 6.dp))

@Composable
private fun Step(text: String, gray: Boolean = false) =
    Text(text, color = if (gray) Ios.gray else androidx.compose.ui.graphics.Color.Unspecified, fontSize = 15.sp, modifier = Modifier.padding(vertical = 2.dp))
