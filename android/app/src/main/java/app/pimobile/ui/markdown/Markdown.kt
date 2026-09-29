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
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.pimobile.data.FilePaths
import app.pimobile.ui.theme.GeistMono
import app.pimobile.ui.theme.LocalChatFontScale
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
import com.mikepenz.markdown.compose.extendedspans.ExtendedSpans
import com.mikepenz.markdown.model.DefaultMarkdownColors
import com.mikepenz.markdown.model.DefaultMarkdownExtendedSpans
import com.mikepenz.markdown.model.DefaultMarkdownTypography
import com.mikepenz.markdown.model.MarkdownAnnotator
import com.mikepenz.markdown.model.State
import com.mikepenz.markdown.model.markdownAnimations
import com.mikepenz.markdown.model.markdownAnnotator
import com.mikepenz.markdown.model.markdownInlineContent
import com.mikepenz.markdown.model.markdownPadding
import com.mikepenz.markdown.utils.EntityConverter
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

/** Code blocks and fences; the text color comes from the theme. */
private val CodeTextStyle = TextStyle(fontFamily = GeistMono, fontSize = 13.sp, lineHeight = 20.sp)

/**
 * Heading sizes as multiples of the body text, like pi-web's em-based `.markdown-body h*` and its
 * `.markdown-file-preview` override: no heading is ever smaller than a paragraph. h4–h6 are body
 * sized, set apart by weight only.
 */
enum class HeadingScale(val h1: Float, val h2: Float, val h3: Float) {
    /** Chat messages: headings stay close to the text around them. */
    Chat(1.25f, 1.125f, 1f),
    /** Whole documents (the file preview): a clearer outline. */
    Document(1.8f, 1.4f, 1.15f),
}

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
    headings: HeadingScale = HeadingScale.Chat,
) {
    MarkdownDocument(text, baseDir, relativeRoot, onOpenFile, headings) { blocks, block ->
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
    headings: HeadingScale = HeadingScale.Chat,
) {
    MarkdownDocument(text, baseDir, relativeRoot, onOpenFile, headings) { blocks, block ->
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
    headings: HeadingScale,
    layout: @Composable (blocks: List<ASTNode>, block: @Composable (ASTNode) -> Unit) -> Unit,
) {
    val t = Pi.tokens
    val typography = MaterialTheme.typography
    val fontScale = LocalChatFontScale.current
    val body = typography.bodyLarge.copy(color = t.text, fontSize = typography.bodyLarge.fontSize * fontScale)

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
    val cellMath = remember(inlineMath, typography, fontScale) { inlineMath.copy(fontSize = typography.bodyMedium.fontSize * fontScale) }
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
    val markdownTypography = remember(t, typography, headings, fontScale) {
        fun heading(scale: Float) = body.copy(fontSize = body.fontSize * scale, lineHeight = 1.35.em, fontWeight = FontWeight.SemiBold)
        val minor = heading(1f)
        val marker = body.copy(color = t.textTertiary)
        DefaultMarkdownTypography(
            h1 = heading(headings.h1),
            h2 = heading(headings.h2),
            h3 = heading(headings.h3),
            h4 = minor,
            h5 = minor,
            h6 = minor,
            text = body,
            code = CodeTextStyle.copy(color = t.text, fontSize = CodeTextStyle.fontSize * fontScale),
            inlineCode = TextStyle(fontFamily = GeistMono, fontSize = InlineCodeSize),
            quote = body.copy(color = t.textSecondary),
            paragraph = body,
            ordered = marker,
            bullet = marker,
            list = body,
            textLink = TextLinkStyles(SpanStyle(color = t.accent, textDecoration = TextDecoration.Underline)),
            table = typography.bodyMedium.copy(color = t.text, fontSize = typography.bodyMedium.fontSize * fontScale),
        )
    }
    val inline = remember(markdownTypography, colors) {
        InlineMarkup(markdownTypography.inlineCode.toSpanStyle().copy(background = colors.inlineCodeBackground), markdownTypography.textLink)
    }
    val baseAnnotator = remember(inline) { inline.annotator() }
    // Every text drawn by the library, and table cells, paint inline code through this.
    val codeSpans = remember(t) {
        val painter = CodeSpanPainter(InlineCodeSize.value, t.border)
        // One ExtendedSpans per text, which keeps that text's draw instructions.
        DefaultMarkdownExtendedSpans { remember(painter) { ExtendedSpans(painter) } }
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
            checkbox = { TaskCheckBox(it) },
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
            // All the space between list items is above them: space below would pile up at the end
            // of a nested list, where the inner and outer items both close.
            padding = markdownPadding(block = 0.dp, list = 0.dp, listItemTop = 6.dp, listItemBottom = 0.dp, listIndent = 0.dp),
            components = components,
            extendedSpans = codeSpans,
            annotator = baseAnnotator,
            // Streaming grows the text on every chunk; the default size animation fights the auto-scroll.
            animations = markdownAnimations(animateTextSize = { this }),
            success = { success, blockComponents, _ ->
                val blocks = remember(success) { success.node.children.filter { it.type != MarkdownTokenTypes.EOL } }
                layout(blocks) { node ->
                    MathScope(success.content, node, measurer, inlineMath, cellMath, inline) {
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
    inline: InlineMarkup,
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
        val annotator = remember(mathContent, inline) { inline.annotator(mathContent.keys) }
        CompositionLocalProvider(
            LocalMarkdownAnnotator provides annotator,
            LocalMarkdownInlineContent provides markdownInlineContent(mathContent),
            content = block,
        )
    }
}

/**
 * Inline markup that the library's annotator renders wrong, handled before it sees the node, in
 * every text: paragraphs, headings, list items, quotes and table cells. The library walks the
 * tokens inside inline code as if they were text, so `~` vanishes, `_` turns into `*` and
 * `<tag>` disappears; it also drops a lone `~`, the address of an email autolink and `<br>`, and
 * leaves entities such as `&amp;` undecoded.
 */
internal class InlineMarkup(private val codeSpan: SpanStyle, private val links: TextLinkStyles) {
    /** [math]: the formulas measured for the block, drawn as inline content (keys: [MathKind] prefix + formula). */
    fun annotator(math: Set<String> = emptySet()): MarkdownAnnotator =
        markdownAnnotator { content, child -> annotate(content, child, math) }

    private fun AnnotatedString.Builder.annotate(content: String, child: ASTNode, math: Set<String>): Boolean {
        when (child.type) {
            GFMElementTypes.INLINE_MATH, GFMElementTypes.BLOCK_MATH -> {
                val latex = mathSource(content, child)
                val key = mathKind(child).prefix + latex
                // A formula the renderer cannot measure stays readable as source.
                if (key in math) appendInlineContent(key, latex) else append(child.getTextInNode(content))
            }
            MarkdownElementTypes.CODE_SPAN -> {
                val code = codeSpanText(content, child) ?: return false
                // Padded with a space on each side like the library does: CodeSpanPainter draws around them.
                withStyle(codeSpan) {
                    append(' ')
                    append(code)
                    append(' ')
                }
            }
            MarkdownTokenTypes.EMAIL_AUTOLINK -> {
                val address = child.getTextInNode(content).toString()
                withLink(LinkAnnotation.Url("mailto:$address", links)) { append(address) }
            }
            // An email autolink's brackets: the parser leaves them as plain tokens beside the address.
            MarkdownTokenTypes.LT, MarkdownTokenTypes.GT -> {
                val siblings = child.parent?.children ?: return false
                val next = siblings.getOrNull(siblings.indexOf(child) + if (child.type == MarkdownTokenTypes.LT) 1 else -1)
                if (next?.type != MarkdownTokenTypes.EMAIL_AUTOLINK) return false
            }
            MarkdownTokenTypes.TEXT -> {
                if ((child.startOffset until child.endOffset).none { content[it] == '&' }) return false
                append(EntityConverter.replaceEntities(child.getTextInNode(content), processEntities = true, processEscapes = true))
            }
            // Delimiters of a strikethrough / emphasis are markup; anywhere else they are text.
            GFMTokenTypes.TILDE -> {
                if (child.parent?.type == GFMElementTypes.STRIKETHROUGH) return false
                append(child.getTextInNode(content))
            }
            MarkdownTokenTypes.EMPH -> {
                val parent = child.parent?.type
                if (parent == MarkdownElementTypes.EMPH || parent == MarkdownElementTypes.STRONG) return false
                append(child.getTextInNode(content))
            }
            MarkdownTokenTypes.HTML_TAG -> {
                if (!LINE_BREAK_TAG.matches(child.getTextInNode(content))) return false
                append('\n')
            }
            else -> return false
        }
        return true
    }

    private companion object {
        val LINE_BREAK_TAG = Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE)
    }
}

/**
 * The text of an inline code span, verbatim as CommonMark defines it: line endings become spaces,
 * and one space is stripped from each side when both sides have one. Null if the node is not
 * delimited by backticks.
 */
private fun codeSpanText(content: String, node: ASTNode): String? {
    val open = node.children.firstOrNull()
    val close = node.children.lastOrNull()
    if (open == null || close == null || open === close ||
        open.type != MarkdownTokenTypes.BACKTICK || close.type != MarkdownTokenTypes.BACKTICK
    ) return null
    val code = content.substring(open.endOffset, close.startOffset).replace("\r\n", " ").replace('\n', ' ')
    return if (code.length >= 2 && code.first() == ' ' && code.last() == ' ' && code.isNotBlank()) {
        code.substring(1, code.length - 1)
    } else code
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

/** A task-list marker drawn as a box, like pi-web's, where the library writes `[x]` as text. */
@Composable
private fun TaskCheckBox(model: MarkdownComponentModel) {
    val t = Pi.tokens
    val checked = remember(model.content, model.node) {
        model.node.getTextInNode(model.content).contains("[x]", ignoreCase = true)
    }
    // Centered on the item's first line, where a bullet would be.
    val line = with(LocalDensity.current) { model.typography.list.lineHeight.toDp() }
    val shape = RoundedCornerShape(4.dp)
    Box(
        Modifier
            .padding(top = ((line - 16.dp) / 2).coerceAtLeast(0.dp), end = 10.dp)
            .size(16.dp)
            .clip(shape)
            .background(if (checked) t.accent.copy(alpha = 0.1f) else t.background)
            .border(1.dp, if (checked) t.accent.copy(alpha = 0.55f) else t.borderStrong, shape)
            .semantics { contentDescription = if (checked) "Done" else "Not done" },
        contentAlignment = Alignment.Center,
    ) {
        if (checked) Icon(PiIcons.Check, contentDescription = null, tint = t.accent, modifier = Modifier.size(12.dp))
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
            style = CodeTextStyle.copy(
                color = t.text,
                fontSize = CodeTextStyle.fontSize * LocalChatFontScale.current,
            ),
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
    val cellStyle = LocalMarkdownTypography.current.table
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
                        // The library's text, as in paragraphs: it also paints inline code and math.
                        MarkdownText(
                            cells[rowIndex].getOrNull(column) ?: AnnotatedString(""),
                            Modifier
                                .width(width)
                                .padding(horizontal = 12.dp, vertical = 9.dp),
                            style = style,
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

/**
 * Drops the padding spaces around a cell's content, keeping its styles. The spaces inside inline
 * code, which has a background, stay: they pad its box (see [InlineMarkup]).
 */
private fun AnnotatedString.trimmed(): AnnotatedString {
    val boxed = spanStyles.filter { it.item.background.isSpecified }
    val kept = { i: Int -> !text[i].isWhitespace() || boxed.any { i >= it.start && i < it.end } }
    val start = text.indices.firstOrNull(kept) ?: return AnnotatedString("")
    return subSequence(start, text.indices.last(kept) + 1)
}
