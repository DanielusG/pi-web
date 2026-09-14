package app.pimobile.ui.markdown

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pimobile.ui.theme.GeistMono
import app.pimobile.ui.theme.Pi
import app.pimobile.ui.theme.PiIcons
import kotlinx.coroutines.delay

sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Paragraph(val text: String) : MdBlock
    data class Code(val lang: String, val code: String) : MdBlock
    data class Items(val ordered: Boolean, val start: Int, val items: List<String>) : MdBlock
    data class Quote(val text: String) : MdBlock
    data class Table(val rows: List<List<String>>) : MdBlock
    data object Rule : MdBlock
}

private val HEADING = Regex("^(#{1,6})\\s+(.*)$")
private val RULE = Regex("^([-*_])(\\s*\\1){2,}\\s*$")
private val BULLET = Regex("^\\s*[-*+]\\s+(.*)$")
private val ORDERED = Regex("^\\s*(\\d+)[.)]\\s+(.*)$")
private val TABLE_SEPARATOR = Regex("^\\s*\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)*\\|?\\s*$")

/** Small block parser covering what coding-agent replies actually use. */
fun parseMarkdown(source: String): List<MdBlock> {
    val lines = source.replace("\r\n", "\n").split('\n')
    val blocks = mutableListOf<MdBlock>()
    val paragraph = StringBuilder()
    fun flush() {
        if (paragraph.isNotBlank()) blocks += MdBlock.Paragraph(paragraph.toString().trim())
        paragraph.clear()
    }

    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trimStart()
        when {
            trimmed.startsWith("```") || trimmed.startsWith("~~~") -> {
                flush()
                val fence = trimmed.take(3)
                val lang = trimmed.drop(3).trim()
                val code = StringBuilder()
                i++
                while (i < lines.size && !lines[i].trimStart().startsWith(fence)) {
                    if (code.isNotEmpty()) code.append('\n')
                    code.append(lines[i])
                    i++
                }
                blocks += MdBlock.Code(lang, code.toString())
                i++ // skip closing fence (or run past the end while streaming)
            }
            trimmed.isEmpty() -> { flush(); i++ }
            HEADING.matches(trimmed) -> {
                flush()
                val m = HEADING.find(trimmed)!!
                blocks += MdBlock.Heading(m.groupValues[1].length, m.groupValues[2].trimEnd('#', ' '))
                i++
            }
            RULE.matches(trimmed) -> { flush(); blocks += MdBlock.Rule; i++ }
            trimmed.startsWith(">") -> {
                flush()
                val quote = StringBuilder()
                while (i < lines.size && lines[i].trimStart().startsWith(">")) {
                    if (quote.isNotEmpty()) quote.append('\n')
                    quote.append(lines[i].trimStart().removePrefix(">").removePrefix(" "))
                    i++
                }
                blocks += MdBlock.Quote(quote.toString())
            }
            trimmed.startsWith("|") && i + 1 < lines.size && TABLE_SEPARATOR.matches(lines[i + 1]) -> {
                flush()
                val rows = mutableListOf(splitRow(trimmed))
                i += 2
                while (i < lines.size && lines[i].trimStart().startsWith("|")) {
                    rows += splitRow(lines[i].trim())
                    i++
                }
                blocks += MdBlock.Table(rows)
            }
            BULLET.matches(line) || ORDERED.matches(line) -> {
                flush()
                val ordered = ORDERED.matches(line)
                val start = if (ordered) ORDERED.find(line)!!.groupValues[1].toIntOrNull() ?: 1 else 1
                val items = mutableListOf<String>()
                while (i < lines.size) {
                    val current = lines[i]
                    val bullet = BULLET.find(current)
                    val number = ORDERED.find(current)
                    when {
                        !ordered && bullet != null -> items += bullet.groupValues[1]
                        ordered && number != null -> items += number.groupValues[2]
                        // indented continuation of the previous item
                        current.startsWith("  ") && current.isNotBlank() && items.isNotEmpty() ->
                            items[items.lastIndex] = items.last() + "\n" + current.trim()
                        else -> break
                    }
                    i++
                }
                blocks += MdBlock.Items(ordered, start, items)
            }
            else -> {
                if (paragraph.isNotEmpty()) paragraph.append('\n')
                paragraph.append(line)
                i++
            }
        }
    }
    flush()
    return blocks
}

private fun splitRow(row: String): List<String> =
    row.trim().removePrefix("|").removeSuffix("|").split('|').map { it.trim() }

private val SmallerEm = TextUnit(0.9f, TextUnitType.Em)

fun inlineMarkdown(text: String, codeBackground: Color, linkColor: Color): AnnotatedString =
    buildAnnotatedString {
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '`' -> {
                    val end = text.indexOf('`', i + 1)
                    if (end > i) {
                        withStyle(SpanStyle(fontFamily = GeistMono, background = codeBackground, fontSize = SmallerEm)) {
                            append(" ")
                            append(text, i + 1, end)
                            append(" ")
                        }
                        i = end + 1
                    } else { append(c); i++ }
                }
                text.startsWith("**", i) -> {
                    val end = text.indexOf("**", i + 2)
                    if (end > i + 2) {
                        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) {
                            append(inlineMarkdown(text.substring(i + 2, end), codeBackground, linkColor))
                        }
                        i = end + 2
                    } else { append("**"); i += 2 }
                }
                c == '*' && i + 1 < text.length && !text[i + 1].isWhitespace() -> {
                    val end = text.indexOf('*', i + 1)
                    if (end > i + 1) {
                        withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(text, i + 1, end) }
                        i = end + 1
                    } else { append(c); i++ }
                }
                c == '[' -> {
                    val close = text.indexOf("](", i)
                    val paren = if (close > i) text.indexOf(')', close + 2) else -1
                    if (close > i && paren > close && !text.substring(i + 1, close).contains('\n')) {
                        val url = text.substring(close + 2, paren)
                        val linkStyle = SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)
                        withLink(LinkAnnotation.Url(url, TextLinkStyles(linkStyle))) {
                            append(text.substring(i + 1, close))
                        }
                        i = paren + 1
                    } else { append(c); i++ }
                }
                else -> { append(c); i++ }
            }
        }
    }

@Composable
fun Markdown(text: String, modifier: Modifier = Modifier) {
    val blocks = remember(text) { parseMarkdown(text) }
    val t = Pi.tokens
    val typography = MaterialTheme.typography
    val inline = { s: String -> inlineMarkdown(s, t.muted, t.accent) }
    val body = typography.bodyLarge.copy(color = t.text)

    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        for (block in blocks) {
            when (block) {
                is MdBlock.Paragraph -> Text(inline(block.text), style = body)
                is MdBlock.Heading -> Text(
                    inline(block.text),
                    style = when (block.level) {
                        1 -> typography.titleLarge
                        2 -> typography.titleMedium.copy(fontSize = 17.sp)
                        else -> typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
                    },
                    color = t.text,
                    modifier = Modifier.padding(top = 6.dp),
                )
                is MdBlock.Code -> CodeBlock(block.lang, block.code)
                is MdBlock.Items -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    block.items.forEachIndexed { index, item ->
                        Row {
                            Text(
                                if (block.ordered) "${block.start + index}." else "•",
                                style = body,
                                color = t.textTertiary,
                                modifier = Modifier.width(if (block.ordered) 24.dp else 16.dp),
                            )
                            Text(inline(item), style = body)
                        }
                    }
                }
                is MdBlock.Quote -> Row(Modifier.height(IntrinsicSize.Min)) {
                    Box(
                        Modifier
                            .width(2.dp)
                            .fillMaxHeight()
                            .background(t.borderStrong),
                    )
                    Text(
                        inline(block.text),
                        style = body.copy(color = t.textSecondary),
                        modifier = Modifier.padding(start = 14.dp),
                    )
                }
                is MdBlock.Table -> TableBlock(block.rows, inline)
                MdBlock.Rule -> HorizontalDivider(color = t.border)
            }
        }
    }
}

@Composable
fun CodeBlock(lang: String, code: String, modifier: Modifier = Modifier, header: Boolean = true) {
    val t = Pi.tokens
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(t.code)
            .border(1.dp, t.border, shape),
    ) {
        if (header) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 14.dp, end = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    lang.ifEmpty { "text" },
                    style = MaterialTheme.typography.labelSmall,
                    color = t.textTertiary,
                    modifier = Modifier.weight(1f),
                )
                CopyButton(code)
            }
        }
        Text(
            code,
            style = TextStyle(fontFamily = GeistMono, fontSize = 13.sp, lineHeight = 20.sp, color = t.text),
            softWrap = false,
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(start = 14.dp, end = 14.dp, top = if (header) 0.dp else 12.dp, bottom = 12.dp),
        )
    }
}

@Composable
private fun CopyButton(text: String) {
    val t = Pi.tokens
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1_500)
            copied = false
        }
    }
    IconButton(
        onClick = {
            clipboard.setText(AnnotatedString(text))
            copied = true
        },
        modifier = Modifier.size(36.dp),
    ) {
        Icon(
            if (copied) PiIcons.Check else PiIcons.Copy,
            contentDescription = if (copied) "Copied" else "Copy",
            tint = t.textTertiary,
            modifier = Modifier.size(15.dp),
        )
    }
}

@Composable
private fun TableBlock(rows: List<List<String>>, inline: (String) -> AnnotatedString) {
    val t = Pi.tokens
    val typography = MaterialTheme.typography
    val columns = rows.maxOf { it.size }
    val natural = (0 until columns).map { column ->
        (rows.maxOf { it.getOrNull(column)?.length ?: 0 } * 8 + 28).coerceIn(64, 280).dp
    }
    val naturalWidth = natural.fold(0.dp) { total, width -> total + width }
    val shape = RoundedCornerShape(12.dp)
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .border(1.dp, t.border, shape),
    ) {
        // Measured outside the scroll (which has unbounded width): narrow
        // tables stretch to the full width, wide ones scroll.
        val available = maxWidth - 2.dp
        val widths = if (naturalWidth < available) natural.map { it * (available / naturalWidth) } else natural
        val tableWidth = widths.fold(0.dp) { total, width -> total + width }
        Column(Modifier.horizontalScroll(rememberScrollState())) {
            rows.forEachIndexed { rowIndex, row ->
                Row(Modifier.background(if (rowIndex == 0) t.code else Color.Transparent)) {
                    widths.forEachIndexed { column, width ->
                        Text(
                            inline(row.getOrNull(column).orEmpty()),
                            style = if (rowIndex == 0) typography.labelMedium.copy(color = t.textSecondary)
                            else typography.bodyMedium.copy(color = t.text),
                            modifier = Modifier
                                .width(width)
                                .padding(horizontal = 12.dp, vertical = 9.dp),
                        )
                    }
                }
                if (rowIndex < rows.lastIndex) HorizontalDivider(Modifier.width(tableWidth), color = t.border)
            }
        }
    }
}
