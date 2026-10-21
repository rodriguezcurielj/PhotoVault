package com.photovault.app.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil3.compose.AsyncImage
import com.photovault.app.BuildConfig
import com.photovault.app.R
import com.photovault.app.backup.BackupWorker
import com.photovault.app.data.Api
import com.photovault.app.data.Repo
import kotlinx.coroutines.launch

// ---------------------------------------------------------------- iOS-style building blocks

/** Rounded "inset grouped" card, like iOS Settings. */
@Composable
fun GroupCard(header: String? = null, footer: String? = null, content: @Composable () -> Unit) {
    if (header != null) Text(header.uppercase(), fontSize = 13.sp, color = Ios.gray, modifier = Modifier.fillMaxWidth().padding(start = 32.dp, top = 22.dp, bottom = 6.dp))
    else Spacer(Modifier.height(22.dp))
    Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Ios.cell)) { content() }
    if (footer != null) Text(footer, fontSize = 13.sp, color = Ios.gray, modifier = Modifier.fillMaxWidth().padding(start = 32.dp, end = 32.dp, top = 6.dp))
}

/** Coloured rounded-square icon on the left of a row. */
@Composable
private fun IconBadge(icon: ImageVector, color: Color) {
    Box(Modifier.size(29.dp).background(color, RoundedCornerShape(7.dp)), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(18.dp))
    }
}

@Composable
fun Cell(title: String, sub: String? = null, icon: ImageVector? = null, iconColor: Color = Ios.blue, value: String? = null,
         titleColor: Color = Ios.label, divider: Boolean = true, chevron: Boolean = true,
         trailing: (@Composable () -> Unit)? = null, onClick: (() -> Unit)? = null) {
    Column {
        Row(Modifier.fillMaxWidth().heightIn(min = 46.dp).then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(start = 16.dp, end = 12.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) { IconBadge(icon, iconColor); Spacer(Modifier.width(14.dp)) }
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 17.sp, color = titleColor)
                if (sub != null) Text(sub, fontSize = 13.sp, color = Ios.gray, lineHeight = 17.sp)
            }
            if (value != null) Text(value, fontSize = 17.sp, color = Ios.gray, maxLines = 1, modifier = Modifier.padding(start = 8.dp))
            if (trailing != null) trailing()
            else if (onClick != null && chevron) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Ios.gray.copy(alpha = 0.6f), modifier = Modifier.size(22.dp))
        }
        if (divider) Box(Modifier.padding(start = if (icon != null) 59.dp else 16.dp).fillMaxWidth().height(0.5.dp).background(Ios.separator))
    }
}

@Composable
private fun ToggleCell(title: String, sub: String?, icon: ImageVector, iconColor: Color, value: Boolean, divider: Boolean = true, onChange: (Boolean) -> Unit) =
    Cell(title, sub, icon, iconColor, divider = divider, trailing = {
        Switch(value, onChange, colors = SwitchDefaults.colors(checkedTrackColor = Color(0xFF34C759), checkedThumbColor = Color.White, checkedBorderColor = Color.Transparent))
    }, onClick = { onChange(!value) })

/** Segmented control (Automatic · Light · Dark). */
@Composable
fun Segmented(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    Row(Modifier.background(Ios.fill, RoundedCornerShape(9.dp)).padding(2.dp)) {
        options.forEach { (key, label) ->
            val on = key == selected
            Box(Modifier.clip(RoundedCornerShape(7.dp)).background(if (on) Ios.cell else Color.Transparent).clickable { onSelect(key) }
                .padding(horizontal = 12.dp, vertical = 6.dp)) {
                Text(label, fontSize = 13.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal, color = Ios.label)
            }
        }
    }
}

/** Profile picture: a chosen photo, or initials on the accent colour. */
@Composable
fun Avatar(profile: Api.Profile?, size: Dp, modifier: Modifier = Modifier) {
    val accent = Ios.blue
    Box(modifier.size(size).clip(CircleShape).background(Brush.linearGradient(listOf(accent.copy(alpha = 0.65f), accent))), contentAlignment = Alignment.Center) {
        if (profile?.avatar != null) AsyncImage(model = Repo.api.thumbUrl(profile.avatar), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        else Text(profile?.initials ?: "", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = (size.value * 0.38f).sp)
    }
}

/** The round profile button in the Library title bar. */
@Composable
fun ProfileButton(onClick: () -> Unit) {
    val profile by Repo.profile.collectAsState()
    LaunchedEffect(Unit) { if (profile == null) Repo.refreshProfile() }
    Box(Modifier.padding(start = 6.dp).border(1.5.dp, Color.White.copy(alpha = 0.7f), CircleShape).clickable(onClick = onClick)) { Avatar(profile, 32.dp) }
}

// ---------------------------------------------------------------- sign in

@Composable
fun LoginScreen() {
    val scope = rememberCoroutineScope()
    var server by remember { mutableStateOf(Repo.prefs.server) }
    var editServer by remember { mutableStateOf(false) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var forgot by remember { mutableStateOf(false) }
    val serverUrl = { server.trim().trimEnd('/').let { if (it.startsWith("http")) it else "https://$it" } }
    fun signedIn(token: String) {
        Repo.prefs.token = token
        Repo.prefs.lastSync = 0
        Repo.loggedIn.value = true
        BackupWorker.schedule(Repo.app)
        com.photovault.app.data.Tailnet.start(Repo.app)
    }
    Column(Modifier.fillMaxSize().background(Ios.grouped).statusBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(72.dp))
        Box(Modifier.size(92.dp).clip(RoundedCornerShape(22.dp)).background(Color(0xFF2E2A6B)), contentAlignment = Alignment.Center) {
            Image(painterResource(R.drawable.ic_launcher_fg), null, modifier = Modifier.size(150.dp))
        }
        Text("Gallery", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = Ios.label, modifier = Modifier.padding(top = 18.dp))
        Text("Sign in to your photos.", fontSize = 16.sp, color = Ios.gray, modifier = Modifier.padding(top = 4.dp, bottom = 28.dp))
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Ios.cell)) {
            PlainField(username, { username = it }, "Username", KeyboardType.Email)
            Box(Modifier.padding(start = 16.dp).fillMaxWidth().height(0.5.dp).background(Ios.separator))
            PlainField(password, { password = it }, "Password", KeyboardType.Password, secret = true)
        }
        if (error != null) Text(error!!, color = Ios.red, fontSize = 14.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 12.dp))
        Spacer(Modifier.height(18.dp))
        Surface(onClick = {
            if (busy || username.isBlank() || password.isBlank()) return@Surface
            busy = true; error = null
            scope.launch {
                val s = serverUrl()
                val problem = Repo.api.health(s)
                if (problem != null) error = problem
                else {
                    Repo.prefs.server = s
                    runCatching { Repo.api.login(username, password) }.onSuccess(::signedIn).onFailure { error = it.message }
                }
                busy = false
            }
        }, shape = RoundedCornerShape(12.dp), color = Ios.blue.copy(alpha = if (username.isBlank() || password.isBlank()) 0.45f else 1f),
            modifier = Modifier.fillMaxWidth().height(50.dp)) {
            Box(contentAlignment = Alignment.Center) {
                if (busy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = Color.White)
                else Text("Sign In", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        TextButton(onClick = { Repo.prefs.server = serverUrl(); forgot = true }, modifier = Modifier.padding(top = 10.dp)) { Text("Forgot password?", color = Ios.blue, fontSize = 15.sp) }
        Spacer(Modifier.height(30.dp))
        if (editServer) Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Ios.cell)) {
            PlainField(server, { server = it }, "Server address", KeyboardType.Uri)
        } else Text("Server: ${server.removePrefix("https://")} · Change", fontSize = 13.sp, color = Ios.gray,
            modifier = Modifier.clickable { editServer = true }.padding(8.dp))
        Spacer(Modifier.height(40.dp))
    }
    if (forgot) ForgotPasswordFlow(username, onDone = { forgot = false }, onToken = ::signedIn)
}

@Composable
private fun PlainField(value: String, onChange: (String) -> Unit, placeholder: String, type: KeyboardType, secret: Boolean = false) {
    androidx.compose.material3.TextField(value, onChange, placeholder = { Text(placeholder, color = Ios.gray) }, singleLine = true,
        visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = type), modifier = Modifier.fillMaxWidth(),
        colors = androidx.compose.material3.TextFieldDefaults.colors(
            focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, cursorColor = Ios.blue))
}

/** Forgot password: send a code to the recovery email, then set a new password with it. */
@Composable
fun ForgotPasswordFlow(username: String, onDone: () -> Unit, onToken: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var user by remember { mutableStateOf(username) }
    var sentTo by remember { mutableStateOf<String?>(null) }
    var code by remember { mutableStateOf("") }
    var pw by remember { mutableStateOf("") }
    var pw2 by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text(if (sentTo == null) "Forgot Password" else "Check Your Email") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (sentTo == null) {
                    Text("We'll email a 6-digit code to the recovery email on your account.", color = Ios.gray)
                    OutlinedTextField(user, { user = it }, label = { Text("Username") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                } else {
                    Text("We sent a code to $sentTo. It works for 15 minutes.", color = Ios.gray)
                    OutlinedTextField(code, { code = it }, label = { Text("Code") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
                    OutlinedTextField(pw, { pw = it }, label = { Text("New password") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                    OutlinedTextField(pw2, { pw2 = it }, label = { Text("New password again") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                }
                if (error != null) Text(error!!, color = Ios.red)
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                busy = true; error = null
                scope.launch {
                    try {
                        if (sentTo == null) sentTo = Repo.api.forgotPassword(user)
                        else if (pw != pw2) error = "The passwords don't match"
                        else { onToken(Repo.api.resetPassword(code, pw)); onDone() }
                    } catch (e: Exception) { error = e.message }
                    busy = false
                }
            }) { Text(if (sentTo == null) "Send Code" else "Change Password") }
        },
        dismissButton = { TextButton(onClick = onDone) { Text("Cancel") } },
    )
}

/** Shown after signing in when the account has no recovery email. It can't be skipped. */
@Composable
fun RequireEmailDialog() {
    val profile by Repo.profile.collectAsState()
    val p = profile ?: return
    if (p.email.isNotBlank()) return
    val scope = rememberCoroutineScope()
    var email by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = {},
        properties = androidx.compose.ui.window.DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        title = { Text("Add a Recovery Email") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("If you ever forget your password, Gallery emails a code here so you can reset it.", color = Ios.gray)
                OutlinedTextField(email, { email = it }, label = { Text("Email") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
                if (error != null) Text(error!!, color = Ios.red)
            }
        },
        confirmButton = {
            TextButton(enabled = email.contains('@'), onClick = {
                scope.launch { runCatching { Repo.api.updateProfile(email = email.trim()) }.onSuccess { Repo.profile.value = it }.onFailure { error = it.message } }
            }) { Text("Save") }
        },
    )
}

/** Change password: the current one is checked first, then the new one is asked for. */
@Composable
fun ChangePasswordFlow(onDone: () -> Unit) {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    var current by remember { mutableStateOf("") }
    var verified by remember { mutableStateOf(false) }
    var pw by remember { mutableStateOf("") }
    var pw2 by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text(if (!verified) "Current Password" else "New Password") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (!verified) {
                    Text("Enter your current password first.", color = Ios.gray)
                    OutlinedTextField(current, { current = it }, label = { Text("Current password") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                } else {
                    OutlinedTextField(pw, { pw = it }, label = { Text("New password") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                    OutlinedTextField(pw2, { pw2 = it }, label = { Text("New password again") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                }
                if (error != null) Text(error!!, color = Ios.red)
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && (if (verified) pw.isNotBlank() else current.isNotBlank()), onClick = {
                busy = true; error = null
                scope.launch {
                    try {
                        if (!verified) { Repo.api.verifyPassword(current); verified = true }
                        else if (pw != pw2) error = "The passwords don't match"
                        else { Repo.api.changePassword(current, pw); app.toast("Password changed"); onDone() }
                    } catch (e: Exception) { error = e.message }
                    busy = false
                }
            }) { Text(if (!verified) "Next" else "Change") }
        },
        dismissButton = { TextButton(onClick = onDone) { Text("Cancel") } },
    )
}

// ---------------------------------------------------------------- settings

/** True when photos already open in Gallery by default and media management is allowed. */
private fun galleryIsDefault(ctx: Context, sample: Uri?): Boolean {
    val intent = Intent(Intent.ACTION_VIEW).setDataAndType(sample ?: Uri.parse("content://media/external/images/media/1"), "image/jpeg")
    val default = ctx.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName == ctx.packageName
    val media = Build.VERSION.SDK_INT < 31 || MediaStore.canManageMedia(ctx)
    return default && media
}

@Composable
fun SettingsScreen() {
    val app = LocalApp.current
    val ctx = LocalContext.current
    val p = Repo.prefs
    val status by Repo.backup.collectAsState()
    val online by Repo.online.collectAsState()
    val all by Repo.all.collectAsState()
    val profile by Repo.profile.collectAsState()
    var backup by remember { mutableStateOf(p.backupEnabled) }
    var wifi by remember { mutableStateOf(p.wifiOnly) }
    var charging by remember { mutableStateOf(p.chargingOnly) }
    var videos by remember { mutableStateOf(p.backupVideos) }
    var stats by remember { mutableStateOf<String?>(null) }
    var confirmLogout by remember { mutableStateOf(false) }
    var serverVersion by remember { mutableStateOf<Int?>(null) }
    // Re-check the default-gallery steps whenever we come back from Android's settings.
    var resumes by remember { mutableIntStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) resumes++ }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
    val sample = remember(all) { all.firstOrNull { it.local != null && !it.isVideo }?.local }
    val isDefault = remember(resumes, sample) { galleryIsDefault(ctx, sample?.uri) }
    val notBacked = remember(all) { all.count { it.local != null && it.remote == null && it.local.bucketId !in p.excludedBuckets && (p.backupVideos || !it.isVideo) } }
    LaunchedEffect(Unit) {
        Repo.refreshProfile()
        runCatching { Repo.api.stats() }.onSuccess { s ->
            stats = "${s.optInt("photos")} photos · ${s.optInt("videos")} videos · ${fmtBytes(s.optLong("bytes"))}" +
                (if (!s.isNull("freeBytes")) " · ${fmtBytes(s.optLong("freeBytes"))} free" else "")
        }
        serverVersion = Repo.api.serverAppVersion()
    }
    fun reschedule() = BackupWorker.schedule(ctx)
    val newer = serverVersion != null && serverVersion!! > BuildConfig.VERSION_CODE

    Column(Modifier.fillMaxSize().background(Ios.grouped)) {
        TitleOverlay("Settings", null, onPhotos = false, onBack = { app.pop() })
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 40.dp)) {
            // Profile card
            Spacer(Modifier.height(8.dp))
            Row(Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Ios.cell)
                .clickable { app.push(Screen.Profile) }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Avatar(profile, 60.dp)
                Column(Modifier.weight(1f).padding(start = 14.dp)) {
                    Text(profile?.display ?: "Your Account", fontSize = 21.sp, fontWeight = FontWeight.SemiBold, color = Ios.label)
                    Text(profile?.email?.ifBlank { null } ?: "Gallery Account, Email & Password", fontSize = 14.sp, color = Ios.gray)
                }
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Ios.gray.copy(alpha = 0.6f))
            }

            GroupCard("Backup", footer = stats) {
                Cell(when {
                    online == false -> "Can't reach the server"
                    status.running -> "Backing up ${status.done} of ${status.pending}"
                    notBacked > 0 && backup -> "$notBacked waiting to back up"
                    backup -> "Everything is backed up"
                    else -> "Backup is off"
                }, status.current, Icons.Filled.CloudUpload, Color(0xFF0A84FF), value = null, chevron = false,
                    trailing = { TextButton(onClick = { BackupWorker.runNow(ctx); app.toast("Backup started") }) { Text("Back Up Now", color = Ios.blue) } })
                ToggleCell("Back Up Photos", null, Icons.Filled.PhotoLibrary, Color(0xFFFF9F0A), backup) { backup = it; p.backupEnabled = it; reschedule() }
                ToggleCell("Back Up Videos", null, Icons.Filled.Videocam, Color(0xFFFF375F), videos) { videos = it; p.backupVideos = it }
                ToggleCell("Only on Wi-Fi", null, Icons.Filled.Wifi, Color(0xFF0A84FF), wifi) { wifi = it; p.wifiOnly = it; reschedule() }
                ToggleCell("Only While Charging", null, Icons.Filled.BatteryChargingFull, Color(0xFF34C759), charging) { charging = it; p.chargingOnly = it; reschedule() }
                Cell("Folders", icon = Icons.Filled.Folder, iconColor = Color(0xFF5AC8FA), onClick = { app.push(Screen.Folders) })
                Cell("Free Up Space", icon = Icons.Filled.CleaningServices, iconColor = Color(0xFF8E8E93), onClick = { app.push(Screen.FreeUp) })
                Cell("Import from Google or Amazon Photos", icon = Icons.Filled.CloudDownload, iconColor = Color(0xFF5E5CE6), divider = false, onClick = { app.push(Screen.Import) })
            }

            GroupCard("Appearance") {
                Cell("Theme", chevron = false, trailing = {
                    Segmented(listOf("system" to "Auto", "light" to "Light", "dark" to "Dark"), Appearance.mode) { Appearance.set(mode = it) }
                })
                Column(Modifier.padding(16.dp, 10.dp, 16.dp, 14.dp)) {
                    Text("Accent Color", fontSize = 17.sp, color = Ios.label)
                    Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Appearance.accents.forEach { (_, c) ->
                            val on = Appearance.accent == c
                            Box(Modifier.size(30.dp).clip(CircleShape).background(Color(c)).clickable { Appearance.set(accent = c) }, contentAlignment = Alignment.Center) {
                                if (on) Icon(Icons.Filled.Check, null, tint = Color.White, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }

            // Disappears once photos open in Gallery by default and media management is allowed.
            if (!isDefault) GroupCard("Make Gallery Your Default", footer = "Android has no single “default gallery” switch. Do these once and this section goes away.") {
                Cell("Stop Google Photos opening pictures", "App info → Open by default → turn off links, Clear defaults", onClick = {
                    runCatching { ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:com.google.android.apps.photos"))) }
                        .onFailure { app.toast("Google Photos isn't installed, nothing to change") }
                })
                Cell("Open a photo and choose Gallery", "Pick Gallery and tap “Always”", onClick = {
                    if (sample != null) ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(sample.uri, sample.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                })
                Cell("Allow media management", if (Build.VERSION.SDK_INT >= 31 && MediaStore.canManageMedia(ctx)) "Allowed" else "Delete and favorite without asking every time",
                    divider = false, onClick = {
                        if (Build.VERSION.SDK_INT >= 31) ctx.startActivity(Intent(Settings.ACTION_REQUEST_MANAGE_MEDIA, Uri.parse("package:${ctx.packageName}")))
                    })
            }

            GroupCard("App") {
                Cell(if (newer) "Update Available" else "Update App", if (newer) "Tap to install the newest Gallery" else "You're on ${BuildConfig.VERSION_NAME}",
                    Icons.Filled.SystemUpdate, Color(0xFF34C759), divider = false, onClick = { app.run { app.actions.updateApp() } })
            }

            GroupCard("Account") {
                Cell("Server", icon = Icons.Filled.Dns, iconColor = Color(0xFF8E8E93), value = p.server.removePrefix("https://").substringBefore('.'), chevron = false)
                val route by com.photovault.app.data.Tailnet.state.collectAsState()
                Cell("Connection", when (route) {
                    com.photovault.app.data.Tailnet.State.Direct -> "Private Tailscale link, straight to your server"
                    com.photovault.app.data.Tailnet.State.Starting -> "Setting up the direct link…"
                    com.photovault.app.data.Tailnet.State.Relayed -> "This network blocks a direct link, so using Funnel"
                    else -> "Through Tailscale Funnel (slower)"
                }, Icons.Filled.Bolt, Color(0xFF34C759),
                    value = if (route == com.photovault.app.data.Tailnet.State.Direct) "Direct" else "Internet", chevron = false)
                Cell("Sign Out", icon = Icons.AutoMirrored.Filled.Logout, iconColor = Color(0xFFFF3B30), titleColor = Ios.red, divider = false, chevron = false, onClick = { confirmLogout = true })
            }
            Text("Gallery ${BuildConfig.VERSION_NAME}", fontSize = 13.sp, color = Ios.gray, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 20.dp))
        }
    }
    if (confirmLogout) ConfirmDialog("Sign out?", "Backup stops until you sign in again.", "Sign out", { confirmLogout = false }) {
        app.run { Repo.api.logout(); Repo.logoutLocal(); BackupWorker.schedule(ctx); while (app.stack.size > 1) app.pop(); null }
    }
}

// ---------------------------------------------------------------- profile

@Composable
fun ProfileScreen() {
    val app = LocalApp.current
    val profile by Repo.profile.collectAsState()
    val all by Repo.all.collectAsState()
    var editName by remember { mutableStateOf(false) }
    var editEmail by remember { mutableStateOf(false) }
    var changePw by remember { mutableStateOf(false) }
    var pickPhoto by remember { mutableStateOf(false) }
    var stats by remember { mutableStateOf<org.json.JSONObject?>(null) }
    LaunchedEffect(Unit) { Repo.refreshProfile(); stats = runCatching { Repo.api.stats() }.getOrNull() }
    val p = profile

    Column(Modifier.fillMaxSize().background(Ios.grouped)) {
        TitleOverlay("Profile", null, onPhotos = false, onBack = { app.pop() })
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(12.dp))
            Avatar(p, 104.dp, Modifier.clickable { pickPhoto = true })
            Text("Edit", color = Ios.blue, fontSize = 15.sp, modifier = Modifier.clickable { pickPhoto = true }.padding(6.dp))
            Text(p?.display ?: "", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Ios.label)
            Text("@${p?.username ?: ""}", fontSize = 15.sp, color = Ios.gray)

            GroupCard("Account", footer = if (p?.mailReady == false) "Password reset emails aren't turned on on the server yet." else null) {
                Cell("Name", icon = Icons.Filled.Person, iconColor = Color(0xFF8E8E93), value = p?.name?.ifBlank { "Add" }, onClick = { editName = true })
                Cell("Recovery Email", null, Icons.Filled.Email, Color(0xFF0A84FF), value = p?.email?.ifBlank { "Add" }, onClick = { editEmail = true })
                Cell("Change Password", icon = Icons.Filled.Lock, iconColor = Color(0xFF8E8E93), onClick = { changePw = true })
                Cell("Profile Photo", icon = Icons.Filled.Photo, iconColor = Color(0xFFFF9F0A), divider = false, onClick = { pickPhoto = true })
            }
            stats?.let { s ->
                GroupCard("Library") {
                    Cell("Photos", value = "%,d".format(s.optInt("photos")), chevron = false)
                    Cell("Videos", value = "%,d".format(s.optInt("videos")), chevron = false)
                    Cell("Space Used", value = fmtBytes(s.optLong("bytes")), chevron = false, divider = s.isNull("freeBytes"))
                    if (!s.isNull("freeBytes")) Cell("Free on Server", value = fmtBytes(s.optLong("freeBytes")), chevron = false, divider = false)
                }
            }
        }
    }
    if (editName) TextPrompt("Your Name", p?.name ?: "", confirm = "Save", onDismiss = { editName = false }) { name ->
        editName = false; app.run { Repo.profile.value = Repo.api.updateProfile(name = name.trim()); null }
    }
    if (editEmail) EmailDialog(p?.email ?: "") { editEmail = false }
    if (changePw) ChangePasswordFlow { changePw = false }
    if (pickPhoto) AlertDialog(
        onDismissRequest = { pickPhoto = false },
        title = { Text("Profile Photo") },
        text = {
            val photos = remember(all) { all.filter { it.remote != null && !it.isVideo && it.remote.trashedAt == null }.take(300) }
            LazyVerticalGrid(GridCells.Fixed(4), Modifier.heightIn(max = 420.dp)) {
                items(photos, key = { it.key }) { item ->
                    AsyncImage(model = Repo.api.thumbUrl(item.remote!!.id), contentDescription = null, contentScale = ContentScale.Crop,
                        modifier = Modifier.aspectRatio(1f).padding(1.dp).clickable {
                            pickPhoto = false
                            app.run { Repo.profile.value = Repo.api.updateProfile(avatar = item.remote.id); null }
                        })
                }
            }
        },
        confirmButton = { TextButton(onClick = { pickPhoto = false }) { Text("Cancel") } },
        dismissButton = if (p?.avatar != null) ({ TextButton(onClick = { pickPhoto = false; app.run { Repo.profile.value = Repo.api.updateProfile(clearAvatar = true); null } }) { Text("Remove", color = Ios.red) } }) else null,
    )
}

/** Changing the recovery email asks for the current password. */
@Composable
private fun EmailDialog(current: String, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var email by remember { mutableStateOf(current) }
    var pw by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text("Recovery Email") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(email, { email = it }, label = { Text("Email") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
                if (current.isNotBlank()) OutlinedTextField(pw, { pw = it }, label = { Text("Current password") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                if (error != null) Text(error!!, color = Ios.red)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                scope.launch {
                    runCatching { Repo.api.updateProfile(email = email.trim(), password = pw.ifBlank { null }) }
                        .onSuccess { Repo.profile.value = it; onDone() }.onFailure { error = it.message }
                }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDone) { Text("Cancel") } },
    )
}
