package app.pimobile.ui.markdown

import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import com.hrm.latex.renderer.utils.GlyphBounds
import java.io.File

/*
 * The LaTeX renderer's precise glyph bounds, with each font loaded once. The library (huarangmeng/latex
 * 1.5.4, GlyphBoundsProvider.android.kt) writes the whole font to a temporary file and loads a new
 * Typeface for every glyph it measures: opening a lesson with a few dozen formulas blocked the app for
 * 3 s. Without precise bounds every glyph is as tall as a text line, which detaches radicals and
 * stretches delimiters (android/docs/latex-rendering-2026-09-26.md).
 *
 * The build sends the library's only call here instead (LatexRendererPatches in app/build.gradle.kts).
 */

/** Keyed by the library's font byte arrays, loaded once per process: arrays compare by identity. */
private val typefaces = HashMap<ByteArray, Typeface>()

/** A measured glyph. Its font compares by identity: a data class would hash the whole font file. */
private class Glyph(val font: ByteArray, val text: String, val fontSizePx: Float) {
    override fun equals(other: Any?) =
        other is Glyph && other.font === font && other.text == text && other.fontSizePx == fontSizePx

    override fun hashCode() = (System.identityHashCode(font) * 31 + text.hashCode()) * 31 + fontSizePx.hashCode()
}

/** The same glyphs come back in every formula: `x`, `(`, `=` at a handful of sizes. */
private val measured = object : LinkedHashMap<Glyph, GlyphBounds>(256, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Glyph, GlyphBounds>) = size > 4096
}

private val paint = Paint().apply { isAntiAlias = true }
private val rect = Rect()

/** Same contract as the library's `measureGlyphBounds`: ink bounds around the baseline, null on failure. */
@Suppress("UNUSED_PARAMETER") // The weight is unused by the library too: each byte array is one face.
fun cachedGlyphBounds(text: String, fontSizePx: Float, fontBytes: ByteArray, fontWeightValue: Int): GlyphBounds? {
    if (text.isEmpty()) return null
    val key = Glyph(fontBytes, text, fontSizePx)
    // Guards the maps and the shared Paint.
    return synchronized(measured) {
        measured[key] ?: run {
            paint.typeface = typeface(fontBytes) ?: return null
            paint.textSize = fontSizePx
            paint.getTextBounds(text, 0, text.length, rect)
            GlyphBounds(
                ascentPx = (-rect.top).toFloat().coerceAtLeast(0f),
                descentPx = rect.bottom.toFloat().coerceAtLeast(0f),
                inkWidth = rect.width().toFloat().coerceAtLeast(0f),
            ).also { measured[key] = it }
        }
    }
}

private fun typeface(fontBytes: ByteArray): Typeface? = typefaces[fontBytes] ?: try {
    // API 26 has no Typeface from bytes. The file stays, in the app's cache directory, so the
    // Typeface never outlives it; one per font, named by its content.
    val file = File(System.getProperty("java.io.tmpdir"), "latex-${fontBytes.size}-${fontBytes.contentHashCode()}.ttf")
    if (file.length() != fontBytes.size.toLong()) file.writeBytes(fontBytes)
    Typeface.createFromFile(file).also { typefaces[fontBytes] = it }
} catch (e: Exception) {
    null
}
