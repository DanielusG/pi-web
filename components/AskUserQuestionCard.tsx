"use client";

import { useState, type ReactNode } from "react";
import { useI18n } from "@/hooks/useI18n";
import { sendAgentCommand } from "@/lib/agent-client";
import {
  isAskUserPendingDetails,
  isQuestionnaireResult,
  type AskUserAnswerEntry,
  type AskUserParams,
  type QuestionAnswer,
} from "@/lib/ask-user-question";
import type { ToolResultMessage } from "@/lib/types";

// The native ask_user_question's card (lib/ask-user-extension.ts): the form while the call waits
// for the user, then what was answered. MessageView renders it only when it has one of the two
// states to show; a call without either (cut off by a restart, refused) keeps the generic card.

/** Which of the card's two states a call is in, or null for the generic tool card. */
export function askUserCardState(params: AskUserParams | null, result: ToolResultMessage | undefined): "pending" | "answered" | null {
  if (!params || !result) return null;
  if (isAskUserPendingDetails(result.details)) {
    // The form takes the trial's shape only: one single-choice question.
    return params.questions.length === 1 && !params.questions[0]!.multiSelect ? "pending" : null;
  }
  if (result.isError || !isQuestionnaireResult(result.details) || result.details.error) return null;
  return "answered";
}

type Choice = { kind: "option"; index: number } | { kind: "custom" };

export function AskUserQuestionCard({ toolCallId, params, result, sessionId }: {
  toolCallId: string;
  params: AskUserParams;
  result: ToolResultMessage;
  sessionId?: string;
}) {
  if (isAskUserPendingDetails(result.details)) {
    return <AskUserQuestionForm toolCallId={toolCallId} params={params} sessionId={sessionId} />;
  }
  return <AskUserQuestionSummary params={params} result={result} />;
}

function CardFrame({ header, waiting, children }: { header: string; waiting: boolean; children: ReactNode }) {
  const { t } = useI18n();
  return (
    <div
      data-ask-user-card={waiting ? "pending" : "answered"}
      style={{
        borderRadius: 10,
        overflow: "hidden",
        fontSize: 13,
        border: waiting ? "1px solid color-mix(in srgb, var(--accent) 45%, transparent)" : "1px solid var(--border)",
        background: "var(--bg)",
      }}
    >
      <div style={{ display: "flex", alignItems: "center", gap: 8, padding: "7px 10px", borderBottom: "1px solid var(--border)", minWidth: 0 }}>
        <span
          style={{
            fontSize: 11,
            fontWeight: 600,
            color: "var(--accent)",
            background: "color-mix(in srgb, var(--accent) 10%, transparent)",
            border: "1px solid color-mix(in srgb, var(--accent) 25%, transparent)",
            borderRadius: 5,
            padding: "1px 7px",
            lineHeight: 1.5,
            flexShrink: 0,
            maxWidth: "60%",
            overflow: "hidden",
            textOverflow: "ellipsis",
            whiteSpace: "nowrap",
          }}
        >
          {header}
        </span>
        {waiting && (
          <span style={{ display: "inline-flex", alignItems: "center", gap: 6, fontSize: 11, color: "var(--text-dim)", minWidth: 0 }}>
            <span aria-hidden="true" style={{ width: 7, height: 7, borderRadius: "50%", background: "var(--accent)", animation: "pulse 1.6s ease-in-out infinite", flexShrink: 0 }} />
            {t("askUser.waiting")}
          </span>
        )}
      </div>
      {children}
    </div>
  );
}

const QUESTION_TEXT_STYLE = {
  color: "var(--text)",
  fontSize: "calc(14px + var(--chat-font-size-offset, 0px))",
  lineHeight: 1.5,
  whiteSpace: "pre-wrap",
  overflowWrap: "anywhere",
} as const;

function AskUserQuestionForm({ toolCallId, params, sessionId }: { toolCallId: string; params: AskUserParams; sessionId?: string }) {
  const { t } = useI18n();
  const question = params.questions[0]!;
  const [choice, setChoice] = useState<Choice | null>(null);
  const [text, setText] = useState("");
  const [sending, setSending] = useState(false);
  const [sent, setSent] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const groupName = `ask-user-${toolCallId}`;
  const locked = sending || sent || !sessionId;
  const canSubmit = !locked && choice !== null && (choice.kind === "option" || text.trim().length > 0);

  const send = async (payload: { answers: AskUserAnswerEntry[] } | { cancel: true }) => {
    if (!sessionId || sending || sent) return;
    setSending(true);
    setError(null);
    try {
      await sendAgentCommand(sessionId, { type: "answer_question", toolCallId, ...payload });
      // The result replaces this form when it arrives; until then nothing can be sent twice.
      setSent(true);
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : String(cause));
    } finally {
      setSending(false);
    }
  };

  const submit = () => {
    if (!canSubmit || !choice) return;
    void send({
      answers: [choice.kind === "option"
        ? { questionIndex: 0, optionIndexes: [choice.index] }
        : { questionIndex: 0, text: text.trim() }],
    });
  };

  const rowStyle = (selected: boolean) => ({
    display: "flex",
    alignItems: "flex-start",
    gap: 10,
    width: "100%",
    padding: "9px 10px",
    borderRadius: 9,
    border: selected ? "1px solid var(--accent)" : "1px solid var(--border)",
    background: selected ? "color-mix(in srgb, var(--accent) 8%, var(--bg-panel))" : "var(--bg-panel)",
    color: "var(--text)",
    cursor: locked ? "default" : "pointer",
    textAlign: "left" as const,
    overflowWrap: "anywhere" as const,
  });

  return (
    <CardFrame header={question.header} waiting>
      <form
        onSubmit={(event) => {
          event.preventDefault();
          submit();
        }}
        style={{ padding: 12, display: "grid", gap: 10 }}
      >
        <div id={`${groupName}-question`} style={QUESTION_TEXT_STYLE}>{question.question}</div>
        <div role="radiogroup" aria-labelledby={`${groupName}-question`} style={{ display: "grid", gap: 8 }}>
          {question.options.map((option, index) => {
            const selected = choice?.kind === "option" && choice.index === index;
            return (
              <label key={option.label} style={rowStyle(selected)}>
                <input
                  type="radio"
                  name={groupName}
                  checked={selected}
                  disabled={locked}
                  onChange={() => setChoice({ kind: "option", index })}
                  style={{ marginTop: 3, accentColor: "var(--accent)", flexShrink: 0 }}
                />
                <span style={{ display: "grid", gap: 2, minWidth: 0 }}>
                  <span style={{ fontWeight: 600 }}>{option.label}</span>
                  {option.description && <span style={{ color: "var(--text-muted)", fontSize: 12, lineHeight: 1.5 }}>{option.description}</span>}
                </span>
              </label>
            );
          })}
          <label style={rowStyle(choice?.kind === "custom")}>
            <input
              type="radio"
              name={groupName}
              checked={choice?.kind === "custom"}
              disabled={locked}
              onChange={() => setChoice({ kind: "custom" })}
              style={{ marginTop: 3, accentColor: "var(--accent)", flexShrink: 0 }}
            />
            <span style={{ color: "var(--text-muted)" }}>{t("askUser.typeSomething")}</span>
          </label>
          {choice?.kind === "custom" && (
            <textarea
              autoFocus
              value={text}
              disabled={locked}
              placeholder={t("askUser.customPlaceholder")}
              aria-label={t("askUser.typeSomething")}
              onChange={(event) => setText(event.target.value)}
              onKeyDown={(event) => {
                if (event.key === "Enter" && !event.shiftKey && !event.nativeEvent.isComposing) {
                  event.preventDefault();
                  submit();
                }
              }}
              rows={2}
              style={{
                width: "100%",
                padding: "8px 10px",
                borderRadius: 9,
                border: "1px solid var(--border)",
                background: "var(--bg-panel)",
                color: "var(--text)",
                outline: "none",
                resize: "vertical",
                fontSize: 13,
                lineHeight: 1.5,
                fontFamily: "inherit",
              }}
            />
          )}
        </div>
        {error && <div role="alert" style={{ color: "var(--danger)", fontSize: 12, overflowWrap: "anywhere" }}>{error}</div>}
        <div style={{ display: "flex", justifyContent: "flex-end", gap: 8 }}>
          <button
            type="button"
            disabled={locked}
            onClick={() => void send({ cancel: true })}
            style={{
              padding: "6px 10px",
              borderRadius: 8,
              border: "1px solid var(--border)",
              background: "var(--bg)",
              color: "var(--text-muted)",
              cursor: locked ? "default" : "pointer",
            }}
          >
            {t("askUser.decline")}
          </button>
          <button
            type="submit"
            disabled={!canSubmit}
            style={{
              padding: "6px 10px",
              borderRadius: 8,
              border: "1px solid var(--accent)",
              background: "var(--accent)",
              color: "var(--accent-contrast)",
              cursor: canSubmit ? "pointer" : "default",
              opacity: canSubmit ? 1 : 0.55,
            }}
          >
            {sending || sent ? t("askUser.sending") : t("askUser.submit")}
          </button>
        </div>
      </form>
    </CardFrame>
  );
}

function answerText(answer: QuestionAnswer, noAnswer: string): string {
  if (answer.kind === "multi") {
    const values = [...(answer.selected ?? [])];
    if (answer.answer) values.push(answer.answer);
    return values.length > 0 ? values.join(", ") : noAnswer;
  }
  return answer.answer || noAnswer;
}

function AskUserQuestionSummary({ params, result }: { params: AskUserParams; result: ToolResultMessage }) {
  const { t } = useI18n();
  const details = isQuestionnaireResult(result.details) ? result.details : null;
  if (!details) return null;
  const header = params.questions.map((question) => question.header).join(" · ");
  return (
    <CardFrame header={header} waiting={false}>
      <div style={{ padding: "10px 12px", display: "grid", gap: 10 }}>
        {params.questions.map((question, index) => {
          const answer = details.cancelled ? undefined : details.answers.find((entry) => entry.questionIndex === index);
          return (
            <div key={question.question} style={{ display: "grid", gap: 4 }}>
              <div style={{ ...QUESTION_TEXT_STYLE, fontSize: "calc(13px + var(--chat-font-size-offset, 0px))" }}>{question.question}</div>
              {answer ? (
                <div style={{ display: "flex", alignItems: "baseline", gap: 6, minWidth: 0, color: "var(--text)", overflowWrap: "anywhere" }}>
                  <span aria-hidden="true" style={{ color: "var(--accent)", flexShrink: 0 }}>→</span>
                  <span style={{ fontWeight: 600, whiteSpace: "pre-wrap" }}>{answerText(answer, t("askUser.noAnswer"))}</span>
                  {answer.kind === "custom" && <span style={{ color: "var(--text-dim)", fontSize: 11, flexShrink: 0 }}>{t("askUser.customAnswer")}</span>}
                </div>
              ) : (
                <div style={{ color: "var(--text-dim)", fontSize: 12 }}>{details.cancelled ? t("askUser.declined") : t("askUser.unanswered")}</div>
              )}
              {answer?.notes && <div style={{ color: "var(--text-muted)", fontSize: 12, whiteSpace: "pre-wrap" }}>{t("askUser.note", { note: answer.notes })}</div>}
            </div>
          );
        })}
        {details.globalNote && <div style={{ color: "var(--text-muted)", fontSize: 12, whiteSpace: "pre-wrap" }}>{t("askUser.note", { note: details.globalNote })}</div>}
      </div>
    </CardFrame>
  );
}
