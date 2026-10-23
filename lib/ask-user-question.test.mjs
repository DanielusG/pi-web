import assert from "node:assert/strict";
import test from "node:test";
import { createJiti } from "jiti";

const {
  askUserAttentionRequest,
  buildAnswerPayload,
  buildQuestionnaireResponse,
  DECLINE_MESSAGE,
  draftAnswerText,
  emptyQuestionDraft,
  isAskUserPendingDetails,
  isDraftAnswered,
  isQuestionnaireResult,
  normalizeQuestionParams,
  prepareQuestionArguments,
  previewRowIndex,
  questionHasPreviews,
  readAskUserParams,
  resolveAnswer,
  toggleDraftOption,
  toggleDraftOwn,
  validateQuestionnaire,
} = await createJiti(import.meta.url).import("./ask-user-question.ts");

const QUESTION = {
  question: "Which library should we use?",
  header: "Library",
  options: [
    { label: "date-fns", description: "Small, functional." },
    { label: "Luxon", description: "Time zones built in." },
  ],
};
const PARAMS = { questions: [QUESTION] };

test("the text the model reads is the package's envelope", () => {
  const picked = resolveAnswer(PARAMS.questions, { answers: [{ questionIndex: 0, optionIndexes: [1] }] });
  assert.equal(picked.ok, true);
  const option = buildQuestionnaireResponse(picked.result, PARAMS);
  assert.deepEqual(option.content, [{
    type: "text",
    text: 'User has answered your questions: "Which library should we use?"="Luxon". You can now continue with the user\'s answers in mind.',
  }]);
  assert.deepEqual(option.details, {
    answers: [{ questionIndex: 0, question: QUESTION.question, kind: "option", answer: "Luxon" }],
    cancelled: false,
  });

  const typed = resolveAnswer(PARAMS.questions, { answers: [{ questionIndex: 0, text: "Temporal polyfill", notes: "  only if small  " }] });
  assert.equal(
    buildQuestionnaireResponse(typed.result, PARAMS).content[0].text,
    'User has answered your questions: "Which library should we use?"="Temporal polyfill". user notes: only if small. You can now continue with the user\'s answers in mind.',
  );

  const declined = buildQuestionnaireResponse(resolveAnswer(PARAMS.questions, { cancel: true }).result, PARAMS);
  assert.equal(declined.content[0].text, DECLINE_MESSAGE);
  assert.deepEqual(declined.details, { answers: [], cancelled: true });
});

test("the envelope keeps the package's multi-select, unanswered and global-note segments", () => {
  const params = {
    questions: [
      { ...QUESTION, multiSelect: true },
      { question: "Ship it now?", header: "Timing", options: [{ label: "Yes", description: "" }, { label: "No", description: "" }] },
    ],
  };
  const result = {
    answers: [{ questionIndex: 0, question: params.questions[0].question, kind: "multi", answer: "Day.js", selected: ["date-fns"] }],
    cancelled: false,
    globalNote: "Keep the bundle small",
  };
  const response = buildQuestionnaireResponse(result, params);
  assert.equal(
    response.content[0].text,
    'User has answered your questions: "Which library should we use?"="date-fns, Day.js". unanswered: "Ship it now?". global note: Keep the bundle small. You can now continue with the user\'s answers in mind.',
  );
  assert.deepEqual(response.details.unanswered, [1]);
  // Nothing answered and no note is a decline, whatever the flag says.
  assert.equal(buildQuestionnaireResponse({ answers: [], cancelled: false }, params).content[0].text, DECLINE_MESSAGE);
});

test("a bad entry refuses the whole answer", () => {
  const refused = (payload) => {
    const outcome = resolveAnswer(PARAMS.questions, payload);
    assert.equal(outcome.ok, false, JSON.stringify(payload));
    return outcome.reason;
  };
  assert.match(refused(null), /payload must be an object/);
  assert.match(refused({ answers: [{ questionIndex: 0, optionIndexes: [2] }] }), /out of range/);
  assert.match(refused({ answers: [{ questionIndex: 0, optionIndexes: [0, 1] }] }), /one option/);
  assert.match(refused({ answers: [{ questionIndex: 0, optionIndexes: [0], text: "x" }] }), /not both/);
  assert.match(refused({ answers: [{ questionIndex: 0, text: "   " }] }), /blank/);
  assert.match(refused({ answers: [{ questionIndex: 1, optionIndexes: [0] }] }), /questionIndex 1 out of range/);
  assert.match(refused({ answers: [{ questionIndex: 0, optionIndexes: [0] }, { questionIndex: 0, optionIndexes: [1] }] }), /answered twice/);
  assert.match(refused({ cancel: true, answers: [{ questionIndex: 0, optionIndexes: [0] }] }), /cancel takes no answers/);
  // Fields the card does not send are ignored, as the command's own `type` and `toolCallId` are.
  assert.equal(resolveAnswer(PARAMS.questions, { type: "answer_question", toolCallId: "x", answers: [{ questionIndex: 0, optionIndexes: [0] }] }).ok, true);
});

test("questions left blank are sent as unanswered, as the package's Submit tab allows", () => {
  const questions = [QUESTION, { ...QUESTION, question: "Ship it now?", header: "Timing" }];
  const drafts = [emptyQuestionDraft(), { ...emptyQuestionDraft(), picked: [1], note: "" }];
  // The blank question's note goes with it: the envelope has no place for it.
  drafts[0] = { ...drafts[0], note: "unsure" };
  const payload = buildAnswerPayload(questions, drafts, "");
  assert.deepEqual(payload, { answers: [{ questionIndex: 1, optionIndexes: [1] }] });
  const outcome = resolveAnswer(questions, payload);
  assert.equal(outcome.ok, true);
  const response = buildQuestionnaireResponse(outcome.result, { questions });
  assert.equal(
    response.content[0].text,
    'User has answered your questions: "Ship it now?"="Luxon". unanswered: "Which library should we use?". You can now continue with the user\'s answers in mind.',
  );
  assert.deepEqual(response.details.unanswered, [0]);
  // Nothing answered and no global note reads as a decline; a global note alone does not.
  const nothing = resolveAnswer(questions, buildAnswerPayload(questions, [emptyQuestionDraft(), emptyQuestionDraft()], ""));
  assert.equal(buildQuestionnaireResponse(nothing.result, { questions }).content[0].text, DECLINE_MESSAGE);
  const noteOnly = resolveAnswer(questions, buildAnswerPayload(questions, [emptyQuestionDraft(), emptyQuestionDraft()], "later"));
  assert.match(buildQuestionnaireResponse(noteOnly.result, { questions }).content[0].text, /unanswered: "Which library should we use\?", "Ship it now\?". global note: later\./);
});

test("a picked option's preview is not echoed back to the model", () => {
  const params = { questions: [{ ...QUESTION, options: [{ ...QUESTION.options[0], preview: "```ts\nformat(date)\n```" }, QUESTION.options[1]] }] };
  const picked = resolveAnswer(params.questions, { answers: [{ questionIndex: 0, optionIndexes: [0] }] });
  // The details keep it, as the package's do, for whoever renders the result.
  assert.equal(picked.result.answers[0].preview, "```ts\nformat(date)\n```");
  assert.equal(
    buildQuestionnaireResponse(picked.result, params).content[0].text,
    'User has answered your questions: "Which library should we use?"="date-fns". You can now continue with the user\'s answers in mind.',
  );
});

test("validation keeps the package's rules", () => {
  assert.deepEqual(validateQuestionnaire(PARAMS), { ok: true });
  const errorOf = (params) => validateQuestionnaire(params).error;
  assert.equal(errorOf({ questions: [] }), "no_questions");
  const others = ["Second?", "Third?", "Fourth?"].map((question) => ({ ...QUESTION, question }));
  assert.equal(errorOf({ questions: [QUESTION, ...others] }), undefined);
  assert.equal(errorOf({ questions: [QUESTION, ...others, { ...QUESTION, question: "Fifth?" }] }), "too_many_questions");
  assert.equal(errorOf({ questions: [QUESTION, { ...QUESTION }] }), "duplicate_question");
  assert.equal(errorOf({ questions: [{ ...QUESTION, options: [QUESTION.options[0]] }] }), "empty_options");
  assert.equal(errorOf({ questions: [{ ...QUESTION, options: [...QUESTION.options, { label: "Other", description: "" }] }] }), "reserved_label");
  assert.equal(errorOf({ questions: [{ ...QUESTION, options: [QUESTION.options[0], QUESTION.options[0]] }] }), "duplicate_option_label");
  // Multiple choice and previews are the package's too; a preview on a multiple-choice question is
  // simply not shown.
  const preview = { ...QUESTION, multiSelect: true, options: [{ ...QUESTION.options[0], preview: "x" }, QUESTION.options[1]] };
  assert.deepEqual(validateQuestionnaire({ questions: [preview] }), { ok: true });
});

test("arguments are decoded and normalized before validation", () => {
  const encoded = prepareQuestionArguments({ questions: JSON.stringify([{ ...QUESTION, options: JSON.stringify(QUESTION.options) }]) });
  assert.deepEqual(encoded, PARAMS);
  // A carriage return inside a label must not slip past the reserved-label check.
  const normalized = normalizeQuestionParams({ questions: [{ ...QUESTION, options: [{ label: "Oth\rer", description: "a\r\nb" }, QUESTION.options[1]] }] });
  assert.deepEqual(normalized.questions[0].options[0], { label: "Other", description: "a\nb" });
  assert.equal(validateQuestionnaire(normalized).error, "reserved_label");
});

test("the card reads only inputs of the tool's shape", () => {
  assert.deepEqual(readAskUserParams(PARAMS), PARAMS);
  assert.equal(readAskUserParams({}), null);
  assert.equal(readAskUserParams({ questions: [{ question: "Q?", header: "H", options: [{ label: "A" }] }] }), null);
  assert.equal(readAskUserParams({ questions: [] }), null);
});

test("the pending update carries the questions and is told apart from the result", () => {
  const pending = { kind: "pi-web-ask-user-pending", questions: [QUESTION] };
  assert.equal(isAskUserPendingDetails(pending), true);
  assert.equal(isAskUserPendingDetails({ kind: "pi-web-ask-user-pending" }), false);
  assert.equal(isAskUserPendingDetails({ kind: "pi-web-ask-user-pending", questions: [] }), false);
  assert.equal(isAskUserPendingDetails({ kind: "pi-web-ask-user-pending", questions: [{ question: "Q?" }] }), false);
  assert.equal(isAskUserPendingDetails({ answers: [], cancelled: true }), false);
  assert.equal(isQuestionnaireResult({ answers: [], cancelled: true }), true);
  assert.equal(isQuestionnaireResult(pending), false);
  assert.deepEqual(askUserAttentionRequest("call-1", pending), {
    type: "extension_ui_request",
    id: "ask-user:call-1",
    method: "select",
    title: QUESTION.question,
    options: [],
  });
});

test("the dialog's drafts follow single and multiple choice", () => {
  const multi = { ...QUESTION, multiSelect: true, options: [...QUESTION.options, { label: "Day.js", description: "" }] };
  let single = emptyQuestionDraft();
  assert.equal(isDraftAnswered(QUESTION, single), false);
  single = toggleDraftOption(QUESTION, single, 1);
  assert.deepEqual(single.picked, [1]);
  single = toggleDraftOption(QUESTION, single, 0);
  assert.deepEqual(single.picked, [0]);
  // Their own answer replaces the picked option, and counts only once it has text.
  single = toggleDraftOwn(QUESTION, single);
  assert.deepEqual([single.picked, single.own], [[], true]);
  assert.equal(isDraftAnswered(QUESTION, single), false);
  single = { ...single, text: "  Temporal  " };
  assert.equal(isDraftAnswered(QUESTION, single), true);
  assert.equal(draftAnswerText(QUESTION, single), "Temporal");

  let many = emptyQuestionDraft();
  many = toggleDraftOption(multi, toggleDraftOption(multi, many, 2), 0);
  assert.deepEqual(many.picked, [0, 2]);
  many = toggleDraftOption(multi, many, 2);
  assert.deepEqual(many.picked, [0]);
  many = toggleDraftOwn(multi, many);
  assert.deepEqual([many.picked, many.own], [[0], true]);
  many = { ...many, text: "Temporal" };
  assert.equal(draftAnswerText(multi, many), "date-fns, Temporal");
  assert.equal(isDraftAnswered(multi, { ...emptyQuestionDraft(), own: true, text: " " }), false);
});

test("the payload the dialog sends resolves to what the user picked", () => {
  const multi = { ...QUESTION, question: "Which extras?", multiSelect: true };
  const questions = [QUESTION, multi];
  const drafts = [
    { picked: [1], own: false, text: "", note: "  check bundle size " },
    { picked: [0, 1], own: true, text: " plus Temporal ", note: " " },
  ];
  const payload = buildAnswerPayload(questions, drafts, "  all for v2 ");
  assert.deepEqual(payload, {
    answers: [
      { questionIndex: 0, optionIndexes: [1], notes: "check bundle size" },
      { questionIndex: 1, optionIndexes: [0, 1], text: "plus Temporal" },
    ],
    globalNote: "all for v2",
  });
  const outcome = resolveAnswer(questions, payload);
  assert.equal(outcome.ok, true);
  assert.equal(
    buildQuestionnaireResponse(outcome.result, { questions }).content[0].text,
    'User has answered your questions: "Which library should we use?"="Luxon". user notes: check bundle size. "Which extras?"="date-fns, Luxon, plus Temporal". global note: all for v2. You can now continue with the user\'s answers in mind.',
  );
  // Their own text on a single-choice question goes alone; a blank global note is left out.
  assert.deepEqual(buildAnswerPayload([QUESTION], [{ picked: [], own: true, text: "Temporal", note: "" }], " "), {
    answers: [{ questionIndex: 0, text: "Temporal" }],
  });
});

test("the preview box is for the highlighted row, then the picked one, then the first", () => {
  const question = {
    ...QUESTION,
    options: [{ label: "A", description: "" }, { label: "B", description: "", preview: "b" }, { label: "C", description: "", preview: "c" }],
  };
  const draft = emptyQuestionDraft();
  assert.equal(questionHasPreviews(question), true);
  assert.equal(questionHasPreviews({ ...question, multiSelect: true }), false);
  assert.equal(questionHasPreviews(QUESTION), false);
  assert.equal(previewRowIndex(question, draft, null), 0);
  assert.equal(previewRowIndex(question, { ...draft, picked: [2] }, null), 2);
  assert.equal(previewRowIndex(question, { ...draft, picked: [2] }, 1), 1);
  // A highlighted row without a preview gets the box to itself ("No preview available"), not the
  // picked option's preview, which would read as that row's.
  assert.equal(previewRowIndex(question, { ...draft, picked: [2] }, 0), 0);
  assert.equal(question.options[previewRowIndex(question, { ...draft, picked: [2] }, 0)].preview, undefined);
  // The own-answer row has none either.
  assert.equal(previewRowIndex(question, { ...draft, own: true }, null), 3);
});
