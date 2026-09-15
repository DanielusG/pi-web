@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package app.pimobile.ui.files

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import app.pimobile.data.FileKind
import app.pimobile.data.FilePaths
import app.pimobile.ui.baseName
import app.pimobile.ui.chat.DiffView
import app.pimobile.ui.markdown.Markdown
import app.pimobile.ui.shortPath
import app.pimobile.ui.theme.GeistMono
import app.pimobile.ui.theme.Pi
import app.pimobile.ui.theme.PiIcons
import app.pimobile.ui.theme.PiPrimaryButton
import app.pimobile.ui.theme.PiSecondaryButton
import app.pimobile.ui.theme.ShimmerText
import app.pimobile.ui.theme.StatusDot
import kotlin.math.roundToInt

private const val MAX_NOWRAP_CHARS = 2_000
private const val MAX_WRAP_CHARS = 20_000
private val SourceText = TextStyle(fontFamily = GeistMono, fontSize = 12.5.sp, lineHeight = 19.sp)

@Composable
fun FileViewerScreen(
    vm: FileViewerViewModel,
    mentionEnabled: Boolean,
    onBack: () -> Unit,
    onMention: (String) -> Unit,
    onOpenFile: (String) -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val t = Pi.tokens
    val snackbar = remember { SnackbarHostState() }
    val exporter = rememberFileExporter(vm.api, vm.sessionId, snackbar)
    val clipboard = LocalClipboardManager.current
    var wrap by rememberSaveable { mutableStateOf(FilePaths.isProse(vm.path)) }
    // Line selection (1-based): long-press sets the anchor, a tap extends to that line.
    var anchor by rememberSaveable { mutableStateOf<Int?>(null) }
    var focus by rememberSaveable { mutableStateOf<Int?>(null) }
    val selection = anchor?.let { a ->
        val b = focus ?: a
        minOf(a, b)..maxOf(a, b)
    }
    fun clearSelection() {
        anchor = null
        focus = null
    }
    BackHandler(enabled = anchor != null) { clearSelection() }
    LaunchedEffect(state.mode) { if (state.mode != ViewMode.Source) clearSelection() }

    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { vm.watch() }
    }
    LaunchedEffect(state.notice) {
        state.notice?.let {
            snackbar.showSnackbar(it)
            vm.clearNotice()
        }
    }

    val relativePath = FilePaths.relative(vm.path, state.root)
    val subtitle = when {
        state.deleted -> "Deleted"
        relativePath != vm.path -> FilePaths.parent(relativePath).ifEmpty { baseName(state.root) }
        else -> shortPath(FilePaths.parent(vm.path))
    }

    Scaffold(
        containerColor = t.background,
        topBar = {
            Column(Modifier.background(t.background)) {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = t.background, scrolledContainerColor = t.background),
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(PiIcons.ArrowLeft, contentDescription = "Back", tint = t.text, modifier = Modifier.size(22.dp))
                        }
                    },
                    title = {
                        Column {
                            Text(
                                FilePaths.name(vm.path),
                                style = MaterialTheme.typography.titleMedium,
                                color = t.text,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (state.live) {
                                    StatusDot(t.success)
                                    Spacer(Modifier.width(6.dp))
                                }
                                Text(
                                    subtitle,
                                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = GeistMono, fontWeight = FontWeight.Normal),
                                    color = if (state.deleted) t.danger else t.textTertiary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    },
                    actions = {
                        if (exporter.busy) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = t.textSecondary)
                        }
                        if (mentionEnabled) {
                            IconButton(onClick = {
                                // Web: the mention button uses the selected lines, or the whole file.
                                val text = selection?.let { FilePaths.lineMention(relativePath, it.first, it.last) }
                                    ?: FilePaths.atMention(relativePath, false)
                                onMention(text)
                            }) {
                                Icon(
                                    PiIcons.AtSign,
                                    contentDescription = if (selection != null) "Mention selected lines" else "Mention file in chat",
                                    tint = t.textSecondary,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                        ViewerMenu(
                            showWrap = state.kind == FileKind.Text && state.mode == ViewMode.Source,
                            wrap = wrap,
                            canExport = !state.deleted,
                            onWrap = { wrap = !wrap },
                            onCopyPath = { clipboard.setText(AnnotatedString(vm.path)) },
                            onShare = { exporter.share(vm.path) },
                            onOpenWith = { exporter.openWith(vm.path) },
                            onReload = vm::reload,
                        )
                    },
                )
                if (state.modes.size > 1 && !state.loading) ModeSwitch(state, vm::selectMode)
                HorizontalDivider(color = t.border)
            }
        },
        bottomBar = {
            if (selection != null) {
                SelectionBar(
                    selection = selection,
                    mentionEnabled = mentionEnabled,
                    onClear = { clearSelection() },
                    onCopy = {
                        val lines = state.lines
                        val text = lines.subList((selection.first - 1).coerceIn(0, lines.size), selection.last.coerceIn(0, lines.size))
                        clipboard.setText(AnnotatedString(text.joinToString("\n")))
                        clearSelection()
                    },
                    onMention = { onMention(FilePaths.lineMention(relativePath, selection.first, selection.last)) },
                )
            }
        },
        snackbarHost = {
            SnackbarHost(snackbar) { data ->
                Snackbar(data, shape = RoundedCornerShape(12.dp), containerColor = t.text, contentColor = t.background)
            }
        },
    ) { padding ->
        Box(
            Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            val diff = state.diff
            when {
                state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { ShimmerText("Loading file") }
                state.mode == ViewMode.Diff && diff != null -> Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(12.dp),
                ) {
                    DiffView(diff, maxLines = 5_000)
                }
                state.deleted -> CenteredMessage("This file was deleted", "It no longer exists in the working tree.")
                state.error != null -> CenteredMessage("Can't open this file", state.error) {
                    PiSecondaryButton("Retry", onClick = vm::retry)
                    PiSecondaryButton("Open with…", onClick = { exporter.openWith(vm.path) })
                }
                else -> when (state.kind) {
                    FileKind.Text -> when {
                        state.mode == ViewMode.Preview && FilePaths.isHtml(vm.path) ->
                            HtmlDocument(state.content, Modifier.fillMaxSize(), scripts = true)
                        state.mode == ViewMode.Preview -> Column(
                            Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .padding(horizontal = 20.dp, vertical = 16.dp),
                        ) {
                            val directory = FilePaths.parent(vm.path)
                            SelectionContainer {
                                Markdown(
                                    state.content,
                                    baseDir = directory,
                                    relativeRoot = state.root.ifEmpty { directory },
                                    onOpenFile = onOpenFile,
                                )
                            }
                        }
                        else -> SourceView(
                            state = state,
                            wrap = wrap,
                            selection = selection,
                            onLongPress = { line ->
                                anchor = line
                                focus = line
                            },
                            onTap = { line -> if (anchor != null) focus = line },
                            onLoadMore = vm::loadMore,
                        )
                    }
                    FileKind.Image -> {
                        val image = state.image
                        if (image != null) {
                            ZoomableImage(image)
                        } else {
                            CenteredMessage("Preview not available", "${FilePaths.ext(vm.path).uppercase()} images can't be shown here.") {
                                PiSecondaryButton("Open with…", onClick = { exporter.openWith(vm.path) })
                            }
                        }
                    }
                    FileKind.Pdf -> state.pdf?.let { PdfPages(it, state.revision, Modifier.fillMaxSize()) }
                    FileKind.Docx -> state.html?.let { HtmlDocument(it, Modifier.fillMaxSize()) }
                    FileKind.Audio, FileKind.Video -> Box(
                        Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        MediaPlayer(
                            url = vm.api.absoluteUrl(FilePaths.api(vm.path, "read", vm.sessionId)),
                            headers = vm.api.authHeaders(),
                            audio = state.kind == FileKind.Audio,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ViewerMenu(
    showWrap: Boolean,
    wrap: Boolean,
    canExport: Boolean,
    onWrap: () -> Unit,
    onCopyPath: () -> Unit,
    onShare: () -> Unit,
    onOpenWith: () -> Unit,
    onReload: () -> Unit,
) {
    val t = Pi.tokens
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(PiIcons.More, contentDescription = "More", tint = t.textSecondary, modifier = Modifier.size(20.dp))
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            shape = RoundedCornerShape(14.dp),
            containerColor = t.surface,
            border = BorderStroke(1.dp, t.border),
            shadowElevation = 6.dp,
        ) {
            @Composable
            fun entry(label: String, icon: ImageVector, checked: Boolean = false, action: () -> Unit) {
                DropdownMenuItem(
                    text = { Text(label, style = MaterialTheme.typography.bodyMedium, color = t.text) },
                    leadingIcon = { Icon(icon, null, tint = t.textSecondary, modifier = Modifier.size(18.dp)) },
                    trailingIcon = { if (checked) Icon(PiIcons.Check, null, tint = t.text, modifier = Modifier.size(16.dp)) },
                    onClick = {
                        open = false
                        action()
                    },
                )
            }
            if (showWrap) entry("Wrap lines", PiIcons.WrapText, checked = wrap, action = onWrap)
            entry("Copy path", PiIcons.Copy, action = onCopyPath)
            if (canExport) {
                entry("Share", PiIcons.Share, action = onShare)
                entry("Open with…", PiIcons.ExternalLink, action = onOpenWith)
            }
            entry("Reload", PiIcons.Refresh, action = onReload)
        }
    }
}

@Composable
private fun ModeSwitch(state: FileViewerState, onSelect: (ViewMode) -> Unit) {
    val t = Pi.tokens
    Row(
        Modifier
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(t.muted)
            .padding(3.dp),
    ) {
        state.modes.forEach { mode ->
            val selected = mode == state.mode
            Row(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (selected) t.background else Color.Transparent)
                    .clickable { onSelect(mode) }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    when (mode) {
                        ViewMode.Source -> "Source"
                        ViewMode.Preview -> "Preview"
                        ViewMode.Diff -> "Diff"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = if (selected) t.text else t.textSecondary,
                )
                val files = state.diff
                if (mode == ViewMode.Diff && files != null) {
                    val mono = MaterialTheme.typography.labelSmall.copy(fontFamily = GeistMono)
                    Spacer(Modifier.width(6.dp))
                    Text("+${files.sumOf { it.added }}", style = mono, color = t.success)
                    Spacer(Modifier.width(3.dp))
                    Text("-${files.sumOf { it.removed }}", style = mono, color = t.danger)
                }
            }
        }
    }
}

/** Line-numbered source; without wrap, every line scrolls horizontally together under a fixed gutter. */
@Composable
private fun SourceView(
    state: FileViewerState,
    wrap: Boolean,
    selection: IntRange?,
    onLongPress: (Int) -> Unit,
    onTap: (Int) -> Unit,
    onLoadMore: () -> Unit,
) {
    val t = Pi.tokens
    val lines = state.lines
    if (lines.isEmpty() && !state.truncated) {
        CenteredMessage("Empty file")
        return
    }
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val charWidth = remember(measurer, density) { measurer.measure("0000000000", SourceText).size.width / 10f }
    val digits = maxOf(2, lines.size.toString().length)
    val gutter = with(density) { (charWidth * digits).toDp() } + 24.dp
    val selectedBackground = t.accent.copy(alpha = 0.12f)

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val viewport = with(density) { (maxWidth - gutter).toPx() }
        val contentWidth = charWidth * minOf(state.maxLineLength, MAX_NOWRAP_CHARS + 30) + with(density) { 24.dp.toPx() }
        val maxOffset = if (wrap) 0f else maxOf(0f, contentWidth - viewport)
        var scrollX by remember { mutableFloatStateOf(0f) }
        val limit by rememberUpdatedState(maxOffset)
        LaunchedEffect(maxOffset) { scrollX = scrollX.coerceIn(0f, maxOffset) }
        val horizontal = rememberScrollableState { delta ->
            val old = scrollX
            scrollX = (old - delta).coerceIn(0f, limit)
            old - scrollX
        }
        LazyColumn(
            Modifier
                .fillMaxSize()
                .then(if (wrap) Modifier else Modifier.scrollable(horizontal, Orientation.Horizontal)),
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
            items(lines.size, contentType = { 0 }) { index ->
                val number = index + 1
                val selected = selection != null && number in selection
                val shown = remember(lines[index], wrap) { displayLine(lines[index], wrap) }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(if (selected) selectedBackground else Color.Transparent)
                        .combinedClickable(onClick = { onTap(number) }, onLongClick = { onLongPress(number) }),
                ) {
                    Text(
                        number.toString(),
                        style = SourceText,
                        color = if (selected) t.textSecondary else t.textTertiary,
                        textAlign = TextAlign.End,
                        modifier = Modifier
                            .width(gutter)
                            .padding(end = 12.dp),
                    )
                    if (wrap) {
                        Text(
                            shown,
                            style = SourceText,
                            color = t.text,
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = 12.dp),
                        )
                    } else {
                        Box(
                            Modifier
                                .weight(1f)
                                .clipToBounds(),
                        ) {
                            Text(
                                shown,
                                style = SourceText,
                                color = t.text,
                                softWrap = false,
                                maxLines = 1,
                                modifier = Modifier
                                    .wrapContentWidth(Alignment.Start, unbounded = true)
                                    .offset { IntOffset(-scrollX.roundToInt(), 0) },
                            )
                        }
                    }
                }
            }
            if (state.truncated) {
                item("more") {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            "Showing ${formatBytes(state.nextOffset)} of ${state.size?.let(::formatBytes) ?: "?"}",
                            style = MaterialTheme.typography.labelSmall,
                            color = t.textTertiary,
                        )
                        Spacer(Modifier.size(8.dp))
                        if (state.loadingMore) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = t.textSecondary)
                        } else {
                            PiSecondaryButton("Load more", onClick = onLoadMore)
                        }
                    }
                }
            }
        }
    }
}

private fun displayLine(line: String, wrap: Boolean): String {
    val expanded = if ('\t' in line) line.replace("\t", "    ") else line
    val max = if (wrap) MAX_WRAP_CHARS else MAX_NOWRAP_CHARS
    return if (expanded.length <= max) expanded else expanded.take(max) + " … ${expanded.length - max} more characters"
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
    bytes >= 1024 -> "${bytes / 1024} KB"
    else -> "$bytes B"
}

@Composable
private fun SelectionBar(
    selection: IntRange,
    mentionEnabled: Boolean,
    onClear: () -> Unit,
    onCopy: () -> Unit,
    onMention: () -> Unit,
) {
    val t = Pi.tokens
    Column(Modifier.background(t.surface)) {
        HorizontalDivider(color = t.border)
        Row(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(start = 4.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClear) {
                Icon(PiIcons.Close, contentDescription = "Clear selection", tint = t.textSecondary, modifier = Modifier.size(18.dp))
            }
            Text(
                if (selection.first == selection.last) "Line ${selection.first}" else "Lines ${selection.first}–${selection.last}",
                style = MaterialTheme.typography.labelLarge,
                color = t.text,
                modifier = Modifier.weight(1f),
            )
            PiSecondaryButton("Copy", onClick = onCopy)
            if (mentionEnabled) {
                Spacer(Modifier.width(8.dp))
                PiPrimaryButton("Mention", onClick = onMention)
            }
        }
    }
}

@Composable
private fun CenteredMessage(title: String, body: String? = null, actions: @Composable RowScope.() -> Unit = {}) {
    val t = Pi.tokens
    Column(
        Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = t.text, textAlign = TextAlign.Center)
        if (body != null) {
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                color = t.textSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), content = actions)
    }
}
