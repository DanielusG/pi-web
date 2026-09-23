# JSON is handled as kotlinx.serialization JsonElement trees (no reflection),
# so it needs no keep rules.

# Set by reflection in disablePreciseGlyphBounds() (ui/markdown/Markdown.kt).
-keep class com.hrm.latex.renderer.model.LatexFontFamilyKt {
    boolean fontBytesLoaded;
}

-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
