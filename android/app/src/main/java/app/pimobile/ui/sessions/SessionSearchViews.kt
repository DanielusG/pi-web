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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.pimobile.ui.baseName
import app.pimobile.ui.relativeTime
import app.pimobile.ui.shortPath
import app.pimobile.ui.theme.GeistMono
import app.pimobile.ui.theme.Pi
import app.pimobile.ui.theme.PiIcons
import app.pimobile.ui.theme.ShimmerText
import app.pimobile.ui.theme.StatusDot
import app.pimobile.ui.theme.piTextFieldColors

private val GroupShape = RoundedCornerShape(14.dp)

/** The list header while searching: Back, and the field pi's selector types into. */
@Composable
fun SessionSearchBar(query: String, onQuery: (String) -> Unit, onClose: () -> Unit) {
    val t = Pi.tokens
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    // Opening the search is asking to type: the keyboard comes with it.
    LaunchedEffect(Unit) { if (query.isEmpty()) focus.requestFocus() }
    Row(
        Modifier
            .fillMaxWidth()
            .background(t.background)
            .padding(start = 4.dp, end = 16.dp, top = 40.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose) {
            Icon(PiIcons.ArrowLeft, contentDescription = "Close search", tint = t.text, modifier = Modifier.size(22.dp))
        }
        // The files screen's search field.
        Row(
            Modifier
                .weight(1f)
                .clip(RoundedCornerShape(12.dp))
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
                keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 11.dp)
                    .focusRequester(focus),
                decorationBox = { field ->
                    Box {
                        if (query.isEmpty()) {
                            Text("Search sessions", style = MaterialTheme.typography.bodyMedium, color = t.textTertiary)
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
                        // Clearing is asking for a new query: the field takes the keyboard back.
                        .clickable {
                            onQuery("")
                            focus.requestFocus()
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(PiIcons.Close, contentDescription = "Clear search", tint = t.textSecondary, modifier = Modifier.size(14.dp))
                }
            }
        }
    }
}

/** Results of [SessionSearch], with the project and sort choices above them. */
@Composable
fun SessionSearchList(
    state: SessionSearchState,
    projects: List<ProjectGroup>,
    running: Set<String>,
    waiting: Set<String>,
    onScope: (SearchScope?) -> Unit,
    onSort: (SearchSort) -> Unit,
    onMore: () -> Unit,
    onRetry: () -> Unit,
    onOpen: (SearchHit) -> Unit,
    onLongClick: (SessionRow) -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = Pi.tokens
    var pickScope by rememberSaveable { mutableStateOf(false) }
    val results = state.results
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 32.dp),
    ) {
        item(key = "controls") {
            Row(
                Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // The chip takes what the sort switch leaves, and only what its name needs.
                ScopeChip(state.scope, onClick = { pickScope = true }, modifier = Modifier.weight(1f, fill = false))
                Spacer(Modifier.width(12.dp))
                SortSwitch(state.sort, onSort)
            }
        }
        if (state.query.isNotBlank()) {
            item(key = "syntax") {
                // pi's selector hint, the same words; an empty query shows the longer help instead.
                Text(
                    "\"phrase\" exact  ·  re:<pattern> regex",
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = GeistMono, fontWeight = FontWeight.Normal),
                    color = t.textTertiary,
                    modifier = Modifier.padding(start = 4.dp, top = 10.dp),
                )
            }
        }
        when {
            state.query.isBlank() -> item(key = "help") { SearchHelp() }
            state.error != null -> item(key = "error") {
                Column(Modifier.padding(start = 4.dp, top = 20.dp)) {
                    Text(state.error, style = MaterialTheme.typography.bodyMedium, color = t.danger)
                    Text(
                        "Retry",
                        style = MaterialTheme.typography.labelLarge,
                        color = t.textSecondary,
                        modifier = Modifier
                            .padding(top = 8.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(onClick = onRetry)
                            .padding(vertical = 6.dp),
                    )
                }
            }
            results == null -> item(key = "searching") {
                Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                    ShimmerText("Searching")
                }
            }
            else -> {
                item(key = "status") { SearchStatus(results, state.loading) }
                if (results.hits.isNotEmpty()) {
                    item(key = "hits") {
                        Column(
                            Modifier
                                .padding(top = 8.dp)
                                .fillMaxWidth()
                                .clip(GroupShape)
                                .border(1.dp, t.border, GroupShape),
                        ) {
                            results.hits.forEachIndexed { index, hit ->
                                if (index > 0) HorizontalDivider(color = t.border)
                                SearchHitRow(
                                    hit,
                                    running = hit.row.id in running,
                                    waiting = hit.row.id in waiting,
                                    onClick = { onOpen(hit) },
                                    onLongClick = { onLongClick(hit.row) },
                                )
                            }
                            if (results.hasMore) {
                                HorizontalDivider(color = t.border)
                                val canMore = state.limit < SessionSearch.MAX_LIMIT
                                Text(
                                    when {
                                        state.loading -> "Loading…"
                                        canMore -> "Show ${SessionSearch.PAGE} more"
                                        else -> "More sessions match: refine the search"
                                    },
                                    style = MaterialTheme.typography.labelLarge,
                                    color = t.textSecondary,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable(enabled = canMore && !state.loading, onClick = onMore)
                                        .padding(horizontal = 16.dp, vertical = 12.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (pickScope) {
        ProjectScopeSheet(
            projects = projects,
            selected = state.scope,
            onDismiss = { pickScope = false },
            onSelect = { scope ->
                pickScope = false
                onScope(scope)
            },
        )
    }
}

@Composable
private fun SearchHelp() {
    val t = Pi.tokens
    Column(
        Modifier.padding(start = 4.dp, end = 4.dp, top = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            "Searches every session as pi's /resume does",
            style = MaterialTheme.typography.bodyMedium,
            color = t.textSecondary,
        )
        HelpLine("deploy fix", "every word, in the name, the messages or the folder")
        HelpLine("\"deploy fix\"", "those words together, exactly")
        HelpLine("re:fix(ed)?", "a regular expression")
    }
}

@Composable
private fun HelpLine(example: String, meaning: String) {
    val t = Pi.tokens
    Column {
        Text(
            example,
            style = MaterialTheme.typography.labelMedium.copy(fontFamily = GeistMono, fontWeight = FontWeight.Normal),
            color = t.text,
        )
        Text(meaning, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Normal), color = t.textTertiary)
    }
}

@Composable
private fun SearchStatus(results: SearchResults, loading: Boolean) {
    val t = Pi.tokens
    val count = results.hits.size
    val main = when {
        // pi's own words, e.g. "Invalid regular expression: /kube(/i: Unterminated group".
        results.queryError != null -> results.queryError
        count == 0 -> "No sessions match"
        results.hasMore -> "First $count sessions"
        count == 1 -> "1 session"
        else -> "$count sessions"
    }
    Column(Modifier.padding(start = 4.dp, top = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                main,
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                color = if (results.queryError != null) t.danger else t.text,
            )
            if (loading) {
                Spacer(Modifier.width(10.dp))
                ShimmerText("Searching")
            }
        }
        if (results.truncated) {
            Text(
                "Searched ${results.scanned} of ${results.total} sessions: the server stopped at its time limit",
                style = MaterialTheme.typography.labelSmall,
                color = t.textTertiary,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/** The text with [match] marked: the files screen is monochrome, so the mark is the muted fill. */
@Composable
private fun highlighted(text: Highlight): AnnotatedString {
    val t = Pi.tokens
    return remember(text, t) {
        buildAnnotatedString {
            append(text.before)
            withStyle(SpanStyle(background = t.muted, color = t.text, fontWeight = FontWeight.SemiBold)) { append(text.match) }
            append(text.after)
        }
    }
}

@Composable
private fun SearchHitRow(hit: SearchHit, running: Boolean, waiting: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val t = Pi.tokens
    val row = hit.row
    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.weight(1f)) {
            // A hit in the message the title shows (an unnamed session's first message) marks the
            // title instead of repeating it as a snippet.
            val snippetIsTitle = hit.snippet?.text?.let { it.before + it.match + it.after } == row.title
            val title = hit.nameMatch?.let { highlighted(it) }
                ?: hit.snippet?.takeIf { snippetIsTitle }?.let { highlighted(it.text) }
                ?: AnnotatedString(row.title)
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                color = t.text,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val meta = buildList {
                add(baseName(hit.projectRoot).ifEmpty { "Unknown project" })
                hit.subagent?.let { add("subagent · $it") }
                add(relativeTime(row.modified))
                add(if (row.messageCount == 1) "1 message" else "${row.messageCount} messages")
            }.filter { it.isNotEmpty() }.joinToString("  ·  ")
            Text(
                meta,
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Normal),
                color = t.textTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
            hit.snippet?.takeUnless { snippetIsTitle }?.let { snippet ->
                Text(
                    highlighted(snippet.text),
                    style = MaterialTheme.typography.bodySmall,
                    color = t.textSecondary,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
        if (waiting || running) {
            Spacer(Modifier.width(12.dp))
            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                StatusDot(if (waiting) t.warning else t.success)
                Spacer(Modifier.width(6.dp))
                Text(if (waiting) "Waiting" else "Running", style = MaterialTheme.typography.labelSmall, color = t.textSecondary)
            }
        }
    }
}

/** The project the search is limited to; it opens [ProjectScopeSheet]. */
@Composable
private fun ScopeChip(scope: SearchScope?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val t = Pi.tokens
    Row(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .border(1.dp, t.border, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(start = 10.dp, end = 8.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(PiIcons.Folder, null, tint = t.textTertiary, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(6.dp))
        Text(
            scope?.let { baseName(it.root).ifEmpty { "Unknown project" } } ?: "All projects",
            style = MaterialTheme.typography.labelMedium,
            color = t.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Spacer(Modifier.width(4.dp))
        Icon(PiIcons.ChevronDown, null, tint = t.textTertiary, modifier = Modifier.size(13.dp))
    }
}

/** pi's Sort toggle, as the file viewer's Source / Preview switch. */
@Composable
private fun SortSwitch(sort: SearchSort, onSort: (SearchSort) -> Unit) {
    val t = Pi.tokens
    Row(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(t.muted)
            .padding(3.dp),
    ) {
        SearchSort.entries.forEach { mode ->
            val selected = mode == sort
            Text(
                mode.label,
                style = MaterialTheme.typography.labelMedium,
                color = if (selected) t.text else t.textSecondary,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (selected) t.background else Color.Transparent)
                    .clickable { onSort(mode) }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

/** Every project of the list, and "All projects": the TUI's Current Folder / All scope. */
@Composable
private fun ProjectScopeSheet(
    projects: List<ProjectGroup>,
    selected: SearchScope?,
    onDismiss: () -> Unit,
    onSelect: (SearchScope?) -> Unit,
) {
    val t = Pi.tokens
    var filter by rememberSaveable { mutableStateOf("") }
    val matches = remember(filter, projects) {
        val needle = filter.trim().lowercase()
        if (needle.isEmpty()) projects
        else projects.filter { baseName(it.root).lowercase().contains(needle) || it.root.lowercase().contains(needle) }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
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
                .padding(bottom = 12.dp),
        ) {
            Text(
                "Search in",
                style = MaterialTheme.typography.titleLarge,
                color = t.text,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            OutlinedTextField(
                value = filter,
                onValueChange = { filter = it },
                placeholder = { Text("Filter projects", style = MaterialTheme.typography.bodyMedium) },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                colors = piTextFieldColors(),
                textStyle = MaterialTheme.typography.bodyMedium,
                leadingIcon = { Icon(PiIcons.Search, null, tint = t.textTertiary, modifier = Modifier.size(18.dp)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 4.dp),
            )
            Spacer(Modifier.height(8.dp))
            LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false)) {
                if (filter.isBlank()) {
                    item(key = "all") {
                        ScopeRow(
                            title = "All projects",
                            subtitle = if (projects.size == 1) "1 project" else "${projects.size} projects",
                            mono = false,
                            count = null,
                            selected = selected == null,
                            onClick = { onSelect(null) },
                        )
                    }
                }
                items(matches, key = { it.key }) { group ->
                    ScopeRow(
                        title = baseName(group.root).ifEmpty { "Unknown project" },
                        subtitle = shortPath(group.root),
                        count = group.total,
                        selected = selected?.key == group.key,
                        onClick = { onSelect(SearchScope(group.key, group.root)) },
                    )
                }
            }
        }
    }
}

/** A row of [ProjectScopeSheet], chosen as the model sheet shows its model: muted fill and a check. */
@Composable
private fun ScopeRow(title: String, subtitle: String, count: Int?, selected: Boolean, onClick: () -> Unit, mono: Boolean = true) {
    val t = Pi.tokens
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) t.muted else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(PiIcons.Folder, null, tint = t.textTertiary, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.labelLarge, color = t.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = if (mono) GeistMono else null, fontWeight = FontWeight.Normal),
                color = t.textTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        count?.let {
            Spacer(Modifier.width(8.dp))
            Text("$it", style = MaterialTheme.typography.labelSmall, color = t.textTertiary)
        }
        if (selected) {
            Spacer(Modifier.width(8.dp))
            Icon(PiIcons.Check, contentDescription = "Selected", tint = t.text, modifier = Modifier.size(18.dp))
        }
    }
}
