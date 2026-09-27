package app.pimobile.ui.markdown

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import com.mikepenz.markdown.annotator.DefaultAnnotatorSettings
import com.mikepenz.markdown.annotator.buildMarkdownAnnotatedString
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InlineMarkupTest {
    private val parser = MarkdownParser(GFMFlavourDescriptor())
    private val code = SpanStyle(background = Color.Gray)
    private val settings = DefaultAnnotatorSettings(TextLinkStyles(), code, InlineMarkup(code, TextLinkStyles()).annotator())

    /** The first paragraph of [markdown] as the renderer annotates it. */
    private fun render(markdown: String): AnnotatedString {
        fun paragraph(node: ASTNode): ASTNode? =
            if (node.type == MarkdownElementTypes.PARAGRAPH) node else node.children.firstNotNullOfOrNull(::paragraph)
        val node = paragraph(parser.buildMarkdownTreeFromString(markdown))!!
        return buildAnnotatedString { buildMarkdownAnnotatedString(markdown, node, settings) }
    }

    private fun AnnotatedString.codeSpans(): List<String> =
        spanStyles.filter { it.item == code }.map { text.substring(it.start, it.end) }

    @Test
    fun inlineCodeIsVerbatim() {
        // The library alone drew this as "/.pi/sessions/*.jsonl".
        val rendered = render("Path `~/.pi/sessions/<timestamp>_<uuid>.jsonl` here")
        assertEquals("Path  ~/.pi/sessions/<timestamp>_<uuid>.jsonl  here", rendered.text)
        assertEquals(listOf(" ~/.pi/sessions/<timestamp>_<uuid>.jsonl "), rendered.codeSpans())
    }

    @Test
    fun markupInsideInlineCodeStaysText() {
        assertEquals(
            listOf(" _not italic_ ", " ~~not struck~~ ", " a\\*b "),
            render("`_not italic_` `~~not struck~~` `a\\*b`").codeSpans(),
        )
    }

    @Test
    fun inlineCodeFollowsCommonMarkSpacing() {
        assertEquals(listOf(" ` "), render("`` ` ``").codeSpans())
        assertEquals(listOf(" a b "), render("`a\nb`").codeSpans())
        assertEquals(listOf("    "), render("`  `").codeSpans())
    }

    @Test
    fun loneTildesAreText() {
        assertEquals("about ~5 minutes, ~/.config, 1~2", render("about ~5 minutes, ~/.config, 1~2").text)
    }

    @Test
    fun strikethroughStillStrikes() {
        val rendered = render("a ~~gone~~ b")
        assertEquals("a gone b", rendered.text)
        assertTrue(rendered.spanStyles.any { it.item.textDecoration == TextDecoration.LineThrough })
    }

    @Test
    fun entitiesAreDecoded() {
        assertEquals("fish & chips, 3 < 4, © 2026", render("fish &amp; chips, 3 &lt; 4, &copy; 2026").text)
    }

    @Test
    fun emailAutolinkKeepsItsAddress() {
        val rendered = render("mail <user@example.com> now")
        assertEquals("mail user@example.com now", rendered.text)
        val link = rendered.getLinkAnnotations(0, rendered.length).single().item as LinkAnnotation.Url
        assertEquals("mailto:user@example.com", link.url)
    }

    @Test
    fun lineBreakTagBreaksTheLine() {
        assertEquals("line\nbreak", render("line<br>break").text)
    }

    @Test
    fun emphasisIsUnchanged() {
        val rendered = render("*it* and **bold** and a _ b")
        assertEquals("it and bold and a _ b", rendered.text)
    }
}
