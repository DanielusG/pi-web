// The native `ask_user_question` tool's data, shared by the tool (lib/ask-user-extension.ts) and
// the chat card (components/AskUserQuestionCard.tsx), so this module stays client-safe.
//
// The arguments, the validation rules, the result `details` and the text the model reads follow
// @juicesharp/rpiv-ask-user-question (MIT): a transcript reads the same whichever of the two wrote
// it, and a model used to that package needs nothing new. The trial accepts one single-choice
// question per call (`MAX_QUESTIONS`, and `validateQuestionnaire` refuses multiple choice and
// previews); answers and results already carry the package's full shape.

import type { BlockingExtensionUiRequest } from "./types";

export const ASK_USER_QUESTION_TOOL_NAME = "ask_user_question";

export const MAX_HEADER_LENGTH = 16;
export const MAX_LABEL_LENGTH = 60;
export const MIN_OPTIONS = 2;
export const MAX_OPTIONS = 4;
/** One question per call while the native card is on trial; the package allows four. */
export const MAX_QUESTIONS = 1;

/** The package's sentinel rows ("Type something.", "Next") and Claude Code's "Other". */
export const RESERVED_LABELS = ["Other", "Type something.", "Next"] as const;

export interface AskUserOption {
  label: string;
  description: string;
  preview?: string;
}

export interface AskUserQuestion {
  question: string;
  header: string;
  options: AskUserOption[];
  multiSelect?: boolean;
}

export interface AskUserParams {
  questions: AskUserQuestion[];
}

/**
 * - `option`: one of the authored options; `answer` is its label.
 * - `custom`: typed text on a single-choice question.
 * - `multi`: a multiple-choice question; `selected` holds the labels, `answer` any typed text.
 */
export interface QuestionAnswer {
  questionIndex: number;
  question: string;
  kind: "option" | "custom" | "multi";
  answer: string | null;
  selected?: string[];
  notes?: string;
  preview?: string;
}

export type QuestionnaireError =
  | "no_questions"
  | "empty_options"
  | "too_many_questions"
  | "duplicate_question"
  | "duplicate_option_label"
  | "reserved_label"
  | "unsupported_question";

/** The result's `details`, as the package writes them. */
export interface QuestionnaireResult {
  answers: QuestionAnswer[];
  cancelled: boolean;
  globalNote?: string;
  unanswered?: number[];
  error?: QuestionnaireError;
}

/** The progress update's `details` while the question waits for the user. */
export const ASK_USER_PENDING_KIND = "pi-web-ask-user-pending";

export interface AskUserPendingDetails {
  kind: typeof ASK_USER_PENDING_KIND;
  /** The first question, for the browser notification: the update carries no tool input. */
  question: string;
}

export interface AskUserToolResult {
  content: { type: "text"; text: string }[];
  details: QuestionnaireResult;
}

/** One question's answer as the card sends it: an option's index, or typed text. */
export interface AskUserAnswerEntry {
  questionIndex: number;
  optionIndexes?: number[];
  text?: string;
  notes?: string;
}

export type AnswerOutcome =
  | { ok: true; result: QuestionnaireResult }
  | { ok: false; reason: string };

export const DECLINE_MESSAGE = "User declined to answer questions";
const ENVELOPE_PREFIX = "User has answered your questions:";
const ENVELOPE_SUFFIX = "You can now continue with the user's answers in mind.";
const NO_INPUT_PLACEHOLDER = "(no input)";

const RESERVED_LABEL_SET: ReadonlySet<string> = new Set(RESERVED_LABELS);

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

export function isAskUserPendingDetails(value: unknown): value is AskUserPendingDetails {
  return isRecord(value) && value.kind === ASK_USER_PENDING_KIND && typeof value.question === "string";
}

/**
 * The browser's "needs your attention" notification for a waiting question, through the path an
 * extension's blocking dialog takes (AppShell's handleAttentionNeeded dedupes by this id). The
 * object never leaves the page.
 */
export function askUserAttentionRequest(toolCallId: string, details: AskUserPendingDetails): BlockingExtensionUiRequest {
  return { type: "extension_ui_request", id: `ask-user:${toolCallId}`, method: "select", title: details.question, options: [] };
}

export function isQuestionnaireResult(value: unknown): value is QuestionnaireResult {
  return isRecord(value) && Array.isArray(value.answers) && typeof value.cancelled === "boolean";
}

/**
 * Some models put a bare carriage return inside string arguments: `\r\n` becomes `\n` and a lone
 * `\r` is dropped, before validation, so the reserved and duplicate checks compare what is shown.
 */
export function normalizeLineTerminators(text: string): string {
  return text.replace(/\r\n/g, "\n").replace(/\r/g, "");
}

function normalizeFields<T extends object>(value: T, keys: readonly (keyof T)[]): T {
  const out = { ...value };
  for (const key of keys) {
    const field = value[key];
    if (typeof field === "string") out[key] = normalizeLineTerminators(field) as T[typeof key];
  }
  return out;
}

export function normalizeQuestionParams(params: AskUserParams): AskUserParams {
  return {
    ...params,
    questions: params.questions.map((question) => ({
      ...normalizeFields(question, ["question", "header"]),
      options: question.options.map((option) => normalizeFields(option, ["label", "description", "preview"])),
    })),
  };
}

function parseJson(text: string): unknown {
  try {
    return JSON.parse(text);
  } catch {
    return undefined;
  }
}

function decodeArray(value: unknown): unknown {
  const parsed = typeof value === "string" ? parseJson(value) : value;
  return Array.isArray(parsed) ? parsed : value;
}

function decodeObject(value: unknown): unknown {
  const parsed = typeof value === "string" ? parseJson(value) : value;
  return isRecord(parsed) ? parsed : value;
}

/**
 * pi's `prepareArguments`: some tool-call parsers send `questions`, a question or its `options`
 * as a JSON string. Only a string where an array or object belongs is decoded, and only when it
 * parses to that shape, so validation still reports anything else as it was sent.
 */
export function prepareQuestionArguments(args: unknown): AskUserParams {
  if (!isRecord(args)) return args as unknown as AskUserParams;
  const questions = decodeArray(args.questions);
  if (!Array.isArray(questions)) return args as unknown as AskUserParams;
  const decoded = questions.map((entry) => {
    const question = decodeObject(entry);
    if (!isRecord(question)) return question;
    const options = decodeArray(question.options);
    return Array.isArray(options) ? { ...question, options: options.map(decodeObject) } : question;
  });
  return { ...args, questions: decoded } as unknown as AskUserParams;
}

export type ValidationResult = { ok: true } | { ok: false; error: QuestionnaireError; message: string };

/**
 * The package's checks, then the trial's: multiple choice and option previews are refused rather
 * than shown in a way the model did not ask for, and the message says how to ask instead.
 */
export function validateQuestionnaire(params: AskUserParams): ValidationResult {
  if (params.questions.length === 0) {
    return { ok: false, error: "no_questions", message: "Error: At least one question is required" };
  }
  if (params.questions.length > MAX_QUESTIONS) {
    return {
      ok: false,
      error: "too_many_questions",
      message: `Error: At most ${MAX_QUESTIONS} question is allowed per invocation in this client`,
    };
  }
  const seenQuestions = new Set<string>();
  for (const question of params.questions) {
    if (seenQuestions.has(question.question)) {
      return { ok: false, error: "duplicate_question", message: "Error: Question text must be unique within an invocation" };
    }
    seenQuestions.add(question.question);
  }
  for (const question of params.questions) {
    if (question.options.length < MIN_OPTIONS) {
      return { ok: false, error: "empty_options", message: `Error: Each question requires at least ${MIN_OPTIONS} options` };
    }
    const seenLabels = new Set<string>();
    for (const option of question.options) {
      if (RESERVED_LABEL_SET.has(option.label)) {
        return {
          ok: false,
          error: "reserved_label",
          message: `Error: Option label is reserved (${RESERVED_LABELS.join(", ")})`,
        };
      }
      if (seenLabels.has(option.label)) {
        return { ok: false, error: "duplicate_option_label", message: "Error: Option labels must be unique within a question" };
      }
      seenLabels.add(option.label);
    }
    if (question.multiSelect === true) {
      return {
        ok: false,
        error: "unsupported_question",
        message: "Error: multiSelect is not available in this client. The user never saw the question. Ask a single-choice question, or ask in plain chat text.",
      };
    }
    if (question.options.some((option) => typeof option.preview === "string" && option.preview.length > 0)) {
      return {
        ok: false,
        error: "unsupported_question",
        message: "Error: option previews are not available in this client. The user never saw the question. Put what matters in each option's description and ask again.",
      };
    }
  }
  return { ok: true };
}

export function questionnaireToolResult(text: string, details: QuestionnaireResult): AskUserToolResult {
  return { content: [{ type: "text", text }], details };
}

function formatAnswerScalar(answer: QuestionAnswer): string {
  switch (answer.kind) {
    case "multi": {
      const values = [...(answer.selected ?? [])];
      if (answer.answer && answer.answer.length > 0) values.push(answer.answer);
      return values.length > 0 ? values.join(", ") : NO_INPUT_PLACEHOLDER;
    }
    case "custom":
      return answer.answer && answer.answer.length > 0 ? answer.answer : NO_INPUT_PLACEHOLDER;
    case "option":
      return answer.answer ?? NO_INPUT_PLACEHOLDER;
  }
}

function answerSegment(answer: QuestionAnswer): string {
  const parts = [`"${answer.question}"="${formatAnswerScalar(answer)}"`];
  if (answer.preview && answer.preview.length > 0) parts.push(`selected preview: ${answer.preview}`);
  if (answer.notes && answer.notes.length > 0) parts.push(`user notes: ${answer.notes}`);
  return `${parts.join(". ")}.`;
}

/**
 * The text the model reads, byte for byte the package's: a decline (or nothing answered) is one
 * canonical sentence; otherwise `"<question>"="<answer>"` per question, the questions left blank,
 * then any global note.
 */
export function buildQuestionnaireResponse(
  result: QuestionnaireResult | null | undefined,
  params: AskUserParams,
): AskUserToolResult {
  if (!result || result.cancelled) {
    return questionnaireToolResult(DECLINE_MESSAGE, {
      answers: result?.answers ?? [],
      cancelled: true,
      ...(result?.globalNote && result.globalNote.length > 0 ? { globalNote: result.globalNote } : {}),
    });
  }
  const segments: string[] = [];
  const unanswered: number[] = [];
  for (let index = 0; index < params.questions.length; index++) {
    const answer = result.answers.find((entry) => entry.questionIndex === index);
    if (answer) segments.push(answerSegment(answer));
    else unanswered.push(index);
  }
  const hasGlobalNote = result.globalNote !== undefined && result.globalNote.length > 0;
  if (segments.length === 0 && !hasGlobalNote) {
    return questionnaireToolResult(DECLINE_MESSAGE, { answers: result.answers, cancelled: true });
  }
  if (unanswered.length > 0) {
    segments.push(`unanswered: ${unanswered.map((index) => `"${params.questions[index]?.question}"`).join(", ")}.`);
  }
  if (hasGlobalNote) segments.push(`global note: ${result.globalNote}.`);
  const details = unanswered.length > 0 ? { ...result, unanswered } : result;
  return questionnaireToolResult(`${ENVELOPE_PREFIX} ${segments.join(" ")} ${ENVELOPE_SUFFIX}`, details);
}

function trimmedNote(value: string | undefined): string | undefined {
  const trimmed = value?.trim();
  return trimmed ? trimmed : undefined;
}

function resolveEntry(
  question: AskUserQuestion,
  index: number,
  entry: Record<string, unknown>,
): { ok: true; answer: QuestionAnswer } | { ok: false; reason: string } {
  const { optionIndexes, text, notes } = entry;
  const refuse = (reason: string) => ({ ok: false as const, reason: `question ${index}: ${reason}` });
  if (optionIndexes !== undefined && (!Array.isArray(optionIndexes) || !optionIndexes.every(Number.isInteger))) {
    return refuse("optionIndexes must be an array of integers");
  }
  if (text !== undefined && typeof text !== "string") return refuse("text must be a string");
  if (notes !== undefined && typeof notes !== "string") return refuse("notes must be a string");

  const picked = (optionIndexes ?? []) as number[];
  const outOfRange = picked.find((n) => n < 0 || n >= question.options.length);
  if (outOfRange !== undefined) return refuse(`option index ${outOfRange} out of range (0..${question.options.length - 1})`);
  if (new Set(picked).size !== picked.length) return refuse("option index repeated");
  if (text !== undefined && text.trim().length === 0) return refuse("text is blank");
  if (picked.length === 0 && text === undefined) return refuse("needs optionIndexes or text");

  const note = trimmedNote(notes as string | undefined);
  const base = { questionIndex: index, question: question.question, ...(note ? { notes: note } : {}) };
  if (question.multiSelect) {
    const selected = question.options.filter((_option, n) => picked.includes(n)).map((option) => option.label);
    return { ok: true, answer: { ...base, kind: "multi", answer: (text as string | undefined) ?? null, selected } };
  }
  if (picked.length > 0 && text !== undefined) return refuse("single-select takes optionIndexes or text, not both");
  if (picked.length > 1) return refuse(`single-select takes one option, got ${picked.length}`);
  if (text !== undefined) return { ok: true, answer: { ...base, kind: "custom", answer: text } };
  const option = question.options[picked[0]!]!;
  return {
    ok: true,
    answer: {
      ...base,
      kind: "option",
      answer: option.label,
      ...(option.preview && option.preview.length > 0 ? { preview: option.preview } : {}),
    },
  };
}

/**
 * An answer the browser sent (still untrusted) as the result the dialog would have produced.
 * All or nothing: every question answered, every index in range, or nothing is applied and the
 * question stays open. A partly applied answer would reach the model as if the user gave it.
 */
export function resolveAnswer(questions: readonly AskUserQuestion[], payload: unknown): AnswerOutcome {
  if (!isRecord(payload)) return { ok: false, reason: "payload must be an object" };
  const { answers, cancel, globalNote } = payload;
  if (cancel !== undefined && typeof cancel !== "boolean") return { ok: false, reason: "cancel must be a boolean" };
  if (globalNote !== undefined && typeof globalNote !== "string") return { ok: false, reason: "globalNote must be a string" };
  const note = trimmedNote(globalNote as string | undefined);
  const withNote = note ? { globalNote: note } : {};

  if (cancel === true) {
    if (answers !== undefined && !(Array.isArray(answers) && answers.length === 0)) {
      return { ok: false, reason: "cancel takes no answers" };
    }
    return { ok: true, result: { answers: [], cancelled: true, ...withNote } };
  }
  if (!Array.isArray(answers)) return { ok: false, reason: "answers must be an array" };

  const byQuestion = new Map<number, Record<string, unknown>>();
  for (const entry of answers) {
    if (!isRecord(entry)) return { ok: false, reason: "each answer must be an object" };
    const index = entry.questionIndex;
    if (!Number.isInteger(index)) return { ok: false, reason: "questionIndex must be an integer" };
    const position = index as number;
    if (position < 0 || position >= questions.length) {
      return { ok: false, reason: `questionIndex ${position} out of range (0..${questions.length - 1})` };
    }
    if (byQuestion.has(position)) return { ok: false, reason: `question ${position} answered twice` };
    byQuestion.set(position, entry);
  }

  const resolved: QuestionAnswer[] = [];
  for (let index = 0; index < questions.length; index++) {
    const entry = byQuestion.get(index);
    if (!entry) return { ok: false, reason: `no answer for question ${index} ("${questions[index]!.header}")` };
    const outcome = resolveEntry(questions[index]!, index, entry);
    if (!outcome.ok) return outcome;
    resolved.push(outcome.answer);
  }
  return { ok: true, result: { answers: resolved, cancelled: false, ...withNote } };
}

/**
 * The questions of a finished tool call's input, for the card; null when the input is not the
 * tool's shape (a call that never validated still renders as a plain tool card).
 */
export function readAskUserParams(input: unknown): AskUserParams | null {
  const prepared = prepareQuestionArguments(input);
  if (!isRecord(prepared) || !Array.isArray(prepared.questions) || prepared.questions.length === 0) return null;
  const questions: AskUserQuestion[] = [];
  for (const entry of prepared.questions as unknown[]) {
    if (!isRecord(entry) || typeof entry.question !== "string" || typeof entry.header !== "string") return null;
    if (!Array.isArray(entry.options)) return null;
    const options: AskUserOption[] = [];
    for (const option of entry.options as unknown[]) {
      if (!isRecord(option) || typeof option.label !== "string" || typeof option.description !== "string") return null;
      options.push({
        label: option.label,
        description: option.description,
        ...(typeof option.preview === "string" ? { preview: option.preview } : {}),
      });
    }
    questions.push({
      question: entry.question,
      header: entry.header,
      options,
      ...(entry.multiSelect === true ? { multiSelect: true } : {}),
    });
  }
  return normalizeQuestionParams({ questions });
}
