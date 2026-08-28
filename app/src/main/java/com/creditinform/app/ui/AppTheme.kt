package com.creditinform.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF2457D6),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE6FF),
    onPrimaryContainer = Color(0xFF082260),
    secondary = Color(0xFF4D5F91),
    error = Color(0xFFB3261E),
    background = Color(0xFFF7F9FC),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFE8ECF4)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFB5C8FF),
    primaryContainer = Color(0xFF163E9F),
    secondary = Color(0xFFBBC6EB),
    background = Color(0xFF101318),
    surface = Color(0xFF181B21)
)

@Composable
fun CreditInformTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content
    )
}

