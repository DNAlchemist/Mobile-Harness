package com.jarves.mh.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// Keep these shared tokens so existing screens inherit the JVM.run palette.
val PocketOrange = Color(0xFF78E8D0)
val PocketBlue = Color(0xFF9AC9F8)
val PocketGreen = Color(0xFFC8F36A)
val PocketBackground = Color(0xFF091619)
val PocketSurface = Color(0xFF102125)
val PocketSurfaceVariant = Color(0xFF193035)
val PocketOutline = Color(0xFF304A50)

private val DarkColors = darkColorScheme(
    primary = PocketOrange,
    onPrimary = Color(0xFF00382F),
    primaryContainer = Color(0xFF124D43),
    onPrimaryContainer = Color(0xFFB8F9E7),
    secondary = PocketBlue,
    onSecondary = Color(0xFF003258),
    tertiary = PocketGreen,
    onTertiary = Color(0xFF263900),
    background = PocketBackground,
    onBackground = Color(0xFFE7F3F1),
    surface = PocketSurface,
    onSurface = Color(0xFFE7F3F1),
    surfaceVariant = PocketSurfaceVariant,
    onSurfaceVariant = Color(0xFFA5BFBE),
    outline = PocketOutline,
    outlineVariant = Color(0xFF274044),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF006B59),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFB5F1DE),
    onPrimaryContainer = Color(0xFF002E25),
    secondary = Color(0xFF285F86),
    onSecondary = Color(0xFFFFFFFF),
    tertiary = Color(0xFF476600),
    onTertiary = Color(0xFFFFFFFF),
    background = Color(0xFFF2F8F5),
    onBackground = Color(0xFF102320),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF102320),
    surfaceVariant = Color(0xFFE2EEE9),
    onSurfaceVariant = Color(0xFF405D54),
    outline = Color(0xFF6E8780),
    outlineVariant = Color(0xFFBFCEC7),
)

enum class AppThemeMode { SYSTEM, DARK, LIGHT }

@Composable
fun PocketTheme(themeMode: AppThemeMode = AppThemeMode.SYSTEM, content: @Composable () -> Unit) {
    val isDark = when (themeMode) {
        AppThemeMode.DARK -> true
        AppThemeMode.LIGHT -> false
        AppThemeMode.SYSTEM -> isSystemInDarkTheme()
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            val insetsController = WindowCompat.getInsetsController(window, view)
            insetsController.isAppearanceLightStatusBars = !isDark
            insetsController.isAppearanceLightNavigationBars = !isDark
        }
    }

    MaterialTheme(
        colorScheme = if (isDark) DarkColors else LightColors,
        content = content,
    )
}
