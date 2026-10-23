package app.pimobile.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

// pi-web's native ask_user_question (lib/ask-user-question.ts, lib/ask-user-extension.ts).
// While a call waits, its `tool_execution_update` carries the questions in
// `details: { kind: "pi-web-ask-user-pending", questions }`; the dialog answers with the
// `answer_question` command, all or nothing, and `tool_execution_end` closes it. The finished
// result's details are the rpiv-ask-user-question shape, so old rpiv transcripts read the same.

data class AskOption(val label: String, val description: String, val preview: String? = null)

data class AskQuestion(
    val question: String,
    val header: String,
    val options: List<AskOption>,
    val multiSelect: Boolean = false,
) {
    /** Previews belong to single-choice questions only; a multiple-choice one ignores them. */
    val hasPreviews: Boolean get() = !multiSelect && options.any { !it.preview.isNullOrEmpty() }
}

data class PendingQuestion(val toolCallId: String, val questions: List<AskQuestion>)

/** One question as the user is filling it in. Single choice keeps at most one index. */
data class AskDraft(
    val selected: Set<Int> = emptySet(),
    /** The "Write your own answer" row; on a single-choice question it replaces the options. */
    val custom: Boolean = false,
    val text: String = "",
    val noteOpen: Boolean = false,
    val note: String = "",
)

/** The dialog's state for one pending call. [tab] == questions.size is the review tab. */
data class AskForm(
    val drafts: List<AskDraft>,
    val tab: Int = 0,
    val globalNoteOpen: Boolean = false,
    val globalNote: String = "",
    val collapsed: Boolean = false,
    /** Sent and waiting for `tool_execution_end`: the form stays locked. */
    val sending: Boolean = false,
    val error: String? = null,
) {
    companion object {
        fun blank(pending: PendingQuestion) = AskForm(drafts = pending.questions.map { AskDraft() })
    }
}

data class AnsweredQuestion(
    val questionIndex: Int,
    val question: String,
    /** "option", "custom" or "multi". */
    val kind: String,
    val answer: String?,
    val selected: List<String>,
    val notes: String?,
)

data class QuestionnaireSummary(
    val answers: List<AnsweredQuestion>,
    val cancelled: Boolean,
    val globalNote: String?,
    val unanswered: List<Int>,
)

object AskUserQuestion {
    const val TOOL_NAME = "ask_user_question"
    const val PENDING_KIND = "pi-web-ask-user-pending"

    /** The questions of a pending update, or null when [details] is not one. */
    fun parsePending(toolCallId: String, details: JsonObject?): PendingQuestion? {
        if (details?.str("kind") != PENDING_KIND) return null
        val questions = parseQuestions(details["questions"]) ?: return null
        return PendingQuestion(toolCallId, questions)
    }

    /**
     * Questions from a pending update or a tool call's input. Some providers send `questions` (or
     * an entry, or its `options`) as a JSON string, which the server decodes the same way.
     */
    fun parseQuestions(element: JsonElement?): List<AskQuestion>? {
        val array = decode(element) as? JsonArray ?: return null
        if (array.isEmpty()) return null
        return array.map { entry ->
            val q = decode(entry) as? JsonObject ?: return null
            val options = (decode(q["options"]) as? JsonArray ?: return null).map { option ->
                val o = decode(option) as? JsonObject ?: return null
                AskOption(
                    label = o.str("label") ?: return null,
                    description = o.str("description") ?: return null,
                    preview = o.str("preview")?.takeIf { it.isNotEmpty() },
                )
            }
            AskQuestion(
                question = q.str("question") ?: return null,
                header = q.str("header") ?: return null,
                options = options,
                multiSelect = q.bool("multiSelect") == true,
            )
        }
    }

    private fun decode(element: JsonElement?): JsonElement? {
        val text = (element as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return element
        return runCatching { Json.parseToJsonElement(text) }.getOrNull()
    }

    /** Picking an option: single choice replaces the answer, multiple choice toggles it. */
    fun select(question: AskQuestion, draft: AskDraft, index: Int): AskDraft =
        if (question.multiSelect) {
            draft.copy(selected = if (index in draft.selected) draft.selected - index else draft.selected + index)
        } else {
            draft.copy(selected = setOf(index), custom = false)
        }

    /** The "Write your own answer" row: on single choice it deselects the options. */
    fun toggleCustom(question: AskQuestion, draft: AskDraft): AskDraft =
        if (question.multiSelect) draft.copy(custom = !draft.custom)
        else draft.copy(custom = true, selected = emptySet())

    /** What the server accepts: an option, or non-blank own text (multiple choice: either or both). */
    fun isAnswered(question: AskQuestion, draft: AskDraft): Boolean {
        val hasText = draft.custom && draft.text.isNotBlank()
        return if (question.multiSelect) draft.selected.isNotEmpty() || hasText
        else draft.selected.size == 1 || hasText
    }

    fun allAnswered(questions: List<AskQuestion>, drafts: List<AskDraft>): Boolean =
        questions.size == drafts.size && questions.indices.all { isAnswered(questions[it], drafts[it]) }

    /** The answer as the review tab and the summary read it, null when unanswered. */
    fun answerText(question: AskQuestion, draft: AskDraft): String? {
        if (!isAnswered(question, draft)) return null
        val text = draft.text.trim().takeIf { draft.custom && it.isNotEmpty() }
        if (!question.multiSelect) return text ?: question.options[draft.selected.first()].label
        val labels = draft.selected.sorted().map { question.options[it].label }
        return (labels + listOfNotNull(text)).joinToString(", ")
    }

    /** The `answer_question` command for a complete form. */
    fun answerCommand(toolCallId: String, questions: List<AskQuestion>, form: AskForm): JsonObject = buildJsonObject {
        put("type", "answer_question")
        put("toolCallId", toolCallId)
        putJsonArray("answers") {
            questions.forEachIndexed { index, question ->
                val draft = form.drafts[index]
                addJsonObject {
                    put("questionIndex", index)
                    val text = draft.text.trim().takeIf { draft.custom && it.isNotEmpty() }
                    // Single choice takes exactly one of the two; own text wins, as it deselects.
                    val indexes = if (!question.multiSelect && text != null) emptyList() else draft.selected.sorted()
                    if (indexes.isNotEmpty()) putJsonArray("optionIndexes") { indexes.forEach { add(it) } }
                    if (text != null) put("text", text)
                    draft.note.trim().takeIf { it.isNotEmpty() }?.let { put("notes", it) }
                }
            }
        }
        form.globalNote.trim().takeIf { it.isNotEmpty() }?.let { put("globalNote", it) }
    }

    fun cancelCommand(toolCallId: String): JsonObject = buildJsonObject {
        put("type", "answer_question")
        put("toolCallId", toolCallId)
        put("cancel", true)
    }

    /**
     * A finished call's details, or null for anything the summary card cannot show (a refused
     * call carries `error`, a stopped one no details at all).
     */
    fun parseResult(details: JsonObject?): QuestionnaireSummary? {
        details ?: return null
        val answers = details.arr("answers") ?: return null
        val cancelled = details.bool("cancelled") ?: return null
        if (details.str("error") != null) return null
        return QuestionnaireSummary(
            answers = answers.mapNotNull { element ->
                val a = element as? JsonObject ?: return@mapNotNull null
                AnsweredQuestion(
                    questionIndex = a.int("questionIndex") ?: return@mapNotNull null,
                    question = a.str("question").orEmpty(),
                    kind = a.str("kind") ?: "option",
                    answer = a.str("answer"),
                    selected = a.arr("selected").strings(),
                    notes = a.str("notes")?.takeIf { it.isNotBlank() },
                )
            },
            cancelled = cancelled,
            globalNote = details.str("globalNote")?.takeIf { it.isNotBlank() },
            unanswered = details.arr("unanswered")?.mapNotNull { (it as? JsonPrimitive)?.content?.toIntOrNull() }.orEmpty(),
        )
    }

    /** An answered question as one line: the picked labels and any own text. */
    fun summaryText(answer: AnsweredQuestion): String {
        if (answer.kind == "multi") {
            val values = answer.selected + listOfNotNull(answer.answer?.takeIf { it.isNotEmpty() })
            return values.joinToString(", ").ifEmpty { "(no input)" }
        }
        return answer.answer?.takeIf { it.isNotEmpty() } ?: "(no input)"
    }
}
