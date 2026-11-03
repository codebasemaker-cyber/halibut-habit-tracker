package com.codebasemaker.habittracker.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/** Colors for the three cell marks, kept apart from the dynamic scheme so they stay recognisable. */
@Immutable
data class MarkColors(val done: Color, val skip: Color, val missed: Color, val derivedAlpha: Float, val override: Color)

val LocalMarkColors = staticCompositionLocalOf {
    MarkColors(Color(0xFF1F6F5C), Color(0xFF6B7280), Color(0xFFB3261E), 0.45f, Color(0xFFE8A33D))
}

@Composable
fun HabitTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme: ColorScheme = if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
    val marks = if (dark) {
        MarkColors(Color(0xFF7FD4BC), Color(0xFFA0A7B4), Color(0xFFFFB4AB), 0.5f, Color(0xFFF2C46B))
    } else {
        MarkColors(Color(0xFF1F6F5C), Color(0xFF5F6673), Color(0xFFB3261E), 0.45f, Color(0xFFD08A1E))
    }
    androidx.compose.runtime.CompositionLocalProvider(LocalMarkColors provides marks) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

@Suppress("unused")
private val fallbackLight = lightColorScheme()
@Suppress("unused")
private val fallbackDark = darkColorScheme()
