package com.example.stock.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val LightColorScheme = lightColorScheme(
    primary = BluePrimary,
    onPrimary = CardSurface,
    secondary = BlueSecondary,
    tertiary = MintAccent,
    background = SkySurface,
    onBackground = TextMain,
    surface = CardSurface,
    onSurface = TextMain,
    surfaceVariant = SkySurface,
    onSurfaceVariant = TextMuted,
)

@Composable
fun StockTheme(
    darkTheme: Boolean = false,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = LightColorScheme,
        typography = Typography,
        content = content,
    )
}
