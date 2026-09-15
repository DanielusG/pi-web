@file:OptIn(ExperimentalFoundationApi::class)

package app.pimobile.ui.chat

import android.icu.text.Collator
import android.icu.text.RuleBasedCollator
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pimobile.ui.theme.GeistMono
import app.pimobile.ui.theme.Pi

/** Declaration order is the palette's group order (web: SLASH_SOURCES). */
enum class SlashSource(val label: String) {
    Builtin("Built-in"),
    Extension("Extensions"),
    Prompt("Prompts"),
    Skill("Skills"),
}

data class SlashCommand(
    val name: String,
    val description: String,
    val source: SlashSource,
    /** Built-ins only: may run while the agent is working. */
    val availableWhileStreaming: Boolean = false,
)

data class SlashGroup(val source: SlashSource, val commands: List<SlashCommand>)

/**
 * Web: BUILTIN_SLASH_COMMANDS. Only the built-ins this client runs are listed;
 * any other `/word` reaches the server as a prompt.
 */
val BUILTIN_SLASH_COMMANDS = listOf(
    SlashCommand("compact", "Compress context, optionally with instructions", SlashSource.Builtin),
    SlashCommand("reload", "Reload extensions, skills, prompts, and tools", SlashSource.Builtin),
    SlashCommand("name", "Set the session display name", SlashSource.Builtin),
    SlashCommand("session", "Show session message, token, and cost stats", SlashSource.Builtin, availableWhileStreaming = true),
    SlashCommand("copy", "Copy the last assistant message", SlashSource.Builtin, availableWhileStreaming = true),
    SlashCommand("clone", "Clone the current branch into a new session", SlashSource.Builtin),
)

private val COMMAND_NAME = Regex("^/(\\S+)(?:\\s|$)")

fun builtinSlashCommand(message: String): SlashCommand? {
    val name = COMMAND_NAME.find(message.trim())?.groupValues?.get(1) ?: return null
    return BUILTIN_SLASH_COMMANDS.firstOrNull { it.name == name }
}

/** The palette filter: the text after a leading `/`; the first whitespace closes the palette. */
fun slashQuery(value: String): String? =
    if (value.startsWith("/") && value.drop(1).none(Char::isWhitespace)) value.substring(1).lowercase() else null

// Web: Intl.Collator(undefined, { numeric: true, sensitivity: "base" }).
private val NAME_COLLATOR: Collator = Collator.getInstance().apply {
    strength = Collator.PRIMARY
    (this as? RuleBasedCollator)?.setNumericCollation(true)
}

private fun matchRank(command: SlashCommand, query: String): Int {
    val name = command.name.lowercase()
    return when {
        name == query -> 0
        name.startsWith(query) -> 1
        name.contains(query) -> 2
        command.description.lowercase().contains(query) -> 3
        else -> 4
    }
}

/** Web: filteredSlashCommands — best name match first, then source order, then name. */
fun filterSlashCommands(query: String, running: Boolean, commands: List<SlashCommand>): List<SlashCommand> {
    val builtins = if (running) BUILTIN_SLASH_COMMANDS.filter { it.availableWhileStreaming } else BUILTIN_SLASH_COMMANDS
    return (builtins + commands)
        .filter { it.name.lowercase().contains(query) || it.description.lowercase().contains(query) }
        .sortedWith { a, b ->
            matchRank(a, query).compareTo(matchRank(b, query)).takeIf { it != 0 }
                ?: a.source.compareTo(b.source).takeIf { it != 0 }
                ?: NAME_COLLATOR.compare(a.name, b.name)
        }
}

/** Skill commands are named `skill:<name>`; skills missing from the dormancy map count as active. */
fun isDormantSkill(command: SlashCommand, dormancy: Map<String, Boolean>): Boolean =
    command.source == SlashSource.Skill && command.name.startsWith("skill:") &&
        dormancy[command.name.removePrefix("skill:")] == true

/** Web: buildSlashCommandLayout — grouped by source, dormant skills after active ones. */
fun slashCommandLayout(commands: List<SlashCommand>, dormancy: Map<String, Boolean>): List<SlashGroup> =
    SlashSource.entries.mapNotNull { source ->
        val sourceCommands = commands.filter { it.source == source }
        val ordered = if (source == SlashSource.Skill) {
            sourceCommands.sortedBy { isDormantSkill(it, dormancy) }
        } else {
            sourceCommands
        }
        if (ordered.isEmpty()) null else SlashGroup(source, ordered)
    }

private const val MIN_CARD_WIDTH_DP = 220
private const val GRID_GAP_DP = 8
private val MenuShape = RoundedCornerShape(10.dp)
private val CardShape = RoundedCornerShape(10.dp)

/**
 * The slash palette above the input (web: ChatInput's slash menu). [active]
 * indexes the commands in group order; it is the one Tab inserts.
 */
@Composable
fun SlashCommandMenu(
    groups: List<SlashGroup>,
    matchCount: Int,
    filtering: Boolean,
    loading: Boolean,
    dormancy: Map<String, Boolean>,
    active: Int,
    onSelect: (SlashCommand) -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = Pi.tokens
    val typography = MaterialTheme.typography
    val density = LocalDensity.current
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    val imeHeight = with(density) { WindowInsets.ime.getBottom(density).toDp() }
    // Web: min(72.8vh, 598px, room above the input); ~250dp keeps the top bar and input in view.
    val maxHeight = minOf(598.dp, screenHeight * 0.728f, screenHeight - imeHeight - 250.dp).coerceAtLeast(120.dp)
    val countLabel = if (matchCount == 1) {
        if (filtering) "1 match" else "1 command"
    } else {
        if (filtering) "$matchCount matches" else "$matchCount commands"
    }

    BoxWithConstraints(modifier.fillMaxWidth()) {
        // Web: grid-template-columns: repeat(auto-fit, minmax(220px, 1fr)) inside 10px padding.
        val columns = maxOf(1, ((maxWidth - 20.dp + GRID_GAP_DP.dp) / (MIN_CARD_WIDTH_DP + GRID_GAP_DP).dp).toInt())
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = maxHeight)
                .shadow(6.dp, MenuShape)
                .clip(MenuShape)
                .border(1.dp, t.border, MenuShape)
                .background(t.background),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (loading) "Loading commands..." else "Slash commands · $countLabel",
                    style = typography.labelSmall.copy(fontSize = 11.sp, fontWeight = FontWeight.Normal),
                    color = t.textTertiary,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "Tab / Enter",
                    style = typography.labelSmall.copy(fontFamily = GeistMono, fontSize = 11.sp, fontWeight = FontWeight.Normal),
                    color = t.textTertiary,
                )
            }
            HorizontalDivider(thickness = 1.dp, color = t.border)
            if (!loading && matchCount == 0) {
                Text(
                    "No extension, prompt, or skill commands found",
                    style = typography.bodySmall.copy(fontSize = 12.sp),
                    color = t.textTertiary,
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 14.dp),
                )
            } else {
                SlashGroupList(groups, columns, dormancy, active, onSelect, Modifier.weight(1f, fill = false))
            }
        }
    }
}

@Composable
private fun SlashGroupList(
    groups: List<SlashGroup>,
    columns: Int,
    dormancy: Map<String, Boolean>,
    active: Int,
    onSelect: (SlashCommand) -> Unit,
    modifier: Modifier,
) {
    val t = Pi.tokens
    val typography = MaterialTheme.typography
    val listState = rememberLazyListState()
    // A group narrower than the grid stretches its cards, as auto-fit collapses empty tracks.
    val groupColumns = groups.map { minOf(columns, it.commands.size) }

    LaunchedEffect(active) {
        var lazyIndex = 0
        var offset = 0
        for ((index, group) in groups.withIndex()) {
            val rows = (group.commands.size + groupColumns[index] - 1) / groupColumns[index]
            if (active < offset + group.commands.size) {
                lazyIndex += 1 + (active - offset) / groupColumns[index]
                break
            }
            lazyIndex += 1 + rows
            offset += group.commands.size
        }
        val visible = listState.layoutInfo.visibleItemsInfo
        if (visible.isNotEmpty() && visible.none { it.index == lazyIndex }) {
            listState.scrollToItem((lazyIndex - 1).coerceAtLeast(0))
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier,
        contentPadding = PaddingValues(start = 10.dp, end = 10.dp, top = 6.dp, bottom = 10.dp),
    ) {
        var offset = 0
        groups.forEachIndexed { groupIndex, group ->
            val groupStart = offset
            val perRow = groupColumns[groupIndex]
            stickyHeader(key = "header:${group.source}") {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(t.background)
                        .padding(top = 4.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        group.source.label.uppercase(),
                        style = typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.4.sp),
                        color = t.textTertiary,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        group.commands.size.toString(),
                        style = typography.labelSmall.copy(fontFamily = GeistMono, fontSize = 10.sp, fontWeight = FontWeight.Medium),
                        color = t.textTertiary,
                    )
                }
            }
            val rows = group.commands.chunked(perRow)
            rows.forEachIndexed { rowIndex, row ->
                val lastRow = rowIndex == rows.lastIndex
                item(key = "row:${group.source}:$rowIndex") {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(bottom = if (!lastRow) GRID_GAP_DP.dp else if (groupIndex < groups.lastIndex) 12.dp else 0.dp),
                        horizontalArrangement = Arrangement.spacedBy(GRID_GAP_DP.dp),
                    ) {
                        row.forEachIndexed { cell, command ->
                            SlashCommandCard(
                                command = command,
                                active = groupStart + rowIndex * perRow + cell == active,
                                dormant = isDormantSkill(command, dormancy),
                                onSelect = onSelect,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        repeat(perRow - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
            offset += group.commands.size
        }
    }
}

@Composable
private fun SlashCommandCard(
    command: SlashCommand,
    active: Boolean,
    dormant: Boolean,
    onSelect: (SlashCommand) -> Unit,
    modifier: Modifier,
) {
    val t = Pi.tokens
    val typography = MaterialTheme.typography
    Column(
        modifier
            // Web: box-shadow 0 0 0 1px color-mix(accent 28%) around the active card.
            .border(1.dp, if (active) t.accent.copy(alpha = 0.28f) else Color.Transparent, RoundedCornerShape(11.dp))
            .padding(1.dp)
            .heightIn(min = 58.dp)
            .clip(CardShape)
            .border(1.dp, if (active) t.accent else t.border, CardShape)
            .background(if (active) t.muted else t.surface)
            .clickable { onSelect(command) }
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "/${command.name}",
                style = typography.bodySmall.copy(fontFamily = GeistMono, fontSize = 13.sp),
                color = if (dormant) t.textTertiary else t.text,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (dormant) {
                Text(
                    "dormant",
                    style = typography.labelSmall.copy(fontSize = 9.sp, lineHeight = 12.sp, fontWeight = FontWeight.Normal),
                    color = t.textTertiary,
                    maxLines = 1,
                    modifier = Modifier
                        .padding(start = 6.dp)
                        .border(1.dp, t.border, RoundedCornerShape(3.dp))
                        .padding(horizontal = 4.dp),
                )
            }
        }
        if (command.description.isNotEmpty()) {
            Text(
                command.description,
                style = typography.bodySmall.copy(fontSize = 11.sp, lineHeight = 15.sp),
                color = t.textTertiary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
