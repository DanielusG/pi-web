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
import app.pimobile.ui.files.FileViewerScreen
import app.pimobile.ui.files.FileViewerViewModel
import app.pimobile.ui.files.FilesScreen
import app.pimobile.ui.files.FilesViewModel
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

private const val CHAT_ROUTE = "chat?id={id}&cwd={cwd}"

/** savedStateHandle key: text a file screen asks the chat composer to insert. */
private const val INSERT_KEY = "insert"

private fun filesRoute(root: String, sessionId: String?, mention: Boolean): String =
    "files?root=${Uri.encode(root)}&session=${Uri.encode(sessionId.orEmpty())}&mention=$mention"

private fun fileRoute(path: String, root: String, sessionId: String?, diff: Boolean, mention: Boolean): String =
    "file?path=${Uri.encode(path)}&root=${Uri.encode(root)}&session=${Uri.encode(sessionId.orEmpty())}" +
        "&diff=$diff&mention=$mention"

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
                // No chat to mention into from here.
                onBrowse = { root -> nav.navigate(filesRoute(root, null, mention = false)) },
            )
        }
        composable(
            CHAT_ROUTE,
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
            val insert by entry.savedStateHandle.getStateFlow<String?>(INSERT_KEY, null).collectAsState()
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
                onOpenFiles = {
                    val chat = vm.state.value
                    nav.navigate(filesRoute(chat.cwd.ifEmpty { cwd }, chat.sessionId, mention = true))
                },
                onOpenFile = { path, diff ->
                    val chat = vm.state.value
                    nav.navigate(fileRoute(path, chat.cwd.ifEmpty { cwd }, chat.sessionId, diff, mention = true))
                },
                pendingInsert = insert,
                onInsertConsumed = { entry.savedStateHandle[INSERT_KEY] = null },
            )
        }
        // Back to the chat below, with the mention for its composer.
        val mentionInChat: (String) -> Unit = { text ->
            if (nav.popBackStack(CHAT_ROUTE, inclusive = false)) {
                nav.currentBackStackEntry?.savedStateHandle?.set(INSERT_KEY, text)
            }
        }
        composable(
            "files?root={root}&session={session}&mention={mention}",
            arguments = listOf(
                navArgument("root") { type = NavType.StringType; defaultValue = "" },
                navArgument("session") { type = NavType.StringType; defaultValue = "" },
                navArgument("mention") { type = NavType.BoolType; defaultValue = false },
            ),
        ) { entry ->
            val root = entry.arguments?.getString("root").orEmpty()
            val session = entry.arguments?.getString("session")?.takeIf { it.isNotEmpty() }
            val mention = entry.arguments?.getBoolean("mention") ?: false
            val vm = viewModel { FilesViewModel(app.api, root, session) }
            FilesScreen(
                vm = vm,
                mentionEnabled = mention,
                onBack = { nav.popBackStack() },
                onOpenFile = { path, diff -> nav.navigate(fileRoute(path, root, session, diff, mention)) },
                onMention = mentionInChat,
            )
        }
        composable(
            "file?path={path}&root={root}&session={session}&diff={diff}&mention={mention}",
            arguments = listOf(
                navArgument("path") { type = NavType.StringType; defaultValue = "" },
                navArgument("root") { type = NavType.StringType; defaultValue = "" },
                navArgument("session") { type = NavType.StringType; defaultValue = "" },
                navArgument("diff") { type = NavType.BoolType; defaultValue = false },
                navArgument("mention") { type = NavType.BoolType; defaultValue = false },
            ),
        ) { entry ->
            val args = entry.arguments
            val path = args?.getString("path").orEmpty()
            val root = args?.getString("root").orEmpty()
            val session = args?.getString("session")?.takeIf { it.isNotEmpty() }
            val diff = args?.getBoolean("diff") ?: false
            val mention = args?.getBoolean("mention") ?: false
            val cacheDir = LocalContext.current.cacheDir
            val vm = viewModel { FileViewerViewModel(app.api, path, root, session, diff, cacheDir) }
            FileViewerScreen(
                vm = vm,
                mentionEnabled = mention,
                onBack = { nav.popBackStack() },
                onMention = mentionInChat,
                onOpenFile = { target -> nav.navigate(fileRoute(target, root, session, diff = false, mention = mention)) },
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
