package io.github.meepdong.talaria.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** docs/brand: ink, brass and paper; the app adds a calm blue and a grey ground. */
object Brand {
    val Ink = Color(0xFF141A33)
    val Brass = Color(0xFFE7B04A)
    val Paper = Color(0xFFF3F5FB)

    /** The menu bar on a wide window. */
    val Rail = Color(0xFF1B232C)
    val RailSelected = Color(0xFF2E3B49)
    val RailText = Color(0xFFC9D1DA)
    val Blue = Color(0xFF1D5A85)

    /** Something at work right now. */
    val Busy = Color(0xFFD08A2E)
}

/** Status colours, shared by the rows and the tray icon. */
fun Health.color(): Color = when (this) {
    Health.GOOD -> Color(0xFF2E9E5B)
    Health.WARN -> Brand.Brass
    Health.BAD -> Color(0xFFD14343)
    Health.UNKNOWN -> Color(0xFF8A90A6)
}

private val Light = lightColorScheme(
    primary = Brand.Blue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE6EEF5),
    onPrimaryContainer = Color(0xFF123E5E),
    secondary = Brand.Brass,
    onSecondary = Brand.Ink,
    secondaryContainer = Color(0xFFE6EEF5),
    onSecondaryContainer = Color(0xFF123E5E),
    background = Color(0xFFEEF0F2),
    onBackground = Color(0xFF1B2229),
    surface = Color.White,
    onSurface = Color(0xFF1B2229),
    surfaceVariant = Color(0xFFF4F6F8),
    onSurfaceVariant = Color(0xFF56606B),
    outline = Color(0xFFCBD2D9),
    outlineVariant = Color(0xFFDCE0E5),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF8DB8DA),
    onPrimary = Color(0xFF0E2333),
    primaryContainer = Color(0xFF203445),
    onPrimaryContainer = Color(0xFFD6E6F3),
    secondary = Brand.Brass,
    onSecondary = Brand.Ink,
    secondaryContainer = Color(0xFF203445),
    onSecondaryContainer = Color(0xFFD6E6F3),
    background = Color(0xFF12171D),
    onBackground = Color(0xFFE4E8EC),
    surface = Color(0xFF1B232C),
    onSurface = Color(0xFFE4E8EC),
    surfaceVariant = Color(0xFF232D38),
    onSurfaceVariant = Color(0xFFA9B3BE),
    outline = Color(0xFF3A4654),
    outlineVariant = Color(0xFF2E3843),
)

private val TalariaShapes = Shapes(
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
)

@Composable
fun TalariaTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) Dark else Light, shapes = TalariaShapes, content = content)
}
