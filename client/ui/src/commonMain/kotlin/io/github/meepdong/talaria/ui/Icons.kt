package io.github.meepdong.talaria.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** Line icons on a 24-unit grid, drawn in the content colour (tinted by Icon). */
object TalariaIcons {
    private fun icon(name: String, draw: PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            path(
                stroke = SolidColor(Color.Black), strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, pathBuilder = draw,
            )
        }.build()

    private fun PathBuilder.rect(x: Float, y: Float, w: Float, h: Float) {
        moveTo(x, y); lineTo(x + w, y); lineTo(x + w, y + h); lineTo(x, y + h); close()
    }

    val Menu = icon("menu") {
        moveTo(4f, 7f); lineTo(20f, 7f)
        moveTo(4f, 12f); lineTo(20f, 12f)
        moveTo(4f, 17f); lineTo(20f, 17f)
    }

    val Close = icon("close") {
        moveTo(6f, 6f); lineTo(18f, 18f)
        moveTo(18f, 6f); lineTo(6f, 18f)
    }

    val Home = icon("home") {
        rect(3f, 3f, 7f, 9f); rect(14f, 3f, 7f, 5f); rect(14f, 12f, 7f, 9f); rect(3f, 16f, 7f, 5f)
    }

    val Chat = icon("chat") {
        moveTo(4f, 5f); lineTo(20f, 5f); lineTo(20f, 16f); lineTo(9f, 16f); lineTo(4f, 20f); close()
    }

    val Files = icon("files") {
        moveTo(3f, 7f); lineTo(9f, 7f); lineTo(11f, 9f); lineTo(21f, 9f); lineTo(21f, 19f); lineTo(3f, 19f); close()
        moveTo(3f, 7f); lineTo(3f, 5f); lineTo(9f, 5f); lineTo(11f, 7f)
    }

    val Schedule = icon("schedule") {
        rect(3f, 5f, 18f, 16f)
        moveTo(3f, 10f); lineTo(21f, 10f)
        moveTo(8f, 3f); lineTo(8f, 7f)
        moveTo(16f, 3f); lineTo(16f, 7f)
    }

    val Mic = icon("mic") {
        moveTo(9f, 6f); arcTo(3f, 3f, 0f, false, true, 15f, 6f); lineTo(15f, 11f)
        arcTo(3f, 3f, 0f, false, true, 9f, 11f); close()
        moveTo(5f, 11f); arcTo(7f, 7f, 0f, false, false, 19f, 11f)
        moveTo(12f, 18f); lineTo(12f, 21f)
    }

    val Plus = icon("plus") {
        moveTo(12f, 5f); lineTo(12f, 19f)
        moveTo(5f, 12f); lineTo(19f, 12f)
    }

    val Todos = icon("todos") {
        moveTo(4f, 6f); lineTo(5.5f, 7.5f); lineTo(8f, 5f)
        moveTo(11f, 6.5f); lineTo(20f, 6.5f)
        moveTo(4f, 12f); lineTo(5.5f, 13.5f); lineTo(8f, 11f)
        moveTo(11f, 12.5f); lineTo(20f, 12.5f)
        moveTo(4f, 18.5f); lineTo(8f, 18.5f)
        moveTo(11f, 18.5f); lineTo(20f, 18.5f)
    }

    fun forTab(tab: Tab): ImageVector = when (tab) {
        Tab.HOME -> Home
        Tab.CHATS -> Chat
        Tab.TODOS -> Todos
        Tab.FILES -> Files
        Tab.SCHEDULE -> Schedule
    }
}
