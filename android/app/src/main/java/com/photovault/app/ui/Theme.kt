package com.photovault.app.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val Dark = darkColorScheme(
    primary = Color(0xFF8AB0F7), onPrimary = Color(0xFF0B1A33), primaryContainer = Color(0xFF203A66),
    background = Color(0xFF111214), surface = Color(0xFF111214), surfaceVariant = Color(0xFF25262A),
    surfaceContainer = Color(0xFF1B1C1F), onBackground = Color(0xFFECEDEF), onSurface = Color(0xFFECEDEF),
)
private val Light = lightColorScheme(
    primary = Color(0xFF2F6FDE), background = Color(0xFFF7F7F5), surface = Color(0xFFF7F7F5),
    surfaceVariant = Color(0xFFEDEDEA), surfaceContainer = Color(0xFFFFFFFF),
)

@Composable
fun PhotoVaultTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    // Material You: follow the phone's wallpaper colors.
    val scheme = if (Build.VERSION.SDK_INT >= 31) (if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)) else if (dark) Dark else Light
    MaterialTheme(colorScheme = scheme, content = content)
}
