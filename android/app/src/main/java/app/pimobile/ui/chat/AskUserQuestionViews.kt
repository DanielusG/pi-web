package app.pimobile.ui.chat

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pimobile.data.AskDraft
import app.pimobile.data.AskForm
import app.pimobile.data.AskQuestion
import app.pimobile.data.AskUserQuestion
import app.pimobile.data.PendingQuestion
import app.pimobile.data.QuestionnaireSummary
import app.pimobile.ui.markdown.CodeBlock
import app.pimobile.ui.markdown.Markdown
import app.pimobile.ui.theme.GeistMono
import app.pimobile.ui.theme.LocalChatFontScale
import app.pimobile.ui.theme.Pi
import app.pimobile.ui.theme.PiIcons
import app.pimobile.ui.theme.PiPrimaryButton
import app.pimobile.ui.theme.PiSecondaryButton
import app.pimobile.ui.theme.StatusDot
import app.pimobile.ui.theme.piTextFieldColors

// The native ask_user_question dialog (data/AskUserQuestion.kt), in the composer slot like the
// extension dialog and with its card look: one tab per question plus a review tab when there are
// several, choice rows, an own-answer row, previews, notes. Back collapses it to the pill instead
// of declining, so a half-filled questionnaire is not lost to a stray gesture. Inside the card it
// stays monochrome like the rest of the app: the files screen's tabs, neutral chips, rows chosen
// the way the model sheet marks its row; only the collapsed pill keeps the extension dialog's accent.

private val RowShape = RoundedCornerShape(9.dp)
private val CardShape = RoundedCornerShape(10.dp)

@Composable
fun AskUserQuestionDialog(
    pending: PendingQuestion,
    form: AskForm,
    /** Further calls queued behind this one. */
    waitingCount: Int,
    onForm: ((AskForm) -> AskForm) -> Unit,
    onSubmit: () -> Unit,
    onDecline: () -> Unit,
) {
    val t = Pi.tokens
    val typography = MaterialTheme.typography
    val questions = pending.questions
    val multi = questions.size > 1
    val reviewTab = questions.size
    val tab = form.tab.coerceIn(0, if (multi) reviewTab else 0)
    val onReview = multi && tab == reviewTab
    val question = questions.getOrNull(tab)
    val locked = form.sending
    // Several questions may go with some left blank (the model reads them as unanswered).
    val canSubmit = !locked && (multi || AskUserQuestion.allAnswered(questions, form.drafts))
    fun updateDraft(index: Int, transform: (AskDraft) -> AskDraft) = onForm { current ->
        current.copy(drafts = current.drafts.mapIndexed { i, draft -> if (i == index) transform(draft) else draft }, error = null)
    }

    BackHandler(enabled = !form.collapsed) { onForm { it.copy(collapsed = true) } }

    val configuration = LocalConfiguration.current
    val maxCardHeight = minOf(520.dp, configuration.screenHeightDp.dp * 0.6f)
    val title = when {
        onReview -> "Review your answers"
        question != null -> question.question
        else -> "The agent has a question"
    }

    Column(
        Modifier
            .fillMaxWidth()
            .background(t.background)
            .navigationBarsPadding()
            .imePadding()
            .padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 10.dp),
    ) {
        if (form.collapsed) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(CardShape)
                    .border(1.dp, t.accent.mixedWith(t.border, 0.55f), CardShape)
                    .background(t.surface)
                    .clickable { onForm { it.copy(collapsed = false) } }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusDot(t.accent, size = 8.dp)
                Spacer(Modifier.width(10.dp))
                Text(
                    questions.first().question,
                    style = typography.bodySmall.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
                    color = t.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                WaitingCount(waitingCount)
                Text(
                    if (multi) "${questions.size} questions" else "question",
                    style = typography.labelSmall.copy(fontFamily = GeistMono, fontSize = 11.sp),
                    color = t.textTertiary,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            return@Column
        }

        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = maxCardHeight)
                .shadow(3.dp, CardShape)
                .clip(CardShape)
                .border(1.dp, t.border, CardShape)
                .background(t.background),
        ) {
            if (multi) {
                QuestionTabs(
                    questions = questions,
                    drafts = form.drafts,
                    selected = tab,
                    onSelect = { index -> onForm { it.copy(tab = index) } },
                )
                HorizontalDivider(thickness = 1.dp, color = t.border)
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 14.dp, end = 6.dp, top = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        title,
                        style = typography.bodyLarge.copy(
                            fontSize = 14.sp * LocalChatFontScale.current,
                            fontWeight = FontWeight.SemiBold,
                        ),
                        color = t.text,
                        modifier = Modifier
                            .heightIn(max = maxCardHeight * 0.3f)
                            .verticalScroll(rememberScrollState()),
                    )
                    Row(
                        Modifier.padding(top = 3.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (!multi && question != null) HeaderChip(question.header)
                        Text(
                            when {
                                !multi -> if (question?.multiSelect == true) "pick any" else "agent question"
                                onReview -> "${questions.size} questions"
                                else -> "question ${tab + 1} of ${questions.size}" +
                                    if (question?.multiSelect == true) " · pick any" else ""
                            },
                            style = typography.labelSmall.copy(fontFamily = GeistMono, fontSize = 11.sp),
                            color = t.textTertiary,
                        )
                        WaitingCount(waitingCount)
                    }
                }
                IconButton(onClick = { onForm { it.copy(collapsed = true) } }, modifier = Modifier.size(26.dp)) {
                    Icon(PiIcons.ChevronDown, contentDescription = "Collapse", tint = t.textSecondary, modifier = Modifier.size(14.dp))
                }
            }
            HorizontalDivider(thickness = 1.dp, color = t.border)

            Column(
                Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(remember(pending.toolCallId, tab) { ScrollState(0) })
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (onReview) {
                    ReviewBody(
                        questions = questions,
                        form = form,
                        locked = locked,
                        onOpen = { index -> onForm { it.copy(tab = index) } },
                        onForm = onForm,
                    )
                } else if (question != null) {
                    QuestionBody(
                        key = "${pending.toolCallId}:$tab",
                        question = question,
                        draft = form.drafts[tab],
                        locked = locked,
                        onDraft = { transform -> updateDraft(tab, transform) },
                    )
                }
                form.error?.let { Text(it, style = typography.bodySmall, color = t.danger) }
            }

            HorizontalDivider(thickness = 1.dp, color = t.border)
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(t.surface)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PiSecondaryButton("Decline", onClick = onDecline, enabled = !locked)
                Spacer(Modifier.weight(1f))
                if (multi && tab > 0) {
                    PiSecondaryButton("Back", onClick = { onForm { it.copy(tab = tab - 1) } }, enabled = !locked)
                }
                when {
                    form.sending -> PiPrimaryButton("Sending…", onClick = {}, enabled = false)
                    multi && !onReview -> PiPrimaryButton("Next", onClick = { onForm { it.copy(tab = tab + 1) } })
                    else -> PiPrimaryButton("Submit", onClick = onSubmit, enabled = canSubmit)
                }
            }
        }
    }
}

@Composable
private fun WaitingCount(count: Int) {
    if (count <= 0) return
    Text(
        "+$count more",
        style = MaterialTheme.typography.labelSmall.copy(fontFamily = GeistMono, fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
        color = Pi.tokens.textSecondary,
        modifier = Modifier.padding(start = 8.dp),
    )
}

/** A question's short header: a neutral mono label, like the tool card's name. */
@Composable
private fun HeaderChip(text: String) {
    val t = Pi.tokens
    val shape = RoundedCornerShape(5.dp)
    Text(
        text,
        style = MaterialTheme.typography.labelSmall.copy(fontFamily = GeistMono, fontSize = 11.sp),
        color = t.textSecondary,
        maxLines = 1,
        modifier = Modifier
            .clip(shape)
            .background(t.muted)
            .border(1.dp, t.border, shape)
            .padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

@Composable
private fun QuestionTabs(questions: List<AskQuestion>, drafts: List<AskDraft>, selected: Int, onSelect: (Int) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(start = 6.dp, end = 6.dp, top = 2.dp),
    ) {
        questions.forEachIndexed { index, question ->
            QuestionTab(
                question.header,
                done = AskUserQuestion.isAnswered(question, drafts[index]),
                selected = index == selected,
                onClick = { onSelect(index) },
            )
        }
        QuestionTab("Review", done = false, selected = selected == questions.size, onClick = { onSelect(questions.size) })
    }
}

/** The files screen's tab (FilesScreen's TabLabel): a label over a line in the text color when chosen. */
@Composable
private fun QuestionTab(text: String, done: Boolean, selected: Boolean, onClick: () -> Unit) {
    val t = Pi.tokens
    val color = if (selected) t.text else t.textTertiary
    Column(
        Modifier
            .width(IntrinsicSize.Max)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp),
    ) {
        Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (done) {
                Icon(PiIcons.Check, contentDescription = "Answered", tint = color, modifier = Modifier.size(12.dp))
                Spacer(Modifier.width(4.dp))
            }
            Text(text, style = MaterialTheme.typography.labelMedium, color = color, maxLines = 1)
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(2.dp)
                .background(if (selected) t.text else Color.Transparent, RoundedCornerShape(1.dp)),
        )
    }
}

@Composable
private fun QuestionBody(
    key: String,
    question: AskQuestion,
    draft: AskDraft,
    locked: Boolean,
    onDraft: ((AskDraft) -> AskDraft) -> Unit,
) {
    val t = Pi.tokens
    question.options.forEachIndexed { index, option ->
        val selected = index in draft.selected
        ChoiceRow(
            selected = selected,
            multi = question.multiSelect,
            enabled = !locked,
            onClick = { onDraft { AskUserQuestion.select(question, it, index) } },
        ) {
            Text(option.label, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), color = t.text)
            if (option.description.isNotBlank()) {
                Text(option.description, style = MaterialTheme.typography.bodySmall, color = t.textSecondary)
            }
        }
    }
    ChoiceRow(
        selected = draft.custom,
        multi = question.multiSelect,
        enabled = !locked,
        onClick = { onDraft { AskUserQuestion.toggleCustom(question, it) } },
    ) {
        Text("Write your own answer", style = MaterialTheme.typography.bodyMedium, color = t.textSecondary)
    }
    if (draft.custom) {
        OutlinedTextField(
            value = draft.text,
            onValueChange = { value -> onDraft { it.copy(text = value) } },
            enabled = !locked,
            placeholder = { Text("Your answer", style = MaterialTheme.typography.bodyMedium) },
            minLines = 2,
            shape = RowShape,
            colors = piTextFieldColors(),
            modifier = Modifier.fillMaxWidth(),
        )
    }
    if (question.hasPreviews) {
        // The chosen row's preview (the first option's before a choice), as the web and rpiv show
        // the highlighted row's: a row without one says so instead of showing another option's.
        val option = when {
            draft.selected.isNotEmpty() -> question.options[draft.selected.first()]
            draft.custom -> null
            else -> question.options.first()
        }
        Text(
            if (option != null) "Preview · ${option.label}" else "Preview",
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = GeistMono, fontSize = 11.sp),
            color = t.textTertiary,
            modifier = Modifier.padding(top = 4.dp),
        )
        val preview = option?.preview
        if (preview.isNullOrEmpty()) NoPreview() else OptionPreview(preview)
    }
    NoteField(
        key = key,
        open = draft.noteOpen,
        value = draft.note,
        locked = locked,
        label = "Add note",
        placeholder = "Note for this question",
        onOpen = { onDraft { it.copy(noteOpen = true) } },
        onValue = { value -> onDraft { it.copy(note = value) } },
    )
}

/**
 * Markdown in a monospace box. A preview with a code fence goes through the chat's renderer; plain
 * text (an ASCII mockup) keeps its spacing and scrolls sideways instead of wrapping.
 */
@Composable
private fun OptionPreview(preview: String) {
    if ("```" in preview) {
        val t = Pi.tokens
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RowShape)
                .background(t.code)
                .border(1.dp, t.border, RowShape)
                .padding(12.dp),
        ) { Markdown(preview) }
    } else {
        CodeBlock("", preview.trimEnd(), header = false)
    }
}

@Composable
private fun NoPreview() {
    val t = Pi.tokens
    Text(
        "No preview available",
        style = MaterialTheme.typography.bodySmall,
        color = t.textTertiary,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RowShape)
            .background(t.code)
            .border(1.dp, t.border, RowShape)
            .padding(12.dp),
    )
}

/** An option row: a muted fill when chosen, as the model sheet marks its row, and a mark in the text color. */
@Composable
private fun ChoiceRow(
    selected: Boolean,
    multi: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    val t = Pi.tokens
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RowShape)
            .border(1.dp, if (selected) t.borderStrong else t.border, RowShape)
            .background(if (selected) t.muted else t.surface)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        ChoiceMark(selected, multi, Modifier.padding(top = 2.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            content()
        }
    }
}

/** A box (multiple choice) or a circle (one answer), filled with the text color when chosen. */
@Composable
private fun ChoiceMark(selected: Boolean, multi: Boolean, modifier: Modifier = Modifier) {
    val t = Pi.tokens
    val shape = if (multi) RoundedCornerShape(4.dp) else CircleShape
    Box(
        modifier
            .size(16.dp)
            .clip(shape)
            .background(if (selected) t.primary else t.background)
            .border(1.dp, if (selected) t.primary else t.borderStrong, shape),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            if (multi) Icon(PiIcons.Check, contentDescription = null, tint = t.onPrimary, modifier = Modifier.size(12.dp))
            else Box(Modifier.size(6.dp).background(t.onPrimary, CircleShape))
        }
    }
}

@Composable
private fun NoteField(
    key: String,
    open: Boolean,
    value: String,
    locked: Boolean,
    label: String,
    placeholder: String,
    onOpen: () -> Unit,
    onValue: (String) -> Unit,
) {
    val t = Pi.tokens
    if (!open) {
        Row(
            Modifier
                .clip(RoundedCornerShape(6.dp))
                .clickable(enabled = !locked, onClick = onOpen)
                .padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(PiIcons.Pencil, contentDescription = null, tint = t.textSecondary, modifier = Modifier.size(13.dp))
            Spacer(Modifier.width(6.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, color = t.textSecondary)
        }
        return
    }
    val focus = remember(key) { FocusRequester() }
    LaunchedEffect(key) { if (value.isEmpty()) runCatching { focus.requestFocus() } }
    // A caption of its own: the placeholder is gone once the note has text.
    Text(placeholder, style = MaterialTheme.typography.labelMedium, color = t.textTertiary, modifier = Modifier.padding(top = 4.dp))
    OutlinedTextField(
        value = value,
        onValueChange = onValue,
        enabled = !locked,
        minLines = 2,
        shape = RowShape,
        colors = piTextFieldColors(),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp)
            .focusRequester(focus),
    )
}

@Composable
private fun ReviewBody(
    questions: List<AskQuestion>,
    form: AskForm,
    locked: Boolean,
    onOpen: (Int) -> Unit,
    onForm: ((AskForm) -> AskForm) -> Unit,
) {
    val t = Pi.tokens
    if (!AskUserQuestion.allAnswered(questions, form.drafts)) {
        Text("Questions left blank are sent as unanswered.", style = MaterialTheme.typography.bodySmall, color = t.textTertiary)
    }
    questions.forEachIndexed { index, question ->
        val answer = AskUserQuestion.answerText(question, form.drafts[index])
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RowShape)
                .border(1.dp, t.border, RowShape)
                .background(t.surface)
                .clickable(enabled = !locked) { onOpen(index) }
                .padding(horizontal = 10.dp, vertical = 9.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                HeaderChip(question.header)
                Spacer(Modifier.width(8.dp))
                Text(
                    question.question,
                    style = MaterialTheme.typography.bodySmall,
                    color = t.textSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (answer != null) {
                Text("→ $answer", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), color = t.text)
            } else {
                Text("Not answered", style = MaterialTheme.typography.bodySmall, color = t.textTertiary)
            }
            form.drafts[index].note.trim().takeIf { it.isNotEmpty() }?.let {
                Text("Note: $it", style = MaterialTheme.typography.bodySmall, color = t.textSecondary)
            }
        }
    }
    NoteField(
        key = "review",
        open = form.globalNoteOpen,
        value = form.globalNote,
        locked = locked,
        label = "Add a note for all answers",
        placeholder = "Note for all answers",
        onOpen = { onForm { it.copy(globalNoteOpen = true) } },
        onValue = { value -> onForm { it.copy(globalNote = value, error = null) } },
    )
}

// region summary card

/** A finished call in the message list: each question and what was answered. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AskUserSummaryCard(
    questions: List<AskQuestion>,
    summary: QuestionnaireSummary,
    selected: Boolean,
    onLongPress: (() -> Unit)?,
) {
    val t = Pi.tokens
    val typography = MaterialTheme.typography
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .border(1.dp, if (selected) t.borderStrong else t.border, shape)
            .combinedClickable(onClick = {}, onLongClick = onLongPress)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            questions.forEach { HeaderChip(it.header) }
        }
        if (summary.cancelled) {
            Text("You declined to answer.", style = typography.bodySmall, color = t.textTertiary)
        } else {
            questions.forEachIndexed { index, question ->
                val answer = summary.answers.firstOrNull { it.questionIndex == index }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        question.question,
                        style = typography.bodySmall.copy(fontSize = typography.bodySmall.fontSize * LocalChatFontScale.current),
                        color = t.textSecondary,
                    )
                    if (answer != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("→ ", style = typography.bodyMedium, color = t.textTertiary)
                            Text(
                                AskUserQuestion.summaryText(answer),
                                style = typography.bodyMedium.copy(
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = typography.bodyMedium.fontSize * LocalChatFontScale.current,
                                ),
                                color = t.text,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            if (answer.kind == "custom") {
                                Text(
                                    "your own answer",
                                    style = typography.labelSmall.copy(fontSize = 11.sp),
                                    color = t.textTertiary,
                                    modifier = Modifier.padding(start = 6.dp),
                                )
                            }
                        }
                        answer.notes?.let { Text("Note: $it", style = typography.bodySmall, color = t.textSecondary) }
                    } else {
                        Text("Not answered", style = typography.bodySmall, color = t.textTertiary)
                    }
                }
            }
        }
        summary.globalNote?.let { Text("Note for all answers: $it", style = typography.bodySmall, color = t.textSecondary) }
    }
}

// endregion
