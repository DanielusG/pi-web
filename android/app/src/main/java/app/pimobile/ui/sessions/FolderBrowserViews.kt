package app.pimobile.ui.sessions

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pimobile.ui.theme.GeistMono
import app.pimobile.ui.theme.Pi
import app.pimobile.ui.theme.PiIcons
import app.pimobile.ui.theme.PiPrimaryButton
import app.pimobile.ui.theme.PiSecondaryButton
import app.pimobile.ui.theme.piTextFieldColors
import kotlinx.coroutines.launch

private val GroupShape = RoundedCornerShape(14.dp)

/**
 * The second page of a project sheet: the server's folders, one level at a time, for a directory
 * that is no project yet. A tap opens a folder, the breadcrumb goes back up, the pencil takes a
 * typed or pasted path, and [chooseLabel] picks the folder on screen. Back returns to the
 * projects through [onBack], after closing the path or new-folder field.
 */
@Composable
internal fun ColumnScope.FolderBrowserPage(
    browser: FolderBrowser,
    title: String,
    chooseLabel: String,
    onBack: () -> Unit,
    onChoose: (cwd: String) -> Unit,
) {
    val t = Pi.tokens
    val scope = rememberCoroutineScope()
    val state by browser.state.collectAsStateWithLifecycle()
    var editing by rememberSaveable { mutableStateOf(false) }
    var naming by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    var creating by remember { mutableStateOf(false) }
    var choosing by remember { mutableStateOf(false) }
    LaunchedEffect(browser) { browser.open() }

    BackHandler {
        when {
            editing -> editing = false
            naming -> naming = false
            else -> onBack()
        }
    }

    val listing = state.listing
    val path = listing?.path.orEmpty()

    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack, modifier = Modifier.padding(end = 4.dp)) {
            Icon(PiIcons.ArrowLeft, contentDescription = "Back to projects", tint = t.text, modifier = Modifier.size(20.dp))
        }
        Column {
            Text(title, style = MaterialTheme.typography.titleLarge, color = t.text)
            Text("Browse the server's folders", style = MaterialTheme.typography.bodyMedium, color = t.textSecondary)
        }
    }
    Spacer(Modifier.height(12.dp))

    if (editing) {
        PathField(
            initial = path,
            onCancel = { editing = false },
            onGo = { typed ->
                scope.launch {
                    browser.go(typed.trim()).join()
                    // A path the server refused stays in the field, with the reason below.
                    if (browser.state.value.error == null) editing = false
                }
            },
        )
    } else {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clip(GroupShape)
                .border(1.dp, t.border, GroupShape),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Crumbs(path = path, loaded = listing != null, onOpen = browser::go, modifier = Modifier.weight(1f))
            if (state.loading) {
                CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    color = t.textTertiary,
                    modifier = Modifier.padding(horizontal = 16.dp).size(16.dp),
                )
            } else {
                IconButton(onClick = { browser.clearError(); editing = true }) {
                    Icon(PiIcons.Pencil, contentDescription = "Type a path", tint = t.textSecondary, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
    Spacer(Modifier.height(12.dp))

    // As the projects page: the list fills the space when long and hugs its rows when short.
    Column(Modifier.fillMaxWidth().weight(1f)) {
        if (listing == null) {
            if (state.loading) {
                NoteRow {
                    CircularProgressIndicator(strokeWidth = 2.dp, color = t.textTertiary, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(12.dp))
                    Text("Loading folders…", style = MaterialTheme.typography.bodyMedium, color = t.textSecondary)
                }
            } else {
                PiSecondaryButton("Retry", onClick = { browser.open() })
            }
        } else {
            FolderList(
                listing = listing,
                showHidden = state.showHidden,
                onOpen = { browser.go(it.path) },
                onToggleHidden = browser::toggleHidden,
            )
        }
        state.error?.let { message ->
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = t.danger,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
    Spacer(Modifier.height(16.dp))

    // A drive list (Windows, no path) is no folder to start in or to create one inside; while a
    // typed path is open the folder on screen is not the one in the field (the web's rule too).
    val inFolder = path.isNotEmpty() && !editing
    if (naming) {
        NewFolderRow(
            name = name,
            onName = { name = it; browser.clearError() },
            creating = creating,
            onCancel = { naming = false },
            onCreate = {
                scope.launch {
                    creating = true
                    val created = browser.create(name)
                    creating = false
                    if (created) {
                        naming = false
                        name = ""
                    }
                }
            },
        )
    } else {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PiSecondaryButton(
                "New folder",
                enabled = inFolder && !state.loading,
                onClick = { browser.clearError(); naming = true },
            )
            Spacer(Modifier.weight(1f))
            PiPrimaryButton(
                chooseLabel,
                enabled = inFolder && !state.loading,
                loading = choosing,
                onClick = {
                    scope.launch {
                        choosing = true
                        val cwd = browser.choose()
                        choosing = false
                        if (cwd != null) onChoose(cwd)
                    }
                },
            )
        }
    }
}

/** The folder on screen and every folder above it; tapping one opens it. Scrolled to the end. */
@Composable
private fun Crumbs(path: String, loaded: Boolean, onOpen: (String?) -> Unit, modifier: Modifier) {
    val t = Pi.tokens
    val crumbs = remember(path, loaded) { if (loaded) FolderBrowser.crumbs(path) else emptyList() }
    val scroll = rememberScrollState()
    LaunchedEffect(path) { snapshotFlow { scroll.maxValue }.collect { scroll.scrollTo(it) } }
    Row(
        modifier
            .horizontalScroll(scroll)
            .padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        crumbs.forEachIndexed { index, crumb ->
            val last = index == crumbs.lastIndex
            if (index > 0) Icon(PiIcons.ChevronRight, null, tint = t.textTertiary, modifier = Modifier.size(12.dp))
            Text(
                crumb.label,
                style = MaterialTheme.typography.labelMedium,
                color = if (last) t.text else t.textSecondary,
                maxLines = 1,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(enabled = !last) { onOpen(crumb.path) }
                    .padding(horizontal = 6.dp, vertical = 9.dp),
            )
        }
    }
}

@Composable
private fun PathField(initial: String, onCancel: () -> Unit, onGo: (String) -> Unit) {
    val t = Pi.tokens
    // The cursor at the end: the usual edit is the last segment or two.
    var value by remember { mutableStateOf(TextFieldValue(initial, TextRange(initial.length))) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    OutlinedTextField(
        value = value,
        onValueChange = { value = it },
        placeholder = { Text("/path or ~/path", style = MaterialTheme.typography.bodyMedium.copy(fontFamily = GeistMono)) },
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
        colors = piTextFieldColors(),
        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = GeistMono),
        // The URI keyboard has `/` on its first layer and offers no word suggestions.
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go, autoCorrectEnabled = false),
        keyboardActions = KeyboardActions(onGo = { if (value.text.isNotBlank()) onGo(value.text) }),
        trailingIcon = {
            IconButton(onClick = onCancel) {
                Icon(PiIcons.Close, contentDescription = "Cancel", tint = t.textTertiary, modifier = Modifier.size(18.dp))
            }
        },
        modifier = Modifier.fillMaxWidth().focusRequester(focus),
    )
}

@Composable
private fun ColumnScope.FolderList(
    listing: FolderListing,
    showHidden: Boolean,
    onOpen: (ServerFolder) -> Unit,
    onToggleHidden: () -> Unit,
) {
    val t = Pi.tokens
    val (hidden, visible) = remember(listing) { listing.folders.partition(FolderBrowser::isHidden) }
    val rows = if (showHidden) listing.folders else visible
    if (listing.folders.isEmpty()) {
        NoteRow { Text("No folders inside", style = MaterialTheme.typography.bodyMedium, color = t.textSecondary) }
        return
    }
    // A new folder starts at the top, not where the previous one was scrolled to.
    val listState = remember(listing.path) { LazyListState() }
    LazyColumn(
        Modifier
            .fillMaxWidth()
            .weight(1f, fill = false)
            .clip(GroupShape)
            .border(1.dp, t.border, GroupShape),
        state = listState,
    ) {
        itemsIndexed(rows, key = { _, folder -> folder.path }) { index, folder ->
            if (index > 0) HorizontalDivider(color = t.border)
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onOpen(folder) }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(PiIcons.Folder, null, tint = t.textTertiary, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(12.dp))
                Text(
                    folder.name,
                    style = MaterialTheme.typography.labelLarge,
                    color = t.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Icon(PiIcons.ChevronRight, null, tint = t.textTertiary, modifier = Modifier.size(14.dp))
            }
        }
        if (hidden.isNotEmpty()) {
            item(key = "hidden") {
                if (rows.isNotEmpty()) HorizontalDivider(color = t.border)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onToggleHidden)
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        if (showHidden) PiIcons.ChevronUp else PiIcons.ChevronDown,
                        null,
                        tint = t.textTertiary,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        if (showHidden) "Hide hidden folders" else "Show ${hidden.size} hidden folder${if (hidden.size == 1) "" else "s"}",
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Normal),
                        color = t.textSecondary,
                    )
                }
            }
        }
    }
}

@Composable
private fun NewFolderRow(
    name: String,
    onName: (String) -> Unit,
    creating: Boolean,
    onCancel: () -> Unit,
    onCreate: () -> Unit,
) {
    val t = Pi.tokens
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = name,
            onValueChange = onName,
            placeholder = { Text("Folder name", style = MaterialTheme.typography.bodyMedium) },
            singleLine = true,
            enabled = !creating,
            shape = RoundedCornerShape(12.dp),
            colors = piTextFieldColors(),
            textStyle = MaterialTheme.typography.bodyMedium,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done, autoCorrectEnabled = false),
            keyboardActions = KeyboardActions(onDone = { if (name.isNotBlank()) onCreate() }),
            trailingIcon = {
                IconButton(onClick = onCancel, enabled = !creating) {
                    Icon(PiIcons.Close, contentDescription = "Cancel", tint = t.textTertiary, modifier = Modifier.size(18.dp))
                }
            },
            modifier = Modifier.weight(1f).focusRequester(focus),
        )
        Spacer(Modifier.width(8.dp))
        PiPrimaryButton("Create", enabled = name.isNotBlank(), loading = creating, onClick = onCreate)
    }
}

@Composable
private fun NoteRow(content: @Composable () -> Unit) {
    val t = Pi.tokens
    Row(
        Modifier
            .fillMaxWidth()
            .clip(GroupShape)
            .border(1.dp, t.border, GroupShape)
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) { content() }
}
