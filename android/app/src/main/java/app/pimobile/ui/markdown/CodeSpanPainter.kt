package app.pimobile.ui.markdown

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.isUnspecified
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.compose.extendedspans.ExtendedSpanPainter
import com.mikepenz.markdown.compose.extendedspans.SpanDrawInstructions

/** Geist Mono's ascent and descent, in em: the box a browser paints behind an inline element. */
private const val CODE_ASCENT = 1.005f
private const val CODE_DESCENT = 0.295f

private val NoInstructions = SpanDrawInstructions {}

/**
 * Draws inline code backgrounds as rounded, outlined boxes around the code font, like pi-web's
 * `.markdown-inline-code`. A plain SpanStyle background fills the whole line height instead,
 * so boxes on consecutive lines touch. The box follows the baseline and the font size of the
 * text it sits in, which fits body text, headings and table cells alike.
 *
 * It paints every span with a background, and in Markdown text only inline code has one
 * (see [InlineMarkup]). [fontScale] is the code font size relative to the text around it (the
 * `inlineCode` style's em size).
 */
internal class CodeSpanPainter(private val fontScale: Float, private val border: Color) : ExtendedSpanPainter() {
    private val path = Path()

    override fun decorate(span: SpanStyle, start: Int, end: Int, text: AnnotatedString, builder: AnnotatedString.Builder): SpanStyle {
        if (span.background.isUnspecified) return span
        builder.addStringAnnotation(TAG, span.background.toArgb().toString(), start, end)
        return span.copy(background = Color.Unspecified)
    }

    override fun decorate(
        linkAnnotation: LinkAnnotation,
        start: Int,
        end: Int,
        text: AnnotatedString,
        builder: AnnotatedString.Builder,
    ): LinkAnnotation = linkAnnotation

    override fun drawInstructionsFor(layoutResult: TextLayoutResult, color: Color?): SpanDrawInstructions {
        val text = layoutResult.layoutInput.text
        val spans = text.getStringAnnotations(TAG, 0, text.length)
        if (spans.isEmpty()) return NoInstructions
        val boxes = with(layoutResult.layoutInput.density) { boxes(layoutResult, spans) }
        val outline = Stroke(width = with(layoutResult.layoutInput.density) { 1.dp.toPx() })
        return SpanDrawInstructions {
            boxes.forEach { box ->
                if (box.whole) {
                    // Most code fits on one line: a plain rounded rect is far cheaper to draw than a path.
                    drawRoundRect(box.fill, box.rect.topLeft, box.rect.size, box.shape.topLeftCornerRadius)
                    drawRoundRect(border, box.rect.topLeft, box.rect.size, box.shape.topLeftCornerRadius, style = outline)
                } else {
                    path.rewind()
                    path.addRoundRect(box.shape)
                    drawPath(path, box.fill)
                    drawPath(path, border, style = outline)
                }
            }
        }
    }

    /** The boxes of every code span, one per line it covers; measured once per layout, not per frame. */
    private fun Density.boxes(layoutResult: TextLayoutResult, spans: List<AnnotatedString.Range<String>>): List<Box> {
        val fontSize = layoutResult.layoutInput.style.fontSize
        // Without a size in sp (never for Markdown's styles) the box falls back to the line's height.
        val codeSize = if (fontSize.isSp) fontSize.toPx() * fontScale else Float.NaN
        val pad = 1.dp.toPx()
        val wrapPad = 4.dp.toPx()
        val radius = CornerRadius(5.dp.toPx())
        val boxes = ArrayList<Box>()
        spans.forEach { span ->
            if (span.start >= span.end) return@forEach
            val fill = Color(span.item.toInt())
            val first = layoutResult.getLineForOffset(span.start)
            // The line of the span's last character: the offset after it can already sit on the
            // next line, which would add an empty box there.
            val last = layoutResult.getLineForOffset(span.end - 1)
            val endsOnLast = layoutResult.getLineForOffset(span.end) == last
            var drawn = false
            for (line in first..last) {
                val startsHere = line == first
                val endsHere = line == last && endsOnLast
                var left = if (startsHere) layoutResult.getHorizontalPosition(span.start, true) else layoutResult.getLineLeft(line)
                var right = if (endsHere) layoutResult.getHorizontalPosition(span.end, true) else layoutResult.getLineRight(line)
                // Only the span's padding space on this line (the right edge leaves out trailing spaces).
                if (right - left < 1f) continue
                // Code is padded with a space on each side. Where a line break takes that space, or
                // cuts the code itself, the box would touch the glyphs: pad it instead.
                if (!startsHere) left -= wrapPad
                if (!endsHere) right += wrapPad
                val baseline = layoutResult.getLineBaseline(line)
                val rect = Rect(
                    left = left,
                    top = if (codeSize.isNaN()) layoutResult.getLineTop(line) else baseline - CODE_ASCENT * codeSize - pad,
                    right = right,
                    bottom = if (codeSize.isNaN()) layoutResult.getLineBottom(line) else baseline + CODE_DESCENT * codeSize + pad,
                )
                // Rounded where the code starts and ends, square where a line break cuts it.
                val startCorner = if (drawn) CornerRadius.Zero else radius
                val endCorner = if (line == last) radius else CornerRadius.Zero
                drawn = true
                boxes += Box(
                    fill,
                    rect,
                    RoundRect(rect, topLeft = startCorner, topRight = endCorner, bottomRight = endCorner, bottomLeft = startCorner),
                )
            }
        }
        return boxes
    }

    private class Box(val fill: Color, val rect: Rect, val shape: RoundRect) {
        /** Rounded on all four corners: the span starts and ends on this line. */
        val whole = shape.topLeftCornerRadius != CornerRadius.Zero && shape.topRightCornerRadius != CornerRadius.Zero
    }

    private companion object {
        const val TAG = "pi-code-span"
    }
}
