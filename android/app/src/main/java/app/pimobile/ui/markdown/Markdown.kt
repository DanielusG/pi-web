package app.pimobile.ui.markdown

import androidx.compose.foundation.ScrollState
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
import androidx.compose.foundation.text.InlineTextContent
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Density
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
import kotlin.math.roundToInt
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

/** How a formula inside text is drawn; its inline-content key is the prefix followed by the formula. */
private enum class MathKind(val prefix: String) {
    /** `$…$`: text style. */
    INLINE("math:"),
    /** `$$…$$` inside a paragraph: display style. */
    DISPLAY("math-display:"),
    /** Any formula in a table cell: text style, at the cell's text size. */
    CELL("math-cell:"),
}

/** Display formulas wider than the screen shrink down to this, then scroll. */
private const val MIN_DISPLAY_SCALE = 0.75f

/** Where Compose keeps inline-content ids in an AnnotatedString (INLINE_CONTENT_TAG, internal to foundation). */
private const val INLINE_CONTENT_TAG = "androidx.compose.foundation.text.inlineContent"

private val InlineCodeSize = TextUnit(0.9f, TextUnitType.Em)

private val QuoteMarkup = setOf(MarkdownTokenTypes.BLOCK_QUOTE, MarkdownTokenTypes.EOL, MarkdownTokenTypes.WHITE_SPACE)

/**
 * Starts loading the LaTeX renderer's font files, which it only does once a formula is composed:
 * formulas laid out before they arrive are laid out again with precise glyph bounds.
 */
@Composable
fun PreloadLatexFonts() {
    rememberLatexMeasurer()
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
    val cellMath = remember(inlineMath, typography) { inlineMath.copy(fontSize = typography.bodyMedium.fontSize) }
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
                    MathScope(success.content, node, measurer, inlineMath, cellMath) {
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
    cellConfig: LatexConfig,
    block: @Composable () -> Unit,
) {
    val formulas = remember(content, node) { buildSet { collectMath(content, node, this) } }
    if (formulas.isEmpty()) {
        block()
    } else {
        val mathContent = remember(formulas, measurer, config, cellConfig) {
            formulas.mapNotNull { (kind, latex) ->
                val inline = when (kind) {
                    MathKind.INLINE -> measurer.inlineContent(inlineLatex(latex), config)
                    MathKind.DISPLAY -> measurer.inlineContent(normalizeLatex(latex), config)
                    MathKind.CELL -> measurer.inlineContent(inlineLatex(latex), cellConfig)
                }
                inline?.let { kind.prefix + latex to it }
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

/** Draws INLINE_MATH / BLOCK_MATH nodes as the inline content in [measured] (keys: [MathKind] prefix + formula). */
private fun mathAnnotator(measured: Set<String>): MarkdownAnnotator =
    markdownAnnotator { content, child ->
        if (child.type != GFMElementTypes.INLINE_MATH && child.type != GFMElementTypes.BLOCK_MATH) {
            return@markdownAnnotator false
        }
        val latex = mathSource(content, child)
        val key = mathKind(child).prefix + latex
        // A formula the renderer cannot measure stays readable as source.
        if (key in measured) appendInlineContent(key, latex)
        else append(child.getTextInNode(content))
        true
    }

@Composable
private fun DisplayMath(latex: String, config: LatexConfig) {
    val source = remember(latex) { normalizeLatex(latex) }
    val scroll = rememberScrollState()
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val available = constraints.maxWidth
        Box(
            Modifier
                .fillMaxWidth()
                // Changes once, after the first layout, and only for a formula that still overflows.
                .then(if (scroll.maxValue > 0) Modifier.fadingEdges(scroll) else Modifier)
                .horizontalScroll(scroll)
                .padding(vertical = 4.dp),
            contentAlignment = Alignment.Center,
        ) {
            Latex(latex = source, config = config, modifier = Modifier.shrinkToFit(available))
        }
    }
}

/**
 * Scales a formula wider than [available] px down to fit, but not below MIN_DISPLAY_SCALE: past that
 * it scrolls sideways, with faded edges as the hint. Scaled when drawn, so it is measured only once.
 */
private fun Modifier.shrinkToFit(available: Int): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    val scale = if (placeable.width <= available) 1f
    else (available.toFloat() / placeable.width).coerceAtLeast(MIN_DISPLAY_SCALE)
    val width = (placeable.width * scale).roundToInt()
    val height = (placeable.height * scale).roundToInt()
    layout(width, height) {
        // The layer scales around its center: shift it so the scaled formula starts at 0, 0.
        placeable.placeWithLayer((width - placeable.width) / 2, (height - placeable.height) / 2) {
            scaleX = scale
            scaleY = scale
        }
    }
}

/** Fades out the sides where [scroll] has more to show. */
private fun Modifier.fadingEdges(scroll: ScrollState): Modifier =
    graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            val fade = 32.dp.toPx().coerceAtMost(size.width / 4)
            if (scroll.canScrollBackward) {
                drawRect(
                    Brush.horizontalGradient(listOf(Color.Black, Color.Transparent), endX = fade),
                    size = Size(fade, size.height),
                    blendMode = BlendMode.DstOut,
                )
            }
            if (scroll.canScrollForward) {
                drawRect(
                    Brush.horizontalGradient(listOf(Color.Transparent, Color.Black), startX = size.width - fade, endX = size.width),
                    topLeft = Offset(size.width - fade, 0f),
                    size = Size(fade, size.height),
                    blendMode = BlendMode.DstOut,
                )
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

private fun collectMath(content: String, node: ASTNode, into: MutableSet<Pair<MathKind, String>>) {
    if (node.type == GFMElementTypes.INLINE_MATH || node.type == GFMElementTypes.BLOCK_MATH) {
        into += mathKind(node) to mathSource(content, node)
    } else {
        node.children.forEach { collectMath(content, it, into) }
    }
}

private fun mathKind(node: ASTNode): MathKind = when {
    generateSequence(node.parent) { it.parent }.any { it.type == GFMElementTypes.TABLE } -> MathKind.CELL
    node.type == GFMElementTypes.BLOCK_MATH -> MathKind.DISPLAY
    else -> MathKind.INLINE
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
    val cells = remember(content, rows, inlineContent, headerStyle, cellStyle) {
        rows.mapIndexed { rowIndex, row ->
            val style = if (rowIndex == 0) headerStyle else cellStyle
            row.map { content.buildMarkdownAnnotatedString(it, style, settings).trimmed() }
        }
    }

    val columns = rows.maxOf { it.size }
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val extents = remember(cells, inlineContent, textMeasurer, density) {
        val padding = with(density) { 24.dp.toPx() }
        val narrowest = with(density) { 40.dp.toPx() }
        (0 until columns).map { column ->
            var natural = narrowest
            var min = 0f
            cells.forEachIndexed { rowIndex, row ->
                val cell = row.getOrNull(column) ?: return@forEachIndexed
                val style = if (rowIndex == 0) headerStyle else cellStyle
                val placeholders = cell.placeholders(inlineContent)
                natural = maxOf(natural, textMeasurer.measure(cell, style, softWrap = false, placeholders = placeholders).size.width.toFloat())
                min = maxOf(min, widestPiece(cell, style, placeholders, textMeasurer, density))
            }
            ColumnExtent(natural + padding, minOf(min, natural) + padding)
        }
    }
    val shape = RoundedCornerShape(12.dp)
    val scroll = rememberScrollState()
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .border(1.dp, t.border, shape),
    ) {
        // Measured outside the scroll, which has unbounded width.
        val widths = with(density) {
            tableWidths(extents, (maxWidth - 2.dp).toPx()).map { it.toDp() }
        }
        val tableWidth = widths.fold(0.dp) { total, width -> total + width }
        Column(
            Modifier
                .then(if (scroll.maxValue > 0) Modifier.fadingEdges(scroll) else Modifier)
                .horizontalScroll(scroll),
        ) {
            rows.indices.forEach { rowIndex ->
                val style = if (rowIndex == 0) headerStyle else cellStyle
                Row(Modifier.background(if (rowIndex == 0) t.code else Color.Transparent)) {
                    widths.forEachIndexed { column, width ->
                        Text(
                            cells[rowIndex].getOrNull(column) ?: AnnotatedString(""),
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

/** Widest piece of a cell that cannot wrap: one of its formulas or words. */
private fun widestPiece(
    cell: AnnotatedString,
    style: TextStyle,
    placeholders: List<AnnotatedString.Range<Placeholder>>,
    measurer: TextMeasurer,
    density: Density,
): Float {
    var widest = 0f
    for (placeholder in placeholders) {
        val width = placeholder.item.width
        if (width.isSp) widest = maxOf(widest, with(density) { width.toPx() })
    }
    // A formula's alternate text is its source, not words. Only the longest words are measured:
    // each one is a text layout.
    WORD.findAll(cell.text)
        .filter { word -> placeholders.none { it.start <= word.range.last && word.range.first < it.end } }
        .sortedByDescending { it.value.length }
        .take(3)
        .forEach { word ->
            val piece = cell.subSequence(word.range.first, word.range.last + 1)
            widest = maxOf(widest, measurer.measure(piece, style, softWrap = false).size.width.toFloat())
        }
    return widest
}

private val WORD = Regex("""\S+""")

/** The inline content of this text as placeholders, to measure it outside a Text. */
private fun AnnotatedString.placeholders(inline: Map<String, InlineTextContent>): List<AnnotatedString.Range<Placeholder>> =
    getStringAnnotations(INLINE_CONTENT_TAG, 0, length).mapNotNull { range ->
        inline[range.item]?.let { AnnotatedString.Range(it.placeholder, range.start, range.end) }
    }

/** Drops the padding spaces around a cell's content, keeping its styles. */
private fun AnnotatedString.trimmed(): AnnotatedString {
    val start = text.indexOfFirst { !it.isWhitespace() }
    if (start == -1) return AnnotatedString("")
    return subSequence(start, text.indexOfLast { !it.isWhitespace() } + 1)
}
