package io.github.meepdong.talaria.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** docs/brand: ink, brass and paper. */
object Brand {
    val Ink = Color(0xFF141A33)
    val Brass = Color(0xFFE7B04A)
    val Paper = Color(0xFFF3F5FB)
}

/** Status colours, shared by the rows and the tray icon. */
fun Health.color(): Color = when (this) {
    Health.GOOD -> Color(0xFF2E9E5B)
    Health.WARN -> Brand.Brass
    Health.BAD -> Color(0xFFD14343)
    Health.UNKNOWN -> Color(0xFF8A90A6)
}

private val Light = lightColorScheme(
    primary = Brand.Ink,
    onPrimary = Brand.Paper,
    secondary = Brand.Brass,
    onSecondary = Brand.Ink,
    background = Brand.Paper,
    onBackground = Brand.Ink,
    surface = Brand.Paper,
    onSurface = Brand.Ink,
)

private val Dark = darkColorScheme(
    primary = Brand.Brass,
    onPrimary = Brand.Ink,
    secondary = Brand.Brass,
    onSecondary = Brand.Ink,
    background = Brand.Ink,
    onBackground = Brand.Paper,
    surface = Brand.Ink,
    onSurface = Brand.Paper,
)

@Composable
fun TalariaTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) Dark else Light, content = content)
}
