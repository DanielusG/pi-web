package app.pimobile.ui.files

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.pimobile.ui.theme.Pi
import app.pimobile.ui.theme.PiIcons
import app.pimobile.ui.theme.ShimmerText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

/** Pinch to zoom and pan; double tap toggles 2.5x. */
@Composable
fun ZoomableImage(bitmap: Bitmap, modifier: Modifier = Modifier) {
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    var scale by remember(bitmap) { mutableFloatStateOf(1f) }
    var offset by remember(bitmap) { mutableStateOf(Offset.Zero) }
    Box(
        modifier
            .fillMaxSize()
            .clipToBounds()
            .pointerInput(bitmap) {
                detectTapGestures(onDoubleTap = {
                    if (scale > 1f) {
                        scale = 1f
                        offset = Offset.Zero
                    } else {
                        scale = 2.5f
                    }
                })
            }
            .pointerInput(bitmap) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 8f)
                    offset = if (scale == 1f) Offset.Zero else offset + pan
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Image(
            image,
            contentDescription = "Image",
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                },
        )
    }
}

/**
 * Server HTML with no network or file access, the native counterpart of the web's
 * sandboxed iframe. An .html preview runs its scripts (web: `sandbox="allow-scripts"`);
 * a converted docx doesn't need them.
 */
@Composable
fun HtmlDocument(html: String, modifier: Modifier = Modifier, scripts: Boolean = false) {
    AndroidView(
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = scripts
                settings.blockNetworkLoads = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
                }
            }
        },
        update = { view ->
            if (view.tag != html) {
                view.tag = html
                view.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
            }
        },
        onRelease = { it.destroy() },
        modifier = modifier,
    )
}

/** PdfRenderer allows one open page at a time, so every render and the close share a lock. */
private class PdfDocument(file: File) {
    private val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = try {
        PdfRenderer(descriptor)
    } catch (e: Exception) {
        descriptor.close()
        throw e
    }
    private val lock = Mutex()
    private var closed = false

    /** Height / width of each page. */
    val ratios: List<Float> = List(renderer.pageCount) { index ->
        val page = renderer.openPage(index)
        try {
            (page.height.toFloat() / page.width.coerceAtLeast(1)).coerceIn(0.1f, 10f)
        } finally {
            page.close()
        }
    }

    suspend fun render(index: Int, widthPx: Int): Bitmap? = lock.withLock {
        if (closed) return@withLock null
        withContext(Dispatchers.IO) {
            val page = renderer.openPage(index)
            try {
                val height = (widthPx * page.height.toFloat() / page.width.coerceAtLeast(1)).roundToInt().coerceAtLeast(1)
                Bitmap.createBitmap(widthPx, height, Bitmap.Config.ARGB_8888).also { bitmap ->
                    bitmap.eraseColor(android.graphics.Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                }
            } finally {
                page.close()
            }
        }
    }

    suspend fun close() = lock.withLock {
        if (closed) return@withLock
        closed = true
        renderer.close()
        descriptor.close()
    }
}

@Composable
fun PdfPages(file: File, revision: Int, modifier: Modifier = Modifier) {
    val t = Pi.tokens
    val document by produceState<Result<PdfDocument>?>(null, file, revision) {
        val opened = withContext(Dispatchers.IO) { runCatching { PdfDocument(file) } }
        value = opened
        awaitDispose {
            opened.getOrNull()?.let { pdf -> CoroutineScope(Dispatchers.IO).launch { pdf.close() } }
        }
    }
    val result = document
    val pdf = result?.getOrNull()
    when {
        result == null -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { ShimmerText("Opening PDF") }
        pdf == null -> Text(
            "This PDF can't be shown here. Try “Open with”.",
            style = MaterialTheme.typography.bodyMedium,
            color = t.textSecondary,
            textAlign = TextAlign.Center,
            modifier = modifier.padding(32.dp),
        )
        else -> BoxWithConstraints(modifier.fillMaxSize()) {
            val widthPx = with(LocalDensity.current) { (maxWidth - 24.dp).roundToPx() }.coerceIn(1, 2000)
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(pdf.ratios.size) { index -> PdfPage(pdf, index, pdf.ratios[index], widthPx) }
            }
        }
    }
}

@Composable
private fun PdfPage(pdf: PdfDocument, index: Int, ratio: Float, widthPx: Int) {
    val t = Pi.tokens
    val bitmap by produceState<Bitmap?>(null, pdf, index, widthPx) { value = pdf.render(index, widthPx) }
    val shape = RoundedCornerShape(4.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(1f / ratio)
            .clip(shape)
            .background(Color.White)
            .border(1.dp, t.border, shape),
    ) {
        bitmap?.let {
            Image(
                remember(it) { it.asImageBitmap() },
                contentDescription = "Page ${index + 1}",
                contentScale = ContentScale.FillBounds,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** Streams audio or video from the server with the Basic auth header (Range requests work). */
@Composable
fun MediaPlayer(url: String, headers: Map<String, String>, audio: Boolean, modifier: Modifier = Modifier) {
    var failed by remember(url) { mutableStateOf(false) }
    Box(
        modifier
            .fillMaxWidth()
            .aspectRatio(if (audio) 16f / 7f else 16f / 9f)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        AndroidView(
            factory = { context ->
                FrameLayout(context).apply {
                    val video = VideoView(context)
                    addView(
                        video,
                        FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER),
                    )
                    val controller = MediaController(context)
                    controller.setAnchorView(this)
                    video.setMediaController(controller)
                    video.setOnPreparedListener {
                        video.start()
                        controller.show(0)
                    }
                    video.setOnErrorListener { _, _, _ ->
                        failed = true
                        true
                    }
                    video.setVideoURI(Uri.parse(url), headers)
                    tag = controller
                }
            },
            onRelease = { frame ->
                (frame.tag as? MediaController)?.hide()
                (frame.getChildAt(0) as? VideoView)?.stopPlayback()
            },
            modifier = Modifier.fillMaxSize(),
        )
        if (audio && !failed) {
            Icon(PiIcons.Music, contentDescription = null, tint = Color.White.copy(alpha = 0.5f), modifier = Modifier.size(40.dp))
        }
        if (failed) {
            Text(
                "Can't play this file here. Try “Open with”.",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(24.dp),
            )
        }
    }
}
