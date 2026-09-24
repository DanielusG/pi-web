package app.pimobile.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.pimobile.notify.RunStatus
import app.pimobile.ui.theme.GeistMono
import app.pimobile.ui.theme.Pi
import app.pimobile.ui.theme.PiIcons
import app.pimobile.ui.theme.PiPrimaryButton
import app.pimobile.ui.theme.PiSecondaryButton

/**
 * Covers the whole app while the server lacks the run-state stream: notifications
 * depend on it, and this version has no polling fallback for older pi-web servers.
 */
@Composable
fun ServerOutdatedScreen(serverLabel: String, checking: Boolean, onRetry: () -> Unit, onSettings: () -> Unit) {
    val t = Pi.tokens
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
        ) {
            Icon(PiIcons.Alert, null, tint = t.warning, modifier = Modifier.size(28.dp))
            Text("Update pi-web", style = MaterialTheme.typography.headlineMedium, color = t.text)
            Text(
                "The server at $serverLabel is too old for this version of Pi Mobile. " +
                    "Update pi-web on that machine, restart it, then retry.",
                style = MaterialTheme.typography.bodyMedium,
                color = t.textSecondary,
            )
            Text(
                "Missing: ${RunStatus.PATH}",
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = GeistMono),
                color = t.textTertiary,
                modifier = Modifier.padding(bottom = 10.dp),
            )
            PiPrimaryButton("Retry", onRetry, Modifier.fillMaxWidth(), loading = checking)
            PiSecondaryButton("Server settings", onSettings, Modifier.fillMaxWidth())
        }
    }
}
