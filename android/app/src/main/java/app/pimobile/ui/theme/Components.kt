package app.pimobile.ui.theme

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Status label with a light sweeping across it, for "the agent is working" states. */
@Composable
fun ShimmerText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.labelLarge,
) {
    val t = Pi.tokens
    val transition = rememberInfiniteTransition(label = "shimmer")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1500, easing = LinearEasing)),
        label = "progress",
    )
    var width by remember { mutableFloatStateOf(300f) }
    val x = -width * 0.5f + progress * width * 2f
    val brush = Brush.linearGradient(
        colors = listOf(t.textTertiary, t.text, t.textTertiary),
        start = Offset(x - width * 0.35f, 0f),
        end = Offset(x + width * 0.35f, 0f),
    )
    Text(
        text,
        modifier = modifier.onSizeChanged { width = it.width.toFloat().coerceAtLeast(1f) },
        style = style.copy(brush = brush),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * Solid dot for a state (running, waiting, live). Deliberately static: an infinite
 * animation keeps the whole window rendering at the display refresh rate for as long
 * as it is on screen, which measured as the app's largest battery cost.
 */
@Composable
fun StatusDot(color: Color, modifier: Modifier = Modifier, size: Dp = 6.dp) {
    Box(
        modifier
            .size(size)
            .background(color, CircleShape),
    )
}

@Composable
fun PiPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
) {
    val t = Pi.tokens
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = 44.dp),
        enabled = enabled && !loading,
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = t.primary,
            contentColor = t.onPrimary,
            disabledContainerColor = t.muted,
            disabledContentColor = t.textTertiary,
        ),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp),
    ) {
        if (loading) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = t.textSecondary)
        else Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun PiSecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val t = Pi.tokens
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 44.dp),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, t.border),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = t.text),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun piTextFieldColors(): TextFieldColors {
    val t = Pi.tokens
    return OutlinedTextFieldDefaults.colors(
        focusedTextColor = t.text,
        unfocusedTextColor = t.text,
        focusedBorderColor = t.textSecondary,
        unfocusedBorderColor = t.border,
        cursorColor = t.text,
        focusedLabelColor = t.textSecondary,
        unfocusedLabelColor = t.textTertiary,
        focusedPlaceholderColor = t.textTertiary,
        unfocusedPlaceholderColor = t.textTertiary,
        focusedContainerColor = Color.Transparent,
        unfocusedContainerColor = Color.Transparent,
    )
}
