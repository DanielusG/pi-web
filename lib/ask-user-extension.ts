import { Type } from "@earendil-works/pi-ai";
import {
  defineTool,
  type InlineExtension,
  type LoadExtensionsResult,
  type ToolExposure,
} from "@earendil-works/pi-coding-agent";
import {
  ASK_USER_PENDING_KIND,
  ASK_USER_QUESTION_TOOL_NAME,
  buildQuestionnaireResponse,
  MAX_HEADER_LENGTH,
  MAX_LABEL_LENGTH,
  MAX_OPTIONS,
  MAX_QUESTIONS,
  MIN_OPTIONS,
  normalizeQuestionParams,
  prepareQuestionArguments,
  questionnaireToolResult,
  resolveAnswer,
  validateQuestionnaire,
  type AskUserParams,
  type QuestionnaireResult,
} from "./ask-user-question";

// pi-web's own `ask_user_question`, an inline extension like the subagents' (ADR 0003): the
// clients show the questions in a dialog of their own (tabs, multiple choice, previews, notes)
// instead of the generic select/input dialogs @juicesharp/rpiv-ask-user-question falls back to
// under RPC. The call waits on an open question kept here, keyed by session and tool call; the
// dialog answers it through the wrapper's `answer_question` command. The factory is always installed and registers nothing while the
// switch (lib/ask-user-settings.ts) is off, so a session reload applies a change.

export const HOST_ASK_USER_EXTENSION_NAME = "pi-web-ask-user";
const HOST_ASK_USER_EXTENSION_PATH = `<inline:${HOST_ASK_USER_EXTENSION_NAME}>`;
const RPIV_ASK_USER_PACKAGE_NAME = "@juicesharp/rpiv-ask-user-question";
const RPIV_ASK_USER_DIRECTORY = "rpiv-ask-user-question";

/**
 * A codemode script's call would carry a nested id (`<codemode call>/<n>`) that no transcript
 * entry shows, so no card could answer it: the model alone may ask.
 */
const ASK_USER_TOOL_EXPOSURE = "model-only" satisfies ToolExposure;

// Shorter than the package's text on purpose (it is sent with every request); what it must
// still say: the hard length limits, the reserved rows, one call for all questions.
const TOOL_DESCRIPTION = `Ask the user 1-${MAX_QUESTIONS} structured questions during execution, each with ${MIN_OPTIONS}-${MAX_OPTIONS} options. Use when you need to:
1. Gather user preferences or requirements
2. Clarify ambiguous instructions
3. Get decisions on implementation choices as you work
4. Offer choices to the user about what direction to take

Usage notes:
- The user can always type their own answer instead of picking an option, add notes, or decline. Do NOT author "Other" or "Type something." options yourself: reserved labels are rejected.
- Put all open questions in one call rather than several calls in a row.
- Set multiSelect: true when several options can apply together.
- If you recommend an option, put it first and add "(Recommended)" at the end of its label.
- An option's optional \`preview\` (markdown, shown in a monospace box beside the options) is for concrete artifacts to compare: ASCII mockups, code snippets, diagrams, configs. Single-choice questions only; leave it out when labels and descriptions suffice.`;

const PROMPT_GUIDELINES = [
  `Use ask_user_question when the request is underspecified and you cannot proceed without concrete decisions from the user; ask up to ${MAX_QUESTIONS} questions in one call.`,
  `Each question needs ${MIN_OPTIONS}-${MAX_OPTIONS} options, each with a concise label (1-5 words) and a description of what the choice means or its trade-offs.`,
];

const QUESTION_PARAMETERS = Type.Object({
  questions: Type.Array(Type.Object({
    question: Type.String({
      description: 'The complete question, clear and specific, ending with a question mark. Phrase a multiSelect question accordingly, e.g. "Which features do you want?"',
    }),
    header: Type.String({
      maxLength: MAX_HEADER_LENGTH,
      description: `MAX ${MAX_HEADER_LENGTH} CHARACTERS: hard limit, longer values are rejected. Short chip/tag shown next to the question, e.g. "Auth method", "Library".`,
    }),
    options: Type.Array(Type.Object({
      label: Type.String({
        maxLength: MAX_LABEL_LENGTH,
        description: `MAX ${MAX_LABEL_LENGTH} CHARACTERS: hard limit, longer values are rejected. Concise (1-5 words) display text of the choice.`,
      }),
      description: Type.String({
        description: "What this option means or what happens if chosen, including trade-offs.",
      }),
      preview: Type.Optional(Type.String({
        description: "Optional markdown shown beside the options while this option is focused: a mockup, code snippet or config to compare. Single-choice questions only.",
      })),
    }), {
      minItems: MIN_OPTIONS,
      maxItems: MAX_OPTIONS,
      description: `${MIN_OPTIONS}-${MAX_OPTIONS} distinct choices, mutually exclusive unless multiSelect. A custom-answer row is added automatically: do NOT author it.`,
    }),
    multiSelect: Type.Optional(Type.Boolean({ description: "Let the user pick several options. Default false." })),
  }), {
    minItems: 1,
    maxItems: MAX_QUESTIONS,
    description: `1-${MAX_QUESTIONS} questions, shown together and answered in one go.`,
  }),
});

// ---------------------------------------------------------------------------
// Open questions
// ---------------------------------------------------------------------------

interface OpenQuestion {
  sessionId: string;
  toolCallId: string;
  params: AskUserParams;
  settle: (result: QuestionnaireResult) => void;
  fail: (error: Error) => void;
}

// Hot reload re-evaluates this module; globalThis keeps one table per process, shared by the
// tool that waits and the wrapper that answers.
const OPEN_QUESTIONS_KEY: symbol = Symbol.for("pi-web.askUserOpenQuestions");

function openQuestions(): Map<string, OpenQuestion> {
  const store = globalThis as Record<symbol, Map<string, OpenQuestion> | undefined>;
  return store[OPEN_QUESTIONS_KEY] ??= new Map();
}

// Providers number tool calls per response (`call_0`), so the id alone is not unique across sessions.
function questionKey(sessionId: string, toolCallId: string): string {
  return `${sessionId}\u0000${toolCallId}`;
}

/**
 * Wait for the user's answer to one question. Stop (the tool's `signal`) and a closed session
 * end the wait with an error: the user never answered, which is neither a decline nor an answer.
 */
export function waitForAnswer(
  sessionId: string,
  toolCallId: string,
  params: AskUserParams,
  signal?: AbortSignal,
): Promise<QuestionnaireResult> {
  const key = questionKey(sessionId, toolCallId);
  return new Promise<QuestionnaireResult>((resolve, reject) => {
    let done = false;
    const finish = () => {
      done = true;
      signal?.removeEventListener("abort", onAbort);
      if (openQuestions().get(key) === entry) openQuestions().delete(key);
    };
    const entry: OpenQuestion = {
      sessionId,
      toolCallId,
      params,
      settle: (result) => {
        if (done) return;
        finish();
        resolve(result);
      },
      fail: (error) => {
        if (done) return;
        finish();
        reject(error);
      },
    };
    const onAbort = () => entry.fail(new Error("The run was stopped before the user answered."));
    if (signal?.aborted) {
      reject(new Error("The run was stopped before the user answered."));
      return;
    }
    signal?.addEventListener("abort", onAbort, { once: true });
    openQuestions().set(key, entry);
  });
}

/** Apply the card's answer; throws, leaving the question open, when it does not fit. */
export function answerQuestion(sessionId: string, toolCallId: unknown, payload: unknown): void {
  if (typeof toolCallId !== "string" || toolCallId === "") throw new Error("toolCallId must be a string");
  const entry = openQuestions().get(questionKey(sessionId, toolCallId));
  if (!entry) throw new Error("This question is no longer waiting for an answer");
  const outcome = resolveAnswer(entry.params.questions, payload);
  if (!outcome.ok) throw new Error(`Invalid answer: ${outcome.reason}`);
  entry.settle(outcome.result);
}

/** Whether a run of this session waits on the user, as an extension's blocking dialog does. */
export function hasOpenQuestion(sessionId: string): boolean {
  for (const entry of openQuestions().values()) {
    if (entry.sessionId === sessionId) return true;
  }
  return false;
}

export function closeSessionQuestions(sessionId: string): void {
  for (const entry of [...openQuestions().values()]) {
    if (entry.sessionId === sessionId) entry.fail(new Error("The session closed before the user answered."));
  }
}

// ---------------------------------------------------------------------------
// Extension
// ---------------------------------------------------------------------------

export function createAskUserExtension(isEnabled: () => boolean): InlineExtension {
  return {
    name: HOST_ASK_USER_EXTENSION_NAME,
    hidden: true,
    factory: (pi) => {
      if (!isEnabled()) return;
      pi.registerTool(defineTool({
        name: ASK_USER_QUESTION_TOOL_NAME,
        label: "Ask User Question",
        description: TOOL_DESCRIPTION,
        promptSnippet: `Ask the user a structured question (${MIN_OPTIONS}-${MAX_OPTIONS} options) when requirements are ambiguous`,
        promptGuidelines: PROMPT_GUIDELINES,
        exposure: ASK_USER_TOOL_EXPOSURE,
        parameters: QUESTION_PARAMETERS,
        prepareArguments: (args) => prepareQuestionArguments(args) as never,
        async execute(toolCallId, params, signal, onUpdate, ctx) {
          const typed = normalizeQuestionParams(params as AskUserParams);
          const validation = validateQuestionnaire(typed);
          if (!validation.ok) {
            return questionnaireToolResult(validation.message, { answers: [], cancelled: true, error: validation.error });
          }
          const answer = waitForAnswer(ctx.sessionManager.getSessionId(), toolCallId, typed, signal);
          // Clients open the dialog from this update alone; a reconnecting one gets it again
          // from the wrapper's replay of running tool events.
          onUpdate?.({
            content: [{ type: "text", text: "Waiting for the user's answer." }],
            details: { kind: ASK_USER_PENDING_KIND, questions: typed.questions } as never,
          });
          return buildQuestionnaireResponse(await answer, typed);
        },
      }));
    },
  };
}

function packageNameOf(source: string): string {
  const spec = source.replace(/^npm:/, "");
  if (!spec.startsWith("@")) return spec.split("@")[0] ?? "";
  const [scope, rest = ""] = spec.split("/");
  return `${scope}/${rest.split("@")[0]}`;
}

/**
 * While the native tool is on it replaces the package that registers the same name: drop that
 * extension (its no-UI reconciler goes with it) and the conflict errors it caused. Unrelated
 * extensions that happen to register `ask_user_question` stay, and the SDK reports them.
 */
export function preferPiWebAskUserExtension(base: LoadExtensionsResult): LoadExtensionsResult {
  const host = base.extensions.find((extension) => extension.path === HOST_ASK_USER_EXTENSION_PATH);
  if (!host?.tools.has(ASK_USER_QUESTION_TOOL_NAME)) return base;
  const packagePaths = new Set(base.extensions
    .filter((extension) => extension.path !== HOST_ASK_USER_EXTENSION_PATH)
    .filter((extension) => {
      const pathSegments = extension.path.replaceAll("\\", "/").split("/");
      return packageNameOf(extension.sourceInfo?.source ?? "") === RPIV_ASK_USER_PACKAGE_NAME
        || pathSegments.includes(RPIV_ASK_USER_DIRECTORY);
    })
    .filter((extension) => extension.tools.has(ASK_USER_QUESTION_TOOL_NAME))
    .map((extension) => extension.path));
  if (packagePaths.size === 0) return base;
  return {
    ...base,
    extensions: base.extensions.filter((extension) => !packagePaths.has(extension.path)),
    errors: base.errors.filter((error) => {
      if (packagePaths.has(error.path)) return false;
      if (error.path !== HOST_ASK_USER_EXTENSION_PATH) return true;
      return ![...packagePaths].some((packagePath) =>
        error.error === `Tool "${ASK_USER_QUESTION_TOOL_NAME}" conflicts with ${packagePath}`
      );
    }),
  };
}
