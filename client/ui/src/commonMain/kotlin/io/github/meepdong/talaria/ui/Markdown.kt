package io.github.meepdong.talaria.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

/** The Markdown agents write most: paragraphs, headings, lists, code blocks and inline styles. */
sealed interface MdBlock {
    data class Paragraph(val text: String) : MdBlock
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Bullet(val marker: String, val text: String, val indent: Int) : MdBlock
    data class Code(val text: String) : MdBlock
}

private val BULLET = Regex("""^(\s*)([-*+]|\d+[.)])\s+(.*)$""")
private val HEADING = Regex("""^(#{1,6})\s+(.*)$""")

fun parseMarkdown(text: String): List<MdBlock> {
    val out = mutableListOf<MdBlock>()
    val para = StringBuilder()
    fun flush() {
        if (para.isNotBlank()) out += MdBlock.Paragraph(para.toString().trim())
        para.clear()
    }
    val lines = text.replace("\r\n", "\n").split("\n")
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        if (line.trimStart().startsWith("```")) {
            flush()
            val code = StringBuilder()
            i++
            while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                if (code.isNotEmpty()) code.append('\n')
                code.append(lines[i])
                i++
            }
            out += MdBlock.Code(code.toString())
            i++ // the closing fence, if any
            continue
        }
        val heading = HEADING.find(line)
        val bullet = BULLET.find(line)
        when {
            line.isBlank() -> flush()
            heading != null -> {
                flush()
                out += MdBlock.Heading(heading.groupValues[1].length, heading.groupValues[2])
            }
            bullet != null -> {
                flush()
                val marker = bullet.groupValues[2].let { if (it.first().isDigit()) it else "•" }
                out += MdBlock.Bullet(marker, bullet.groupValues[3], bullet.groupValues[1].length / 2)
            }
            else -> {
                if (para.isNotEmpty()) para.append('\n')
                para.append(line)
            }
        }
        i++
    }
    flush()
    return out
}

/** **bold**, *italic* or _italic_, `code`; anything unmatched stays as typed. */
fun inlineMarkdown(text: String, codeBackground: androidx.compose.ui.graphics.Color): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < text.length) {
        val c = text[i]
        val tick = if (c == '`') text.indexOf('`', i + 1) else -1
        when {
            tick > i -> {
                withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBackground)) { append(text, i + 1, tick) }
                i = tick + 1
            }
            text.startsWith("**", i) && text.indexOf("**", i + 2).let { it > i + 2 } -> {
                val end = text.indexOf("**", i + 2)
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(inlineMarkdown(text.substring(i + 2, end), codeBackground)) }
                i = end + 2
            }
            (c == '*' || c == '_') && i + 1 < text.length && !text[i + 1].isWhitespace() &&
                text.indexOf(c, i + 1).let { it > i + 1 && !text[it - 1].isWhitespace() } &&
                (i == 0 || !text[i - 1].isLetterOrDigit()) -> {
                val end = text.indexOf(c, i + 1)
                withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(text, i + 1, end) }
                i = end + 1
            }
            else -> {
                append(c)
                i++
            }
        }
    }
}

@Composable
fun MarkdownText(text: String, modifier: Modifier = Modifier) {
    val blocks = remember(text) { parseMarkdown(text) }
    val codeBg = MaterialTheme.colorScheme.surfaceVariant
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        blocks.forEach { b ->
            when (b) {
                is MdBlock.Paragraph -> Text(inlineMarkdown(b.text, codeBg), style = MaterialTheme.typography.bodyLarge)
                is MdBlock.Heading -> Text(inlineMarkdown(b.text, codeBg),
                    style = if (b.level <= 2) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium)
                is MdBlock.Bullet -> Row(Modifier.padding(start = (12 * b.indent).dp)) {
                    Text(b.marker, Modifier.width(24.dp), style = MaterialTheme.typography.bodyLarge)
                    Text(inlineMarkdown(b.text, codeBg), style = MaterialTheme.typography.bodyLarge)
                }
                is MdBlock.Code -> Surface(color = codeBg, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Text(b.text, Modifier.horizontalScroll(rememberScrollState()).padding(10.dp),
                        fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium, softWrap = false)
                }
            }
        }
    }
}
