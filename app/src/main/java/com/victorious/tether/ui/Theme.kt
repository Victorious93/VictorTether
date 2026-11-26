package com.victorious.tether.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.victorious.tether.data.ThemeMode

internal val ConnectionHighlight: Color
    @Composable get() = if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) Color(0xFF203849) else Color(0xFFDDEBFA)

internal val ConnectionWarning: Color
    @Composable get() = if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) Color(0xFFFFC98C) else Color(0xFF804900)

private val darkColors = darkColorScheme(
    primary = Color(0xFF7EE7D0),
    onPrimary = Color(0xFF082D26),
    primaryContainer = Color(0xFF223F3D),
    onPrimaryContainer = Color(0xFFB4F2E5),
    secondary = Color(0xFFADBDE8),
    onSecondary = Color(0xFF202F52),
    secondaryContainer = Color(0xFF25364C),
    onSecondaryContainer = Color(0xFFDDE6FC),
    background = Color(0xFF0B111B),
    onBackground = Color(0xFFF0F4FA),
    surface = Color(0xFF141E2C),
    onSurface = Color(0xFFF0F4FA),
    surfaceVariant = Color(0xFF1D2B3B),
    onSurfaceVariant = Color(0xFFAEBED0),
    outline = Color(0xFF6F859C),
    outlineVariant = Color(0xFF304458),
    error = Color(0xFFFFB4AD),
    onError = Color(0xFF5D1610),
    errorContainer = Color(0xFF442124),
    onErrorContainer = Color(0xFFFFDAD7),
)

private val lightColors = lightColorScheme(
    primary = Color(0xFF126958),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD5EEE7),
    onPrimaryContainer = Color(0xFF0A3F35),
    secondary = Color(0xFF475982),
    secondaryContainer = Color(0xFFDEE6FA),
    onSecondaryContainer = Color(0xFF1A2C53),
    background = Color(0xFFF3F7FB),
    onBackground = Color(0xFF141F2E),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF141F2E),
    surfaceVariant = Color(0xFFE9EFF5),
    onSurfaceVariant = Color(0xFF45576A),
    outline = Color(0xFF687B90),
    outlineVariant = Color(0xFFCBD8E6),
)

// Controls supply the foreground color appropriate to each container.
private val typography = Typography(
    headlineSmall = TextStyle(fontSize = 26.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 21.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 17.sp, lineHeight = 23.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 23.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 17.sp, fontWeight = FontWeight.Medium),
)

@Composable
fun VictoriousTheme(mode: ThemeMode, dynamicColors: Boolean, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    val context = LocalContext.current
    val colors = when {
        dynamicColors && Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColors
        else -> lightColors
    }
    MaterialTheme(colorScheme = colors, typography = typography, content = content)
}
