import assert from "node:assert/strict";
import test from "node:test";
import { createJiti } from "jiti";

const {
  askUserAttentionRequest,
  buildQuestionnaireResponse,
  DECLINE_MESSAGE,
  isAskUserPendingDetails,
  isQuestionnaireResult,
  normalizeQuestionParams,
  prepareQuestionArguments,
  readAskUserParams,
  resolveAnswer,
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

test("the text the model reads matches the package's envelope", () => {
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

test("answers apply all or nothing", () => {
  const refused = (payload) => {
    const outcome = resolveAnswer(PARAMS.questions, payload);
    assert.equal(outcome.ok, false, JSON.stringify(payload));
    return outcome.reason;
  };
  assert.match(refused(null), /payload must be an object/);
  assert.match(refused({ answers: [] }), /no answer for question 0/);
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

test("validation keeps the package's rules and refuses what the trial card cannot show", () => {
  assert.deepEqual(validateQuestionnaire(PARAMS), { ok: true });
  const errorOf = (params) => validateQuestionnaire(params).error;
  assert.equal(errorOf({ questions: [] }), "no_questions");
  assert.equal(errorOf({ questions: [QUESTION, { ...QUESTION, question: "Another?" }] }), "too_many_questions");
  assert.equal(errorOf({ questions: [{ ...QUESTION, options: [QUESTION.options[0]] }] }), "empty_options");
  assert.equal(errorOf({ questions: [{ ...QUESTION, options: [...QUESTION.options, { label: "Other", description: "" }] }] }), "reserved_label");
  assert.equal(errorOf({ questions: [{ ...QUESTION, options: [QUESTION.options[0], QUESTION.options[0]] }] }), "duplicate_option_label");
  const multi = validateQuestionnaire({ questions: [{ ...QUESTION, multiSelect: true }] });
  assert.equal(multi.error, "unsupported_question");
  assert.match(multi.message, /never saw the question/);
  const preview = { ...QUESTION, options: [{ ...QUESTION.options[0], preview: "```ts\nformat()\n```" }, QUESTION.options[1]] };
  assert.equal(errorOf({ questions: [preview] }), "unsupported_question");
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

test("the pending update and the result are told apart by their details", () => {
  assert.equal(isAskUserPendingDetails({ kind: "pi-web-ask-user-pending", question: "Q?" }), true);
  assert.equal(isAskUserPendingDetails({ kind: "pi-web-ask-user-pending" }), false);
  assert.equal(isAskUserPendingDetails({ answers: [], cancelled: true }), false);
  assert.equal(isQuestionnaireResult({ answers: [], cancelled: true }), true);
  assert.equal(isQuestionnaireResult({ kind: "pi-web-ask-user-pending", question: "Q?" }), false);
  assert.deepEqual(askUserAttentionRequest("call-1", { kind: "pi-web-ask-user-pending", question: "Q?" }), {
    type: "extension_ui_request",
    id: "ask-user:call-1",
    method: "select",
    title: "Q?",
    options: [],
  });
});
