@file:OptIn(ExperimentalLayoutApi::class)

package app.pimobile.ui.chat

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.pimobile.data.AttachedImage
import app.pimobile.data.ImageAttachments
import app.pimobile.data.ImagePayload
import app.pimobile.ui.theme.Pi
import app.pimobile.ui.theme.PiIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val BUBBLE_DECODE_PX = 720
private const val PREVIEW_DECODE_PX = 2048

@Composable
private fun rememberDecoded(data: String, maxPx: Int): Bitmap? =
    produceState<Bitmap?>(null, data, maxPx) {
        value = withContext(Dispatchers.Default) { ImageAttachments.decodeBase64(data, maxPx) }
    }.value

/** Composer strip: 56dp thumbnails with a remove badge, plus spinners for images still being read. */
@Composable
fun AttachmentStrip(images: List<AttachedImage>, pending: Int, onRemove: (Long) -> Unit) {
    val t = Pi.tokens
    var preview by remember { mutableStateOf<ImagePayload?>(null) }
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(bottom = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val shape = RoundedCornerShape(10.dp)
        images.forEach { image ->
            // Top/end room so the badge, which overhangs the corner, isn't clipped.
            Box(Modifier.padding(top = 6.dp, end = 6.dp)) {
                Box(
                    Modifier
                        .size(56.dp)
                        .clip(shape)
                        .border(1.dp, t.border, shape)
                        .background(t.muted)
                        .clickable { preview = image.payload },
                ) {
                    image.thumbnail?.let { bitmap ->
                        Image(
                            remember(bitmap) { bitmap.asImageBitmap() },
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 6.dp, y = (-6).dp)
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(t.surface)
                        .border(1.dp, t.border, CircleShape)
                        .clickable { onRemove(image.id) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(PiIcons.Close, contentDescription = "Remove image", tint = t.textSecondary, modifier = Modifier.size(10.dp))
                }
            }
        }
        repeat(pending) {
            Box(Modifier.padding(top = 6.dp, end = 6.dp)) {
                Box(
                    Modifier
                        .size(56.dp)
                        .clip(shape)
                        .border(1.dp, t.border, shape)
                        .background(t.muted),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = t.textTertiary)
                }
            }
        }
    }
    preview?.let { ImagePreviewDialog(it, onDismiss = { preview = null }) }
}

/** Images inside a user bubble; tap opens the full-screen preview (web: ImagePreview). */
@Composable
fun MessageImages(images: List<ImagePayload>, modifier: Modifier = Modifier) {
    var preview by remember { mutableStateOf<ImagePayload?>(null) }
    val maxSide: Dp = if (images.size == 1) 220.dp else 120.dp
    FlowRow(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        images.forEach { image ->
            MessageImage(image, maxSide, onClick = { preview = image })
        }
    }
    preview?.let { ImagePreviewDialog(it, onDismiss = { preview = null }) }
}

@Composable
private fun MessageImage(image: ImagePayload, maxSide: Dp, onClick: () -> Unit) {
    val t = Pi.tokens
    val bitmap = rememberDecoded(image.data, BUBBLE_DECODE_PX)
    val shape = RoundedCornerShape(12.dp)
    val sizing = if (bitmap == null) {
        Modifier.size(maxSide)
    } else {
        // Contain within a maxSide square, keeping the image's own proportions.
        val ratio = ImageAttachments.aspectRatio(bitmap).coerceIn(0.25f, 4f)
        if (ratio >= 1f) Modifier.width(maxSide).aspectRatio(ratio)
        else Modifier.width(maxSide * ratio).aspectRatio(ratio)
    }
    Box(
        sizing
            .clip(shape)
            .border(1.dp, t.accent.copy(alpha = 0.15f), shape)
            .background(t.background)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                remember(bitmap) { bitmap.asImageBitmap() },
                contentDescription = "Image",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(PiIcons.Image, null, tint = t.textTertiary, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
fun ImagePreviewDialog(image: ImagePayload, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val bitmap = rememberDecoded(image.data, PREVIEW_DECODE_PX)
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.92f))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            if (bitmap != null) {
                Image(
                    remember(bitmap) { bitmap.asImageBitmap() },
                    contentDescription = "Preview image",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                )
            } else {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp, color = Color.White)
            }
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.14f))
                    .clickable(onClick = onDismiss),
                contentAlignment = Alignment.Center,
            ) {
                Icon(PiIcons.Close, contentDescription = "Close", tint = Color.White, modifier = Modifier.size(18.dp))
            }
        }
    }
}

/** Web: ModelNoticeBanner tone="warning" — shown when the model can't take images. */
@Composable
fun ImageWarningBanner(modelName: String, onClose: () -> Unit) {
    val t = Pi.tokens
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .clip(shape)
            .border(1.dp, t.warning.copy(alpha = 0.3f), shape)
            .background(t.warning.copy(alpha = 0.07f))
            .padding(start = 12.dp, top = 9.dp, bottom = 9.dp, end = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(PiIcons.Warning, null, tint = t.warning, modifier = Modifier.padding(top = 2.dp).size(14.dp))
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "Images may not be sent",
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                color = t.warning,
            )
            Text(
                "The selected model ($modelName) does not support image input. The attached images will likely be ignored.",
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                color = t.warning,
            )
        }
        Box(
            Modifier
                .size(28.dp)
                .clip(CircleShape)
                .clickable(onClick = onClose),
            contentAlignment = Alignment.Center,
        ) {
            Icon(PiIcons.Close, contentDescription = "Dismiss", tint = t.warning, modifier = Modifier.size(12.dp))
        }
    }
}
