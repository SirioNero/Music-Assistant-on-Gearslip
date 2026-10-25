package it.xonos.ma.phone.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

/**
 * Un testo in Markdown come lo scrive Music Assistant nei suoi moduli: paragrafi, elenchi, titoli,
 * grassetto, corsivo, codice e link (si aprono nel browser). Il resto resta com'è.
 */
@Composable
internal fun Markdown(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    val link = MaterialTheme.colorScheme.primary
    val blocks = remember(text, link) { markdownBlocks(text, link) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (block in blocks) {
            when (block) {
                is MdBlock.Heading -> Text(block.text, style = MaterialTheme.typography.titleSmall)
                is MdBlock.Paragraph -> Text(block.text, style = style, color = color)
                is MdBlock.Bullet -> Row {
                    Text(block.mark, style = style, color = color)
                    Spacer(Modifier.width(8.dp))
                    Text(block.text, style = style, color = color, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

internal sealed interface MdBlock {
    class Heading(val text: AnnotatedString) : MdBlock
    class Paragraph(val text: AnnotatedString) : MdBlock
    class Bullet(val mark: String, val text: AnnotatedString) : MdBlock
}

private val BULLET = Regex("""^\s*([-*+]|\d+[.)])\s+(.*)$""")
private val HEADING = Regex("""^\s*#{1,6}\s+(.*)$""")

/** I blocchi del testo: le righe vicine fanno un paragrafo, una riga vuota lo chiude. */
internal fun markdownBlocks(text: String, link: Color): List<MdBlock> {
    val blocks = mutableListOf<MdBlock>()
    val paragraph = mutableListOf<String>()
    fun flush() {
        if (paragraph.isNotEmpty()) blocks += MdBlock.Paragraph(inline(paragraph.joinToString(" ") { it.trim() }, link))
        paragraph.clear()
    }
    for (line in text.replace("\r", "").split('\n')) {
        val heading = HEADING.find(line)
        val bullet = BULLET.find(line)
        when {
            line.isBlank() -> flush()
            heading != null -> {
                flush()
                blocks += MdBlock.Heading(inline(heading.groupValues[1], link))
            }
            bullet != null -> {
                flush()
                val mark = bullet.groupValues[1].let { if (it[0].isDigit()) it else "•" }
                blocks += MdBlock.Bullet(mark, inline(bullet.groupValues[2], link))
            }
            else -> paragraph += line
        }
    }
    flush()
    return blocks
}

private val INLINE = Regex("""\[([^\]]+)]\(([^)\s]+)\)|<(https?://[^>\s]+)>|\*\*(.+?)\*\*|__(.+?)__|`([^`]+)`|\*([^*\s][^*]*)\*|(?<![\w])_([^_\s][^_]*)_(?![\w])|(https?://[^\s)]+)""")

/** Grassetto, corsivo, codice e link dentro una riga. */
internal fun inline(text: String, link: Color): AnnotatedString = buildAnnotatedString {
    val linkStyle = TextLinkStyles(SpanStyle(color = link, textDecoration = TextDecoration.Underline))
    var at = 0
    for (match in INLINE.findAll(text)) {
        append(text.substring(at, match.range.first))
        val g = match.groupValues
        when {
            g[1].isNotEmpty() -> withLink(LinkAnnotation.Url(g[2], linkStyle)) { append(inline(g[1], link)) }
            g[3].isNotEmpty() -> withLink(LinkAnnotation.Url(g[3], linkStyle)) { append(g[3]) }
            g[4].isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(inline(g[4], link)) }
            g[5].isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(inline(g[5], link)) }
            g[6].isNotEmpty() -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(g[6]) }
            g[7].isNotEmpty() -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(inline(g[7], link)) }
            g[8].isNotEmpty() -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(inline(g[8], link)) }
            else -> withLink(LinkAnnotation.Url(g[9], linkStyle)) { append(g[9]) }
        }
        at = match.range.last + 1
    }
    append(text.substring(at))
}
