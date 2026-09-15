@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package app.pimobile.ui.files

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import app.pimobile.data.FileKind
import app.pimobile.data.FilePaths
import app.pimobile.data.RecentFiles
import app.pimobile.ui.baseName
import app.pimobile.ui.shortPath
import app.pimobile.ui.theme.GeistMono
import app.pimobile.ui.theme.Pi
import app.pimobile.ui.theme.PiIcons
import app.pimobile.ui.theme.PiSecondaryButton
import app.pimobile.ui.theme.ShimmerText
import app.pimobile.ui.theme.StatusDot
import kotlinx.coroutines.awaitCancellation

@Composable
fun FilesScreen(
    vm: FilesViewModel,
    mentionEnabled: Boolean,
    onBack: () -> Unit,
    onOpenFile: (path: String, diff: Boolean) -> Unit,
    onMention: (String) -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val recents by RecentFiles.all.collectAsStateWithLifecycle()
    val t = Pi.tokens
    val snackbar = remember { SnackbarHostState() }
    val exporter = rememberFileExporter(vm.api, vm.sessionId, snackbar)
    val clipboard = LocalClipboardManager.current
    var actionsFor by remember { mutableStateOf<FileEntry?>(null) }

    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            vm.onStart()
            awaitCancellation()
        }
    }

    val searching = state.query.isNotEmpty()
    BackHandler(enabled = searching || (state.tab == FilesTab.Files && state.dir != state.root)) {
        if (searching) vm.setQuery("") else vm.up()
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
                                baseName(state.root),
                                style = MaterialTheme.typography.titleMedium,
                                color = t.text,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                shortPath(state.root),
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = GeistMono, fontWeight = FontWeight.Normal),
                                color = t.textTertiary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    },
                    actions = {
                        if (exporter.busy) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = t.textSecondary)
                        }
                        IconButton(onClick = { vm.refresh() }) {
                            Icon(PiIcons.Refresh, contentDescription = "Refresh", tint = t.textSecondary, modifier = Modifier.size(20.dp))
                        }
                    },
                )
                SearchField(state.query, vm::setQuery)
                if (state.isRepo && !searching) {
                    Row(Modifier.padding(horizontal = 12.dp)) {
                        TabLabel("Files", state.tab == FilesTab.Files) { vm.selectTab(FilesTab.Files) }
                        TabLabel(
                            if (state.changes.isEmpty()) "Changes" else "Changes  ${state.changes.size}",
                            state.tab == FilesTab.Changes,
                        ) { vm.selectTab(FilesTab.Changes) }
                    }
                }
                if (state.tab == FilesTab.Files && !searching) {
                    Breadcrumb(state.root, state.dir, vm::open)
                }
                HorizontalDivider(color = t.border)
            }
        },
        snackbarHost = {
            SnackbarHost(snackbar) { data ->
                Snackbar(data, shape = RoundedCornerShape(12.dp), containerColor = t.text, contentColor = t.background)
            }
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = { vm.refresh() },
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            val row = @Composable { entry: FileEntry, subtitle: String?, onClick: () -> Unit ->
                EntryRow(
                    entry = entry,
                    subtitle = subtitle,
                    change = state.changeByPath[entry.path],
                    changedDir = entry.isDir && entry.path in state.changedDirs,
                    onClick = onClick,
                    onLongClick = { actionsFor = entry },
                )
            }
            fun subtitleOf(path: String): String {
                val dir = FilePaths.parent(path)
                return if (dir == state.root) "" else FilePaths.relative(dir, state.root)
            }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                when {
                    searching -> {
                        val results = state.results
                        when {
                            state.searchError != null -> item("search-error") { Note(state.searchError!!) }
                            results == null -> item("searching") { LoadingNote("Searching") }
                            results.isEmpty() && !state.searching -> item("no-matches") { Note("No matches") }
                            else -> items(results, key = { "match:${it.path}" }) { entry ->
                                row(entry, subtitleOf(entry.path)) {
                                    if (entry.isDir) {
                                        vm.setQuery("")
                                        vm.selectTab(FilesTab.Files)
                                        vm.open(entry.path)
                                    } else {
                                        onOpenFile(entry.path, false)
                                    }
                                }
                            }
                        }
                    }
                    state.tab == FilesTab.Changes -> changeItems(state, row = { change ->
                        row(FileEntry(FilePaths.name(change.path), change.path, false), subtitleOf(change.path)) {
                            onOpenFile(change.path, true)
                        }
                    })
                    else -> {
                        val recent = recents[state.root].orEmpty()
                        if (state.dir == state.root && recent.isNotEmpty()) {
                            item("recent-label") { SectionLabel("Recent") }
                            items(recent, key = { "recent:$it" }) { path ->
                                row(FileEntry(FilePaths.name(path), path, false), subtitleOf(path)) { onOpenFile(path, false) }
                            }
                            item("all-label") { SectionLabel("All files") }
                        }
                        val current = state.current
                        when {
                            current.loaded && current.entries.isEmpty() -> item("empty") { Note("This folder is empty") }
                            current.loaded -> items(current.entries, key = { it.path }) { entry ->
                                row(entry, null) {
                                    if (entry.isDir) vm.open(entry.path) else onOpenFile(entry.path, false)
                                }
                            }
                            current.error != null -> item("error") {
                                Column(Modifier.padding(20.dp)) {
                                    Text(current.error, style = MaterialTheme.typography.bodyMedium, color = t.textSecondary)
                                    Spacer(Modifier.height(12.dp))
                                    PiSecondaryButton("Retry", onClick = vm::retry)
                                }
                            }
                            else -> item("loading") { LoadingNote("Loading") }
                        }
                    }
                }
            }
        }
    }

    actionsFor?.let { entry ->
        EntryActionsSheet(
            entry = entry,
            mentionEnabled = mentionEnabled,
            onDismiss = { actionsFor = null },
            onMention = {
                actionsFor = null
                onMention(FilePaths.atMention(FilePaths.relative(entry.path, state.root), entry.isDir))
            },
            onCopyPath = {
                actionsFor = null
                clipboard.setText(AnnotatedString(entry.path))
            },
            onShare = {
                actionsFor = null
                exporter.share(entry.path)
            },
            onOpenWith = {
                actionsFor = null
                exporter.openWith(entry.path)
            },
        )
    }
}

private fun LazyListScope.changeItems(state: FilesUiState, row: @Composable (GitChange) -> Unit) {
    if (state.changes.isEmpty()) {
        item("no-changes") { Note("No uncommitted changes") }
        return
    }
    item("changes-summary") {
        val t = Pi.tokens
        Row(Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (state.changes.size == 1) "1 changed file" else "${state.changes.size} changed files",
                style = MaterialTheme.typography.labelMedium,
                color = t.textTertiary,
                modifier = Modifier.weight(1f),
            )
            val mono = MaterialTheme.typography.labelMedium.copy(fontFamily = GeistMono)
            Text("+${state.additions}", style = mono, color = t.success)
            Spacer(Modifier.width(6.dp))
            Text("-${state.deletions}", style = mono, color = t.danger)
        }
    }
    items(state.changes, key = { "change:${it.path}" }) { row(it) }
}

@Composable
private fun SearchField(query: String, onQuery: (String) -> Unit) {
    val t = Pi.tokens
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(shape)
            .background(t.muted)
            .padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(PiIcons.Search, contentDescription = null, tint = t.textTertiary, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(10.dp))
        BasicTextField(
            value = query,
            onValueChange = onQuery,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = t.text),
            cursorBrush = SolidColor(t.text),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 11.dp),
            decorationBox = { field ->
                Box {
                    if (query.isEmpty()) {
                        Text("Search files", style = MaterialTheme.typography.bodyMedium, color = t.textTertiary)
                    }
                    field()
                }
            },
        )
        if (query.isNotEmpty()) {
            Box(
                Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .clickable { onQuery("") },
                contentAlignment = Alignment.Center,
            ) {
                Icon(PiIcons.Close, contentDescription = "Clear search", tint = t.textSecondary, modifier = Modifier.size(14.dp))
            }
        }
    }
}

@Composable
private fun TabLabel(text: String, selected: Boolean, onClick: () -> Unit) {
    val t = Pi.tokens
    Column(
        Modifier
            .width(IntrinsicSize.Max)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) t.text else t.textTertiary,
            modifier = Modifier.padding(top = 8.dp, bottom = 8.dp),
        )
        Box(
            Modifier
                .fillMaxWidth()
                .height(2.dp)
                .background(if (selected) t.text else Color.Transparent, RoundedCornerShape(1.dp)),
        )
    }
}

@Composable
private fun Breadcrumb(root: String, dir: String, onOpen: (String) -> Unit) {
    val t = Pi.tokens
    val crumbs = remember(root, dir) {
        buildList {
            add(root to baseName(root))
            if (dir != root && FilePaths.isInside(dir, root)) {
                var path = root
                FilePaths.relative(dir, root).split('/').filter { it.isNotEmpty() }.forEach { segment ->
                    path = FilePaths.join(path, segment)
                    add(path to segment)
                }
            }
        }
    }
    val scroll = rememberScrollState()
    // Keep the current folder in view as the trail grows.
    LaunchedEffect(dir) { snapshotFlow { scroll.maxValue }.collect { scroll.scrollTo(it) } }
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(scroll)
            .padding(horizontal = 14.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        crumbs.forEachIndexed { index, (path, label) ->
            val last = index == crumbs.lastIndex
            if (index > 0) Icon(PiIcons.ChevronRight, null, tint = t.textTertiary, modifier = Modifier.size(12.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = if (last) t.text else t.textSecondary,
                maxLines = 1,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(enabled = !last) { onOpen(path) }
                    .padding(horizontal = 6.dp, vertical = 7.dp),
            )
        }
    }
}

internal fun fileIcon(path: String, isDir: Boolean): ImageVector = when {
    isDir -> PiIcons.Folder
    else -> when (FilePaths.kind(path)) {
        FileKind.Image -> PiIcons.Image
        FileKind.Audio, FileKind.Video -> PiIcons.Music
        FileKind.Text -> PiIcons.FileText
        else -> PiIcons.File
    }
}

@Composable
internal fun changeColor(code: String): Color {
    val t = Pi.tokens
    return when (code) {
        "A", "U" -> t.success
        "D", "C" -> t.danger
        "R" -> t.accent
        "M" -> t.warning
        else -> t.textSecondary
    }
}

@Composable
internal fun ChangeBadge(code: String) {
    val color = changeColor(code)
    Text(
        code,
        style = MaterialTheme.typography.labelSmall.copy(fontFamily = GeistMono, fontWeight = FontWeight.SemiBold, fontSize = 11.sp),
        color = color,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

@Composable
private fun EntryRow(
    entry: FileEntry,
    subtitle: String?,
    change: GitChange?,
    changedDir: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val t = Pi.tokens
    val deleted = change?.code == "D"
    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 20.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            fileIcon(entry.path, entry.isDir),
            contentDescription = null,
            tint = if (entry.isDir) t.textSecondary else t.textTertiary,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                entry.name,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = if (entry.isDir) FontWeight.Medium else FontWeight.Normal,
                    textDecoration = if (deleted) TextDecoration.LineThrough else null,
                ),
                color = if (deleted) t.textTertiary else t.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrEmpty()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = GeistMono, fontWeight = FontWeight.Normal),
                    color = t.textTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        when {
            change != null -> {
                Spacer(Modifier.width(8.dp))
                ChangeBadge(change.code)
            }
            changedDir -> {
                Spacer(Modifier.width(8.dp))
                StatusDot(t.warning)
            }
        }
        if (entry.isDir) {
            Spacer(Modifier.width(8.dp))
            Icon(PiIcons.ChevronRight, null, tint = t.textTertiary, modifier = Modifier.size(14.dp))
        }
    }
}

@Composable
private fun EntryActionsSheet(
    entry: FileEntry,
    mentionEnabled: Boolean,
    onDismiss: () -> Unit,
    onMention: () -> Unit,
    onCopyPath: () -> Unit,
    onShare: () -> Unit,
    onOpenWith: () -> Unit,
) {
    val t = Pi.tokens
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = t.background,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        dragHandle = {
            Box(
                Modifier
                    .padding(vertical = 10.dp)
                    .size(width = 36.dp, height = 4.dp)
                    .clip(CircleShape)
                    .background(t.borderStrong),
            )
        },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 12.dp),
        ) {
            Row(Modifier.padding(horizontal = 24.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(fileIcon(entry.path, entry.isDir), null, tint = t.textSecondary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(entry.name, style = MaterialTheme.typography.titleMedium, color = t.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        shortPath(entry.path),
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = GeistMono, fontWeight = FontWeight.Normal),
                        color = t.textTertiary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp), color = t.border)
            if (mentionEnabled) SheetAction(PiIcons.AtSign, "Mention in chat", onMention)
            SheetAction(PiIcons.Copy, "Copy path", onCopyPath)
            if (!entry.isDir) {
                SheetAction(PiIcons.Share, "Share", onShare)
                SheetAction(PiIcons.ExternalLink, "Open with…", onOpenWith)
            }
        }
    }
}

@Composable
private fun SheetAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    val t = Pi.tokens
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = t.textSecondary, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(16.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, color = t.text)
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, letterSpacing = 0.6.sp),
        color = Pi.tokens.textTertiary,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun Note(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = Pi.tokens.textSecondary,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp),
    )
}

@Composable
private fun LoadingNote(text: String) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(48.dp),
        contentAlignment = Alignment.Center,
    ) {
        ShimmerText(text)
    }
}
