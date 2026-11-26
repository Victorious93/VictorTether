package com.victorious.tether.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

internal val ConnectionHighlight = Color(0xFF203849)
internal val ConnectionWarning = Color(0xFFFFC98C)

private val colors = darkColorScheme(
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
fun VictoriousTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, typography = typography, content = content)
}
