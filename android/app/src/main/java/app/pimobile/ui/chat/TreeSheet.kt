@file:OptIn(ExperimentalMaterial3Api::class)

package app.pimobile.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pimobile.data.BranchRow
import app.pimobile.data.SessionTree
import app.pimobile.data.TreeNode
import app.pimobile.ui.theme.GeistMono
import app.pimobile.ui.theme.Pi
import app.pimobile.ui.theme.PiIcons

private val GuideWidth = 18.dp

/** /tree: the session's branches (web: BranchNavigator); tapping one continues from it. */
@Composable
fun TreeSheet(tree: List<TreeNode>, leafId: String?, onDismiss: () -> Unit, onSelect: (entryId: String) -> Unit) {
    val t = Pi.tokens
    val rows = remember(tree, leafId) { SessionTree.rows(tree, leafId) }
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
        Text(
            "Branches",
            style = MaterialTheme.typography.titleLarge,
            color = t.text,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
        if (rows.isEmpty()) {
            Text(
                "This session has no branches",
                style = MaterialTheme.typography.bodyMedium,
                color = t.textSecondary,
                modifier = Modifier
                    .padding(horizontal = 24.dp, vertical = 16.dp)
                    .navigationBarsPadding(),
            )
            return@ModalBottomSheet
        }
        LazyColumn(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding(),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 16.dp),
        ) {
            itemsIndexed(rows, key = { index, row -> "$index:${row.targetId}" }) { _, row ->
                BranchRowView(row, current = row.targetId == leafId, onClick = { onSelect(row.targetId) })
            }
        }
    }
}

@Composable
private fun BranchRowView(row: BranchRow, current: Boolean, onClick: () -> Unit) {
    val t = Pi.tokens
    val line = t.border
    Row(
        Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        row.parentLines.forEach { continues ->
            Box(
                Modifier
                    .width(GuideWidth)
                    .fillMaxHeight()
                    .drawBehind {
                        if (continues) {
                            val x = size.width / 2
                            drawLine(line, Offset(x, 0f), Offset(x, size.height), 1.dp.toPx())
                        }
                    },
            )
        }
        Box(
            Modifier
                .width(GuideWidth)
                .fillMaxHeight()
                .drawBehind {
                    val x = size.width / 2
                    val middle = size.height / 2
                    val stroke = 1.dp.toPx()
                    drawLine(line, Offset(x, 0f), Offset(x, if (row.isLast) middle else size.height), stroke)
                    drawLine(line, Offset(x, middle), Offset(size.width, middle), stroke)
                },
        )
        Box(
            Modifier
                .size(9.dp)
                .clip(CircleShape)
                .background(
                    when {
                        row.active -> t.accent
                        row.onPath -> t.textSecondary
                        else -> t.border
                    },
                )
                .then(if (row.active) Modifier else Modifier.border(1.dp, t.textTertiary, CircleShape)),
        )
        Spacer(Modifier.width(8.dp))
        row.role?.let { role -> RoleBadge(user = role == "user") }
        if (row.skipped > 0) {
            Text(
                "+${row.skipped}",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, fontWeight = FontWeight.Normal),
                color = t.textTertiary,
                modifier = Modifier.padding(end = 6.dp),
            )
        }
        Text(
            row.label,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = if (row.active) FontWeight.Medium else FontWeight.Normal),
            color = when {
                row.active -> t.text
                row.onPath -> t.textSecondary
                else -> t.textTertiary
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (current) {
            Spacer(Modifier.width(8.dp))
            Icon(PiIcons.Check, contentDescription = "Current", tint = t.text, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun RoleBadge(user: Boolean) {
    val t = Pi.tokens
    val shape = RoundedCornerShape(5.dp)
    Text(
        if (user) "U" else "A",
        style = MaterialTheme.typography.labelSmall.copy(fontFamily = GeistMono, fontSize = 10.sp, fontWeight = FontWeight.Medium),
        color = if (user) t.accent else t.textTertiary,
        modifier = Modifier
            .padding(end = 6.dp)
            .clip(shape)
            .background(if (user) t.accent.copy(alpha = 0.08f) else t.muted)
            .border(1.dp, if (user) t.accent.copy(alpha = 0.2f) else t.border, shape)
            .padding(horizontal = 5.dp, vertical = 1.dp),
    )
}
