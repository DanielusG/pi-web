// Tool-aware display helpers for ToolCallBlock: per-tool one-line header
// summaries and per-tool expanded argument views, so the UI renders
// human-readable content (a command, a path, a prompt) instead of a raw
// JSON dump of the tool input.

export type ToolArgsView =
  | { kind: "pre"; label?: string; text: string }
  | { kind: "meta"; lines: string[] }
  | { kind: "json"; text: string };

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function firstString(...values: unknown[]): string | null {
  for (const v of values) {
    if (typeof v === "string" && v.length > 0) return v;
  }
  return null;
}

function firstLine(text: string): string {
  const line = text
    .split("\n")
    .map((l) => l.trim())
    .find((l) => l.length > 0);
  return line ?? "";
}

function clip(text: string, max: number): string {
  const t = text.trim();
  return t.length > max ? `${t.slice(0, max)}…` : t;
}

function plural(n: number, word: string): string {
  return n === 1 ? word : `${word}s`;
}

/**
 * One-line, human-readable summary of a tool call's arguments for the
 * collapsed header. Returns "" when nothing meaningful can be shown.
 */
export function toolSummaryLine(toolName: string, input: unknown): string {
  if (!isRecord(input)) return "";
  switch (toolName) {
    case "bash":
    case "run_command": {
      const cmd = firstString(input.command, input.cmd);
      return cmd ? clip(firstLine(cmd), 160) : "";
    }
    case "read": {
      const path = firstString(input.path);
      if (!path) return "";
      const details: string[] = [];
      if (typeof input.offset === "number") details.push(`from line ${input.offset}`);
      if (typeof input.limit === "number") details.push(`${input.limit} lines`);
      return details.length > 0 ? `${path} (${details.join(", ")})` : path;
    }
    case "write":
      return firstString(input.path) ?? "";
    case "edit": {
      const path = firstString(input.path);
      if (!path) return "";
      const n = Array.isArray(input.edits) ? input.edits.length : 0;
      return n > 0 ? `${path} · ${n} ${plural(n, "edit")}` : path;
    }
    case "grep":
    case "find": {
      const pattern = firstString(input.pattern);
      if (!pattern) return "";
      const path = firstString(input.path);
      return path ? `${pattern} in ${path}` : clip(pattern, 120);
    }
    case "ls":
      return firstString(input.path) ?? "";
    case "web_search": {
      const q = firstString(input.query);
      return q ? clip(q, 120) : "";
    }
    case "web_fetch":
      return firstString(input.url) ?? "";
    case "batch_web_fetch": {
      const n = Array.isArray(input.requests) ? input.requests.length : 0;
      return n > 0 ? `${n} ${plural(n, "url")}` : "";
    }
    case "Agent":
    case "agent":
    case "Task": {
      const d = firstString(input.description);
      return d ? clip(d, 120) : "";
    }
    case "ask_user_question": {
      const n = Array.isArray(input.questions) ? input.questions.length : 0;
      return n > 0 ? `${n} ${plural(n, "question")}` : "";
    }
    case "compress_tool": {
      const n = Array.isArray(input.tool_compressions) ? input.tool_compressions.length : 0;
      return n > 0 ? `compress ${n} ${plural(n, "result")}` : "";
    }
    case "steer_subagent":
    case "get_subagent_result":
      return firstString(input.agent_id) ?? "";
    default: {
      // Generic: prefer well-known fields, then the first string value.
      const preferred = firstString(
        input.command,
        input.path,
        input.pattern,
        input.query,
        input.url,
        input.description,
        input.agent_id,
      );
      if (preferred) return clip(preferred, 140);
      for (const value of Object.values(input)) {
        if (typeof value === "string" && value.trim().length > 0) {
          return clip(value, 140);
        }
      }
      return "";
    }
  }
}

/**
 * Describes how to render a tool call's arguments in the expanded body.
 * Falls back to pretty-printed JSON for unknown tools.
 */
export function getToolArgsView(toolName: string, input: unknown): ToolArgsView {
  if (!isRecord(input)) {
    return { kind: "json", text: JSON.stringify(input, null, 2) };
  }
  switch (toolName) {
    case "bash":
    case "run_command": {
      const cmd = firstString(input.command, input.cmd);
      if (cmd) return { kind: "pre", text: cmd };
      break;
    }
    case "write": {
      const path = firstString(input.path);
      const content = typeof input.content === "string" ? input.content : null;
      if (content !== null) {
        return { kind: "pre", label: path ?? undefined, text: content };
      }
      break;
    }
    case "read": {
      const path = firstString(input.path);
      if (path) {
        const lines = [path];
        const details: string[] = [];
        if (typeof input.offset === "number") details.push(`offset ${input.offset}`);
        if (typeof input.limit === "number") details.push(`limit ${input.limit}`);
        if (details.length > 0) lines.push(details.join(" · "));
        return { kind: "meta", lines };
      }
      break;
    }
    case "web_fetch": {
      const url = firstString(input.url);
      if (url) return { kind: "meta", lines: [url] };
      break;
    }
    case "Agent":
    case "agent":
    case "Task": {
      const prompt = typeof input.prompt === "string" ? input.prompt : null;
      const desc = firstString(input.description);
      if (prompt) return { kind: "pre", label: desc ?? undefined, text: prompt };
      break;
    }
    default:
      break;
  }
  return { kind: "json", text: JSON.stringify(input, null, 2) };
}
