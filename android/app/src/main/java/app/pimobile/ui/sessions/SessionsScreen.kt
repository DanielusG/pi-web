@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package app.pimobile.ui.sessions

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import app.pimobile.ui.baseName
import app.pimobile.ui.relativeTime
import app.pimobile.ui.shortPath
import app.pimobile.ui.theme.GeistMono
import app.pimobile.ui.theme.Pi
import app.pimobile.ui.theme.PiIcons
import app.pimobile.ui.theme.PiPrimaryButton
import app.pimobile.ui.theme.PiSecondaryButton
import app.pimobile.ui.theme.ShimmerText
import app.pimobile.ui.theme.StatusDot
import app.pimobile.ui.theme.piTextFieldColors
import kotlinx.coroutines.launch

private const val COLLAPSED_COUNT = 5
private val GroupShape = RoundedCornerShape(14.dp)

@Composable
fun SessionsScreen(
    vm: SessionsViewModel,
    serverLabel: String,
    onOpen: (id: String, cwd: String) -> Unit,
    onNew: (cwd: String) -> Unit,
    onSettings: () -> Unit,
    onBrowse: (root: String) -> Unit = {},
    /** Assistant trigger with no remembered cwd: open the new-session sheet directly. */
    startWithNewSheet: Boolean = false,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val t = Pi.tokens
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { vm.watch() }
    }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.error, state.groups.isEmpty()) {
        val error = state.error
        if (error != null && state.groups.isNotEmpty()) {
            snackbar.showSnackbar(error)
            vm.clearError()
        }
    }
    var showNew by rememberSaveable { mutableStateOf(startWithNewSheet) }
    var sheetRow by remember { mutableStateOf<SessionRow?>(null) }

    Scaffold(
        containerColor = t.background,
        topBar = { SessionsHeader(serverLabel, runningCount = state.running.size, waitingCount = state.waiting.size, onSettings = onSettings) },
        floatingActionButton = { NewSessionButton(onClick = { showNew = true }) },
        snackbarHost = {
            SnackbarHost(snackbar) { data ->
                Snackbar(data, shape = RoundedCornerShape(12.dp), containerColor = t.text, contentColor = t.background)
            }
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = { vm.refresh(force = true) },
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 112.dp),
            ) {
                when {
                    state.loading -> item("loading") {
                        Box(Modifier.fillMaxWidth().padding(64.dp), contentAlignment = Alignment.Center) {
                            ShimmerText("Loading sessions")
                        }
                    }
                    state.groups.isEmpty() && state.error != null -> item("error") {
                        ErrorState(state.error!!, onRetry = { vm.refresh(force = true) }, onSettings = onSettings)
                    }
                    state.groups.isEmpty() -> item("empty") {
                        Text(
                            "No sessions yet. Start one with “New session”.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = t.textSecondary,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 48.dp),
                        )
                    }
                }
                state.groups.forEach { group ->
                    item(key = "project:${group.root}") {
                        val expanded = group.root in state.expanded
                        ProjectGroupView(
                            group = group,
                            expanded = expanded,
                            running = state.running,
                            waiting = state.waiting,
                            onOpen = onOpen,
                            onLongClick = { sheetRow = it },
                            onToggle = { vm.toggleProject(group.root) },
                            onBrowse = { onBrowse(group.root) },
                        )
                    }
                }
            }
        }
    }

    if (showNew) {
        NewSessionSheet(
            recentCwds = state.recentCwds,
            onDismiss = { showNew = false },
            validate = vm::validateCwd,
            onStart = { cwd ->
                showNew = false
                onNew(cwd)
            },
        )
    }

    sheetRow?.let { row ->
        SessionActionsSheet(
            row = row,
            onDismiss = { sheetRow = null },
            rename = { name -> vm.rename(row.id, name) },
            delete = { vm.delete(row.id) },
        )
    }
}

@Composable
private fun SessionsHeader(serverLabel: String, runningCount: Int, waitingCount: Int, onSettings: () -> Unit) {
    val t = Pi.tokens
    Row(
        Modifier
            .fillMaxWidth()
            .background(t.background)
            .padding(start = 20.dp, end = 8.dp, top = 40.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Sessions", style = MaterialTheme.typography.headlineMedium, color = t.text)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                Text(serverLabel, style = MaterialTheme.typography.labelMedium, color = t.textTertiary)
                if (runningCount > 0) {
                    Spacer(Modifier.width(10.dp))
                    StatusDot(t.success, pulsing = true)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (runningCount == 1) "1 running" else "$runningCount running",
                        style = MaterialTheme.typography.labelMedium,
                        color = t.textSecondary,
                    )
                }
                if (waitingCount > 0) {
                    Spacer(Modifier.width(10.dp))
                    StatusDot(t.warning, pulsing = false)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (waitingCount == 1) "1 waiting" else "$waitingCount waiting",
                        style = MaterialTheme.typography.labelMedium,
                        color = t.textSecondary,
                    )
                }
            }
        }
        IconButton(onClick = onSettings) {
            Icon(PiIcons.Sliders, contentDescription = "Server settings", tint = t.textSecondary, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun NewSessionButton(onClick: () -> Unit) {
    val t = Pi.tokens
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(t.primary)
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 20.dp, top = 13.dp, bottom = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(PiIcons.Plus, contentDescription = null, tint = t.onPrimary, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text("New session", style = MaterialTheme.typography.labelLarge, color = t.onPrimary)
    }
}

@Composable
private fun ProjectGroupView(
    group: ProjectGroup,
    expanded: Boolean,
    running: Set<String>,
    waiting: Set<String>,
    onOpen: (id: String, cwd: String) -> Unit,
    onLongClick: (SessionRow) -> Unit,
    onToggle: () -> Unit,
    onBrowse: () -> Unit,
) {
    val t = Pi.tokens
    Column(Modifier.padding(top = 20.dp)) {
        Row(
            Modifier.padding(start = 4.dp, end = 4.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(PiIcons.Folder, null, tint = t.textTertiary, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                baseName(group.root).ifEmpty { "Unknown project" },
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                color = t.text,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                shortPath(group.root),
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = GeistMono, fontWeight = FontWeight.Normal),
                color = t.textTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text("${group.sessions.size}", style = MaterialTheme.typography.labelSmall, color = t.textTertiary)
            if (group.root.isNotEmpty()) {
                Spacer(Modifier.width(6.dp))
                Row(
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .clickable(onClick = onBrowse)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(PiIcons.FileText, contentDescription = null, tint = t.textSecondary, modifier = Modifier.size(13.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Files", style = MaterialTheme.typography.labelSmall, color = t.textSecondary)
                }
            }
        }
        Column(
            Modifier
                .fillMaxWidth()
                .clip(GroupShape)
                .border(1.dp, t.border, GroupShape),
        ) {
            val visible = if (expanded) group.sessions else group.sessions.take(COLLAPSED_COUNT)
            visible.forEachIndexed { index, row ->
                if (index > 0) HorizontalDivider(color = t.border)
                SessionRowView(
                    row,
                    running = row.id in running,
                    waiting = row.id in waiting,
                    onClick = { onOpen(row.id, row.cwd) },
                    onLongClick = { onLongClick(row) },
                )
            }
            if (group.sessions.size > COLLAPSED_COUNT) {
                HorizontalDivider(color = t.border)
                Text(
                    if (expanded) "Show less" else "Show all ${group.sessions.size}",
                    style = MaterialTheme.typography.labelLarge,
                    color = t.textSecondary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onToggle)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun SessionRowView(row: SessionRow, running: Boolean, waiting: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val t = Pi.tokens
    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                row.title,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                color = t.text,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val meta = buildList {
                add(relativeTime(row.modified))
                add(if (row.messageCount == 1) "1 message" else "${row.messageCount} messages")
                row.branch?.let { add(it) }
            }.filter { it.isNotEmpty() }.joinToString("  ·  ")
            Text(
                meta,
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Normal),
                color = t.textTertiary,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        if (waiting) {
            Spacer(Modifier.width(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(t.warning, pulsing = false)
                Spacer(Modifier.width(6.dp))
                Text("Waiting", style = MaterialTheme.typography.labelSmall, color = t.textSecondary)
            }
        } else if (running) {
            Spacer(Modifier.width(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(t.success, pulsing = true)
                Spacer(Modifier.width(6.dp))
                Text("Running", style = MaterialTheme.typography.labelSmall, color = t.textSecondary)
            }
        }
    }
}

@Composable
private fun ErrorState(message: String, onRetry: () -> Unit, onSettings: () -> Unit) {
    val t = Pi.tokens
    Column(
        Modifier.padding(horizontal = 4.dp, vertical = 48.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Can't reach the server", style = MaterialTheme.typography.titleLarge, color = t.text)
        Text(message, style = MaterialTheme.typography.bodyMedium, color = t.textSecondary)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
            PiPrimaryButton("Retry", onClick = onRetry)
            PiSecondaryButton("Server settings", onClick = onSettings)
        }
    }
}

@Composable
private fun NewSessionSheet(
    recentCwds: List<String>,
    onDismiss: () -> Unit,
    validate: suspend (String) -> String?,
    onStart: (String) -> Unit,
) {
    val t = Pi.tokens
    val scope = rememberCoroutineScope()
    var path by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

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
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 20.dp),
        ) {
            Text("New session", style = MaterialTheme.typography.titleLarge, color = t.text)
            Text(
                "Pick the working directory on the server",
                style = MaterialTheme.typography.bodyMedium,
                color = t.textSecondary,
                modifier = Modifier.padding(top = 2.dp, bottom = 16.dp),
            )
            if (recentCwds.isNotEmpty()) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(GroupShape)
                        .border(1.dp, t.border, GroupShape),
                ) {
                    recentCwds.forEachIndexed { index, cwd ->
                        if (index > 0) HorizontalDivider(color = t.border)
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onStart(cwd) }
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(PiIcons.Folder, null, tint = t.textTertiary, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(baseName(cwd), style = MaterialTheme.typography.labelLarge, color = t.text)
                                Text(
                                    shortPath(cwd),
                                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = GeistMono, fontWeight = FontWeight.Normal),
                                    color = t.textTertiary,
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = path,
                    onValueChange = { path = it },
                    placeholder = { Text("Other directory, e.g. ~/projects/app") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = piTextFieldColors(),
                    textStyle = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                PiPrimaryButton(
                    "Start",
                    enabled = path.isNotBlank(),
                    loading = busy,
                    onClick = {
                        scope.launch {
                            busy = true
                            val cwd = validate(path)
                            busy = false
                            if (cwd != null) onStart(cwd)
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun SessionActionsSheet(
    row: SessionRow,
    onDismiss: () -> Unit,
    rename: suspend (String) -> String?,
    delete: suspend () -> String?,
) {
    val t = Pi.tokens
    val scope = rememberCoroutineScope()
    var name by rememberSaveable { mutableStateOf(row.title) }
    var renameBusy by remember { mutableStateOf(false) }
    var deleteBusy by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

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
                .imePadding()
                .padding(start = 20.dp, end = 20.dp, bottom = 20.dp),
        ) {
            Text(
                row.title,
                style = MaterialTheme.typography.titleLarge,
                color = t.text,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${relativeTime(row.modified)}  ·  ${if (row.messageCount == 1) "1 message" else "${row.messageCount} messages"}",
                style = MaterialTheme.typography.bodyMedium,
                color = t.textSecondary,
                modifier = Modifier.padding(top = 2.dp, bottom = 20.dp),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(PiIcons.Pencil, null, tint = t.textTertiary, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(8.dp))
                Text("Rename", style = MaterialTheme.typography.labelLarge, color = t.textTertiary)
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = name,
                onValueChange = { name = it; error = null },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                colors = piTextFieldColors(),
                textStyle = MaterialTheme.typography.bodyMedium,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PiPrimaryButton(
                    "Save",
                    enabled = name.isNotBlank(),
                    loading = renameBusy,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        scope.launch {
                            // No-op check mirroring the web sidebar: the fallback title isn't a
                            // real stored name, so don't persist it as one.
                            if (name == row.title || name.trim() == (row.name ?: "")) {
                                onDismiss()
                                return@launch
                            }
                            renameBusy = true
                            error = rename(name.trim())
                            renameBusy = false
                            if (error == null) onDismiss()
                        }
                    },
                )
                PiSecondaryButton("Cancel", onClick = onDismiss, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = t.border)
            Spacer(Modifier.height(16.dp))
            if (confirmDelete) {
                Text(
                    "Delete “${row.title.take(40)}${if (row.title.length > 40) "…" else ""}”?",
                    style = MaterialTheme.typography.bodyMedium,
                    color = t.danger,
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            scope.launch {
                                deleteBusy = true
                                error = delete()
                                deleteBusy = false
                                if (error == null) onDismiss()
                            }
                        },
                        enabled = !deleteBusy,
                        modifier = Modifier.weight(1f).heightIn(min = 44.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = t.danger, contentColor = Color.White),
                    ) {
                        if (deleteBusy) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White)
                        } else {
                            Text("Delete", style = MaterialTheme.typography.labelLarge)
                        }
                    }
                    PiSecondaryButton("Cancel", onClick = { confirmDelete = false }, modifier = Modifier.weight(1f))
                }
            } else {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { confirmDelete = true }
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(PiIcons.Trash, null, tint = t.danger, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(10.dp))
                    Text("Delete session", style = MaterialTheme.typography.labelLarge, color = t.danger)
                }
            }
            error?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, style = MaterialTheme.typography.bodyMedium, color = t.danger)
            }
        }
    }
}
