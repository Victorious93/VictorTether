package com.victorious.tether.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Typography

object Term {
    val bg = Color(0xFF0A0F0A)
    val panel = Color(0xFF111A11)
    val line = Color(0xFF1F3A1F)
    val green = Color(0xFF39FF14)
    val dim = Color(0xFF7FA37F)
    val amber = Color(0xFFFFB000)
    val red = Color(0xFFFF4D4D)
}

private val mono = FontFamily.Monospace

@Composable
fun VictoriousTheme(content: @Composable () -> Unit) {
    // Terminal look is the identity of the app, so it ignores the system light/dark setting.
    @Suppress("UNUSED_VARIABLE") val ignored = isSystemInDarkTheme()
    val scheme = darkColorScheme(
        primary = Term.green, onPrimary = Term.bg,
        background = Term.bg, onBackground = Term.green,
        surface = Term.panel, onSurface = Term.green,
        surfaceVariant = Term.panel, onSurfaceVariant = Term.dim,
        outline = Term.line, error = Term.red,
    )
    val base = TextStyle(fontFamily = mono, color = Term.green)
    val type = Typography(
        headlineSmall = base.copy(fontSize = 22.sp),
        titleMedium = base.copy(fontSize = 16.sp),
        bodyLarge = base.copy(fontSize = 15.sp),
        bodyMedium = base.copy(fontSize = 13.sp),
        labelLarge = base.copy(fontSize = 13.sp),
        labelMedium = base.copy(fontSize = 11.sp, color = Term.dim),
    )
    MaterialTheme(colorScheme = scheme, typography = type, content = content)
}
