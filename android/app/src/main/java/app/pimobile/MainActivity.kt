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
import androidx.compose.animation.AnimatedContentTransitionScope.SlideDirection
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.pimobile.notify.Notifications
import app.pimobile.ui.chat.ChatScreen
import app.pimobile.ui.chat.ChatViewModel
import app.pimobile.ui.files.FileViewerScreen
import app.pimobile.ui.files.FileViewerViewModel
import app.pimobile.ui.files.FilesScreen
import app.pimobile.ui.files.FilesViewModel
import app.pimobile.ui.markdown.PreloadLatexFonts
import app.pimobile.ui.sessions.SessionsScreen
import app.pimobile.ui.sessions.SessionsViewModel
import app.pimobile.ui.settings.ServerOutdatedScreen
import app.pimobile.ui.settings.SettingsScreen
import app.pimobile.ui.theme.PiTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/** A session to open, from a notification tap. */
data class OpenRequest(val sessionId: String, val cwd: String)

class MainActivity : ComponentActivity() {
    private val openRequests = MutableStateFlow<OpenRequest?>(null)
    /** Cwd for a fresh session from the system assistant trigger; empty = pick via sheet. */
    private val assistCwds = MutableStateFlow<String?>(null)
    /** Token of the last consumed notification intent; survives activity recreation. */
    private var consumedToken: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        consumedToken = savedInstanceState?.getString(KEY_CONSUMED_TOKEN)
        // Always process the intent, also on recreation: the system keeps the process
        // alive via the RunWatcherService foreground service but destroys the activity
        // for memory, so a notification tap recreates the activity and must still open
        // the session. [firstCreation] gates only the ASSIST action.
        handleIntent(intent, firstCreation = savedInstanceState == null)
        val app = application as PiApp
        setContent {
            PiTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    PreloadLatexFonts()
                    PiNavHost(app, openRequests, assistCwds)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent, firstCreation = true)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        consumedToken?.let { outState.putString(KEY_CONSUMED_TOKEN, it) }
    }

    private fun handleIntent(intent: Intent?, firstCreation: Boolean) {
        when (intent?.action) {
            // System assistant trigger (corner swipe, long-press power/home): always a fresh session.
            Intent.ACTION_ASSIST -> {
                if (!firstCreation) return // a recreation must not open another fresh session
                val app = application as PiApp
                // Tiny preferences read, served from cache after first emission; needed
                // synchronously to pick the fresh session's cwd (chosen project, else last cwd).
                assistCwds.value = runBlocking { app.settings.assistLaunchCwd.first() }
            }
            else -> {
                val sessionId = intent?.getStringExtra(Notifications.EXTRA_SESSION_ID) ?: return
                // One-shot: a tap is consumed exactly once, across activity recreation.
                // A null token (notification posted by an older APK) is consumed without tracking.
                val token = intent.getStringExtra(Notifications.EXTRA_TOKEN)
                if (token != null && token == consumedToken) return
                consumedToken = token
                openRequests.value = OpenRequest(sessionId, intent.getStringExtra(Notifications.EXTRA_CWD).orEmpty())
            }
        }
    }

    private companion object {
        const val KEY_CONSUMED_TOKEN = "consumed_notification_token"
    }
}

private fun chatRoute(id: String?, cwd: String, focus: Boolean = false): String =
    if (id == null) "chat?cwd=${Uri.encode(cwd)}&focus=$focus"
    else "chat?id=${Uri.encode(id)}&cwd=${Uri.encode(cwd)}&focus=$focus"

private const val CHAT_ROUTE = "chat?id={id}&cwd={cwd}&focus={focus}"

private const val SESSIONS_ROUTE = "sessions?newSheet={newSheet}"

/** Screen changes slide horizontally (push) instead of NavHost's default cross-fade. */
private val navSlideSpec = tween<IntOffset>(durationMillis = 300, easing = FastOutSlowInEasing)

/** savedStateHandle key: text a file screen asks the chat composer to insert. */
private const val INSERT_KEY = "insert"

private fun filesRoute(root: String, sessionId: String?, mention: Boolean): String =
    "files?root=${Uri.encode(root)}&session=${Uri.encode(sessionId.orEmpty())}&mention=$mention"

private fun fileRoute(path: String, root: String, sessionId: String?, diff: Boolean, mention: Boolean): String =
    "file?path=${Uri.encode(path)}&root=${Uri.encode(root)}&session=${Uri.encode(sessionId.orEmpty())}" +
        "&diff=$diff&mention=$mention"

@Composable
private fun PiNavHost(app: PiApp, openRequests: MutableStateFlow<OpenRequest?>, assistCwds: MutableStateFlow<String?>) {
    val nav = rememberNavController()
    val navScope = rememberCoroutineScope()
    val start = if (app.api.config.isConfigured) "sessions" else "settings"
    // A notice for the next chat screen, when a chat replaces itself (/clone).
    var carriedNotice by remember { mutableStateOf<String?>(null) }

    val request by openRequests.collectAsState()
    LaunchedEffect(request) {
        val open = request ?: return@LaunchedEffect
        openRequests.value = null
        if (app.api.config.isConfigured) {
            // Not launchSingleTop: over another chat it would hand the new id to that entry, whose
            // ViewModel still holds the previous session, so the tap changed nothing.
            val top = nav.currentBackStackEntry
            val showing = top?.destination?.route == CHAT_ROUTE && top.arguments?.getString("id") == open.sessionId
            if (!showing) nav.navigate(chatRoute(open.sessionId, open.cwd))
        }
    }

    val assistCwd by assistCwds.collectAsState()
    LaunchedEffect(assistCwd) {
        val cwd = assistCwd ?: return@LaunchedEffect
        assistCwds.value = null
        if (!app.api.config.isConfigured) return@LaunchedEffect
        // Pop by the list's route pattern, not findStartDestination(): the start route
        // "sessions" has a different id than SESSIONS_ROUTE, so that lookup falls back
        // to the root graph and popping to it drops the list too.
        if (cwd.isNotBlank()) {
            // Fresh session above the session list: Back returns to the list,
            // never to the previous chats (they are popped, the list is kept).
            nav.navigate(chatRoute(null, cwd, focus = true)) {
                popUpTo(SESSIONS_ROUTE) { inclusive = false }
            }
        } else {
            // No remembered cwd: replace the list with one that opens the sheet.
            nav.navigate("sessions?newSheet=true") {
                popUpTo(SESSIONS_ROUTE) { inclusive = true }
            }
        }
    }

    // This version needs pi-web's run-state stream: an older server gets an update
    // prompt over the whole app instead of a degraded, polling one.
    val outdated by app.runStatus.outdated.collectAsState()
    var checkingServer by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (app.api.config.isConfigured) app.runStatus.check()
    }
    val route = nav.currentBackStackEntryAsState().value?.destination?.route

    Box(Modifier.fillMaxSize()) {
        NavHost(
            navController = nav,
            startDestination = start,
            // Forward: the new screen comes in from the right. Back: the reverse.
            enterTransition = { slideIntoContainer(SlideDirection.Start, navSlideSpec) },
            exitTransition = { slideOutOfContainer(SlideDirection.Start, navSlideSpec) },
            popEnterTransition = { slideIntoContainer(SlideDirection.End, navSlideSpec) },
            popExitTransition = { slideOutOfContainer(SlideDirection.End, navSlideSpec) },
        ) {
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
            composable(
                SESSIONS_ROUTE,
                // Declared, or "true" arrives as a String and getBoolean reads false.
                arguments = listOf(navArgument("newSheet") { type = NavType.BoolType; defaultValue = false }),
            ) { entry ->
                val newSheet = entry.arguments?.getBoolean("newSheet") ?: false
                AskNotificationPermissionOnce()
                val vm = viewModel { SessionsViewModel(app.api, app::onRunActive, app.runStatus.snapshot) }
                SessionsScreen(
                    vm = vm,
                    serverLabel = app.api.config.baseUrl.removePrefix("http://").removePrefix("https://"),
                    onOpen = { id, cwd -> nav.navigate(chatRoute(id, cwd)) },
                    onNew = { cwd -> nav.navigate(chatRoute(null, cwd)) },
                    onSettings = { nav.navigate("settings") },
                    // No chat to mention into from here.
                    onBrowse = { root -> nav.navigate(filesRoute(root, null, mention = false)) },
                    startWithNewSheet = newSheet,
                )
            }
            composable(
                CHAT_ROUTE,
                arguments = listOf(
                    navArgument("id") { type = NavType.StringType; nullable = true; defaultValue = null },
                    navArgument("cwd") { type = NavType.StringType; defaultValue = "" },
                    navArgument("focus") { type = NavType.BoolType; defaultValue = false },
                ),
            ) { entry ->
                val id = entry.arguments?.getString("id")
                val cwd = entry.arguments?.getString("cwd").orEmpty()
                val focus = entry.arguments?.getBoolean("focus") ?: false
                val vm = viewModel {
                    ChatViewModel(app, app.api, id, cwd, app::onRunActive) { last ->
                        navScope.launch { app.settings.saveLastCwd(last) }
                    }
                }
                LaunchedEffect(vm) {
                    carriedNotice?.let(vm::showNotice)
                    carriedNotice = null
                }
                val insert by entry.savedStateHandle.getStateFlow<String?>(INSERT_KEY, null).collectAsState()
                ChatScreen(
                    vm,
                    tts = app.tts,
                    onBack = { nav.popBackStack() },
                    onOpenSession = { target ->
                        // Replace this chat (web: /clone switches the active session).
                        carriedNotice = target.notice
                        nav.navigate(chatRoute(target.sessionId, target.cwd)) {
                            popUpTo(entry.destination.id) { inclusive = true }
                        }
                    },
                    onOpenSubagent = { subagentId ->
                        // Push, not replace: the back button returns to this session.
                        nav.navigate(chatRoute(subagentId, vm.state.value.cwd.ifEmpty { cwd }))
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
                    autoFocusComposer = focus,
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
        if (outdated && route != "settings") {
            ServerOutdatedScreen(
                serverLabel = app.api.config.baseUrl.removePrefix("http://").removePrefix("https://"),
                checking = checkingServer,
                onRetry = {
                    navScope.launch {
                        checkingServer = true
                        app.runStatus.check()
                        checkingServer = false
                    }
                },
                onSettings = { nav.navigate("settings") },
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
