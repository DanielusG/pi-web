@file:OptIn(ExperimentalMaterial3Api::class)

package app.pimobile.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.pimobile.PiApp
import app.pimobile.ui.baseName
import app.pimobile.ui.sessions.SessionsViewModel
import app.pimobile.ui.shortPath
import app.pimobile.ui.theme.GeistMono
import app.pimobile.ui.theme.Pi
import app.pimobile.ui.theme.PiIcons
import app.pimobile.ui.theme.PiPrimaryButton
import app.pimobile.ui.theme.piTextFieldColors
import kotlinx.coroutines.launch

private val GroupShape = RoundedCornerShape(14.dp)

/**
 * Settings row: the project a fresh session opens in when the app is launched from the
 * system assistant trigger. Saved on pick; unset follows the last used project.
 */
@Composable
fun AssistProjectSetting(app: PiApp, modifier: Modifier = Modifier) {
    val t = Pi.tokens
    val scope = rememberCoroutineScope()
    // null until the first read, so the row doesn't flash "Last used project".
    val chosen by app.settings.assistCwd.collectAsState(initial = null)
    val lastCwd by app.settings.lastCwd.collectAsState(initial = "")
    var picking by rememberSaveable { mutableStateOf(false) }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FieldLabel("Assistant project")
        Row(
            Modifier
                .fillMaxWidth()
                .clip(GroupShape)
                .border(1.dp, t.border, GroupShape)
                .clickable { picking = true }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(PiIcons.Folder, null, tint = t.textTertiary, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                val current = chosen
                Text(
                    when {
                        current == null -> ""
                        current.isBlank() -> "Last used project"
                        else -> baseName(current)
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = t.text,
                )
                Text(
                    when {
                        current == null -> ""
                        current.isNotBlank() -> shortPath(current)
                        lastCwd.isNotBlank() -> shortPath(lastCwd)
                        else -> "None yet"
                    },
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = GeistMono, fontWeight = FontWeight.Normal),
                    color = t.textTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(PiIcons.ChevronRight, contentDescription = "Change", tint = t.textTertiary, modifier = Modifier.size(16.dp))
        }
        Text(
            "Where a new session opens when Pi Mobile is launched as the system assistant (corner swipe, long-press power).",
            style = MaterialTheme.typography.labelSmall,
            color = t.textTertiary,
        )
    }

    if (picking) {
        AssistProjectSheet(
            app = app,
            chosen = chosen.orEmpty(),
            lastCwd = lastCwd,
            onDismiss = { picking = false },
            onPick = { cwd ->
                picking = false
                scope.launch { app.settings.saveAssistCwd(cwd) }
            },
        )
    }
}

@Composable
private fun AssistProjectSheet(
    app: PiApp,
    chosen: String,
    lastCwd: String,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    val t = Pi.tokens
    val scope = rememberCoroutineScope()
    // Same project list and path validation as the session list.
    val vm = viewModel { SessionsViewModel(app.api) }
    val state by vm.state.collectAsState()
    LaunchedEffect(vm) { vm.refresh(force = false) }
    var path by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    val roots = state.groups.map { it.root }
    // A typed directory isn't a project in the list; keep it visible as the selection.
    val projects = if (chosen.isNotBlank() && chosen !in roots) listOf(chosen) + roots else roots

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
            Text("Assistant project", style = MaterialTheme.typography.titleLarge, color = t.text)
            Text(
                "Where the assistant trigger opens a new session",
                style = MaterialTheme.typography.bodyMedium,
                color = t.textSecondary,
                modifier = Modifier.padding(top = 2.dp, bottom = 16.dp),
            )
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(GroupShape)
                    .border(1.dp, t.border, GroupShape),
            ) {
                PickRow(
                    icon = PiIcons.Refresh,
                    title = "Last used project",
                    subtitle = if (lastCwd.isNotBlank()) shortPath(lastCwd) else "None yet: opens the new-session sheet",
                    selected = chosen.isBlank(),
                    onClick = { onPick("") },
                )
                projects.forEach { root ->
                    HorizontalDivider(color = t.border)
                    PickRow(
                        icon = PiIcons.Folder,
                        title = baseName(root),
                        subtitle = shortPath(root),
                        selected = root == chosen,
                        onClick = { onPick(root) },
                    )
                }
                if (state.loading) {
                    HorizontalDivider(color = t.border)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(strokeWidth = 2.dp, color = t.textTertiary, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(12.dp))
                        Text("Loading projects…", style = MaterialTheme.typography.bodyMedium, color = t.textSecondary)
                    }
                }
            }
            state.error?.let { message ->
                Text(
                    message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = t.danger,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = path,
                    onValueChange = { path = it; vm.clearError() },
                    placeholder = { Text("Other directory, e.g. ~/projects/app") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = piTextFieldColors(),
                    textStyle = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                PiPrimaryButton(
                    "Use",
                    enabled = path.isNotBlank(),
                    loading = busy,
                    onClick = {
                        scope.launch {
                            busy = true
                            val cwd = vm.validateCwd(path)
                            busy = false
                            if (cwd != null) onPick(cwd)
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun PickRow(icon: ImageVector, title: String, subtitle: String, selected: Boolean, onClick: () -> Unit) {
    val t = Pi.tokens
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = t.textTertiary, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.labelLarge, color = t.text)
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = GeistMono, fontWeight = FontWeight.Normal),
                color = t.textTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (selected) {
            Spacer(Modifier.width(8.dp))
            Icon(PiIcons.Check, contentDescription = "Selected", tint = t.text, modifier = Modifier.size(18.dp))
        }
    }
}
