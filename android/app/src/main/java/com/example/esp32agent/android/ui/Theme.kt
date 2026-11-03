package com.example.esp32agent.android.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** The warm paper/ink look of the iPhone app's Theme.swift, as far as a skeleton needs it. */
private val Paper = Color(0xFFF7F5F0)
private val Ink = Color(0xFF1F2933)
private val Amber = Color(0xFFC77700)
private val AmberDark = Color(0xFFF5A623)

private val LightColors = lightColorScheme(
    primary = Amber, onPrimary = Color.White, secondary = Ink, background = Paper, surface = Color.White,
)
private val DarkColors = darkColorScheme(
    primary = AmberDark, onPrimary = Color.Black, background = Color(0xFF14181C), surface = Color(0xFF1D232A),
)

@Composable
fun Theme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}