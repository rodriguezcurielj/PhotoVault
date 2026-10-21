package com.photovault.app.ui

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.photovault.app.data.Repo
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Look chosen in Settings → Appearance (saved in Prefs): theme "system" | "light" | "dark" and an accent colour. */
object Appearance {
    val accents = listOf("Blue" to 0xFF007AFF, "Purple" to 0xFF6E5CE6, "Pink" to 0xFFE8457C, "Red" to 0xFFE5383B,
        "Orange" to 0xFFF08C00, "Green" to 0xFF2FA84F, "Teal" to 0xFF1B9AAA, "Graphite" to 0xFF6E6E73)
    var mode by mutableStateOf(Repo.prefs.theme)
    var accent by mutableLongStateOf(Repo.prefs.accent)
    fun set(mode: String = this.mode, accent: Long = this.accent) {
        this.mode = mode; this.accent = accent
        Repo.prefs.theme = mode; Repo.prefs.accent = accent
    }
}

@Composable @ReadOnlyComposable
fun isDark(): Boolean = when (Appearance.mode) { "light" -> false; "dark" -> true; else -> isSystemInDarkTheme() }

/** Apple Photos–style palette (iOS system colours); "blue" is the chosen accent. */
object Ios {
    val blue @Composable @ReadOnlyComposable get() = Color(Appearance.accent)
    val red @Composable @ReadOnlyComposable get() = if (isDark()) Color(0xFFFF453A) else Color(0xFFFF3B30)
    val gray @Composable @ReadOnlyComposable get() = Color(0xFF8E8E93)
    val background @Composable @ReadOnlyComposable get() = if (isDark()) Color.Black else Color.White
    /** Grouped table background (Albums tab). */
    val grouped @Composable @ReadOnlyComposable get() = if (isDark()) Color.Black else Color(0xFFF2F2F7)
    val cell @Composable @ReadOnlyComposable get() = if (isDark()) Color(0xFF1C1C1E) else Color.White
    val fill @Composable @ReadOnlyComposable get() = if (isDark()) Color(0xFF2C2C2E) else Color(0xFFE5E5EA)
    val separator @Composable @ReadOnlyComposable get() = if (isDark()) Color(0xFF38383A) else Color(0xFFC6C6C8)
    val bar @Composable @ReadOnlyComposable get() = if (isDark()) Color(0xF0161618) else Color(0xF2F9F9F9)
    val label @Composable @ReadOnlyComposable get() = if (isDark()) Color.White else Color.Black
}

private val Light = lightColorScheme(
    primary = Color(0xFF007AFF), onPrimary = Color.White, primaryContainer = Color(0xFFD6E8FF),
    background = Color.White, surface = Color.White, surfaceVariant = Color(0xFFF2F2F7), surfaceContainer = Color.White,
    surfaceContainerLow = Color(0xFFF2F2F7), surfaceContainerHigh = Color.White, onSurfaceVariant = Color(0xFF8E8E93),
    error = Color(0xFFFF3B30), outline = Color(0xFFC6C6C8),
)
private val Dark = darkColorScheme(
    primary = Color(0xFF0A84FF), onPrimary = Color.White, primaryContainer = Color(0xFF0A3A70),
    background = Color.Black, surface = Color.Black, surfaceVariant = Color(0xFF1C1C1E), surfaceContainer = Color(0xFF1C1C1E),
    surfaceContainerLow = Color(0xFF1C1C1E), surfaceContainerHigh = Color(0xFF2C2C2E), onSurfaceVariant = Color(0xFF8E8E93),
    onBackground = Color.White, onSurface = Color.White, error = Color(0xFFFF453A), outline = Color(0xFF38383A),
)

// iOS-like type scale: big bold titles, 17sp body.
private val AppleType = Typography(
    displaySmall = TextStyle(fontSize = 34.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.3.sp),
    headlineMedium = TextStyle(fontSize = 34.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.3.sp),
    headlineSmall = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.Bold),
    titleLarge = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold),
    titleMedium = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold),
    titleSmall = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 17.sp),
    bodyMedium = TextStyle(fontSize = 15.sp),
    bodySmall = TextStyle(fontSize = 13.sp),
    labelLarge = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium),
)

@Composable
fun PhotoVaultTheme(content: @Composable () -> Unit) {
    val dark = isDark()
    val accent = Color(Appearance.accent)
    val scheme = (if (dark) Dark else Light).copy(primary = accent, primaryContainer = accent.copy(alpha = 0.18f))
    // Status bar icons follow the app's theme, not the phone's.
    val view = LocalView.current
    if (!view.isInEditMode) SideEffect {
        (view.context as? Activity)?.window?.let { w ->
            WindowCompat.getInsetsController(w, view).run { isAppearanceLightStatusBars = !dark; isAppearanceLightNavigationBars = !dark }
        }
    }
    MaterialTheme(colorScheme = scheme, typography = AppleType, content = content)
}
