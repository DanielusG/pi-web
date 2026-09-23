@file:OptIn(ExperimentalMaterial3Api::class)

package app.pimobile.ui.chat

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FabPosition
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import app.pimobile.data.Block
import app.pimobile.data.ChatItem
import app.pimobile.data.FilePaths
import app.pimobile.data.ImageAttachments
import app.pimobile.data.SubagentInfo
import app.pimobile.data.ToolResult
import app.pimobile.data.TtsPlayer
import app.pimobile.data.TtsUiState
import app.pimobile.notify.AppVisibility
import app.pimobile.notify.Notifications
import app.pimobile.ui.baseName
import app.pimobile.ui.compactNumber
import app.pimobile.ui.formatDuration
import app.pimobile.ui.groupedNumber
import app.pimobile.ui.isoMillis
import app.pimobile.ui.relativeTime
import app.pimobile.ui.shortPath
import app.pimobile.ui.theme.Geist
import app.pimobile.ui.theme.GeistMono
import app.pimobile.ui.theme.Pi
import app.pimobile.ui.theme.PiIcons
import app.pimobile.ui.theme.PiPrimaryButton
import app.pimobile.ui.theme.PiSecondaryButton
import app.pimobile.ui.theme.ShimmerText
import app.pimobile.ui.theme.StatusDot
import app.pimobile.ui.theme.piTextFieldColors
import app.pimobile.ui.markdown.Markdown
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlin.math.ceil
import kotlin.math.roundToInt

private val FALLBACK_THINKING_LEVELS = listOf("off", "minimal", "low", "medium", "high")

/** Shared idle state for chats without a TTS player. */
private val NoTtsState = MutableStateFlow<TtsUiState>(TtsUiState.Idle)

@Composable
fun ChatScreen(
    vm: ChatViewModel,
    onBack: () -> Unit,
    onOpenSession: (OpenSession) -> Unit = {},
    onOpenFiles: () -> Unit = {},
    onOpenFile: (path: String, diff: Boolean) -> Unit = { _, _ -> },
    /** A mention from the file screens, inserted at the cursor. */
    pendingInsert: String? = null,
    onInsertConsumed: () -> Unit = {},
    /** Fresh-session launches from the assistant trigger: focus the composer and raise the keyboard. */
    autoFocusComposer: Boolean = false,
    /** The app-level TTS player; null disables the Listen action. */
    tts: TtsPlayer? = null,
    /** Subagent bar: open a subagent's own chat (push, so Back returns here). */
    onOpenSubagent: (String) -> Unit = {},
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val ttsState by (tts?.state ?: NoTtsState).collectAsStateWithLifecycle()
    val t = Pi.tokens
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    DisposableEffect(tts) {
        val handler: (String) -> Unit = { message -> scope.launch { snackbar.showSnackbar(message) } }
        tts?.onError = handler
        onDispose {
            if (tts?.onError == handler) tts.onError = null
        }
    }
    val listState = rememberLazyListState()
    // A TextFieldValue, so inserting a slash command can put the cursor after it.
    var draft by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue()) }
    val composerFocus = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    LaunchedEffect(autoFocusComposer, keyboardController) {
        if (!autoFocusComposer || keyboardController == null) return@LaunchedEffect
        delay(150) // let the window settle after the hardware-trigger launch
        composerFocus.requestFocus()
        keyboardController.show()
    }
    var showModels by remember { mutableStateOf(false) }
    // System photo picker: no storage permission; falls back to the document picker on old devices.
    val context = LocalContext.current
    val resolver = context.applicationContext.contentResolver
    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(ImageAttachments.MAX_IMAGES),
    ) { uris -> vm.addImages(resolver, uris) }

    // --- Voice dictation (long-press send, Nemotron ASR server) ---
    var voicePressHeld by remember { mutableStateOf(false) }
    // Where the live transcript is anchored in the draft: the cursor at press time.
    var dictationAnchor by remember { mutableIntStateOf(-1) }
    var dictationLen by remember { mutableIntStateOf(0) }
    // Breaks the reference cycle between the permission launcher and the local start function.
    var startVoiceRef by remember { mutableStateOf({}) }

    val micPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted && voicePressHeld) startVoiceRef()
        voicePressHeld = false
    }

    fun startVoiceDictation() {
        dictationAnchor = draft.selection.min
        dictationLen = 0
        vm.startDictation { token ->
            val d = draft
            if (dictationAnchor < 0) return@startDictation
            if (dictationLen == 0) {
                // Leading space when the cursor sits mid-text, not right after whitespace.
                val before = d.text.getOrNull(dictationAnchor - 1)
                val prefix = if (before != null && !before.isWhitespace()) " " else ""
                val text = d.text.substring(0, dictationAnchor) + prefix + token +
                        d.text.substring(dictationAnchor)
                dictationLen = prefix.length + token.length
                draft = TextFieldValue(text, TextRange(dictationAnchor + dictationLen))
            } else {
                val pos = dictationAnchor + dictationLen
                val text = d.text.substring(0, pos) + token + d.text.substring(pos)
                dictationLen += token.length
                draft = TextFieldValue(text, TextRange(pos + token.length))
            }
        }
    }

    fun onVoiceStart() {
        if (!vm.hasAsr()) return
        voicePressHeld = true
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            startVoiceDictation()
        } else {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    fun onVoiceStop() {
        voicePressHeld = false
        vm.stopDictation()
    }

    fun onVoiceCancel() {
        voicePressHeld = false
    }

    startVoiceRef = { startVoiceDictation() }

    // Release the anchor once the session has fully ended (flushed tokens are already in).
    LaunchedEffect(state.dictating) {
        if (!state.dictating) {
            dictationAnchor = -1
            dictationLen = 0
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            vm.onForeground()
            awaitCancellation()
        }
    }
    // Completion notifications are skipped for the session on screen, and its pending
    // one-shot notifications are stale the moment it is.
    LaunchedEffect(lifecycleOwner, state.sessionId) {
        val id = state.sessionId ?: return@LaunchedEffect
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            AppVisibility.viewingSessionId = id
            Notifications.cancelForSession(context, id)
            try {
                awaitCancellation()
            } finally {
                if (AppVisibility.viewingSessionId == id) AppVisibility.viewingSessionId = null
            }
        }
    }
    LaunchedEffect(state.error) {
        state.error?.let {
            snackbar.showSnackbar(it)
            vm.clearError()
        }
    }
    LaunchedEffect(state.notice) {
        state.notice?.let {
            snackbar.showSnackbar(it)
            vm.clearNotice()
        }
    }
    val clipboard = LocalClipboardManager.current
    LaunchedEffect(state.clipboard) {
        state.clipboard?.let {
            clipboard.setText(AnnotatedString(it))
            vm.consumeClipboard()
        }
    }
    LaunchedEffect(state.openSession) {
        state.openSession?.let {
            vm.consumeOpenSession()
            onOpenSession(it)
        }
    }
    LaunchedEffect(state.restoredDraft) {
        state.restoredDraft?.let {
            if (draft.text.isBlank()) draft = TextFieldValue(it, TextRange(it.length))
            vm.consumeRestoredDraft()
        }
    }
    LaunchedEffect(state.editDraft) {
        state.editDraft?.let {
            draft = TextFieldValue(it, TextRange(it.length))
            vm.consumeEditDraft()
        }
    }
    // Edit from here rewinds the session, so a confirmation guards it.
    var editRequest by remember { mutableStateOf<EditRequest?>(null) }
    val canNavigate = !state.running && !state.commandPending
    var selectedToolId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(canNavigate) { if (!canNavigate) selectedToolId = null }
    var showTree by remember { mutableStateOf(false) }
    LaunchedEffect(state.openTree) {
        if (state.openTree) {
            showTree = true
            vm.consumeTreeRequest()
        }
    }
    LaunchedEffect(pendingInsert) {
        pendingInsert?.let {
            draft = insertAtCursor(draft, it)
            onInsertConsumed()
        }
    }
    val writtenFiles = remember(state.items, state.toolResults, state.cwd) {
        turnWrittenFiles(state.items, state.toolResults, state.cwd)
    }

    // Follow the bottom unless the user scrolled up to read.
    val follow = rememberBottomFollow(listState)
    val showEmpty = !state.loading && state.items.isEmpty() && state.streaming == null

    // Slash palette (web: ChatInput). Commands load once per `/` typed; Back closes
    // the palette until the query changes, like Escape on the web.
    val slashQuery = slashQuery(draft.text)
    var slashDismissed by remember { mutableStateOf(false) }
    var slashActive by remember { mutableIntStateOf(0) }
    LaunchedEffect(slashQuery) {
        slashDismissed = false
        slashActive = 0
    }
    LaunchedEffect(slashQuery != null) {
        if (slashQuery != null) vm.loadSlashCommands()
    }
    val slashMenuOpen = slashQuery != null && !slashDismissed && state.dialog == null
    LaunchedEffect(slashMenuOpen) {
        if (slashMenuOpen) vm.loadSkillDormancy()
    }
    val slashMatches = remember(slashQuery, state.running, state.slashCommands) {
        slashQuery?.let { filterSlashCommands(it, state.running, state.slashCommands) }.orEmpty()
    }
    val slashGroups =
        remember(slashMatches, state.skillDormancy) { slashCommandLayout(slashMatches, state.skillDormancy) }
    val slashDisplayed = remember(slashGroups) { slashGroups.flatMap { it.commands } }
    val activeIndex = slashActive.coerceIn(0, maxOf(0, slashDisplayed.lastIndex))
    BackHandler(enabled = slashMenuOpen) { slashDismissed = true }

    fun applySlashCommand(command: SlashCommand) {
        val text = "/${command.name} "
        draft = TextFieldValue(text, TextRange(text.length))
    }

    // Web: handleSend. A built-in runs here and clears the input only when it
    // succeeded and the input still holds what was submitted.
    fun submit() {
        val message = draft.text.trim()
        val handled = vm.runBuiltinCommand(message) { succeeded ->
            if (succeeded && draft.text.trim() == message) draft = TextFieldValue()
        }
        if (handled) return
        vm.send(draft.text)
        draft = TextFieldValue()
        follow.attach()
    }

    // Hardware keyboards: arrows move the highlight, Tab inserts it, Escape closes the palette.
    val onComposerKey: (KeyEvent) -> Boolean = handler@{ event ->
        if (!slashMenuOpen) return@handler false
        val consumed = when (event.key) {
            Key.DirectionDown, Key.DirectionRight, Key.DirectionUp, Key.DirectionLeft, Key.Escape -> true
            Key.Tab -> slashDisplayed.isNotEmpty()
            else -> false
        }
        if (consumed && event.type == KeyEventType.KeyDown) {
            when (event.key) {
                Key.DirectionDown, Key.DirectionRight -> slashActive = minOf(slashDisplayed.lastIndex, activeIndex + 1)
                Key.DirectionUp, Key.DirectionLeft -> slashActive = maxOf(0, activeIndex - 1)
                Key.Escape -> slashDismissed = true
                Key.Tab -> applySlashCommand(slashDisplayed[activeIndex])
            }
        }
        consumed
    }

    Scaffold(
        containerColor = t.background,
        topBar = {
            Column {
                ChatTopBar(
                    state = state,
                    scrolled = listState.canScrollBackward,
                    onBack = onBack,
                    onStatsOpened = vm::consumeStatsRequest,
                    onOpenFiles = onOpenFiles,
                )
                if (ttsState != TtsUiState.Idle) {
                    TtsBar(
                        state = ttsState,
                        onToggle = { tts?.toggle() },
                        onSpeed = { tts?.setSpeed(it) },
                        onClose = { tts?.stop() },
                    )
                }
            }
        },
        snackbarHost = {
            SnackbarHost(snackbar) { data ->
                Snackbar(
                    data,
                    shape = RoundedCornerShape(12.dp),
                    containerColor = t.text,
                    contentColor = t.background,
                )
            }
        },
        bottomBar = {
            Column(Modifier.fillMaxWidth()) {
                if (state.subagents.isNotEmpty()) {
                    SubagentBar(
                        subagents = state.subagents,
                        onOpen = onOpenSubagent,
                        onAbort = vm::abortSubagent,
                    )
                }
                val dialog = state.dialog
                if (dialog != null) {
                    // The extension dialog replaces the input bar in the composer slot, as on the web.
                    ExtensionDialogView(dialog, vm)
                } else {
                    Composer(
                    state = state,
                    draft = draft,
                    onDraft = { draft = it },
                    onSend = { submit() },
                    onKey = onComposerKey,
                    slashMenu = {
                        if (slashMenuOpen) {
                            SlashCommandMenu(
                                groups = slashGroups,
                                matchCount = slashMatches.size,
                                filtering = !slashQuery.isNullOrEmpty(),
                                loading = state.slashCommandsLoading,
                                dormancy = state.skillDormancy,
                                active = activeIndex,
                                onSelect = { applySlashCommand(it) },
                                modifier = Modifier.padding(bottom = 8.dp),
                            )
                        }
                    },
                    onStop = vm::abort,
                    onModels = { showModels = true },
                    onThinking = vm::selectThinking,
                    onAttach = {
                        imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                    onRemoveImage = vm::removeImage,
                    focusRequester = composerFocus,
                    voiceAvailable = vm.hasAsr(),
                    onVoiceStart = { onVoiceStart() },
                    onVoiceStop = { onVoiceStop() },
                    onVoiceCancel = { onVoiceCancel() },
                )
                }
            }
        },
        floatingActionButton = {
            JumpToBottomButton(
                visible = !follow.attached && listState.canScrollForward,
                onClick = follow::scrollToEnd,
            )
        },
        floatingActionButtonPosition = FabPosition.Center,
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .nestedScroll(follow.connection),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            if (state.loading) {
                item("loading") {
                    Box(Modifier.fillMaxWidth().padding(64.dp), contentAlignment = Alignment.Center) {
                        ShimmerText("Loading session")
                    }
                }
            }
            if (state.hasMore) {
                item("earlier") {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        TextButton(onClick = vm::loadEarlier, enabled = !state.loadingEarlier) {
                            Text(
                                if (state.loadingEarlier) "Loading…" else "Load earlier messages",
                                style = MaterialTheme.typography.labelLarge,
                                color = t.textSecondary,
                            )
                        }
                    }
                }
            }
            if (showEmpty) {
                item("empty") { EmptyChat(state.cwd) }
            }
            items(state.items, key = { it.key }, contentType = { it::class.simpleName }) { item ->
                when (item) {
                    is ChatItem.User -> UserBubble(
                        item,
                        onEditFromHere = item.entryId?.takeIf { canNavigate }?.let { id ->
                            { editRequest = EditRequest(id, item.command ?: item.text) }
                        },
                    )

                    is ChatItem.Assistant -> AssistantMessage(
                        item = item,
                        toolResults = state.toolResults,
                        liveTools = state.liveTools,
                        running = state.running,
                        fullThinking = state.fullThinking,
                        onLoadThinking = vm::loadFullThinking,
                        editPreviews = state.editPreviews,
                        onPreview = vm::loadEditPreview,
                        cwd = state.cwd,
                        writtenFiles = writtenFiles[item.key].orEmpty(),
                        onOpenFile = onOpenFile,
                        onEditFromHere = if (canNavigate) {
                            { id ->
                                selectedToolId = null
                                editRequest = EditRequest(id, draft = null)
                            }
                        } else null,
                        selectedToolId = selectedToolId,
                        onToolLongPress = if (canNavigate) {
                            { id -> selectedToolId = if (selectedToolId == id) null else id }
                        } else null,
                        onListen = if (tts != null && tts.config.ttsUrl.isNotBlank()) {
                            { key, text -> tts.play(key, text, state.title) }
                        } else null,
                        ttsLoadingKey = (ttsState as? TtsUiState.Loading)?.key,
                    )

                    is ChatItem.Bash -> BashCard(item)
                    is ChatItem.Notice -> NoticeRow(item)
                }
            }
            state.streaming?.let { streaming ->
                item("streaming") {
                    AssistantMessage(
                        item = streaming,
                        toolResults = state.toolResults,
                        liveTools = state.liveTools,
                        running = true,
                        fullThinking = state.fullThinking,
                        onLoadThinking = vm::loadFullThinking,
                        editPreviews = state.editPreviews,
                        onPreview = vm::loadEditPreview,
                        cwd = state.cwd,
                        onOpenFile = onOpenFile,
                    )
                }
            }
            item("bottom") { Spacer(Modifier.height(4.dp)) }
        }
    }

    editRequest?.let { request ->
        EditFromHereDialog(
            restoresText = request.draft != null,
            onDismiss = { editRequest = null },
            onConfirm = {
                editRequest = null
                vm.editFromHere(request.targetId, request.draft)
            },
        )
    }

    if (showTree) {
        TreeSheet(
            tree = state.tree,
            leafId = state.leafId,
            onDismiss = { showTree = false },
            onSelect = {
                showTree = false
                vm.selectBranch(it)
            },
        )
    }

    if (showModels) {
        ModelSheet(state, onDismiss = { showModels = false }, onSelect = {
            vm.selectModel(it)
            showModels = false
        })
    }
}

/** [draft] replaces the composer content once the session has moved; null leaves the composer alone. */
private data class EditRequest(val targetId: String, val draft: String?)

@Composable
private fun EditFromHereDialog(restoresText: Boolean, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val t = Pi.tokens
    val shape = RoundedCornerShape(20.dp)
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .clip(shape)
                .background(t.background)
                .border(1.dp, t.border, shape)
                .padding(20.dp),
        ) {
            Text("Edit from here?", style = MaterialTheme.typography.titleMedium, color = t.text)
            Spacer(Modifier.height(8.dp))
            Text(
                (
                        if (restoresText) "The session goes back to just before this message and its text returns to the composer. "
                        else "The session goes back to this point. "
                        ) + "Later messages stay saved as a branch you can reopen with /tree.",
                style = MaterialTheme.typography.bodyMedium,
                color = t.textSecondary,
            )
            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                PiSecondaryButton("Cancel", onClick = onDismiss)
                Spacer(Modifier.width(8.dp))
                PiPrimaryButton("Edit from here", onClick = onConfirm)
            }
        }
    }
}

/** Web: ChatInput insertText — at the cursor, separated by a space from the text before it. */
private fun insertAtCursor(value: TextFieldValue, text: String): TextFieldValue {
    val start = value.selection.min.coerceIn(0, value.text.length)
    val end = value.selection.max.coerceIn(start, value.text.length)
    val before = value.text.substring(0, start)
    val inserted = if (before.isNotEmpty() && !before.last().isWhitespace()) " $text" else text
    return TextFieldValue(before + inserted + value.text.substring(end), TextRange(before.length + inserted.length))
}

/**
 * Web: extractTurnWrittenFiles per turn, keyed by the turn's last assistant item.
 * A file counts only when its write/edit call returned without error.
 */
private fun turnWrittenFiles(
    items: List<ChatItem>,
    results: Map<String, ToolResult>,
    cwd: String
): Map<String, List<String>> {
    val byItem = HashMap<String, List<String>>()
    var files = LinkedHashSet<String>()
    var lastAssistant: String? = null
    fun endTurn() {
        val key = lastAssistant
        if (key != null && files.isNotEmpty()) byItem[key] = files.toList()
        files = LinkedHashSet()
        lastAssistant = null
    }
    for (item in items) {
        when (item) {
            is ChatItem.User -> endTurn()
            is ChatItem.Assistant -> {
                lastAssistant = item.key
                for (block in item.blocks) {
                    if (block !is Block.ToolCall || !(isWriteToolName(block.name) || isEditToolName(block.name))) continue
                    val result = results[block.id] ?: continue
                    if (result.isError) continue
                    FilePaths.resolveToolPath(toolInputPath(block.input), cwd.ifEmpty { null })?.let(files::add)
                }
            }

            else -> Unit
        }
    }
    endTurn()
    return byItem
}

@Composable
private fun ChatTopBar(
    state: ChatUiState,
    scrolled: Boolean,
    onBack: () -> Unit,
    onStatsOpened: () -> Unit,
    onOpenFiles: () -> Unit,
) {
    val t = Pi.tokens
    // The idle runtime the slash palette creates (ensure_session) is still a new chat.
    val fresh = state.sessionId == null ||
            (!state.loading && !state.running && state.items.isEmpty() && state.streaming == null)
    Column(Modifier.background(t.background)) {
        TopAppBar(
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = t.background,
                scrolledContainerColor = t.background,
            ),
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(PiIcons.ArrowLeft, contentDescription = "Back", tint = t.text, modifier = Modifier.size(22.dp))
                }
            },
            title = {
                Column {
                    Text(
                        state.title.ifBlank { if (fresh) "New session" else "Session" },
                        style = MaterialTheme.typography.titleMedium,
                        color = t.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    // Inside a subagent's own chat: say so (web: the agent switcher row).
                    state.subagentRelation?.let { relation ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(PiIcons.Bot, null, tint = t.accent, modifier = Modifier.size(12.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(
                                "Subagent · " + (relation.description ?: relation.profile ?: "subagent"),
                                style = MaterialTheme.typography.labelSmall,
                                color = t.accent,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            baseName(state.cwd),
                            style = MaterialTheme.typography.labelSmall,
                            color = t.textTertiary,
                            maxLines = 1,
                        )
                        // Run status lives here; connection state only surfaces when it's broken.
                        val (dot, label) = when {
                            state.link == LinkState.Reconnecting -> t.danger to "Reconnecting…"
                            state.running -> t.success to (state.status ?: "Working…")
                            state.commandStatus != null -> t.success to state.commandStatus
                            else -> null to null
                        }
                        if (dot != null && label != null) {
                            Spacer(Modifier.width(8.dp))
                            StatusDot(dot, pulsing = true)
                            Spacer(Modifier.width(6.dp))
                            Text(
                                label,
                                style = MaterialTheme.typography.labelSmall,
                                color = t.textSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            },
            actions = {
                if (state.cwd.isNotBlank()) {
                    IconButton(onClick = onOpenFiles) {
                        Icon(
                            PiIcons.Folder,
                            contentDescription = "Files",
                            tint = t.textSecondary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                // /session in a new chat still opens the stats it fetched.
                if (!fresh || state.stats != null) ContextIndicator(state, onStatsOpened)
            },
        )
        HorizontalDivider(color = if (scrolled) t.border else Color.Transparent)
    }
}

/** Telegram-style playback bar: play/pause, title, speed menu, stop. Shown while TTS is active. */
@Composable
private fun TtsBar(
    state: TtsUiState,
    onToggle: () -> Unit,
    onSpeed: (Float) -> Unit,
    onClose: () -> Unit,
) {
    val t = Pi.tokens
    val loading = state is TtsUiState.Loading
    val active = state as? TtsUiState.Active
    val speed = active?.speed ?: 1f
    var speedMenuOpen by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(t.background)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(t.muted)
                    .clickable(enabled = !loading, onClick = onToggle),
                contentAlignment = Alignment.Center,
            ) {
            when {
                loading -> CircularProgressIndicator(
                    Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = t.accent,
                    trackColor = Color.Transparent,
                )
                active?.isPlaying == true ->
                    Icon(PiIcons.Pause, "Pause", tint = t.text, modifier = Modifier.size(18.dp))
                else ->
                    Icon(PiIcons.Play, "Play", tint = t.text, modifier = Modifier.size(18.dp))
            }
        }
        Spacer(Modifier.width(10.dp))
        Text(
            (state as? TtsUiState.Loading)?.title ?: active?.title.orEmpty(),
            style = MaterialTheme.typography.labelLarge,
            color = t.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Box {
            Row(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .border(1.dp, t.border, RoundedCornerShape(8.dp))
                    .alpha(if (loading) 0.5f else 1f)
                    .clickable(enabled = !loading) { speedMenuOpen = true }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(speedLabel(speed), style = MaterialTheme.typography.labelMedium, color = t.accent)
                Spacer(Modifier.width(4.dp))
                Icon(PiIcons.ChevronDown, null, tint = t.textTertiary, modifier = Modifier.size(12.dp))
            }
            DropdownMenu(
                expanded = speedMenuOpen,
                onDismissRequest = { speedMenuOpen = false },
                shape = RoundedCornerShape(14.dp),
                containerColor = t.surface,
                border = BorderStroke(1.dp, t.border),
                shadowElevation = 8.dp,
            ) {
                TTS_SPEEDS.forEach { (value, label) ->
                    DropdownMenuItem(
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    speedLabel(value),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = if (value == speed) t.accent else t.text,
                                    modifier = Modifier.width(48.dp),
                                )
                                Text(
                                    label,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = if (value == speed) t.accent else t.textSecondary,
                                )
                            }
                        },
                        onClick = {
                            onSpeed(value)
                            speedMenuOpen = false
                        },
                    )
                }
            }
        }
        Spacer(Modifier.width(6.dp))
        Box(
            Modifier
                .size(36.dp)
                .clip(CircleShape)
                .clickable(onClick = onClose),
            contentAlignment = Alignment.Center,
        ) {
            Icon(PiIcons.Close, "Stop", tint = t.textSecondary, modifier = Modifier.size(18.dp))
        }
    }
    HorizontalDivider(color = t.border)
}
}

/**
 * Collapsible bar of the session's subagents (web: AgentSessionPanel). Starts
 * expanded; the header toggles it. Tap a row to open the subagent's chat;
 * long-press an active row to abort it.
 */
@Composable
private fun SubagentBar(
    subagents: List<SubagentInfo>,
    onOpen: (String) -> Unit,
    onAbort: (String) -> Unit,
) {
    val t = Pi.tokens
    val shape = RoundedCornerShape(26.dp)
    var expanded by remember { mutableStateOf(true) }
    var abortTarget by remember { mutableStateOf<SubagentInfo?>(null) }
    val activeCount = subagents.count { it.active }
    // A floating bubble, styled like the composer below it.
    Column(
        Modifier
            .fillMaxWidth()
            .background(t.background)
            .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 4.dp),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(t.surface)
                .border(1.dp, t.border, shape),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(start = 14.dp, end = 12.dp, top = 8.dp, bottom = if (expanded) 2.dp else 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(PiIcons.Bot, null, tint = t.textSecondary, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                Text("Subagents", style = MaterialTheme.typography.labelLarge, color = t.text)
                Spacer(Modifier.width(6.dp))
                Text("${subagents.size}", style = MaterialTheme.typography.labelMedium, color = t.textTertiary)
                if (activeCount > 0) {
                    Spacer(Modifier.width(6.dp))
                    Text("$activeCount running", style = MaterialTheme.typography.labelMedium, color = t.accent)
                }
                Spacer(Modifier.weight(1f))
                Icon(
                    if (expanded) PiIcons.ChevronUp else PiIcons.ChevronDown,
                    contentDescription = if (expanded) "Collapse subagents" else "Expand subagents",
                    tint = t.textTertiary,
                    modifier = Modifier.size(16.dp),
                )
            }
            if (expanded) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 168.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    subagents.forEach { agent ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .combinedClickable(
                                    onClick = { onOpen(agent.id) },
                                    onLongClick = if (agent.active) ({ abortTarget = agent }) else null,
                                )
                                .padding(start = 14.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            SubagentStatusIcon(agent)
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    agent.title,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = t.text,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                val secondary = buildList {
                                    agent.profile?.let { add(it) }
                                    val ago = relativeTime(isoMillis(agent.modified))
                                    if (ago.isNotEmpty()) add(ago)
                                }.joinToString(" · ")
                                if (secondary.isNotEmpty()) {
                                    Text(
                                        secondary,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = t.textTertiary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            Spacer(Modifier.width(8.dp))
                            Text(
                                if (agent.active) "running" else agent.status,
                                style = MaterialTheme.typography.labelMedium,
                                color = subagentStatusColor(agent),
                                maxLines = 1,
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                }
            }
        }
    }
    abortTarget?.let { target ->
        AbortSubagentDialog(
            agent = target,
            onDismiss = { abortTarget = null },
            onConfirm = {
                abortTarget = null
                onAbort(target.id)
            },
        )
    }
}

@Composable
private fun SubagentStatusIcon(agent: SubagentInfo) {
    val t = Pi.tokens
    when {
        agent.active -> CircularProgressIndicator(
            Modifier.size(14.dp),
            strokeWidth = 2.dp,
            color = t.accent,
            trackColor = Color.Transparent,
        )
        agent.status == "failed" -> Icon(PiIcons.Close, null, tint = t.danger, modifier = Modifier.size(14.dp))
        agent.status == "aborted" || agent.status == "interrupted" ->
            Icon(PiIcons.Warning, null, tint = t.warning, modifier = Modifier.size(14.dp))
        else -> Icon(PiIcons.Check, null, tint = t.success, modifier = Modifier.size(14.dp))
    }
}

@Composable
private fun subagentStatusColor(agent: SubagentInfo): Color {
    val t = Pi.tokens
    return when {
        agent.active -> t.accent
        agent.status == "failed" -> t.danger
        agent.status == "aborted" || agent.status == "interrupted" -> t.warning
        else -> t.success
    }
}

@Composable
private fun AbortSubagentDialog(agent: SubagentInfo, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val t = Pi.tokens
    val shape = RoundedCornerShape(20.dp)
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .clip(shape)
                .background(t.background)
                .border(1.dp, t.border, shape)
                .padding(20.dp),
        ) {
            Text("Abort this subagent?", style = MaterialTheme.typography.titleMedium, color = t.text)
            Spacer(Modifier.height(8.dp))
            Text(
                "\"${agent.title}\" is stopped mid-run; the parent session sees the aborted result.",
                style = MaterialTheme.typography.bodyMedium,
                color = t.textSecondary,
            )
            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                PiSecondaryButton("Cancel", onClick = onDismiss)
                Spacer(Modifier.width(8.dp))
                PiPrimaryButton("Abort", onClick = onConfirm)
            }
        }
    }
}

private val TTS_SPEEDS = listOf(
    0.5f to "Slow",
    1f to "Normal",
    1.2f to "Medium",
    1.5f to "Fast",
    1.7f to "Very fast",
    2f to "Super fast",
)

private fun speedLabel(speed: Float): String =
    if (speed == speed.toLong().toFloat()) "${speed.toLong()}x" else "${speed}x"

@Composable
private fun ContextIndicator(state: ChatUiState, onStatsOpened: () -> Unit) {
    val t = Pi.tokens
    var open by remember { mutableStateOf(false) }
    LaunchedEffect(state.openStats) {
        if (state.openStats) {
            open = true
            onStatsOpened()
        }
    }
    val percent = state.contextPercent
    Box(Modifier.padding(end = 6.dp)) {
        Row(
            Modifier
                .clip(RoundedCornerShape(50))
                .clickable { open = true }
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ContextRing(percent, size = 18.dp, stroke = 2.5.dp)
            if (percent != null) {
                Spacer(Modifier.width(7.dp))
                // The Box wraps exactly the percent text, so the chip layout (and the ring's
                // position on the title axis) is unchanged; the used-tokens label is a
                // layout-neutral overlay centered under the percent text.
                Box {
                    Text("${percent.roundToInt()}%", style = MaterialTheme.typography.labelMedium, color = t.textSecondary)
                    state.contextTokens?.let { tokens ->
                        Text(
                            compactNumber(tokens),
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, lineHeight = 11.sp),
                            color = t.textTertiary,
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .offset(y = 14.dp),
                        )
                    }
                }
            }
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            shape = RoundedCornerShape(16.dp),
            containerColor = t.surface,
            border = BorderStroke(1.dp, t.border),
            shadowElevation = 8.dp,
            modifier = Modifier.width(288.dp),
        ) {
            ContextDetails(state)
        }
    }
}

@Composable
private fun contextColor(percent: Double?): Color {
    val t = Pi.tokens
    return when {
        percent == null -> t.textTertiary
        percent >= 90 -> t.danger
        percent >= 75 -> t.warning
        else -> t.text
    }
}

@Composable
private fun ContextRing(percent: Double?, size: Dp, stroke: Dp) {
    val progress by animateFloatAsState(((percent ?: 0.0) / 100.0).toFloat().coerceIn(0f, 1f), label = "context")
    CircularProgressIndicator(
        progress = { progress },
        modifier = Modifier.size(size),
        color = contextColor(percent),
        trackColor = Pi.tokens.border,
        strokeWidth = stroke,
        strokeCap = StrokeCap.Round,
        gapSize = 0.dp,
    )
}

@Composable
private fun ContextDetails(state: ChatUiState) {
    val t = Pi.tokens
    val typography = MaterialTheme.typography
    val percent = state.contextPercent
    Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(contentAlignment = Alignment.Center) {
                ContextRing(percent, size = 46.dp, stroke = 4.dp)
                Text(
                    percent?.let { "${it.roundToInt()}%" } ?: "–",
                    style = typography.labelSmall.copy(fontSize = 11.sp),
                    color = t.text,
                )
            }
            Spacer(Modifier.width(14.dp))
            Column {
                Text("Context window", style = typography.labelLarge, color = t.text)
                val tokens = state.contextTokens
                val window = state.contextWindow
                Text(
                    when {
                        tokens != null && window != null -> "${compactNumber(tokens)} / ${compactNumber(window)} tokens"
                        window != null -> "? / ${compactNumber(window)} tokens"
                        else -> "Available while the agent is loaded"
                    },
                    style = typography.labelSmall.copy(fontWeight = FontWeight.Normal),
                    color = t.textTertiary,
                )
            }
        }
        val stats = state.stats ?: return@Column
        StatsSection("Tokens") {
            StatRow("Input", groupedNumber(stats.input))
            StatRow("Output", groupedNumber(stats.output))
            if (stats.cacheRead > 0) StatRow("Cache read", groupedNumber(stats.cacheRead))
            if (stats.cacheWrite > 0) StatRow("Cache write", groupedNumber(stats.cacheWrite))
            stats.cacheHitRate?.let { StatRow("Cache hit rate", String.format(java.util.Locale.US, "%.1f%%", it)) }
            StatRow("Total", groupedNumber(stats.totalTokens), strong = true)
            if (stats.cost > 0) StatRow("Cost", String.format(java.util.Locale.US, "$%.4f", stats.cost), strong = true)
        }
        StatsSection("Session") {
            StatRow("Your messages", groupedNumber(stats.userMessages.toLong()))
            StatRow("Replies", groupedNumber(stats.assistantMessages.toLong()))
            StatRow("Tool calls", groupedNumber(stats.toolCalls.toLong()))
            if (stats.activeMs > 0) StatRow("Active time", formatDuration(stats.activeMs))
        }
    }
}

@Composable
private fun StatsSection(title: String, content: @Composable () -> Unit) {
    val t = Pi.tokens
    HorizontalDivider(Modifier.padding(vertical = 12.dp), color = t.border)
    Text(
        title.uppercase(),
        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, letterSpacing = 0.6.sp),
        color = t.textTertiary,
        modifier = Modifier.padding(bottom = 6.dp),
    )
    content()
}

@Composable
private fun StatRow(label: String, value: String, strong: Boolean = false) {
    val t = Pi.tokens
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = t.textSecondary, modifier = Modifier.weight(1f))
        Text(
            value,
            style = MaterialTheme.typography.labelMedium.copy(
                fontFamily = GeistMono,
                fontWeight = if (strong) FontWeight.Medium else FontWeight.Normal,
            ),
            color = t.text,
        )
    }
}

@Composable
private fun EmptyChat(cwd: String) {
    val t = Pi.tokens
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 120.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(t.primary),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "π",
                color = t.onPrimary,
                fontFamily = Geist,
                fontWeight = FontWeight.SemiBold,
                fontSize = 30.sp,
            )
        }
        Spacer(Modifier.height(20.dp))
        Text("What should we work on?", style = MaterialTheme.typography.headlineSmall, color = t.text)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(PiIcons.Folder, null, tint = t.textTertiary, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                shortPath(cwd),
                style = MaterialTheme.typography.labelMedium.copy(fontFamily = GeistMono),
                color = t.textTertiary,
            )
        }
    }
}

/** Latest composer send-button values, read from the long-press gesture across recompositions. */
private data class SendGesture(
    val enabled: Boolean,
    val stopping: Boolean,
    val dictating: Boolean,
    val voiceAvailable: Boolean,
    val onSend: () -> Unit,
    val onStop: () -> Unit,
    val onVoiceStart: () -> Unit,
    val onVoiceStop: () -> Unit,
    val onVoiceCancel: () -> Unit,
)

@Composable
private fun Composer(
    state: ChatUiState,
    draft: TextFieldValue,
    onDraft: (TextFieldValue) -> Unit,
    onSend: () -> Unit,
    onKey: (KeyEvent) -> Boolean,
    slashMenu: @Composable () -> Unit,
    onStop: () -> Unit,
    onModels: () -> Unit,
    onThinking: (String) -> Unit,
    onAttach: () -> Unit,
    onRemoveImage: (Long) -> Unit,
    focusRequester: FocusRequester,
    voiceAvailable: Boolean,
    onVoiceStart: () -> Unit,
    onVoiceStop: () -> Unit,
    onVoiceCancel: () -> Unit,
) {
    val t = Pi.tokens
    val typography = MaterialTheme.typography
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(26.dp)
    val hasImages = state.attachments.isNotEmpty()
    // Web: the composer fieldset is disabled and dimmed while a built-in command runs.
    val pending = state.commandPending
    val model = state.model
    val modelName = model?.let { ref ->
        state.models.firstOrNull { it.provider == ref.provider && it.id == ref.modelId }?.name ?: ref.modelId
    }
    var imageWarningDismissed by remember { mutableStateOf(false) }
    LaunchedEffect(hasImages) { if (!hasImages) imageWarningDismissed = false }
    val hasContent = draft.text.isNotBlank() || hasImages
    val stopping = state.running && !hasContent
    // Wait for picked images to finish reading, so none is silently left behind.
    val enabled = !pending && (stopping || (hasContent && state.pendingImages == 0))
    val dictating = state.dictating
    // Dictation indicator: a soft red halo around the composer card (colored
    // shadow, which can spread outside the card bounds — unlike a blur layer).
    // The send button itself must stay a plain send button, so it never changes look.
    val glowElevation by animateDpAsState(
        targetValue = if (dictating) 24.dp else 0.dp,
        animationSpec = tween(150),
        label = "dictationGlow",
    )
    // The gesture coroutine outlives recompositions; the latest values go through snapshot state.
    val gesture = remember {
        mutableStateOf(SendGesture(false, false, false, false, {}, {}, {}, {}, {}))
    }
    gesture.value = SendGesture(
        enabled = enabled,
        stopping = stopping,
        dictating = dictating,
        voiceAvailable = voiceAvailable,
        onSend = onSend,
        onStop = onStop,
        onVoiceStart = onVoiceStart,
        onVoiceStop = onVoiceStop,
        onVoiceCancel = onVoiceCancel,
    )
    var voiceAttempted by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .background(t.background)
            .navigationBarsPadding()
            .imePadding()
            .padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 10.dp)
            .alpha(if (pending) 0.5f else 1f),
    ) {
        if (state.queued.isNotEmpty()) {
            Text(
                "Queued · " + state.queued.joinToString(" · "),
                style = typography.labelSmall,
                color = t.textTertiary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 10.dp, bottom = 8.dp),
            )
        }
        if (hasImages && !state.modelSupportsImages && !imageWarningDismissed) {
            ImageWarningBanner(modelName.orEmpty(), onClose = { imageWarningDismissed = true })
        }
        state.compactResult?.let { CompactResultLine(it) }
        slashMenu()
        Column(
            Modifier
                .fillMaxWidth()
                .shadow(glowElevation, shape, ambientColor = t.danger, spotColor = t.danger)
                .clip(shape)
                .background(t.surface)
                .border(1.dp, if (focused) t.borderStrong else t.border, shape)
                .padding(start = 18.dp, end = 8.dp, top = 14.dp, bottom = 8.dp),
        ) {
            if (hasImages || state.pendingImages > 0) {
                AttachmentStrip(state.attachments, state.pendingImages, onRemoveImage)
            }
            BasicTextField(
                value = draft,
                onValueChange = onDraft,
                enabled = !pending,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(end = 10.dp)
                    .focusRequester(focusRequester)
                    .onFocusChanged { focused = it.isFocused }
                    .onPreviewKeyEvent(onKey),
                textStyle = typography.bodyLarge.copy(color = t.text),
                cursorBrush = SolidColor(t.text),
                maxLines = 6,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                decorationBox = { field ->
                    Box {
                        if (draft.text.isEmpty()) {
                            Text(
                                if (state.running) "Steer the agent…" else "Ask pi anything…",
                                style = typography.bodyLarge,
                                color = t.textTertiary,
                            )
                        }
                        field()
                    }
                },
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    Modifier
                        .weight(1f)
                        .offset(x = (-8).dp) // align chip text with the placeholder
                        .horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(34.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .clickable(enabled = !pending, onClick = onAttach),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            PiIcons.Image,
                            contentDescription = "Attach image",
                            tint = if (hasImages) t.accent else t.textSecondary,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    GhostChip(
                        modelName ?: "Model",
                        onClick = onModels,
                        style = MaterialTheme.typography.labelSmall,
                        enabled = !pending
                    )
                    ThinkingChip(state, onThinking, enabled = !pending)
                }
                Box(
                    Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(if (enabled) t.primary else t.muted)
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                // Always await (and consume) the down before anything else:
                                // returning from this block without suspending on a pointer
                                // event makes awaitEachGesture's internal loop spin on the
                                // main thread (ANR) while the button is disabled.
                                awaitFirstDown(requireUnconsumed = false)
                                val g = gesture.value
                                // A long-press can start dictation even when the button is
                                // disabled (empty draft) — dictating is its main use case.
                                if (!g.enabled && !g.dictating && !g.voiceAvailable) return@awaitEachGesture
                                // Quick release = tap (send / stop dictation / stop run);
                                // held past 300 ms = voice dictation.
                                val releasedEarly = withTimeoutOrNull(300) {
                                    while (true) {
                                        val event = awaitPointerEvent(PointerEventPass.Main)
                                        if (event.changes.any { !it.pressed }) return@withTimeoutOrNull true
                                    }
                                }
                                if (releasedEarly == true) {
                                    when {
                                        // A quick tap while dictating stops the dictation.
                                        gesture.value.dictating -> gesture.value.onVoiceStop()
                                        gesture.value.stopping -> gesture.value.onStop()
                                        gesture.value.enabled -> gesture.value.onSend()
                                    }
                                } else {
                                    if (gesture.value.voiceAvailable) {
                                        voiceAttempted = true
                                        gesture.value.onVoiceStart()
                                    }
                                    // Keep holding until the pointer is released.
                                    while (true) {
                                        val event = awaitPointerEvent(PointerEventPass.Main)
                                        if (event.changes.any { !it.pressed }) break
                                    }
                                    when {
                                        gesture.value.dictating -> gesture.value.onVoiceStop()
                                        voiceAttempted -> {
                                            voiceAttempted = false
                                            gesture.value.onVoiceCancel()
                                        }
                                    }
                                }
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    if (stopping) {
                        Box(
                            Modifier
                                .size(11.dp)
                                .background(t.onPrimary, RoundedCornerShape(2.5.dp)),
                        )
                    } else {
                        Icon(
                            PiIcons.ArrowUp,
                            contentDescription = "Send",
                            tint = if (enabled) t.onPrimary else t.textTertiary,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CompactResultLine(text: String) {
    val t = Pi.tokens
    val shape = RoundedCornerShape(6.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .clip(shape)
            .background(t.success.copy(alpha = 0.08f))
            .border(1.dp, t.success.copy(alpha = 0.24f), shape)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(PiIcons.Check, contentDescription = null, tint = t.success, modifier = Modifier.size(11.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp), color = t.success)
    }
}

@Composable
private fun GhostChip(
    label: String,
    onClick: () -> Unit,
    style: TextStyle = MaterialTheme.typography.labelMedium,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val t = Pi.tokens
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = style, color = t.textSecondary, maxLines = 1)
        Spacer(Modifier.width(3.dp))
        Icon(PiIcons.ChevronDown, null, tint = t.textTertiary, modifier = Modifier.size(13.dp))
    }
}

@Composable
private fun ThinkingChip(state: ChatUiState, onThinking: (String) -> Unit, enabled: Boolean = true) {
    val t = Pi.tokens
    var open by remember { mutableStateOf(false) }
    val levels =
        state.model?.let { state.thinkingLevels[it.key] }?.takeIf { it.isNotEmpty() } ?: FALLBACK_THINKING_LEVELS
    Box {
        GhostChip(state.thinkingLevel ?: "default", onClick = { open = true }, enabled = enabled)
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            shape = RoundedCornerShape(14.dp),
            containerColor = t.surface,
            border = BorderStroke(1.dp, t.border),
            shadowElevation = 6.dp,
        ) {
            levels.forEach { level ->
                DropdownMenuItem(
                    text = { Text(level, style = MaterialTheme.typography.bodyMedium, color = t.text) },
                    trailingIcon = {
                        if (level == state.thinkingLevel) Icon(
                            PiIcons.Check,
                            null,
                            tint = t.text,
                            modifier = Modifier.size(16.dp)
                        )
                    },
                    onClick = {
                        open = false
                        onThinking(level)
                    },
                )
            }
        }
    }
}

@Composable
private fun ModelSheet(state: ChatUiState, onDismiss: () -> Unit, onSelect: (ModelOption) -> Unit) {
    val t = Pi.tokens
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
            "Model",
            style = MaterialTheme.typography.titleLarge,
            color = t.text,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
        if (state.models.isEmpty()) {
            Text(
                "No models available. Configure providers in pi-web.",
                style = MaterialTheme.typography.bodyMedium,
                color = t.textSecondary,
                modifier = Modifier.padding(24.dp),
            )
        }
        LazyColumn(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding(),
            contentPadding = PaddingValues(bottom = 16.dp),
        ) {
            state.models.groupBy { it.provider }.forEach { (provider, options) ->
                item(key = "provider:$provider") {
                    Text(
                        provider.uppercase(),
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, letterSpacing = 0.8.sp),
                        color = t.textTertiary,
                        modifier = Modifier.padding(start = 24.dp, top = 16.dp, bottom = 4.dp),
                    )
                }
                items(options, key = { it.key }) { option ->
                    val selected =
                        state.model?.let { it.provider == option.provider && it.modelId == option.id } == true
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (selected) t.muted else Color.Transparent)
                            .clickable { onSelect(option) }
                            .padding(horizontal = 12.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                option.name,
                                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                                color = t.text
                            )
                            Text(
                                option.id,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = GeistMono,
                                    fontWeight = FontWeight.Normal
                                ),
                                color = t.textTertiary,
                            )
                        }
                        if (selected) Icon(
                            PiIcons.Check,
                            contentDescription = "Selected",
                            tint = t.text,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ExtensionDialogView(dialog: ExtensionDialog, vm: ChatViewModel) {
    val t = Pi.tokens
    val typography = MaterialTheme.typography
    val title = dialog.title.ifBlank { "The agent needs input" }
    var collapsed by rememberSaveable(dialog.id) { mutableStateOf(false) }
    var text by rememberSaveable(dialog.id) {
        mutableStateOf(if (dialog.method == "editor") dialog.prefill.orEmpty() else "")
    }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(dialog.id) {
        if (dialog.method == "input" || dialog.method == "editor") focusRequester.requestFocus()
    }

    // The server closes expired requests via extension_ui_closed.
    val expiresAt = dialog.expiresAt
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(expiresAt) {
        if (expiresAt == null) return@LaunchedEffect
        while (true) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    val remainingSeconds = expiresAt?.let { e -> maxOf(0, ceil((e - now) / 1000.0).toInt()) }

    // Back cancels the request while the card is expanded (web: Esc).
    BackHandler(enabled = !collapsed) { vm.respondDialog(cancelled = true) }

    val configuration = LocalConfiguration.current
    val maxCardHeight = minOf(360.dp, configuration.screenHeightDp.dp * 0.45f)
    val maxSummaryWidth = configuration.screenWidthDp.dp * 0.34f

    Column(
        Modifier
            .fillMaxWidth()
            .background(t.background)
            .navigationBarsPadding()
            .imePadding()
            .padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 10.dp),
    ) {
        if (collapsed) {
            val summary = when (dialog.method) {
                "select" -> dialog.options.firstOrNull()
                "confirm" -> dialog.message?.split("\n")?.firstOrNull { it.isNotBlank() }?.trim()
                else -> null
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .border(1.dp, t.accent.mixedWith(t.border, 0.55f), RoundedCornerShape(10.dp))
                    .background(t.surface)
                    .clickable { collapsed = false }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusDot(t.accent, pulsing = true, size = 8.dp)
                Spacer(Modifier.width(10.dp))
                Text(
                    title,
                    style = typography.bodySmall.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
                    color = t.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                summary?.let {
                    Text(
                        it,
                        style = typography.bodySmall.copy(fontSize = 12.sp),
                        color = t.textTertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .widthIn(max = maxSummaryWidth)
                            .padding(start = 8.dp),
                    )
                }
                remainingSeconds?.let {
                    Text(
                        "expires in ${it}s",
                        style = typography.labelSmall.copy(fontFamily = GeistMono, fontSize = 11.sp),
                        color = t.textTertiary,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
                Text(
                    "request",
                    style = typography.labelSmall.copy(fontFamily = GeistMono, fontSize = 11.sp),
                    color = t.textTertiary,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        } else {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxCardHeight)
                    .shadow(3.dp, RoundedCornerShape(10.dp))
                    .clip(RoundedCornerShape(10.dp))
                    .border(1.dp, t.border, RoundedCornerShape(10.dp))
                    .background(t.background),
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 14.dp, end = 6.dp, top = 12.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            title,
                            style = typography.bodyLarge.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
                            color = t.text,
                        )
                        Row(
                            Modifier.padding(top = 3.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                "extension request",
                                style = typography.labelSmall.copy(fontFamily = GeistMono, fontSize = 11.sp),
                                color = t.textTertiary,
                            )
                            remainingSeconds?.let {
                                Text(
                                    "expires in ${it}s",
                                    style = typography.labelSmall.copy(fontFamily = GeistMono, fontSize = 11.sp),
                                    color = t.textTertiary,
                                )
                            }
                        }
                    }
                    IconButton(
                        onClick = { collapsed = true },
                        modifier = Modifier.size(26.dp),
                    ) {
                        Icon(
                            PiIcons.ChevronDown,
                            contentDescription = "Collapse",
                            tint = t.textSecondary,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
                HorizontalDivider(thickness = 1.dp, color = t.border)

                Column(
                    Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                        .padding(14.dp),
                ) {
                    when (dialog.method) {
                        "confirm" -> dialog.message?.let { Markdown(it) }
                        "select" -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            dialog.options.forEach { option ->
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(9.dp))
                                        .border(1.dp, t.border, RoundedCornerShape(9.dp))
                                        .background(t.surface)
                                        .clickable { vm.respondDialog(value = option) }
                                        .padding(horizontal = 10.dp, vertical = 9.dp),
                                ) {
                                    Markdown(option)
                                }
                            }
                        }

                        "input" -> OutlinedTextField(
                            value = text,
                            onValueChange = { text = it },
                            placeholder = { dialog.placeholder?.let { Text(it) } },
                            singleLine = true,
                            shape = RoundedCornerShape(9.dp),
                            colors = piTextFieldColors(),
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(focusRequester),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { vm.respondDialog(value = text) }),
                        )

                        "editor" -> OutlinedTextField(
                            value = text,
                            onValueChange = { text = it },
                            placeholder = { dialog.placeholder?.let { Text(it) } },
                            minLines = 6,
                            shape = RoundedCornerShape(9.dp),
                            colors = piTextFieldColors(),
                            textStyle = typography.bodyMedium.copy(fontFamily = GeistMono, color = t.text),
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(focusRequester),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(onSend = { vm.respondDialog(value = text) }),
                        )
                    }
                }

                HorizontalDivider(thickness = 1.dp, color = t.border)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(t.surface)
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PiSecondaryButton("Cancel", onClick = { vm.respondDialog(cancelled = true) })
                    when (dialog.method) {
                        "confirm" -> PiPrimaryButton("Confirm", onClick = { vm.respondDialog(confirmed = true) })
                        "input", "editor" -> PiPrimaryButton("Submit", onClick = { vm.respondDialog(value = text) })
                    }
                }
            }
        }
    }
}

/** Web: `color-mix(in srgb, accent 45%, border)` — Compose 1.7 has no Color.blend. */
private fun Color.mixedWith(other: Color, fraction: Float): Color = Color(
    red = red + (other.red - red) * fraction,
    green = green + (other.green - green) * fraction,
    blue = blue + (other.blue - blue) * fraction,
    alpha = alpha + (other.alpha - alpha) * fraction,
)
