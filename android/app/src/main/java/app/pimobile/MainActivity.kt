package app.pimobile

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.pimobile.notify.Notifications
import app.pimobile.ui.chat.ChatScreen
import app.pimobile.ui.chat.ChatViewModel
import app.pimobile.ui.sessions.SessionsScreen
import app.pimobile.ui.sessions.SessionsViewModel
import app.pimobile.ui.settings.SettingsScreen
import app.pimobile.ui.theme.PiTheme
import kotlinx.coroutines.flow.MutableStateFlow

/** A session to open, from a notification tap. */
data class OpenRequest(val sessionId: String, val cwd: String)

class MainActivity : ComponentActivity() {
    private val openRequests = MutableStateFlow<OpenRequest?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleIntent(intent)
        val app = application as PiApp
        setContent {
            PiTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    PiNavHost(app, openRequests)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val sessionId = intent?.getStringExtra(Notifications.EXTRA_SESSION_ID) ?: return
        openRequests.value = OpenRequest(sessionId, intent.getStringExtra(Notifications.EXTRA_CWD).orEmpty())
    }
}

private fun chatRoute(id: String?, cwd: String): String =
    if (id == null) "chat?cwd=${Uri.encode(cwd)}" else "chat?id=${Uri.encode(id)}&cwd=${Uri.encode(cwd)}"

@Composable
private fun PiNavHost(app: PiApp, openRequests: MutableStateFlow<OpenRequest?>) {
    val nav = rememberNavController()
    val start = if (app.api.config.isConfigured) "sessions" else "settings"
    // A notice for the next chat screen, when a chat replaces itself (/clone).
    var carriedNotice by remember { mutableStateOf<String?>(null) }

    val request by openRequests.collectAsState()
    LaunchedEffect(request) {
        val open = request ?: return@LaunchedEffect
        openRequests.value = null
        if (app.api.config.isConfigured) {
            nav.navigate(chatRoute(open.sessionId, open.cwd)) { launchSingleTop = true }
        }
    }

    NavHost(navController = nav, startDestination = start) {
        composable("settings") {
            val canGoBack = nav.previousBackStackEntry != null
            SettingsScreen(
                app = app,
                canGoBack = canGoBack,
                onBack = { nav.popBackStack() },
                onConnected = {
                    if (canGoBack) nav.popBackStack()
                    else nav.navigate("sessions") { popUpTo("settings") { inclusive = true } }
                },
            )
        }
        composable("sessions") {
            AskNotificationPermissionOnce()
            val vm = viewModel { SessionsViewModel(app.api, app::onRunActive) }
            SessionsScreen(
                vm = vm,
                serverLabel = app.api.config.baseUrl.removePrefix("http://").removePrefix("https://"),
                onOpen = { id, cwd -> nav.navigate(chatRoute(id, cwd)) },
                onNew = { cwd -> nav.navigate(chatRoute(null, cwd)) },
                onSettings = { nav.navigate("settings") },
            )
        }
        composable(
            "chat?id={id}&cwd={cwd}",
            arguments = listOf(
                navArgument("id") { type = NavType.StringType; nullable = true; defaultValue = null },
                navArgument("cwd") { type = NavType.StringType; defaultValue = "" },
            ),
        ) { entry ->
            val id = entry.arguments?.getString("id")
            val cwd = entry.arguments?.getString("cwd").orEmpty()
            val vm = viewModel { ChatViewModel(app.api, id, cwd, app::onRunActive) }
            LaunchedEffect(vm) {
                carriedNotice?.let(vm::showNotice)
                carriedNotice = null
            }
            ChatScreen(
                vm,
                onBack = { nav.popBackStack() },
                onOpenSession = { target ->
                    // Replace this chat (web: /clone switches the active session).
                    carriedNotice = target.notice
                    nav.navigate(chatRoute(target.sessionId, target.cwd)) {
                        popUpTo(entry.destination.id) { inclusive = true }
                    }
                },
            )
        }
    }
}

/** Android 13+ needs a runtime grant; ask once, the first time the session list shows. */
@Composable
private fun AskNotificationPermissionOnce() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        val prefs = context.getSharedPreferences("prompts", Context.MODE_PRIVATE)
        if (!Notifications.canPost(context) && !prefs.getBoolean("asked_notifications", false)) {
            prefs.edit().putBoolean("asked_notifications", true).apply()
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
