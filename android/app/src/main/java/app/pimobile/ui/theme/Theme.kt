package app.pimobile.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pimobile.R

/**
 * Design tokens: a monochrome palette with a single accent, hairline borders
 * instead of elevation. Components read these through [Pi.tokens]; Material
 * colors are derived from them so stock components match.
 */
@Immutable
data class PiTokens(
    val background: Color,
    /** Raised containers that sit on the page: composer, menus. */
    val surface: Color,
    /** Filled neutrals: user bubble, pressed chips, inline code. */
    val muted: Color,
    val code: Color,
    val border: Color,
    val borderStrong: Color,
    val text: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val primary: Color,
    val onPrimary: Color,
    val accent: Color,
    val success: Color,
    val warning: Color,
    val danger: Color,
)

val LightTokens = PiTokens(
    background = Color(0xFFFFFFFF),
    surface = Color(0xFFFFFFFF),
    muted = Color(0xFFF2F2F2),
    code = Color(0xFFFAFAFA),
    border = Color(0xFFEAEAEA),
    borderStrong = Color(0xFFD4D4D4),
    text = Color(0xFF0A0A0A),
    textSecondary = Color(0xFF5E5E5E),
    textTertiary = Color(0xFF9B9B9B),
    primary = Color(0xFF0A0A0A),
    onPrimary = Color(0xFFFFFFFF),
    accent = Color(0xFF0070F3),
    success = Color(0xFF10B981),
    warning = Color(0xFFD97706),
    danger = Color(0xFFE5484D),
)

val DarkTokens = PiTokens(
    background = Color(0xFF0A0A0A),
    surface = Color(0xFF141414),
    muted = Color(0xFF1F1F1F),
    code = Color(0xFF111111),
    border = Color(0xFF242424),
    borderStrong = Color(0xFF363636),
    text = Color(0xFFEDEDED),
    textSecondary = Color(0xFFA1A1A1),
    textTertiary = Color(0xFF6F6F6F),
    primary = Color(0xFFEDEDED),
    onPrimary = Color(0xFF0A0A0A),
    accent = Color(0xFF3B9EFF),
    success = Color(0xFF34D399),
    warning = Color(0xFFF5A524),
    danger = Color(0xFFFF6166),
)

val LocalPiTokens = staticCompositionLocalOf { LightTokens }

/** The scale of the enclosing [ScaledUi], read again by [KeepUiScale] in windows opened under it. */
val LocalUiScale = staticCompositionLocalOf { 1f }

/**
 * Draws [content] at [scale] times its size (Settings → "Chat size", around the chat screen).
 * It scales LocalDensity, so every dp and sp inside shrinks or grows together: text, line
 * heights, padding, icons and bars keep their proportions. Window insets are pixels and stay
 * right; LocalConfiguration's dp sizes do not, so read those through [screenSizeDp]. Dialogs,
 * sheets and menus get their window's own density back: wrap their content in [KeepUiScale].
 */
@Composable
fun ScaledUi(scale: Float, content: @Composable () -> Unit) {
    val current = LocalDensity.current
    // Applied again inside a scaled composition, it replaces the scale instead of compounding it.
    val base = (current as? ScaledDensity)?.base ?: current
    val density = remember(base, scale) { if (scale == 1f) base else ScaledDensity(base, scale) }
    CompositionLocalProvider(LocalUiScale provides scale, LocalDensity provides density, content = content)
}

/**
 * Dialogs, bottom sheets and dropdown menus compose in a window of their own, which provides
 * the display's density again. Their content wrapped in this takes the scale of the [ScaledUi]
 * they were opened from; the frame the component draws around it (shape, container padding, a
 * width given to the component itself) stays at the display's size.
 */
@Composable
fun KeepUiScale(content: @Composable () -> Unit) = ScaledUi(LocalUiScale.current, content)

/** [base] with every dp and sp [scale] times larger; sp to dp stays [base]'s (nonlinear font scaling). */
private class ScaledDensity(val base: Density, val scale: Float) : Density {
    override val density = base.density * scale
    override val fontScale: Float get() = base.fontScale
    override fun TextUnit.toDp(): Dp = with(base) { this@toDp.toDp() }
    override fun Dp.toSp(): TextUnit = with(base) { this@toSp.toSp() }
    override fun equals(other: Any?) = other is ScaledDensity && other.base == base && other.scale == scale
    override fun hashCode() = base.hashCode() * 31 + scale.hashCode()
}

/** LocalConfiguration's screen size in this composition's dp: under [ScaledUi] they differ. */
@Composable
@ReadOnlyComposable
fun screenSizeDp(): DpSize {
    val configuration = LocalConfiguration.current
    val scale = LocalDensity.current.density / (configuration.densityDpi / 160f)
    return DpSize((configuration.screenWidthDp / scale).dp, (configuration.screenHeightDp / scale).dp)
}

object Pi {
    val tokens: PiTokens
        @Composable @ReadOnlyComposable get() = LocalPiTokens.current
}

// Static instances: one variable TTF referenced at several weights renders
// every weight as Regular (the typeface is cached per resource id).
val Geist = FontFamily(
    Font(R.font.geist_regular, FontWeight.Normal),
    Font(R.font.geist_medium, FontWeight.Medium),
    Font(R.font.geist_semibold, FontWeight.SemiBold),
    Font(R.font.geist_bold, FontWeight.Bold),
)

val GeistMono = FontFamily(
    Font(R.font.geist_mono_regular, FontWeight.Normal),
    Font(R.font.geist_mono_medium, FontWeight.Medium),
    Font(R.font.geist_mono_semibold, FontWeight.SemiBold),
)

private fun style(size: Float, line: Float, weight: FontWeight, tracking: Float = 0f) = TextStyle(
    fontFamily = Geist,
    fontSize = size.sp,
    lineHeight = line.sp,
    fontWeight = weight,
    letterSpacing = tracking.sp,
)

private val PiTypography = Typography(
    displayLarge = style(44f, 48f, FontWeight.SemiBold, -1.6f),
    displayMedium = style(36f, 40f, FontWeight.SemiBold, -1.2f),
    displaySmall = style(30f, 36f, FontWeight.SemiBold, -0.9f),
    headlineLarge = style(28f, 34f, FontWeight.SemiBold, -0.7f),
    headlineMedium = style(24f, 30f, FontWeight.SemiBold, -0.5f),
    headlineSmall = style(22f, 28f, FontWeight.SemiBold, -0.4f),
    titleLarge = style(19f, 26f, FontWeight.SemiBold, -0.3f),
    titleMedium = style(16f, 22f, FontWeight.SemiBold, -0.15f),
    titleSmall = style(14f, 20f, FontWeight.Medium, -0.1f),
    bodyLarge = style(16f, 26f, FontWeight.Normal, -0.1f),
    bodyMedium = style(14f, 21f, FontWeight.Normal),
    bodySmall = style(13f, 18f, FontWeight.Normal),
    labelLarge = style(14f, 20f, FontWeight.Medium, -0.05f),
    labelMedium = style(13f, 18f, FontWeight.Medium),
    labelSmall = style(12f, 16f, FontWeight.Medium),
)

private val PiShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

private fun colorScheme(t: PiTokens, dark: Boolean) = (if (dark) darkColorScheme() else lightColorScheme()).copy(
    primary = t.primary,
    onPrimary = t.onPrimary,
    primaryContainer = t.muted,
    onPrimaryContainer = t.text,
    inversePrimary = t.background,
    secondary = t.textSecondary,
    onSecondary = t.background,
    secondaryContainer = t.muted,
    onSecondaryContainer = t.text,
    tertiary = t.accent,
    onTertiary = Color.White,
    background = t.background,
    onBackground = t.text,
    surface = t.background,
    onSurface = t.text,
    surfaceVariant = t.muted,
    onSurfaceVariant = t.textSecondary,
    // Flat UI: no tonal tint on elevated surfaces.
    surfaceTint = Color.Transparent,
    inverseSurface = t.text,
    inverseOnSurface = t.background,
    error = t.danger,
    onError = Color.White,
    errorContainer = t.danger.copy(alpha = 0.10f).compositeOver(t.background),
    onErrorContainer = t.danger,
    outline = t.borderStrong,
    outlineVariant = t.border,
    scrim = Color.Black.copy(alpha = 0.45f),
    surfaceBright = t.surface,
    surfaceDim = t.background,
    surfaceContainerLowest = t.background,
    surfaceContainerLow = t.code,
    surfaceContainer = t.surface,
    surfaceContainerHigh = t.muted,
    surfaceContainerHighest = t.muted,
)

@Composable
fun PiTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val tokens = if (dark) DarkTokens else LightTokens
    val scheme = remember(dark) { colorScheme(tokens, dark) }
    CompositionLocalProvider(LocalPiTokens provides tokens) {
        MaterialTheme(colorScheme = scheme, typography = PiTypography, shapes = PiShapes, content = content)
    }
}
