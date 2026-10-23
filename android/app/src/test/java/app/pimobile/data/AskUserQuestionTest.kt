package app.pimobile.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The native ask_user_question contract (pi-web lib/ask-user-question.ts): the pending update's
 * questions, what counts as answered, and the `answer_question` command the server applies all
 * or nothing (questions left blank are left out of it).
 */
class AskUserQuestionTest {

    private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private val library = AskQuestion(
        question = "Which library?",
        header = "Library",
        options = listOf(
            AskOption("date-fns", "Small.", preview = "import { format } from 'date-fns'"),
            AskOption("Luxon", "Time zones."),
        ),
    )
    private val features = AskQuestion(
        question = "Which features?",
        header = "Features",
        options = listOf(AskOption("Dark mode", ""), AskOption("Export", ""), AskOption("Sync", "")),
        multiSelect = true,
    )

    @Test
    fun parsesThePendingUpdate() {
        val details = json(
            """{"kind":"pi-web-ask-user-pending","questions":[
              {"question":"Which library?","header":"Library","options":[
                {"label":"date-fns","description":"Small.","preview":"import { format } from 'date-fns'"},
                {"label":"Luxon","description":"Time zones."}]},
              {"question":"Which features?","header":"Features","multiSelect":true,"options":[
                {"label":"Dark mode","description":""},{"label":"Export","description":""},{"label":"Sync","description":""}]}]}""",
        )
        val pending = AskUserQuestion.parsePending("call_1", details)
        assertEquals(PendingQuestion("call_1", listOf(library, features)), pending)
        assertTrue(library.hasPreviews)
        assertFalse(features.hasPreviews)
    }

    @Test
    fun ignoresOtherDetailsAndMalformedQuestions() {
        assertNull(AskUserQuestion.parsePending("c", null))
        assertNull(AskUserQuestion.parsePending("c", json("""{"answers":[],"cancelled":true}""")))
        assertNull(AskUserQuestion.parsePending("c", json("""{"kind":"pi-web-ask-user-pending","questions":[]}""")))
        assertNull(
            AskUserQuestion.parsePending(
                "c",
                json("""{"kind":"pi-web-ask-user-pending","questions":[{"question":"Q?","header":"H","options":[{"label":"A"}]}]}"""),
            ),
        )
    }

    @Test
    fun decodesQuestionsSentAsJsonStrings() {
        val encoded = JsonPrimitive("""[{"question":"Which library?","header":"Library","options":"[{\"label\":\"Luxon\",\"description\":\"Time zones.\"}]"}]""")
        val questions = AskUserQuestion.parseQuestions(encoded)
        assertEquals(listOf(AskOption("Luxon", "Time zones.")), questions?.single()?.options)
    }

    @Test
    fun singleChoiceTakesOneOptionOrOwnText() {
        var draft = AskDraft()
        assertFalse(AskUserQuestion.isAnswered(library, draft))
        draft = AskUserQuestion.select(library, draft, 0)
        draft = AskUserQuestion.select(library, draft, 1)
        assertEquals(setOf(1), draft.selected)
        assertTrue(AskUserQuestion.isAnswered(library, draft))
        // Own answer deselects the options and needs text.
        draft = AskUserQuestion.toggleCustom(library, draft)
        assertEquals(emptySet<Int>(), draft.selected)
        assertFalse(AskUserQuestion.isAnswered(library, draft))
        assertFalse(AskUserQuestion.isAnswered(library, draft.copy(text = "   ")))
        assertTrue(AskUserQuestion.isAnswered(library, draft.copy(text = "Temporal")))
        // Picking an option again leaves the own answer.
        draft = AskUserQuestion.select(library, draft.copy(text = "Temporal"), 0)
        assertFalse(draft.custom)
        assertEquals("date-fns", AskUserQuestion.answerText(library, draft))
    }

    @Test
    fun multipleChoiceTogglesAndAddsText() {
        var draft = AskDraft()
        draft = AskUserQuestion.select(features, draft, 2)
        draft = AskUserQuestion.select(features, draft, 0)
        assertEquals(setOf(0, 2), draft.selected)
        draft = AskUserQuestion.toggleCustom(features, draft).copy(text = "Offline")
        assertEquals("Dark mode, Sync, Offline", AskUserQuestion.answerText(features, draft))
        draft = AskUserQuestion.select(features, draft, 0)
        draft = AskUserQuestion.select(features, draft, 2)
        assertTrue("text alone answers a multiple-choice question", AskUserQuestion.isAnswered(features, draft))
        assertFalse(AskUserQuestion.isAnswered(features, draft.copy(custom = false)))
    }

    @Test
    fun buildsTheAnswerCommand() {
        val form = AskForm(
            drafts = listOf(
                AskDraft(selected = setOf(1), note = "  only if small  "),
                AskDraft(selected = setOf(2, 0), custom = true, text = " Offline "),
            ),
            globalNote = " ship it ",
        )
        assertTrue(AskUserQuestion.allAnswered(listOf(library, features), form.drafts))
        val command = AskUserQuestion.answerCommand("call_1", listOf(library, features), form)
        assertEquals(
            json(
                """{"type":"answer_question","toolCallId":"call_1","answers":[
                  {"questionIndex":0,"optionIndexes":[1],"notes":"only if small"},
                  {"questionIndex":1,"optionIndexes":[0,2],"text":"Offline"}],"globalNote":"ship it"}""",
            ),
            command,
        )
    }

    @Test
    fun questionsLeftBlankAreLeftOut() {
        val form = AskForm(drafts = listOf(AskDraft(note = "unsure"), AskDraft(selected = setOf(1))))
        assertFalse(AskUserQuestion.allAnswered(listOf(library, features), form.drafts))
        val command = AskUserQuestion.answerCommand("call_3", listOf(library, features), form)
        assertEquals(
            json("""{"type":"answer_question","toolCallId":"call_3","answers":[{"questionIndex":1,"optionIndexes":[1]}]}"""),
            command,
        )
    }

    @Test
    fun ownTextOnASingleChoiceQuestionSendsNoOption() {
        val form = AskForm(drafts = listOf(AskDraft(custom = true, text = "Temporal", note = " ")))
        val command = AskUserQuestion.answerCommand("call_2", listOf(library), form)
        assertEquals(
            json("""{"type":"answer_question","toolCallId":"call_2","answers":[{"questionIndex":0,"text":"Temporal"}]}"""),
            command,
        )
    }

    @Test
    fun buildsTheDeclineCommand() {
        assertEquals(
            json("""{"type":"answer_question","toolCallId":"call_3","cancel":true}"""),
            AskUserQuestion.cancelCommand("call_3"),
        )
    }

    @Test
    fun readsTheFinishedResult() {
        val summary = AskUserQuestion.parseResult(
            json(
                """{"answers":[
                  {"questionIndex":0,"question":"Which library?","kind":"custom","answer":"Temporal","notes":"small"},
                  {"questionIndex":1,"question":"Which features?","kind":"multi","answer":null,"selected":["Dark mode","Sync"]}],
                  "cancelled":false,"globalNote":"ship it"}""",
            ),
        )!!
        assertEquals(2, summary.answers.size)
        assertEquals("Temporal", AskUserQuestion.summaryText(summary.answers[0]))
        assertEquals("small", summary.answers[0].notes)
        assertEquals("Dark mode, Sync", AskUserQuestion.summaryText(summary.answers[1]))
        assertEquals("ship it", summary.globalNote)
        assertTrue(AskUserQuestion.parseResult(json("""{"answers":[],"cancelled":true}"""))!!.cancelled)
        // A refused call and a stopped one have nothing to summarize.
        assertNull(AskUserQuestion.parseResult(json("""{"answers":[],"cancelled":true,"error":"reserved_label"}""")))
        assertNull(AskUserQuestion.parseResult(null))
    }
}
