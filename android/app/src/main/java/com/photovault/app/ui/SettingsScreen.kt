package com.photovault.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.photovault.app.BuildConfig
import com.photovault.app.backup.BackupWorker
import com.photovault.app.data.Repo
import kotlinx.coroutines.launch

@Composable
fun LoginScreen() {
    val scope = rememberCoroutineScope()
    var server by remember { mutableStateOf(Repo.prefs.server) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().statusBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(28.dp),
        verticalArrangement = Arrangement.Center) {
        Spacer(Modifier.height(60.dp))
        Text("PhotoVault", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
        Text("Your photos, on your own server.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp, bottom = 32.dp))
        OutlinedTextField(server, { server = it }, label = { Text("Server address") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(username, { username = it }, label = { Text("Username") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(password, { password = it }, label = { Text("Password") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
        if (error != null) Text(error!!, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 10.dp))
        Spacer(Modifier.height(20.dp))
        Button(enabled = !busy && username.isNotBlank() && password.isNotBlank(), modifier = Modifier.fillMaxWidth().height(50.dp), onClick = {
            busy = true; error = null
            scope.launch {
                val s = server.trim().trimEnd('/').let { if (it.startsWith("http")) it else "https://$it" }
                val problem = Repo.api.health(s)
                if (problem != null) {
                    error = problem
                } else {
                    Repo.prefs.server = s
                    runCatching { Repo.api.login(username, password) }
                        .onSuccess { token ->
                            Repo.prefs.token = token
                            Repo.prefs.lastSync = 0
                            Repo.loggedIn.value = true
                            BackupWorker.schedule(Repo.app)
                        }
                        .onFailure { error = it.message }
                }
                busy = false
            }
        }) { if (busy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp) else Text("Sign in") }
        Text("Works from anywhere — no Tailscale needed.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 18.dp))
    }
}

@Composable
fun SettingsScreen() {
    val app = LocalApp.current
    val ctx = LocalContext.current
    val p = Repo.prefs
    val status by Repo.backup.collectAsState()
    val online by Repo.online.collectAsState()
    val all by Repo.all.collectAsState()
    var backup by remember { mutableStateOf(p.backupEnabled) }
    var wifi by remember { mutableStateOf(p.wifiOnly) }
    var charging by remember { mutableStateOf(p.chargingOnly) }
    var videos by remember { mutableStateOf(p.backupVideos) }
    var stats by remember { mutableStateOf<String?>(null) }
    var changePw by remember { mutableStateOf(false) }
    var newPw by remember { mutableStateOf<String?>(null) }
    var confirmLogout by remember { mutableStateOf(false) }
    val notBacked = remember(all) { all.count { it.local != null && it.remote == null && it.local.bucketId !in p.excludedBuckets && (p.backupVideos || !it.isVideo) } }
    LaunchedEffect(Unit) {
        runCatching { Repo.api.stats() }.onSuccess { s ->
            stats = "${s.optInt("photos")} photos · ${s.optInt("videos")} videos · ${fmtBytes(s.optLong("bytes"))} used" +
                (if (!s.isNull("freeBytes")) " · ${fmtBytes(s.optLong("freeBytes"))} free on the server" else "")
        }
    }
    fun reschedule() = BackupWorker.schedule(ctx)

    Column(Modifier.fillMaxSize().background(Ios.grouped)) {
        TitleOverlay("Settings", null, onPhotos = false, onBack = { app.pop() })
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 40.dp)) {
            SectionTitle("Backup")
            Text(when {
                online == false -> "Can't reach the server right now"
                status.running -> "Backing up ${status.done} of ${status.pending}${status.current?.let { " · $it" } ?: ""}"
                notBacked > 0 && backup -> "$notBacked waiting to back up"
                backup -> "Everything is backed up"
                else -> "Backup is off"
            }, modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (stats != null) Text(stats!!, modifier = Modifier.padding(16.dp, 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.padding(16.dp, 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { BackupWorker.runNow(ctx); app.toast("Backup started") }) { Text("Back up now") }
                OutlinedButton(onClick = { app.push(Screen.Folders) }) { Text("Choose folders") }
            }
            ToggleRow("Back up photos", "New photos and videos upload to your server automatically", backup) { backup = it; p.backupEnabled = it; reschedule() }
            ToggleRow("Back up videos", null, videos) { videos = it; p.backupVideos = it }
            ToggleRow("Only on Wi-Fi", "Don't use mobile data for backup", wifi) { wifi = it; p.wifiOnly = it; reschedule() }
            ToggleRow("Only while charging", null, charging) { charging = it; p.chargingOnly = it; reschedule() }
            SettingRow("Free up space", "Remove phone copies that are already backed up") { app.push(Screen.FreeUp) }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            SectionTitle("Make PhotoVault your gallery")
            Text("Android has no single “default gallery” switch. Do these once:", modifier = Modifier.padding(horizontal = 16.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SettingRow("1. Stop Google Photos opening pictures", "Opens Google Photos' app info → “Open by default” → turn off “Open supported links”, and “Clear defaults”") {
                runCatching { ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:com.google.android.apps.photos"))) }
                    .onFailure { app.toast("Google Photos isn't installed, nothing to change") }
            }
            SettingRow("2. Open a photo from Files or the camera", "Pick PhotoVault and tap “Always”. From then on photos open here.") {
                // Show the chooser with a real photo so the user can pick "Always".
                val sample = all.firstOrNull { it.local != null && !it.isVideo }?.local
                if (sample != null) ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(sample.uri, sample.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            }
            SettingRow("3. Allow media management", "Lets PhotoVault delete, trash and favourite without asking every time") {
                if (Build.VERSION.SDK_INT >= 31) ctx.startActivity(Intent(Settings.ACTION_REQUEST_MANAGE_MEDIA, Uri.parse("package:${ctx.packageName}")))
            }
            if (Build.VERSION.SDK_INT >= 31) Text(if (MediaStore.canManageMedia(ctx)) "Media management: allowed" else "Media management: not allowed yet",
                modifier = Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            SectionTitle("Account")
            SettingRow("Server", p.server) {}
            SettingRow("Change password", null) { changePw = true }
            SettingRow("Sign out", "Photos on this phone stay put") { confirmLogout = true }
            Text("PhotoVault ${BuildConfig.VERSION_NAME}", modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (changePw) TextPrompt("Current password", password = true, confirm = "Next", onDismiss = { changePw = false }) { cur -> changePw = false; newPw = cur }
    newPw?.let { cur ->
        TextPrompt("New password", password = true, confirm = "Save", onDismiss = { newPw = null }) { pw -> newPw = null; app.run { Repo.api.changePassword(cur, pw); "Password changed" } }
    }
    if (confirmLogout) ConfirmDialog("Sign out?", "Backup stops until you sign in again.", "Sign out", { confirmLogout = false }) {
        app.run { Repo.api.logout(); Repo.logoutLocal(); BackupWorker.schedule(ctx); while (app.stack.size > 1) app.pop(); null }
    }
}

@Composable
private fun ToggleRow(title: String, sub: String?, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!value) }.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title)
            if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(value, onChange)
    }
}

@Composable
private fun SettingRow(title: String, sub: String?, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(title)
        if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
