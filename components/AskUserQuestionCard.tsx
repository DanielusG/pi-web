"use client";

import type { ReactNode } from "react";
import { useI18n } from "@/hooks/useI18n";
import { isQuestionnaireResult, type AskUserParams, type QuestionAnswer } from "@/lib/ask-user-question";
import type { ToolResultMessage } from "@/lib/types";

// What an ask_user_question call was answered, in the message list. The question itself is asked
// in the dialog that takes the composer's place (AskUserQuestionDialog); a call that is still
// waiting, or ended without answers (Stop, a refused call), keeps the generic tool card. Results
// the rpiv package wrote have the same details and render here too.

export function isAnsweredAskUserResult(params: AskUserParams | null, result: ToolResultMessage | undefined): boolean {
  return Boolean(params && result && !result.isError && isQuestionnaireResult(result.details) && !result.details.error);
}

function CardFrame({ header, children }: { header: string; children: ReactNode }) {
  return (
    <div data-ask-user-card="answered" style={{ borderRadius: 10, overflow: "hidden", fontSize: 13, border: "1px solid var(--border)", background: "var(--bg)" }}>
      <div style={{ display: "flex", alignItems: "center", gap: 8, padding: "7px 10px", borderBottom: "1px solid var(--border)", minWidth: 0 }}>
        <span
          style={{
            fontFamily: "var(--font-mono)",
            fontSize: 11,
            fontWeight: 600,
            color: "var(--text-muted)",
            background: "var(--bg-subtle)",
            border: "1px solid var(--border)",
            borderRadius: 5,
            padding: "1px 7px",
            lineHeight: 1.5,
            maxWidth: "100%",
            overflow: "hidden",
            textOverflow: "ellipsis",
            whiteSpace: "nowrap",
          }}
        >
          {header}
        </span>
      </div>
      {children}
    </div>
  );
}

const QUESTION_TEXT_STYLE = {
  color: "var(--text)",
  fontSize: "calc(13px + var(--chat-font-size-offset, 0px))",
  lineHeight: 1.5,
  whiteSpace: "pre-wrap",
  overflowWrap: "anywhere",
} as const;

function answerText(answer: QuestionAnswer, noAnswer: string): string {
  if (answer.kind === "multi") {
    const values = [...(answer.selected ?? [])];
    if (answer.answer) values.push(answer.answer);
    return values.length > 0 ? values.join(", ") : noAnswer;
  }
  return answer.answer || noAnswer;
}

export function AskUserQuestionCard({ params, result }: { params: AskUserParams; result: ToolResultMessage }) {
  const { t } = useI18n();
  const details = isQuestionnaireResult(result.details) ? result.details : null;
  if (!details) return null;
  const header = params.questions.map((question) => question.header).join(" · ");
  return (
    <CardFrame header={header}>
      <div style={{ padding: "10px 12px", display: "grid", gap: 10 }}>
        {params.questions.map((question, index) => {
          const answer = details.cancelled ? undefined : details.answers.find((entry) => entry.questionIndex === index);
          return (
            <div key={question.question} style={{ display: "grid", gap: 4 }}>
              <div style={QUESTION_TEXT_STYLE}>{question.question}</div>
              {answer ? (
                <div style={{ display: "flex", alignItems: "baseline", gap: 6, minWidth: 0, color: "var(--text)", overflowWrap: "anywhere" }}>
                  <span aria-hidden="true" style={{ color: "var(--text-dim)", flexShrink: 0 }}>→</span>
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
        {details.globalNote && (
          <div style={{ color: "var(--text-muted)", fontSize: 12, whiteSpace: "pre-wrap", borderTop: "1px solid var(--border)", paddingTop: 8 }}>
            {t("askUser.globalNote", { note: details.globalNote })}
          </div>
        )}
      </div>
    </CardFrame>
  );
}
