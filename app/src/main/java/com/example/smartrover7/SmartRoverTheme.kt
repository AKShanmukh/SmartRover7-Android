package com.example.smartrover7

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// This defines the custom color palette for your app's dark, futuristic UI.
private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF00E5FF),      // A bright cyan for primary buttons and highlights
    secondary = Color(0xFF7C4DFF),    // A purple for secondary elements
    background = Color(0xFF0B1220),  // The main dark blue background
    surface = Color(0xFF121A2A),      // The color of cards and dialogs
    onPrimary = Color.White,          // Text color on primary-colored elements
    onSurface = Color(0xFFE0EDFF),    // The main text color for most surfaces
    onBackground = Color(0xFFE0EDFF)  // The main text color for the background
)

// This is the Composable function that MainActivity calls.
// It applies the color scheme to your entire app.
@Composable
fun SmartRoverTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        // You could also define custom typography and shapes here
        content = content
    )
}
