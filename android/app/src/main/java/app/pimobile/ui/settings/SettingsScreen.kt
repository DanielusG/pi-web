@file:OptIn(ExperimentalMaterial3Api::class)

package app.pimobile.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import app.pimobile.PiApp
import app.pimobile.data.ServerConfig
import app.pimobile.data.asObj
import app.pimobile.ui.theme.GeistMono
import app.pimobile.ui.theme.Pi
import app.pimobile.ui.theme.PiIcons
import app.pimobile.ui.theme.PiPrimaryButton
import app.pimobile.ui.theme.piTextFieldColors
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(app: PiApp, canGoBack: Boolean, onBack: () -> Unit, onConnected: () -> Unit) {
    val t = Pi.tokens
    val scope = rememberCoroutineScope()
    val current = app.api.config
    var url by rememberSaveable { mutableStateOf(current.baseUrl) }
    var password by rememberSaveable { mutableStateOf(current.password) }
    var asrUrl by rememberSaveable { mutableStateOf(current.asrUrl) }
    var ttsUrl by rememberSaveable { mutableStateOf(current.ttsUrl) }
    var ttsModel by rememberSaveable { mutableStateOf(current.ttsModel) }
    var ttsVoice by rememberSaveable { mutableStateOf(current.ttsVoice) }
    var showPassword by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Scaffold(
        containerColor = t.background,
        topBar = {
            TopAppBar(
                title = {},
                colors = TopAppBarDefaults.topAppBarColors(containerColor = t.background),
                navigationIcon = {
                    if (canGoBack) {
                        IconButton(onClick = onBack) {
                            Icon(PiIcons.ArrowLeft, contentDescription = "Back", tint = t.text, modifier = Modifier.size(22.dp))
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Connect to pi-web", style = MaterialTheme.typography.headlineMedium, color = t.text)
            Text(
                "Enter the address of the machine running pi-web.",
                style = MaterialTheme.typography.bodyMedium,
                color = t.textSecondary,
                modifier = Modifier.padding(bottom = 10.dp),
            )
            FieldLabel("Server address")
            OutlinedTextField(
                value = url,
                onValueChange = { url = it; error = null },
                placeholder = { Text("192.168.1.20:30141") },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                colors = piTextFieldColors(),
                textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = GeistMono),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            FieldLabel("Password")
            OutlinedTextField(
                value = password,
                onValueChange = { password = it; error = null },
                placeholder = { Text("PI_WEB_PASSWORD") },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                colors = piTextFieldColors(),
                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    TextButton(onClick = { showPassword = !showPassword }) {
                        Text(
                            if (showPassword) "Hide" else "Show",
                            style = MaterialTheme.typography.labelMedium,
                            color = t.textSecondary,
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            FieldLabel("Voice input (ASR)")
            OutlinedTextField(
                value = asrUrl,
                onValueChange = { asrUrl = it },
                placeholder = { Text("ws://192.168.1.56:8000/ws") },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                colors = piTextFieldColors(),
                textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = GeistMono),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "WebSocket ASR server for voice dictation (long-press send). Leave empty to disable.",
                style = MaterialTheme.typography.labelSmall,
                color = t.textTertiary,
            )
            FieldLabel("Text to speech (TTS)")
            OutlinedTextField(
                value = ttsUrl,
                onValueChange = { ttsUrl = it },
                placeholder = { Text("http://192.168.1.56:8880") },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                colors = piTextFieldColors(),
                textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = GeistMono),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = ttsModel,
                    onValueChange = { ttsModel = it },
                    label = { Text("Model") },
                    placeholder = { Text("kokoro") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = piTextFieldColors(),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = GeistMono),
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = ttsVoice,
                    onValueChange = { ttsVoice = it },
                    label = { Text("Voice") },
                    placeholder = { Text("if_sara") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = piTextFieldColors(),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = GeistMono),
                    modifier = Modifier.weight(1f),
                )
            }
            Text(
                "OpenAI-compatible TTS server (POST /v1/audio/speech) for the Listen action. " +
                    "Model and voice are sent as-is; leave them empty for the server defaults. " +
                    "Leave the URL empty to disable.",
                style = MaterialTheme.typography.labelSmall,
                color = t.textTertiary,
            )
            PiPrimaryButton(
                "Connect",
                enabled = url.isNotBlank(),
                loading = testing,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                onClick = {
                    scope.launch {
                        testing = true
                        error = null
                        val candidate = ServerConfig(
                            ServerConfig.normalizeUrl(url),
                            password,
                            ServerConfig.normalizeAsrUrl(asrUrl),
                            ServerConfig.normalizeTtsUrl(ttsUrl),
                            ttsModel.trim(),
                            ttsVoice.trim(),
                        )
                        val previous = app.api.config
                        app.api.config = candidate
                        try {
                            val body = app.api.get("/api/agent/running").asObj()
                            if (body?.containsKey("runningSessionIds") != true) {
                                throw IllegalStateException("The server answered, but it doesn't look like pi-web.")
                            }
                            app.settings.save(candidate)
                            onConnected()
                        } catch (e: Exception) {
                            app.api.config = previous
                            error = e.message ?: e.toString()
                        } finally {
                            testing = false
                        }
                    }
                },
            )
            error?.let { message ->
                val shape = RoundedCornerShape(12.dp)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(shape)
                        .background(t.danger.copy(alpha = 0.06f))
                        .border(1.dp, t.danger.copy(alpha = 0.3f), shape)
                        .padding(12.dp),
                ) {
                    Icon(PiIcons.Alert, null, tint = t.danger, modifier = Modifier.padding(top = 2.dp).size(16.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(message, style = MaterialTheme.typography.bodyMedium, color = t.text)
                }
            }
            // The project list comes from the saved server, so only once one is set.
            if (current.isConfigured) AssistProjectSetting(app, Modifier.padding(top = 18.dp))
            Checklist(modifier = Modifier.padding(top = 18.dp))
        }
    }
}

@Composable
internal fun FieldLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = Pi.tokens.textSecondary,
    )
}

@Composable
private fun Checklist(modifier: Modifier = Modifier) {
    val t = Pi.tokens
    val shape = RoundedCornerShape(14.dp)
    val items = listOf(
        "Listen on the network" to "npm run start:lan",
        "Use the IP address (LAN or Tailscale 100.x). Hostnames need" to "PI_WEB_ALLOWED_HOSTS",
        "Protect it with a password. Traffic is plain HTTP: outside your LAN use Tailscale or HTTPS" to "PI_WEB_PASSWORD",
    )
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .border(1.dp, t.border, shape)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "SERVER CHECKLIST",
            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = MaterialTheme.typography.labelSmall.letterSpacing),
            color = t.textTertiary,
        )
        items.forEachIndexed { index, (text, code) ->
            Row {
                Text("${index + 1}", style = MaterialTheme.typography.labelMedium, color = t.textTertiary, modifier = Modifier.width(20.dp))
                Column {
                    Text(text, style = MaterialTheme.typography.bodyMedium, color = t.textSecondary)
                    Text(
                        code,
                        style = MaterialTheme.typography.labelMedium.copy(fontFamily = GeistMono, fontWeight = FontWeight.Normal),
                        color = t.text,
                        modifier = Modifier
                            .padding(top = 4.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(t.muted)
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
        }
    }
}
