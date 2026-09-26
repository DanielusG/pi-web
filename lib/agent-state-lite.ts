/**
 * `GET /api/agent/[id]?lite=1`, a fork addition for the Android client: the live state
 * without `systemPrompt`. The prompt carries every context file (43 KB on this repository),
 * the app never reads it, and it asks for the state around every turn of a run.
 */
export function liteAgentState(state: unknown): unknown {
  if (typeof state !== "object" || state === null || Array.isArray(state)) return state;
  const { systemPrompt: _systemPrompt, ...lite } = state as Record<string, unknown>;
  void _systemPrompt;
  return lite;
}
