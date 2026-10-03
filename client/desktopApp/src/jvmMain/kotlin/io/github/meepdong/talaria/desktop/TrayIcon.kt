package io.github.meepdong.talaria.desktop

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.loadSvgPainter
import androidx.compose.ui.unit.Density
import io.github.meepdong.talaria.ui.Brand

/** The brand icon from docs/brand. */
fun loadAppIcon(): Painter {
    val stream = checkNotNull(Thread.currentThread().contextClassLoader.getResourceAsStream("talaria-icon.svg")) {
        "talaria-icon.svg is missing from the app's resources"
    }
    return stream.use { loadSvgPainter(it, Density(1f)) }
}

/** The app icon with a dot in the state's colour, so the tray shows the connection. */
class TrayIconPainter(private val base: Painter, private val dot: Color) : Painter() {
    override val intrinsicSize: Size get() = base.intrinsicSize

    override fun DrawScope.onDraw() {
        with(base) { draw(size) }
        val r = size.minDimension * 0.23f
        val c = Offset(size.width - r, size.height - r)
        drawCircle(Brand.Ink, radius = r, center = c)
        drawCircle(dot, radius = r * 0.72f, center = c)
    }
}
