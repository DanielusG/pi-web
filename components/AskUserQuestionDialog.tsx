"use client";

import { useCallback, useEffect, useId, useLayoutEffect, useRef, useState, type ReactNode } from "react";
import { useI18n } from "@/hooks/useI18n";
import { sendAgentCommand } from "@/lib/agent-client";
import {
  buildAnswerPayload,
  draftAnswerText,
  emptyQuestionDraft,
  isDraftAnswered,
  previewOptionIndex,
  toggleDraftOption,
  toggleDraftOwn,
  type AskUserQuestion,
  type QuestionDraft,
} from "@/lib/ask-user-question";
import { MarkdownBody } from "./MarkdownBody";

// The native ask_user_question's dialog (lib/ask-user-extension.ts). Like an extension's blocking
// dialog it takes the composer's place (ChatWindow) and keeps that card's chrome: collapse to a
// pill, maximize, "+N more", Escape to decline. Several questions get a tab each and a review tab
// that sends them together; previews sit beside the options when there is room.

export interface PendingAskUserQuestion {
  toolCallId: string;
  questions: AskUserQuestion[];
}

interface FormState {
  drafts: QuestionDraft[];
  globalNote: string;
  /** Which note fields the user opened, by question index; `questions.length` is the global one. */
  openNotes: number[];
  tab: number;
}

// The form survives the dialog's remounts (a session switch and back, a ChatWindow remount) for
// as long as its question is open; a sent answer drops it.
const formStore = new Map<string, FormState>();
const FORM_STORE_MAX = 20;

function initialForm(toolCallId: string, questions: AskUserQuestion[]): FormState {
  const stored = formStore.get(toolCallId);
  if (stored && stored.drafts.length === questions.length) return stored;
  return { drafts: questions.map(() => emptyQuestionDraft()), globalNote: "", openNotes: [], tab: 0 };
}

function rememberForm(toolCallId: string, form: FormState): void {
  formStore.delete(toolCallId);
  formStore.set(toolCallId, form);
  if (formStore.size > FORM_STORE_MAX) {
    const oldest = formStore.keys().next().value;
    if (oldest !== undefined) formStore.delete(oldest);
  }
}

const DIALOG_MAX_HEIGHT = "min(60vh, 520px)";
const DIALOG_FULL_MAX_HEIGHT = "min(85vh, 820px)";
/** Below this body width a preview goes under the options instead of beside them. */
const SIDE_BY_SIDE_MIN_WIDTH = 620;

const iconButtonStyle = {
  display: "grid",
  placeItems: "center",
  width: 26,
  height: 26,
  borderRadius: 8,
  border: "1px solid var(--border)",
  background: "var(--bg-panel)",
  color: "var(--text-muted)",
  cursor: "pointer",
  flexShrink: 0,
} as const;

const textAreaStyle = {
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
} as const;

function footerButtonStyle(primary: boolean, enabled: boolean) {
  return {
    padding: "6px 10px",
    borderRadius: 8,
    border: primary ? "1px solid var(--accent)" : "1px solid var(--border)",
    background: primary ? "var(--accent)" : "var(--bg)",
    color: primary ? "var(--accent-contrast)" : "var(--text-muted)",
    cursor: enabled ? "pointer" : "default",
    opacity: enabled ? 1 : 0.55,
  } as const;
}

function SizeIcon({ expanded }: { expanded: boolean }) {
  return (
    <svg width="10" height="10" viewBox="0 0 10 10" fill="none" stroke="currentColor" strokeWidth={1.6} strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      {expanded
        ? <path d="M3.5 1v2.5H1M6.5 1v2.5H9M6.5 9v-2.5H9M3.5 9v-2.5H1" />
        : <path d="M1 3.5V1h2.5M6.5 1H9v2.5M9 6.5V9H6.5M3.5 9H1V6.5" />}
    </svg>
  );
}

function HeaderChip({ children }: { children: ReactNode }) {
  return (
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
        whiteSpace: "nowrap",
      }}
    >
      {children}
    </span>
  );
}

export function AskUserQuestionDialog({ request, sessionId, waitingCount }: {
  request: PendingAskUserQuestion;
  sessionId: string;
  /** Further questions queued behind this one; each opens after this one is answered. */
  waitingCount: number;
}) {
  const { t } = useI18n();
  const { toolCallId, questions } = request;
  const titleId = useId();
  const multiple = questions.length > 1;
  const reviewTab = questions.length;
  const [form, setFormState] = useState<FormState>(() => initialForm(toolCallId, questions));
  const [collapsed, setCollapsed] = useState(false);
  const [full, setFull] = useState(false);
  const [focusedOption, setFocusedOption] = useState<number | null>(null);
  const [sending, setSending] = useState(false);
  const [sent, setSent] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [bodyWidth, setBodyWidth] = useState(0);
  const bodyRef = useRef<HTMLDivElement>(null);
  const locked = sending || sent;

  const setForm = useCallback((update: (previous: FormState) => FormState) => {
    setFormState((previous) => {
      const next = update(previous);
      rememberForm(toolCallId, next);
      return next;
    });
  }, [toolCallId]);

  const tab = Math.min(form.tab, multiple ? reviewTab : 0);
  const onReview = multiple && tab === reviewTab;
  const question = onReview ? null : questions[tab]!;
  const draft = onReview ? null : form.drafts[tab]!;
  const answered = questions.map((entry, index) => isDraftAnswered(entry, form.drafts[index]!));
  const allAnswered = answered.every(Boolean);
  const hasPreviews = questions.some((entry) => !entry.multiSelect && entry.options.some((option) => Boolean(option.preview)));

  useLayoutEffect(() => {
    const body = bodyRef.current;
    if (!body || collapsed) return;
    const observer = new ResizeObserver(() => setBodyWidth(body.clientWidth));
    observer.observe(body);
    setBodyWidth(body.clientWidth);
    return () => observer.disconnect();
  }, [collapsed]);

  // A new tab starts at its first row, so the keyboard lands on the choices.
  useEffect(() => {
    setFocusedOption(null);
    if (collapsed) return;
    const first = bodyRef.current?.querySelector<HTMLInputElement>("input[data-ask-user-choice]");
    first?.focus({ preventScroll: true });
    bodyRef.current?.scrollTo({ top: 0 });
  }, [tab, collapsed]);

  const updateDraft = (index: number, update: (previous: QuestionDraft) => QuestionDraft) => {
    setForm((previous) => ({
      ...previous,
      drafts: previous.drafts.map((entry, position) => (position === index ? update(entry) : entry)),
    }));
  };
  const goTo = (next: number) => setForm((previous) => ({ ...previous, tab: next }));
  const openNote = (index: number) => setForm((previous) => (
    previous.openNotes.includes(index) ? previous : { ...previous, openNotes: [...previous.openNotes, index] }
  ));

  const send = async (payload: Record<string, unknown>) => {
    if (locked) return;
    setSending(true);
    setError(null);
    try {
      await sendAgentCommand(sessionId, { type: "answer_question", toolCallId, ...payload });
      // The dialog closes when the call ends; until then nothing can be sent twice.
      setSent(true);
      formStore.delete(toolCallId);
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : String(cause));
    } finally {
      setSending(false);
    }
  };

  const submit = () => {
    if (locked || !allAnswered) return;
    void send(buildAnswerPayload(questions, form.drafts, form.globalNote));
  };
  const decline = () => void send({ cancel: true });
  // Next on a question tab, Submit on the review tab or a lone question.
  const advance = () => {
    if (multiple && !onReview) goTo(tab + 1);
    else submit();
  };
  const canAdvance = !locked && (multiple && !onReview ? true : allAnswered);

  const title = question ? question.question : t("askUser.reviewTitle");

  if (collapsed) {
    return (
      <button
        type="button"
        onClick={() => setCollapsed(false)}
        aria-label={t("chat.extensionExpand")}
        style={{
          display: "flex",
          alignItems: "center",
          gap: 10,
          width: "100%",
          maxWidth: "var(--chat-content-max-width, 820px)",
          margin: "0 auto",
          padding: "10px 12px",
          borderRadius: 10,
          border: "1px solid color-mix(in srgb, var(--accent) 45%, var(--border))",
          background: "var(--bg-panel)",
          cursor: "pointer",
          textAlign: "left",
        }}
      >
        <span className="animate-[pulse_1.5s_infinite]" style={{ width: 8, height: 8, borderRadius: "50%", background: "var(--accent)", flexShrink: 0 }} />
        <span style={{ color: "var(--text)", fontSize: 13, fontWeight: 650, minWidth: 0, flex: 1, overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>
          {questions[0]!.question}
        </span>
        {multiple && (
          <span style={{ fontSize: 12, color: "var(--text-dim)", whiteSpace: "nowrap", flexShrink: 0 }}>
            {t("askUser.answeredCount", { answered: answered.filter(Boolean).length, total: questions.length })}
          </span>
        )}
        {waitingCount > 0 && (
          <span style={{ fontSize: 11, fontWeight: 650, color: "var(--accent)", whiteSpace: "nowrap", flexShrink: 0 }}>
            {t("chat.extensionMoreWaiting", { count: waitingCount })}
          </span>
        )}
        <span style={{ color: "var(--text-dim)", fontSize: 11, fontFamily: "var(--font-mono)", flexShrink: 0 }}>ask_user_question</span>
      </button>
    );
  }

  const choiceRow = (selected: boolean) => ({
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
    fontSize: 13,
  });

  const noteField = (index: number, label: string, placeholder: string) => {
    const value = index === reviewTab ? form.globalNote : form.drafts[index]!.note;
    if (!form.openNotes.includes(index) && !value) {
      return (
        <button
          type="button"
          disabled={locked}
          onClick={() => openNote(index)}
          style={{ justifySelf: "start", padding: 0, border: "none", background: "none", color: "var(--accent)", fontSize: 12, cursor: locked ? "default" : "pointer" }}
        >
          + {label}
        </button>
      );
    }
    // The placeholder is gone once the note has text, so the field keeps a caption of its own.
    return (
      <label style={{ display: "grid", gap: 4 }}>
        <span style={{ color: "var(--text-dim)", fontSize: 12 }}>{placeholder}</span>
        <textarea
          autoFocus={!value}
          value={value}
          disabled={locked}
          rows={2}
          aria-label={label}
          onChange={(event) => {
            const text = event.target.value;
            if (index === reviewTab) setForm((previous) => ({ ...previous, globalNote: text }));
            else updateDraft(index, (previous) => ({ ...previous, note: text }));
          }}
          style={textAreaStyle}
        />
      </label>
    );
  };

  const questionBody = (entry: AskUserQuestion, index: number, entryDraft: QuestionDraft) => {
    const multi = entry.multiSelect === true;
    const previewIndex = previewOptionIndex(entry, entryDraft, focusedOption);
    const preview = previewIndex !== null ? entry.options[previewIndex]?.preview : undefined;
    const sideBySide = preview !== undefined && bodyWidth >= SIDE_BY_SIDE_MIN_WIDTH;
    const inputType = multi ? "checkbox" : "radio";
    const groupName = `ask-user-${toolCallId}-${index}`;
    const choices = (
      <div role={multi ? "group" : "radiogroup"} aria-labelledby={titleId} style={{ display: "grid", gap: 8, alignContent: "start", minWidth: 0 }}>
        {entry.options.map((option, optionIndex) => {
          const selected = entryDraft.picked.includes(optionIndex);
          return (
            <label
              key={option.label}
              style={choiceRow(selected)}
              onMouseEnter={() => setFocusedOption(optionIndex)}
            >
              <input
                data-ask-user-choice
                type={inputType}
                name={groupName}
                checked={selected}
                disabled={locked}
                onFocus={() => setFocusedOption(optionIndex)}
                onChange={() => updateDraft(index, (previous) => toggleDraftOption(entry, previous, optionIndex))}
                style={{ marginTop: 3, accentColor: "var(--accent)", flexShrink: 0 }}
              />
              <span style={{ display: "grid", gap: 2, minWidth: 0 }}>
                <span style={{ fontWeight: 600 }}>{option.label}</span>
                {option.description && <span style={{ color: "var(--text-muted)", fontSize: 12, lineHeight: 1.5 }}>{option.description}</span>}
              </span>
            </label>
          );
        })}
        <label style={choiceRow(entryDraft.own)}>
          <input
            data-ask-user-choice
            type={inputType}
            name={groupName}
            checked={entryDraft.own}
            disabled={locked}
            onFocus={() => setFocusedOption(null)}
            onChange={() => updateDraft(index, (previous) => toggleDraftOwn(entry, previous))}
            style={{ marginTop: 3, accentColor: "var(--accent)", flexShrink: 0 }}
          />
          <span style={{ color: "var(--text-muted)" }}>{t("askUser.typeSomething")}</span>
        </label>
        {entryDraft.own && (
          <textarea
            autoFocus
            value={entryDraft.text}
            disabled={locked}
            rows={2}
            placeholder={t("askUser.customPlaceholder")}
            aria-label={t("askUser.typeSomething")}
            onChange={(event) => {
              const text = event.target.value;
              updateDraft(index, (previous) => ({ ...previous, text }));
            }}
            onKeyDown={(event) => {
              if (event.key === "Enter" && !event.shiftKey && !event.ctrlKey && !event.metaKey && !event.nativeEvent.isComposing) {
                event.preventDefault();
                if (canAdvance) advance();
              }
            }}
            style={textAreaStyle}
          />
        )}
      </div>
    );
    return (
      <div style={{ display: "grid", gap: 10 }}>
        {multi && <div style={{ color: "var(--text-dim)", fontSize: 12 }}>{t("askUser.selectAll")}</div>}
        {preview !== undefined ? (
          <div style={{ display: "grid", gap: 10, gridTemplateColumns: sideBySide ? "minmax(0, 2fr) minmax(0, 3fr)" : "minmax(0, 1fr)", alignItems: "start" }}>
            {choices}
            <div
              aria-label={t("askUser.preview")}
              role="region"
              style={{ border: "1px solid var(--border)", borderRadius: 9, background: "var(--bg-panel)", padding: "8px 10px", overflowX: "auto", minWidth: 0 }}
            >
              <MarkdownBody className="ask-user-preview" keepLineBreaks>{preview}</MarkdownBody>
            </div>
          </div>
        ) : choices}
        {noteField(index, t("askUser.addNote"), t("askUser.notePlaceholder"))}
      </div>
    );
  };

  const reviewBody = (
    <div style={{ display: "grid", gap: 10 }}>
      {questions.map((entry, index) => {
        const done = answered[index];
        const entryDraft = form.drafts[index]!;
        return (
          <button
            key={entry.question}
            type="button"
            disabled={locked}
            onClick={() => goTo(index)}
            style={{ display: "grid", gap: 3, padding: "8px 10px", borderRadius: 9, border: "1px solid var(--border)", background: "var(--bg-panel)", textAlign: "left", cursor: locked ? "default" : "pointer", color: "var(--text)", overflowWrap: "anywhere" }}
          >
            <span style={{ display: "flex", gap: 8, alignItems: "center", minWidth: 0 }}>
              <HeaderChip>{entry.header}</HeaderChip>
              <span style={{ fontSize: 13, minWidth: 0 }}>{entry.question}</span>
            </span>
            {done
              ? <span style={{ fontSize: 13, fontWeight: 600 }}>→ {draftAnswerText(entry, entryDraft)}</span>
              : <span style={{ fontSize: 12, color: "var(--danger)" }}>{t("askUser.unanswered")}</span>}
            {entryDraft.note.trim() && <span style={{ fontSize: 12, color: "var(--text-muted)", whiteSpace: "pre-wrap" }}>{t("askUser.note", { note: entryDraft.note.trim() })}</span>}
          </button>
        );
      })}
      {noteField(reviewTab, t("askUser.addGlobalNote"), t("askUser.globalNotePlaceholder"))}
    </div>
  );

  return (
    <div
      role="dialog"
      aria-labelledby={titleId}
      data-ask-user-dialog
      onKeyDown={(event) => {
        if (event.nativeEvent.isComposing) return;
        if (event.key === "Escape") {
          event.preventDefault();
          event.stopPropagation();
          if (!locked) decline();
        } else if (event.key === "Enter" && (event.ctrlKey || event.metaKey)) {
          event.preventDefault();
          if (canAdvance) advance();
        }
      }}
      style={{
        display: "flex",
        flexDirection: "column",
        width: "100%",
        // The chat column by default; previews ask for room beside the options.
        maxWidth: full
          ? "100%"
          : hasPreviews
            ? "max(var(--chat-content-max-width, 820px), min(100%, 980px))"
            : "var(--chat-content-max-width, 820px)",
        margin: "0 auto",
        maxHeight: full ? DIALOG_FULL_MAX_HEIGHT : DIALOG_MAX_HEIGHT,
        border: "1px solid var(--border)",
        borderRadius: 10,
        background: "var(--bg)",
        boxShadow: "var(--shadow-md)",
        overflow: "hidden",
      }}
    >
      <div style={{ display: "flex", alignItems: "flex-start", gap: 10, padding: "10px 14px", borderBottom: "1px solid var(--border)", flexShrink: 0 }}>
        <div style={{ minWidth: 0, flex: 1, display: "grid", gap: 6 }}>
          {multiple ? (
            <div role="tablist" aria-label={t("askUser.questions")} style={{ display: "flex", flexWrap: "wrap", gap: 6 }}>
              {[...questions.map((entry) => entry.header), t("askUser.review")].map((label, index) => {
                const active = index === tab;
                const done = index < reviewTab && answered[index];
                return (
                  <button
                    key={`${index}-${label}`}
                    type="button"
                    role="tab"
                    aria-selected={active}
                    disabled={locked}
                    onClick={() => goTo(index)}
                    style={{
                      display: "inline-flex",
                      alignItems: "center",
                      gap: 5,
                      padding: "2px 9px",
                      borderRadius: 999,
                      border: active ? "1px solid var(--accent)" : "1px solid var(--border)",
                      background: active ? "color-mix(in srgb, var(--accent) 10%, transparent)" : "var(--bg-panel)",
                      color: active ? "var(--accent)" : "var(--text-muted)",
                      fontSize: 12,
                      fontWeight: 600,
                      cursor: locked ? "default" : "pointer",
                      whiteSpace: "nowrap",
                    }}
                  >
                    {done && <span aria-label={t("askUser.answeredMark")} style={{ color: "var(--success)" }}>✓</span>}
                    {label}
                  </button>
                );
              })}
            </div>
          ) : (
            <div style={{ display: "flex", alignItems: "center", gap: 8, minWidth: 0 }}>
              <HeaderChip>{questions[0]!.header}</HeaderChip>
              <span style={{ fontSize: 11, color: "var(--text-dim)" }}>{t("askUser.waiting")}</span>
            </div>
          )}
          <div id={titleId} style={{ color: "var(--text)", fontSize: 14, fontWeight: 650, lineHeight: 1.45, whiteSpace: "pre-wrap", overflowWrap: "anywhere", maxHeight: "6.5em", overflowY: "auto" }}>
            {title}
          </div>
          {waitingCount > 0 && (
            <span style={{ fontSize: 11, fontWeight: 650, color: "var(--accent)" }}>{t("chat.extensionMoreWaiting", { count: waitingCount })}</span>
          )}
        </div>
        <button
          type="button"
          onClick={() => setFull((previous) => !previous)}
          title={full ? t("chat.extensionRestoreSize") : t("chat.extensionMaximize")}
          aria-label={full ? t("chat.extensionRestoreSize") : t("chat.extensionMaximize")}
          style={iconButtonStyle}
        >
          <SizeIcon expanded={full} />
        </button>
        <button type="button" onClick={() => setCollapsed(true)} aria-label={t("chat.extensionCollapse")} title={t("chat.extensionCollapse")} style={iconButtonStyle}>
          <svg width="14" height="14" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
            <path d="M4 6l4 4 4-4" />
          </svg>
        </button>
      </div>

      <div ref={bodyRef} style={{ padding: 14, overflowY: "auto", minHeight: 0, flex: 1 }}>
        {question && draft ? questionBody(question, tab, draft) : reviewBody}
      </div>

      <div style={{ display: "flex", alignItems: "center", gap: 8, padding: "10px 14px", borderTop: "1px solid var(--border)", background: "var(--bg-panel)", flexShrink: 0 }}>
        <div role={error ? "alert" : undefined} style={{ flex: 1, minWidth: 0, color: "var(--danger)", fontSize: 12, overflowWrap: "anywhere" }}>{error}</div>
        <button type="button" disabled={locked} onClick={decline} style={footerButtonStyle(false, !locked)}>
          {t("askUser.decline")}
        </button>
        {multiple && tab > 0 && (
          <button type="button" disabled={locked} onClick={() => goTo(tab - 1)} style={footerButtonStyle(false, !locked)}>
            {t("askUser.back")}
          </button>
        )}
        <button type="button" disabled={!canAdvance} onClick={advance} style={footerButtonStyle(true, canAdvance)}>
          {locked ? t("askUser.sending") : multiple && !onReview ? t("askUser.next") : multiple ? t("askUser.submitAll") : t("askUser.submit")}
        </button>
      </div>
    </div>
  );
}
