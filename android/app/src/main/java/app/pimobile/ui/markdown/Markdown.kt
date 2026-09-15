package app.pimobile.ui.markdown

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pimobile.data.FilePaths
import app.pimobile.ui.theme.GeistMono
import app.pimobile.ui.theme.Pi
import app.pimobile.ui.theme.PiIcons
import com.hrm.latex.renderer.Latex
import com.hrm.latex.renderer.measure.LatexMeasurerState
import com.hrm.latex.renderer.measure.rememberLatexMeasurer
import com.hrm.latex.renderer.model.LatexConfig
import com.hrm.latex.renderer.model.LatexTheme
import com.mikepenz.markdown.annotator.annotatorSettings
import com.mikepenz.markdown.annotator.buildMarkdownAnnotatedString
import com.mikepenz.markdown.compose.LocalMarkdownAnnotator
import com.mikepenz.markdown.compose.LocalMarkdownComponents
import com.mikepenz.markdown.compose.LocalMarkdownInlineContent
import com.mikepenz.markdown.compose.LocalMarkdownTypography
import com.mikepenz.markdown.compose.MarkdownElement
import com.mikepenz.markdown.compose.components.MarkdownComponentModel
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownBulletList
import com.mikepenz.markdown.compose.elements.MarkdownCodeBlock
import com.mikepenz.markdown.compose.elements.MarkdownCodeFence
import com.mikepenz.markdown.compose.elements.MarkdownHeader
import com.mikepenz.markdown.compose.elements.MarkdownOrderedList
import com.mikepenz.markdown.compose.elements.MarkdownText
import com.mikepenz.markdown.compose.elements.listDepth
import com.mikepenz.markdown.model.DefaultMarkdownColors
import com.mikepenz.markdown.model.DefaultMarkdownTypography
import com.mikepenz.markdown.model.MarkdownAnnotator
import com.mikepenz.markdown.model.State
import com.mikepenz.markdown.model.markdownAnimations
import com.mikepenz.markdown.model.markdownAnnotator
import com.mikepenz.markdown.model.markdownInlineContent
import com.mikepenz.markdown.model.markdownPadding
import kotlinx.coroutines.delay
import org.intellij.markdown.IElementType
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.getTextInNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.flavours.gfm.GFMTokenTypes
import org.intellij.markdown.parser.MarkdownParser
import com.mikepenz.markdown.compose.Markdown as LibraryMarkdown

/** Inline-content key of a formula: this prefix followed by the formula. */
private const val MATH_KEY = "math:"

private val InlineCodeSize = TextUnit(0.9f, TextUnitType.Em)

private val QuoteMarkup = setOf(MarkdownTokenTypes.BLOCK_QUOTE, MarkdownTokenTypes.EOL, MarkdownTokenTypes.WHITE_SPACE)

/**
 * Turns off the LaTeX renderer's precise glyph bounds. On Android they write the whole font file to
 * disk and reload it for every glyph run, with no cache (GlyphBoundsProvider.android.kt in
 * huarangmeng/latex 1.5.4): a document with a few dozen formulas took seconds to open. The renderer
 * then uses text-layout metrics, as it does until its font bytes have loaded.
 *
 * Sets the library's private "bytes already loaded" flag, so it must run before the first formula is
 * composed; proguard-rules.pro keeps the field.
 */
fun disablePreciseGlyphBounds() {
    try {
        Class.forName("com.hrm.latex.renderer.model.LatexFontFamilyKt")
            .getDeclaredField("fontBytesLoaded")
            .apply { isAccessible = true }
            .setBoolean(null, true)
    } catch (e: ReflectiveOperationException) {
        // A library update renamed the flag: formulas still render, only slower.
        Log.w("Markdown", "Could not disable precise glyph bounds", e)
    }
}

/**
 * GitHub-flavored Markdown with LaTeX math, like pi-web's MarkdownBody: parsed by the JetBrains
 * parser and drawn by multiplatform-markdown-renderer, formulas drawn natively by huarangmeng/latex.
 * `$…$` and `\(…\)` render inline, `$$…$$` and `\[…\]` as centered blocks.
 *
 * Links resolving to a local file (see [FilePaths.resolveHref]) call [onOpenFile] instead of
 * opening a browser.
 */
@Composable
fun Markdown(
    text: String,
    modifier: Modifier = Modifier,
    /** Directory that relative links resolve against. */
    baseDir: String? = null,
    /** Relative links must stay inside this directory. */
    relativeRoot: String? = baseDir,
    onOpenFile: ((String) -> Unit)? = null,
) {
    MarkdownDocument(text, baseDir, relativeRoot, onOpenFile) { blocks, block ->
        Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            blocks.forEach { block(it) }
        }
    }
}

/** [Markdown] for whole documents: only the blocks on screen are composed and their formulas measured. */
@Composable
fun LazyMarkdown(
    text: String,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
    baseDir: String? = null,
    relativeRoot: String? = baseDir,
    onOpenFile: ((String) -> Unit)? = null,
) {
    MarkdownDocument(text, baseDir, relativeRoot, onOpenFile) { blocks, block ->
        LazyColumn(modifier, contentPadding = contentPadding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(blocks) { block(it) }
        }
    }
}

/** Parses [text] and sets up styles, links and math; [layout] places the top-level blocks. */
@Composable
private fun MarkdownDocument(
    text: String,
    baseDir: String?,
    relativeRoot: String?,
    onOpenFile: ((String) -> Unit)?,
    layout: @Composable (blocks: List<ASTNode>, block: @Composable (ASTNode) -> Unit) -> Unit,
) {
    val t = Pi.tokens
    val typography = MaterialTheme.typography
    val body = typography.bodyLarge.copy(color = t.text)

    // Parsed synchronously: the library's async parse leaves the item empty for a frame (the chat
    // list jumps) and would flash on every streamed chunk.
    val parser = remember { MarkdownParser(GFMFlavourDescriptor()) }
    val state = remember(text) {
        val source = prepareMathMarkdown(text)
        State.Success(parser.buildMarkdownTreeFromString(source), source, linksLookedUp = false)
    }

    val inlineMath = remember(t.text, body.fontSize) {
        LatexConfig(fontSize = body.fontSize, theme = LatexTheme.light(color = t.text))
    }
    val displayMath = remember(inlineMath) { inlineMath.copy(fontSize = body.fontSize * 1.15f) }
    val measurer = rememberLatexMeasurer(inlineMath)

    val currentOpenFile by rememberUpdatedState(onOpenFile)
    val systemUriHandler = LocalUriHandler.current
    val uriHandler = remember(systemUriHandler, baseDir, relativeRoot) {
        object : UriHandler {
            override fun openUri(uri: String) {
                val openFile = currentOpenFile
                val path = if (openFile != null) FilePaths.resolveHref(uri, baseDir, relativeRoot) else null
                if (openFile != null && path != null) openFile(path) else systemUriHandler.openUri(uri)
            }
        }
    }

    val colors = remember(t) {
        DefaultMarkdownColors(
            text = t.text,
            codeBackground = t.code,
            inlineCodeBackground = t.muted,
            dividerColor = t.border,
            tableBackground = t.code,
        )
    }
    val markdownTypography = remember(t, typography) {
        val heading = typography.titleSmall.copy(fontWeight = FontWeight.SemiBold, color = t.text)
        val marker = body.copy(color = t.textTertiary)
        DefaultMarkdownTypography(
            h1 = typography.titleLarge.copy(color = t.text),
            h2 = typography.titleMedium.copy(fontSize = 17.sp, color = t.text),
            h3 = heading,
            h4 = heading,
            h5 = heading,
            h6 = heading,
            text = body,
            code = TextStyle(fontFamily = GeistMono, fontSize = 13.sp, lineHeight = 20.sp, color = t.text),
            inlineCode = TextStyle(fontFamily = GeistMono, fontSize = InlineCodeSize),
            quote = body.copy(color = t.textSecondary),
            paragraph = body,
            ordered = marker,
            bullet = marker,
            list = body,
            textLink = TextLinkStyles(SpanStyle(color = t.accent, textDecoration = TextDecoration.Underline)),
            table = typography.bodyMedium.copy(color = t.text),
        )
    }

    val components = remember(t, displayMath) {
        markdownComponents(
            codeFence = { model ->
                MarkdownCodeFence(model.content, model.node) { code, language, _ ->
                    if (language.equals("math", ignoreCase = true)) DisplayMath(code, displayMath)
                    else CodeBlock(language.orEmpty(), code)
                }
            },
            codeBlock = { model ->
                MarkdownCodeBlock(model.content, model.node) { code, language, _ -> CodeBlock(language.orEmpty(), code) }
            },
            heading1 = { Heading(it, it.typography.h1) },
            heading2 = { Heading(it, it.typography.h2) },
            heading3 = { Heading(it, it.typography.h3) },
            heading4 = { Heading(it, it.typography.h4) },
            heading5 = { Heading(it, it.typography.h5) },
            heading6 = { Heading(it, it.typography.h6) },
            setextHeading1 = { Heading(it, it.typography.h1, MarkdownTokenTypes.SETEXT_CONTENT) },
            setextHeading2 = { Heading(it, it.typography.h2, MarkdownTokenTypes.SETEXT_CONTENT) },
            blockQuote = { BlockQuote(it, t.borderStrong) },
            orderedList = {
                MarkdownOrderedList(it.content, it.node, depth = it.listDepth, markerModifier = { Modifier.widthIn(min = 24.dp) })
            },
            unorderedList = {
                MarkdownBulletList(it.content, it.node, depth = it.listDepth, markerModifier = { Modifier.widthIn(min = 16.dp) })
            },
            horizontalRule = { HorizontalDivider(color = t.border) },
            table = { TableBlock(it.content, it.node) },
            // Raw HTML blocks are shown as source.
            custom = { type, model ->
                if (type == MarkdownElementTypes.HTML_BLOCK) {
                    MarkdownText(model.node.getTextInNode(model.content).toString(), style = model.typography.paragraph)
                }
            },
        )
    }

    CompositionLocalProvider(LocalUriHandler provides uriHandler) {
        LibraryMarkdown(
            state = state,
            colors = colors,
            typography = markdownTypography,
            padding = markdownPadding(block = 0.dp, list = 0.dp, listItemTop = 3.dp, listItemBottom = 3.dp, listIndent = 0.dp),
            components = components,
            // Streaming grows the text on every chunk; the default size animation fights the auto-scroll.
            animations = markdownAnimations(animateTextSize = { this }),
            success = { success, blockComponents, _ ->
                val blocks = remember(success) { success.node.children.filter { it.type != MarkdownTokenTypes.EOL } }
                layout(blocks) { node ->
                    MathScope(success.content, node, measurer, inlineMath) {
                        MarkdownElement(node, blockComponents, success.content, includeSpacer = false)
                    }
                }
            },
        )
    }
}

/**
 * Measures the inline formulas of one top-level block, when it is composed, and hands them to the
 * block's text: inline placeholders need each formula's size before the paragraph is laid out.
 */
@Composable
private fun MathScope(
    content: String,
    node: ASTNode,
    measurer: LatexMeasurerState,
    config: LatexConfig,
    block: @Composable () -> Unit,
) {
    val formulas = remember(content, node) { buildSet { collectMath(content, node, this) } }
    if (formulas.isEmpty()) {
        block()
    } else {
        val mathContent = remember(formulas, measurer, config) {
            formulas.mapNotNull { latex ->
                measurer.inlineContent(latex, config)?.let { inline -> MATH_KEY + latex to inline }
            }.toMap()
        }
        val annotator = remember(mathContent) { mathAnnotator(mathContent.keys) }
        CompositionLocalProvider(
            LocalMarkdownAnnotator provides annotator,
            LocalMarkdownInlineContent provides markdownInlineContent(mathContent),
            content = block,
        )
    }
}

/** Draws INLINE_MATH / BLOCK_MATH nodes as the inline content in [measured] (keys are MATH_KEY + formula). */
private fun mathAnnotator(measured: Set<String>): MarkdownAnnotator =
    markdownAnnotator { content, child ->
        if (child.type != GFMElementTypes.INLINE_MATH && child.type != GFMElementTypes.BLOCK_MATH) {
            return@markdownAnnotator false
        }
        val latex = mathSource(content, child)
        // A formula the renderer cannot measure stays readable as source.
        if ((MATH_KEY + latex) in measured) appendInlineContent(MATH_KEY + latex, latex)
        else append(child.getTextInNode(content))
        true
    }

@Composable
private fun DisplayMath(latex: String, config: LatexConfig) {
    // Wide formulas scroll sideways instead of shrinking.
    Box(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Latex(latex = latex, config = config)
    }
}

@Composable
private fun Heading(
    model: MarkdownComponentModel,
    style: TextStyle,
    contentChildType: IElementType = MarkdownTokenTypes.ATX_CONTENT,
) {
    Box(Modifier.padding(top = 6.dp)) {
        MarkdownHeader(model.content, model.node, style, contentChildType)
    }
}

@Composable
private fun BlockQuote(model: MarkdownComponentModel, bar: Color) {
    val components = LocalMarkdownComponents.current
    val typography = LocalMarkdownTypography.current
    val quoted = remember(typography) {
        (typography as? DefaultMarkdownTypography)?.copy(paragraph = typography.quote) ?: typography
    }
    CompositionLocalProvider(LocalMarkdownTypography provides quoted) {
        // The bar is drawn rather than measured with IntrinsicSize: a table inside can't answer intrinsics.
        Column(
            Modifier
                .drawBehind { drawRect(bar, size = Size(2.dp.toPx(), size.height)) }
                .padding(start = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            model.node.children.forEach { child ->
                if (child.type !in QuoteMarkup) MarkdownElement(child, components, model.content, includeSpacer = false)
            }
        }
    }
}

private fun collectMath(content: String, node: ASTNode, into: MutableSet<String>) {
    if (node.type == GFMElementTypes.INLINE_MATH || node.type == GFMElementTypes.BLOCK_MATH) {
        into += mathSource(content, node)
    } else {
        node.children.forEach { collectMath(content, it, into) }
    }
}

/** The formula between the `$` / `$$` delimiters of an INLINE_MATH or BLOCK_MATH node. */
private fun mathSource(content: String, node: ASTNode): String {
    val open = node.children.firstOrNull()
    val close = node.children.lastOrNull()
    return if (open != null && close != null && open !== close &&
        open.type == GFMTokenTypes.DOLLAR && close.type == GFMTokenTypes.DOLLAR
    ) {
        content.substring(open.endOffset, close.startOffset).trim()
    } else {
        node.getTextInNode(content).toString().trim('$', ' ')
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
private fun TableBlock(content: String, node: ASTNode) {
    val t = Pi.tokens
    val typography = MaterialTheme.typography
    val rows = remember(node) {
        node.children
            .filter { it.type == GFMElementTypes.HEADER || it.type == GFMElementTypes.ROW }
            .map { row -> row.children.filter { it.type == GFMTokenTypes.CELL } }
    }
    if (rows.isEmpty()) return
    // Cells get the same inline rendering as paragraphs: emphasis, code, links and math.
    val settings = annotatorSettings()
    val inlineContent = LocalMarkdownInlineContent.current.inlineContent
    val headerStyle = typography.labelMedium.copy(color = t.textSecondary)
    val cellStyle = typography.bodyMedium.copy(color = t.text)

    val columns = rows.maxOf { it.size }
    val natural = (0 until columns).map { column ->
        val chars = rows.maxOf { row -> row.getOrNull(column)?.let { it.endOffset - it.startOffset } ?: 0 }
        (chars * 8 + 28).coerceIn(64, 280).dp
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
                val style = if (rowIndex == 0) headerStyle else cellStyle
                Row(Modifier.background(if (rowIndex == 0) t.code else Color.Transparent)) {
                    widths.forEachIndexed { column, width ->
                        val cell = row.getOrNull(column)
                        Text(
                            cell?.let { content.buildMarkdownAnnotatedString(it, style, settings).trimmed() } ?: AnnotatedString(""),
                            style = style,
                            inlineContent = inlineContent,
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

/** Drops the padding spaces around a cell's content, keeping its styles. */
private fun AnnotatedString.trimmed(): AnnotatedString {
    val start = text.indexOfFirst { !it.isWhitespace() }
    if (start == -1) return AnnotatedString("")
    return subSequence(start, text.indexOfLast { !it.isWhitespace() } + 1)
}
