import assert from "node:assert/strict";
import { mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";
import {
  fauxAssistantMessage,
  fauxProvider,
  fauxText,
  fauxToolCall,
  getCurrentTools,
} from "@earendil-works/pi-ai";
import {
  createAgentSessionFromServices,
  createAgentSessionServices,
  ModelRuntime,
  SessionManager,
  SettingsManager,
} from "@earendil-works/pi-coding-agent";
import { createJiti } from "jiti";

const { answerQuestion, closeSessionQuestions, createAskUserExtension, hasOpenQuestion } =
  await createJiti(import.meta.url).import("./ask-user-extension.ts");

const QUESTIONS = [{
  question: "Which library should we use?",
  header: "Library",
  options: [
    { label: "date-fns", description: "Small, functional." },
    { label: "Luxon", description: "Time zones built in." },
  ],
}];

async function createSession(dir, faux, enabled = true) {
  const modelRuntime = await ModelRuntime.create({
    authPath: join(dir, "auth.json"),
    modelsPath: null,
    refreshOnCreate: false,
  });
  modelRuntime.registerNativeProvider(faux.provider);
  const services = await createAgentSessionServices({
    cwd: dir,
    agentDir: dir,
    settingsManager: SettingsManager.inMemory(),
    modelRuntime,
    resourceLoaderOptions: {
      noSkills: true,
      noPromptTemplates: true,
      noThemes: true,
      noContextFiles: true,
      extensionFactories: [createAskUserExtension(() => enabled)],
    },
  });
  const { session } = await createAgentSessionFromServices({
    services,
    sessionManager: SessionManager.inMemory(dir),
    model: faux.getModel("faux-model"),
  });
  return session;
}

/** Resolves with the call id once the tool reports that it waits for the user. */
function pendingQuestion(session) {
  return new Promise((resolve) => {
    const unsubscribe = session.subscribe((event) => {
      if (event.type !== "tool_execution_update" || event.toolName !== "ask_user_question") return;
      if (event.partialResult?.details?.kind !== "pi-web-ask-user-pending") return;
      unsubscribe();
      resolve({ toolCallId: event.toolCallId, details: event.partialResult.details });
    });
  });
}

function askResult(session) {
  return session.agent.state.messages.findLast(
    (message) => message.role === "toolResult" && message.toolName === "ask_user_question",
  );
}

const textOf = (message) => message.content.map((block) => block.text ?? "").join("");

async function withSession(name, run, enabled) {
  const dir = await mkdtemp(join(tmpdir(), `pi-web-ask-user-${name}-`));
  let session;
  try {
    const faux = fauxProvider({ models: [{ id: "faux-model" }] });
    session = await createSession(dir, faux, enabled);
    await run(session, faux);
  } finally {
    session?.dispose();
    await rm(dir, { recursive: true, force: true });
  }
}

test("the tool is declared to the model only, and absent while the switch is off", async () => {
  await withSession("exposure", async (session, faux) => {
    const tool = session.getAllTools().find((entry) => entry.name === "ask_user_question");
    assert.equal(tool?.exposure, "model-only");
    assert.ok(session.getActiveToolNames().includes("ask_user_question"));
    assert.ok(!session.getCallableToolNames().includes("ask_user_question"));
    let declared = [];
    faux.setResponses([(context) => {
      declared = getCurrentTools(context.messages).map((entry) => entry.name);
      return fauxAssistantMessage([fauxText("done")]);
    }]);
    await session.prompt("hello");
    assert.ok(declared.includes("ask_user_question"));
  });
  await withSession("off", async (session) => {
    assert.equal(session.getAllTools().some((entry) => entry.name === "ask_user_question"), false);
  }, false);
});

test("a call waits for the card's answer and returns the package's envelope", async () => {
  await withSession("answer", async (session, faux) => {
    const sessionId = session.sessionManager.getSessionId();
    faux.setResponses([
      fauxAssistantMessage([fauxToolCall("ask_user_question", { questions: QUESTIONS })], { stopReason: "toolUse" }),
      fauxAssistantMessage([fauxText("Using Luxon.")]),
    ]);
    const waiting = pendingQuestion(session);
    const run = session.prompt("pick a date library");
    const { toolCallId, details } = await waiting;
    assert.deepEqual(details.questions, QUESTIONS);
    assert.equal(hasOpenQuestion(sessionId), true);

    // A wrong answer is refused and the question stays open; another session cannot answer it.
    assert.throws(() => answerQuestion(sessionId, toolCallId, { answers: [{ questionIndex: 0, optionIndexes: [5] }] }), /out of range/);
    assert.throws(() => answerQuestion("other-session", toolCallId, { answers: [{ questionIndex: 0, optionIndexes: [1] }] }), /no longer waiting/);
    assert.equal(hasOpenQuestion(sessionId), true);

    answerQuestion(sessionId, toolCallId, { type: "answer_question", toolCallId, answers: [{ questionIndex: 0, optionIndexes: [1] }] });
    await run;
    assert.equal(hasOpenQuestion(sessionId), false);
    const result = askResult(session);
    assert.equal(result.isError, false);
    assert.equal(
      textOf(result),
      'User has answered your questions: "Which library should we use?"="Luxon". You can now continue with the user\'s answers in mind.',
    );
    assert.deepEqual(result.details, {
      answers: [{ questionIndex: 0, question: QUESTIONS[0].question, kind: "option", answer: "Luxon" }],
      cancelled: false,
    });
    // Once answered the question is closed: a second answer is refused.
    assert.throws(() => answerQuestion(sessionId, toolCallId, { cancel: true }), /no longer waiting/);
  });
});

test("Stop and a closed session end the wait as an error, not as a decline", async () => {
  await withSession("stop", async (session, faux) => {
    const sessionId = session.sessionManager.getSessionId();
    faux.setResponses([
      fauxAssistantMessage([fauxToolCall("ask_user_question", { questions: QUESTIONS })], { stopReason: "toolUse" }),
      fauxAssistantMessage([fauxText("unused")]),
    ]);
    const waiting = pendingQuestion(session);
    const run = session.prompt("ask me");
    await waiting;
    await session.abort();
    await run;
    assert.equal(hasOpenQuestion(sessionId), false);
    const result = askResult(session);
    assert.equal(result.isError, true);
    assert.match(textOf(result), /stopped before the user answered/);
  });
  await withSession("closed", async (session, faux) => {
    const sessionId = session.sessionManager.getSessionId();
    faux.setResponses([
      fauxAssistantMessage([fauxToolCall("ask_user_question", { questions: QUESTIONS })], { stopReason: "toolUse" }),
      fauxAssistantMessage([fauxText("The user could not answer.")]),
    ]);
    const waiting = pendingQuestion(session);
    const run = session.prompt("ask me");
    await waiting;
    closeSessionQuestions(sessionId);
    await run;
    const result = askResult(session);
    assert.equal(result.isError, true);
    assert.match(textOf(result), /session closed before the user answered/);
  });
});

test("several questions, multiple choice and notes come back in one answer", async () => {
  await withSession("multi", async (session, faux) => {
    const sessionId = session.sessionManager.getSessionId();
    const questions = [
      { ...QUESTIONS[0], options: [{ ...QUESTIONS[0].options[0], preview: "```ts\nformat(date)\n```" }, QUESTIONS[0].options[1]] },
      {
        question: "Which extras do you want?",
        header: "Extras",
        multiSelect: true,
        options: [{ label: "Time zones", description: "" }, { label: "Locales", description: "" }, { label: "Durations", description: "" }],
      },
    ];
    faux.setResponses([
      fauxAssistantMessage([fauxToolCall("ask_user_question", { questions })], { stopReason: "toolUse" }),
      fauxAssistantMessage([fauxText("done")]),
    ]);
    const waiting = pendingQuestion(session);
    const run = session.prompt("set up dates");
    const { toolCallId, details } = await waiting;
    assert.deepEqual(details.questions, questions);
    answerQuestion(sessionId, toolCallId, {
      answers: [
        { questionIndex: 0, optionIndexes: [0], notes: "tree-shaken" },
        { questionIndex: 1, optionIndexes: [2, 0], text: "ISO weeks" },
      ],
      globalNote: "for v2",
    });
    await run;
    const result = askResult(session);
    assert.equal(
      textOf(result),
      'User has answered your questions: "Which library should we use?"="date-fns". user notes: tree-shaken. "Which extras do you want?"="Time zones, Durations, ISO weeks". global note: for v2. You can now continue with the user\'s answers in mind.',
    );
    assert.deepEqual(result.details.answers[1], {
      questionIndex: 1,
      question: "Which extras do you want?",
      kind: "multi",
      answer: "ISO weeks",
      selected: ["Time zones", "Durations"],
    });
    assert.equal(result.details.answers[0].preview, "```ts\nformat(date)\n```");
  });
});

test("a call that breaks the package's rules is refused at once, without waiting", async () => {
  await withSession("refused", async (session, faux) => {
    const sessionId = session.sessionManager.getSessionId();
    faux.setResponses([
      fauxAssistantMessage([fauxToolCall("ask_user_question", { questions: [{ ...QUESTIONS[0], options: [...QUESTIONS[0].options, { label: "Other", description: "" }] }] })], { stopReason: "toolUse" }),
      fauxAssistantMessage([fauxText("I will ask again.")]),
    ]);
    await session.prompt("ask me");
    assert.equal(hasOpenQuestion(sessionId), false);
    const result = askResult(session);
    assert.match(textOf(result), /Option label is reserved/);
    assert.deepEqual(result.details, { answers: [], cancelled: true, error: "reserved_label" });
  });
});
