import type { AgentMessage, AssistantMessage, ToolCallContent } from "./types";

function isObject(val: unknown): val is Record<string, unknown> {
  return typeof val === "object" && val !== null && !Array.isArray(val);
}

function streamingRawInput(block: Record<string, unknown>): string | undefined {
  if (typeof block.rawInput === "string") return block.rawInput;
  if (typeof block.partialJson === "string") return block.partialJson;
  if (typeof block.partialArgs === "string") return block.partialArgs;

  const customInput = isObject(block.customInput) ? block.customInput : null;
  const property = customInput && typeof customInput.property === "string"
    ? customInput.property
    : null;
  const args = isObject(block.arguments) ? block.arguments : null;
  return property && args && typeof args[property] === "string"
    ? args[property]
    : undefined;
}

function normalizeToolCallBlock(
  block: unknown,
  options: { includeStreamingRawInput?: boolean } = {},
): ToolCallContent | null {
  if (!isObject(block) || block.type !== "toolCall") return null;
  const normalized: ToolCallContent = {
    type: "toolCall",
    toolCallId: typeof block.toolCallId === "string" ? block.toolCallId : (typeof block.id === "string" ? block.id : ""),
    toolName: typeof block.toolName === "string" ? block.toolName : (typeof block.name === "string" ? block.name : ""),
    input: typeof block.input === "object" && block.input !== null && !Array.isArray(block.input)
      ? block.input as Record<string, unknown>
      : (typeof block.arguments === "object" && block.arguments !== null && !Array.isArray(block.arguments)
        ? block.arguments as Record<string, unknown>
        : {}),
  };
  const rawInput = options.includeStreamingRawInput ? streamingRawInput(block) : undefined;
  return rawInput === undefined ? normalized : { ...normalized, rawInput };
}

function normalizeAssistantToolCalls(
  msg: AgentMessage,
  options: { includeStreamingRawInput?: boolean } = {},
): AgentMessage {
  // Non-assistant roles (user, toolResult, bashExecution, custom) are returned
  // unchanged — only assistant messages go through tool-call field normalization.
  if (msg.role !== "assistant") return msg;
  const content = (msg as AssistantMessage).content;
  if (!Array.isArray(content)) return msg;
  const normalized = content.map((block) => {
    const result = normalizeToolCallBlock(block, options);
    return result ?? block;
  });
  return { ...msg, content: normalized } as AgentMessage;
}

export function normalizeToolCalls(msg: AgentMessage): AgentMessage {
  return normalizeAssistantToolCalls(msg);
}

/**
 * Structural equality for persisted session messages. Used to preserve object
 * identity across `loadSession()`/`loadContext()` re-fetches: when a message's
 * content is unchanged we keep the old reference so memoized message views
 * (which compare `prev.message === next.message`) skip re-rendering and
 * re-parsing markdown / re-highlighting code on every turn-end re-fetch.
 */
export function sameMessage(a: AgentMessage, b: AgentMessage): boolean {
  return deepEqualValue(a, b);
}

function deepEqualValue(a: unknown, b: unknown): boolean {
  if (a === b) return true;
  if (typeof a !== "object" || typeof b !== "object" || a === null || b === null) return a === b;
  if (Array.isArray(a) !== Array.isArray(b)) return false;
  const aArr = a as unknown[];
  const bArr = b as unknown[];
  if (aArr.length !== bArr.length) return false;
  for (let i = 0; i < aArr.length; i++) {
    if (!deepEqualValue(aArr[i], bArr[i])) return false;
  }
  const aObj = a as Record<string, unknown>;
  const bObj = b as Record<string, unknown>;
  const aKeys = Object.keys(aObj);
  if (aKeys.length !== Object.keys(bObj).length) return false;
  for (const key of aKeys) {
    if (!Object.prototype.hasOwnProperty.call(bObj, key)) return false;
    if (!deepEqualValue(aObj[key], bObj[key])) return false;
  }
  return true;
}

/**
 * Merge a freshly fetched message list against the current one, keeping the
 * old object reference whenever the message at the same index is unchanged.
 * Returns `prev` untouched when every message is referentially identical
 * (so a state update with it can bail out of re-rendering entirely).
 * Index matching is safe: any content mismatch falls back to the fresh
 * object, and a length mismatch falls back to a full replace.
 */
export function mergeMessageIdentities(
  prev: AgentMessage[],
  next: AgentMessage[],
): AgentMessage[] {
  if (prev.length === 0) return next;
  if (prev.length !== next.length) return next;
  let changed = false;
  const merged = next.map((message, i) => {
    const previous = prev[i];
    if (previous === message) return message;
    if (sameMessage(previous, message)) {
      changed = true;
      return previous;
    }
    changed = true;
    return message;
  });
  return changed ? merged : prev;
}

export function normalizeStreamingToolCalls(msg: AgentMessage): AgentMessage {
  return normalizeAssistantToolCalls(msg, { includeStreamingRawInput: true });
}
