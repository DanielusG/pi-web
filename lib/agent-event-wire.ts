import type { JsonAgentSessionEvent } from "@earendil-works/pi-coding-agent";

export interface AgentEventLike {
  type: string;
  [key: string]: unknown;
}

type JsonMessageUpdateEvent = Extract<
  JsonAgentSessionEvent,
  { type: "message_update" }
>;

type JsonAssistantMessageEvent = JsonMessageUpdateEvent["assistantMessageEvent"];
type JsonToolCallStartEvent = Extract<JsonAssistantMessageEvent, { type: "toolcall_start" }>;
type JsonToolCallDeltaEvent = Extract<JsonAssistantMessageEvent, { type: "toolcall_delta" }>;

export type ClientAssistantMessageEvent =
  | Exclude<JsonAssistantMessageEvent, { type: "toolcall_start" | "toolcall_delta" }>
  | (JsonToolCallStartEvent & { id?: string; toolName?: string })
  | (JsonToolCallDeltaEvent & { id?: string; toolName?: string });

export type ClientMessageUpdateEvent = Omit<JsonMessageUpdateEvent, "assistantMessageEvent"> & {
  assistantMessageEvent: ClientAssistantMessageEvent;
};

/** Choices a client makes per event stream (query parameters of the SSE route). */
export interface ClientAgentEventOptions {
  /**
   * "tail" (the Android client, a fork addition): each `tool_execution_update` carries only
   * the end of the output so far. Tools resend their whole accumulated output with every
   * update (bash: up to 50 KB, ten times a second), while a live view shows its last lines.
   * The clip is applied on top of Pi Web's own slimming (nested calls dropped, codemode
   * snapshots capped), so a phone's update is both the newest one and a short one.
   * "full", the default, is what the web client gets.
   */
  toolUpdates?: "full" | "tail";
}

const TAIL_MAX_LINES = 16;
/** The Android client's output clip: a tail never ends up with its "… more" suffix. */
const TAIL_MAX_CHARS = 8_000;

const OMITTED_EVENT_TYPES = new Set([
  "turn_start",
  "turn_end",
  // Carries the whole appended entry (an extension's custom entry, such as a
  // codemode `store()` write, can be 1 MiB), and nothing in the browser reads it.
  "entry_appended",
]);

/**
 * How many nested calls a codemode progress snapshot keeps. The script
 * publishes every call made so far each time one starts or finishes, so the
 * bytes on the wire grow with the square of the call count.
 */
export const CODEMODE_SNAPSHOT_CALL_LIMIT = 200;

function isObject(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

/**
 * A `tool_execution_*` event for a call that another tool made while it ran
 * (`ctx.executeTool()`, which codemode scripts use). The SDK gives those
 * `parentToolCallId` and ids of the form `<parent>/<n>`; the model never sees
 * them as tool calls, and the parent's own updates already report them.
 */
export function isNestedToolExecutionEvent(event: AgentEventLike): boolean {
  return (
    event.type === "tool_execution_start"
    || event.type === "tool_execution_update"
    || event.type === "tool_execution_end"
  ) && typeof event.parentToolCallId === "string" && event.parentToolCallId !== "";
}

/** Keep the newest calls of a codemode snapshot (`details.calls`) and count the rest. */
function truncateCodemodeSnapshot(partialResult: unknown): unknown {
  if (!isObject(partialResult) || !isObject(partialResult.details)) return partialResult;
  const calls = partialResult.details.calls;
  if (!Array.isArray(calls) || calls.length <= CODEMODE_SNAPSHOT_CALL_LIMIT) return partialResult;
  return {
    ...partialResult,
    details: {
      ...partialResult.details,
      calls: calls.slice(-CODEMODE_SNAPSHOT_CALL_LIMIT),
      omittedCalls: calls.length - CODEMODE_SNAPSHOT_CALL_LIMIT,
    },
  };
}

/**
 * Nested calls travel as a slim start and end, and their progress updates are
 * dropped. A `tool_execution_end` never carries `result`: the browser only
 * reads its ids and renders the tool result message that follows, while the
 * raw result can hold up to 1 MiB of bash output in `structuredContent`.
 *
 * `options.toolUpdates` is the fork's per-client choice, applied to the
 * surviving update after the snapshot cap.
 */
function toClientToolExecutionEvent(
  event: AgentEventLike,
  options: ClientAgentEventOptions = {},
): AgentEventLike | null {
  const nested = isNestedToolExecutionEvent(event);
  if (event.type === "tool_execution_start") {
    if (!nested) return event;
    return {
      type: "tool_execution_start",
      toolCallId: event.toolCallId,
      toolName: event.toolName,
      parentToolCallId: event.parentToolCallId,
    };
  }
  if (event.type === "tool_execution_update") {
    if (nested) return null;
    const slimmed = event.toolName === "codemode"
      ? truncateCodemodeSnapshot(event.partialResult)
      : event.partialResult;
    return {
      type: "tool_execution_update",
      toolCallId: event.toolCallId,
      toolName: event.toolName,
      partialResult: options.toolUpdates === "tail"
        ? tailToolOutput(slimmed)
        : slimmed,
    };
  }
  return {
    type: "tool_execution_end",
    toolCallId: event.toolCallId,
    toolName: event.toolName,
    isError: event.isError,
    ...(nested ? { parentToolCallId: event.parentToolCallId } : {}),
  };
}

function toolCallMetadata(
  event: Record<string, unknown>,
): { id: string; toolName: string } | null {
  if (
    (event.type !== "toolcall_start" && event.type !== "toolcall_delta")
    || !isObject(event.partial)
  ) return null;
  const content = event.partial.content;
  const contentIndex = event.contentIndex;
  if (!Array.isArray(content) || typeof contentIndex !== "number") return null;

  const block = content[contentIndex];
  if (!isObject(block) || block.type !== "toolCall") return null;
  const id = typeof block.id === "string"
    ? block.id
    : (typeof block.toolCallId === "string" ? block.toolCallId : null);
  const toolName = typeof block.name === "string"
    ? block.name
    : (typeof block.toolName === "string" ? block.toolName : null);
  return id !== null && toolName !== null ? { id, toolName } : null;
}

/**
 * The last TAIL_MAX_LINES lines of `text`, at most TAIL_MAX_CHARS long and starting at a line
 * start; text within both limits comes back unchanged. A single line longer than the limit
 * keeps its end.
 */
function tailText(text: string): string {
  let tail = text;
  if (text.length > TAIL_MAX_CHARS) {
    const cut = text.length - TAIL_MAX_CHARS;
    const lineStart = text[cut - 1] === "\n" ? cut : text.indexOf("\n", cut) + 1;
    tail = text.slice(lineStart > 0 && lineStart < text.length ? lineStart : cut);
  }
  // A trailing newline ends the last line rather than starting an empty one.
  let start = tail.endsWith("\n") ? tail.length - 1 : tail.length;
  for (let line = 0; line < TAIL_MAX_LINES; line++) {
    if (start <= 0) return tail;
    start = tail.lastIndexOf("\n", start - 1);
    if (start < 0) return tail;
  }
  return tail.slice(start + 1);
}

function tailToolOutput(partialResult: unknown): unknown {
  if (!isObject(partialResult) || !Array.isArray(partialResult.content)) return partialResult;
  return {
    ...partialResult,
    content: partialResult.content.map((block) => (
      isObject(block) && block.type === "text" && typeof block.text === "string"
        ? { ...block, text: tailText(block.text) }
        : block
    )),
  };
}

/** A `message_start` / `message_end` for a transcript system message (prompt and tool loadout). */
export function isSystemMessageEvent(event: AgentEventLike): boolean {
  return (event.type === "message_start" || event.type === "message_end")
    && isObject(event.message)
    && event.message.role === "system";
}

/** Apply pi-web's event filters plus Pi 0.84's message_update projection. */
export function toClientAgentEvent(
  event: AgentEventLike,
  options: ClientAgentEventOptions = {},
): AgentEventLike | ClientMessageUpdateEvent | null {
  if (OMITTED_EVENT_TYPES.has(event.type)) return null;
  // Pi >= 0.86 appends the prompt and tool loadout to the transcript as system
  // messages, which the agent loop announces like any message. They carry the
  // whole prompt plus every tool schema and are never rendered, so drop them
  // before they cost the browser bandwidth.
  if (isSystemMessageEvent(event)) return null;

  if (event.type === "message_update") {
    const assistantMessageEvent = event.assistantMessageEvent;
    if (
      typeof assistantMessageEvent !== "object"
      || assistantMessageEvent === null
      || Array.isArray(assistantMessageEvent)
    ) return null;

    if (!("partial" in assistantMessageEvent)) {
      return {
        type: "message_update",
        assistantMessageEvent,
      } as ClientMessageUpdateEvent;
    }

    const metadata = toolCallMetadata(assistantMessageEvent as Record<string, unknown>);
    const { partial: _partial, ...deltaEvent } = assistantMessageEvent;
    void _partial;
    return {
      type: "message_update",
      assistantMessageEvent: metadata ? { ...deltaEvent, ...metadata } : deltaEvent,
    } as ClientMessageUpdateEvent;
  }

  if (
    event.type === "tool_execution_start"
    || event.type === "tool_execution_update"
    || event.type === "tool_execution_end"
  ) return toClientToolExecutionEvent(event, options);

  if (event.type === "agent_end") return { type: "agent_end" };
  return event;
}

export function isEventIncludedInSnapshot(
  event: AgentEventLike,
  snapshot: unknown,
): boolean {
  return snapshot !== undefined
    && (event.type === "message_start" || event.type === "message_update")
    && event.message === snapshot;
}
