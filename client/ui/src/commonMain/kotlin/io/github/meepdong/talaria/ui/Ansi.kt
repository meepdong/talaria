package io.github.meepdong.talaria.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle

/** xterm's 16 colours, a little softened for a dark background. */
private val BASIC = listOf(
    0xFF1B2229, 0xFFE06C75, 0xFF98C379, 0xFFE5C07B, 0xFF61AFEF, 0xFFC678DD, 0xFF56B6C2, 0xFFD7DAE0,
    0xFF5C6370, 0xFFFF7A85, 0xFFB5E890, 0xFFFFD68A, 0xFF7CC4FF, 0xFFE09CFF, 0xFF7FD8E3, 0xFFFFFFFF,
).map { Color(it) }

/** The 256-colour palette: the 16 above, a 6×6×6 cube and 24 greys. */
internal fun xterm256(n: Int): Color = when {
    n < 16 -> BASIC[n]
    n < 232 -> {
        val i = n - 16
        val step = { v: Int -> if (v == 0) 0 else 55 + v * 40 }
        Color(step(i / 36), step(i / 6 % 6), step(i % 6))
    }
    else -> (8 + (n - 232) * 10).let { Color(it, it, it) }
}

private data class Pen(
    val fg: Color? = null, val bg: Color? = null, val bold: Boolean = false, val dim: Boolean = false,
    val italic: Boolean = false, val underline: Boolean = false, val reverse: Boolean = false,
) {
    fun style(defaultFg: Color, defaultBg: Color): SpanStyle {
        var f = fg ?: defaultFg
        var b = bg
        if (reverse) {
            f = bg ?: defaultBg
            b = fg ?: defaultFg
        }
        return SpanStyle(
            color = if (dim) f.copy(alpha = 0.6f) else f, background = b ?: Color.Unspecified,
            fontWeight = if (bold) FontWeight.Bold else null, fontStyle = if (italic) FontStyle.Italic else null,
            textDecoration = if (underline) TextDecoration.Underline else null,
        )
    }

    /** One SGR sequence's parameters ("1;38;5;208"). */
    fun apply(params: String): Pen {
        val p = params.split(';', ':').map { it.toIntOrNull() ?: 0 }.ifEmpty { listOf(0) }
        var pen = this
        var i = 0
        while (i < p.size) {
            when (val c = p[i]) {
                0 -> pen = Pen()
                1 -> pen = pen.copy(bold = true)
                2 -> pen = pen.copy(dim = true)
                3 -> pen = pen.copy(italic = true)
                4 -> pen = pen.copy(underline = true)
                7 -> pen = pen.copy(reverse = true)
                22 -> pen = pen.copy(bold = false, dim = false)
                23 -> pen = pen.copy(italic = false)
                24 -> pen = pen.copy(underline = false)
                27 -> pen = pen.copy(reverse = false)
                in 30..37 -> pen = pen.copy(fg = BASIC[c - 30])
                39 -> pen = pen.copy(fg = null)
                in 40..47 -> pen = pen.copy(bg = BASIC[c - 40])
                49 -> pen = pen.copy(bg = null)
                in 90..97 -> pen = pen.copy(fg = BASIC[c - 90 + 8])
                in 100..107 -> pen = pen.copy(bg = BASIC[c - 100 + 8])
                38, 48 -> {
                    val color = when (p.getOrNull(i + 1)) {
                        5 -> p.getOrNull(i + 2)?.let { xterm256(it.coerceIn(0, 255)) }.also { i += 2 }
                        2 -> if (i + 4 < p.size) Color(p[i + 2].coerceIn(0, 255), p[i + 3].coerceIn(0, 255), p[i + 4].coerceIn(0, 255)).also { i += 4 } else null
                        else -> null
                    }
                    pen = if (c == 38) pen.copy(fg = color) else pen.copy(bg = color)
                }
            }
            i++
        }
        return pen
    }
}

private val SGR = Regex("\u001b\\[([0-9;:]*)m")

/**
 * A tmux screen (lines with SGR colour sequences, spec §16.1) as styled text. [cursor] (column, row), when given,
 * is shown as a block.
 */
fun ansiText(text: String, defaultFg: Color, defaultBg: Color, cursor: Pair<Int, Int>? = null): AnnotatedString =
    buildAnnotatedString {
        var pen = Pen()
        text.split('\n').forEachIndexed { row, line ->
            if (row > 0) append('\n')
            var col = 0
            var at = 0
            fun put(chunk: String) {
                val cursorHere = cursor != null && cursor.second == row && cursor.first in col until col + chunk.length
                if (!cursorHere) {
                    withStyle(pen.style(defaultFg, defaultBg)) { append(chunk) }
                } else {
                    val k = cursor!!.first - col
                    withStyle(pen.style(defaultFg, defaultBg)) { append(chunk.substring(0, k)) }
                    withStyle(pen.copy(reverse = !pen.reverse).style(defaultFg, defaultBg)) { append(chunk[k]) }
                    withStyle(pen.style(defaultFg, defaultBg)) { append(chunk.substring(k + 1)) }
                }
                col += chunk.length
            }
            for (m in SGR.findAll(line)) {
                if (m.range.first > at) put(line.substring(at, m.range.first))
                pen = pen.apply(m.groupValues[1])
                at = m.range.last + 1
            }
            if (at < line.length) put(line.substring(at))
            if (cursor != null && cursor.second == row && cursor.first >= col) {
                // the cursor is past the text on its line: pad to it and show it there
                withStyle(pen.style(defaultFg, defaultBg)) { append(" ".repeat(cursor.first - col)) }
                withStyle(pen.copy(reverse = true).style(defaultFg, defaultBg)) { append(' ') }
            }
        }
    }
