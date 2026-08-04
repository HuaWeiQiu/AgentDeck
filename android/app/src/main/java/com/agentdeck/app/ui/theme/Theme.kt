package com.agentdeck.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Color(0xFF38BDF8),
    onPrimary = Color(0xFF0F172A),
    secondary = Color(0xFFA78BFA),
    background = Color(0xFF0B1220),
    surface = Color(0xFF111827),
    onBackground = Color(0xFFF8FAFC),
    onSurface = Color(0xFFF8FAFC),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF0284C7),
    onPrimary = Color.White,
    secondary = Color(0xFF7C3AED),
    background = Color(0xFFF8FAFC),
    surface = Color.White,
    onBackground = Color(0xFF0F172A),
    onSurface = Color(0xFF0F172A),
)

@Composable
fun AgentDeckTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
