import {
  buildSessionContext,
  calculateContextTokens,
  createAgentSessionServices,
  estimateTokens,
  getAgentDir,
  getLatestCompactionEntry,
  type ModelRuntime,
} from "@earendil-works/pi-coding-agent";
import { projectTrustReloadOptions } from "./project-trust";
import { sliceActiveBranch } from "./session-reader";
import type { SessionEntry } from "./types";

export interface FileContextUsage {
  tokens: number | null;
  contextWindow: number | null;
  percent: number | null;
}

const SERVICE_TTL_MS = 60_000;
const MAX_SERVICE_CACHE_ENTRIES = 32;

interface ServiceCacheEntry {
  runtime: ModelRuntime;
  expiresAt: number;
}

interface ServiceCacheState {
  entries: Map<string, ServiceCacheEntry>;
  inFlight: Map<string, Promise<ModelRuntime | undefined>>;
}

declare global {
  var __piFileContextServices: ServiceCacheState | undefined;
}

function getServiceCacheState(): ServiceCacheState {
  if (!globalThis.__piFileContextServices) {
    globalThis.__piFileContextServices = { entries: new Map(), inFlight: new Map() };
  }
  return globalThis.__piFileContextServices;
}

/**
 * ModelRuntime with extension-registered providers (e.g. litellm) — the same
 * service stack a live AgentSession is built from, so cold and live resolve
 * the same context windows. Extensions are project-scoped, hence per-cwd
 * cache with in-flight dedupe (same discipline as lib/models-cache.ts).
 * Untrusted project extensions are gated like the models route (#236).
 */
async function getModelRuntimeForCwd(cwd: string): Promise<ModelRuntime | undefined> {
  const state = getServiceCacheState();
  const cached = state.entries.get(cwd);
  if (cached && cached.expiresAt > Date.now()) return cached.runtime;
  const inFlight = state.inFlight.get(cwd);
  if (inFlight) return inFlight;

  const agentDir = getAgentDir();
  const load = (async () => {
    try {
      const trustReloadOptions = projectTrustReloadOptions(cwd, agentDir);
      const services = await createAgentSessionServices({
        cwd,
        agentDir,
        ...(trustReloadOptions ? { resourceLoaderReloadOptions: trustReloadOptions } : {}),
      });
      return services.modelRuntime;
    } catch {
      return undefined;
    } finally {
      state.inFlight.delete(cwd);
    }
  })();
  state.inFlight.set(cwd, load);
  const runtime = await load;
  if (runtime) {
    const now = Date.now();
    for (const [key, entry] of state.entries) {
      if (entry.expiresAt <= now) state.entries.delete(key);
    }
    while (state.entries.size >= MAX_SERVICE_CACHE_ENTRIES) {
      const oldest = state.entries.keys().next().value;
      if (oldest === undefined) break;
      state.entries.delete(oldest);
    }
    state.entries.set(cwd, { runtime, expiresAt: now + SERVICE_TTL_MS });
  }
  return runtime;
}

/**
 * Replicates the SDK's estimateContextTokens() (not exported from the package
 * index): the exact provider usage of the last valid assistant message plus a
 * chars/4 estimate for the trailing messages after it.
 */
function estimateContextTokensTotal(messages: unknown[]): number {
  let usageIndex = -1;
  let usageTokens = 0;
  for (let i = messages.length - 1; i >= 0; i--) {
    const msg = messages[i] as { role?: string; stopReason?: string; usage?: unknown };
    if (
      msg.role === "assistant" &&
      msg.stopReason !== "aborted" &&
      msg.stopReason !== "error" &&
      msg.usage &&
      calculateContextTokens(msg.usage as never) > 0
    ) {
      usageIndex = i;
      usageTokens = calculateContextTokens(msg.usage as never);
      break;
    }
  }
  if (usageIndex === -1) {
    let estimated = 0;
    for (const message of messages) estimated += estimateTokens(message as never);
    return estimated;
  }
  let trailingTokens = 0;
  for (let i = usageIndex + 1; i < messages.length; i++) {
    trailingTokens += estimateTokens(messages[i] as never);
  }
  return usageTokens + trailingTokens;
}

/**
 * Compute context usage from session-file entries, replicating the semantics
 * of AgentSession.getContextUsage(): the exact provider usage of the last
 * valid assistant message on the active branch, plus a chars/4 estimate for
 * any trailing messages after it. When the branch's latest compaction has no
 * assistant usage after it, the context size is unknown until the next LLM
 * response (tokens: null) — the same guard the runtime applies.
 *
 * `cwd` is the session's working directory: model resolution must load the
 * same project-scoped extension providers the live session sees.
 *
 * Accepts SDK or local entry shapes (they are structurally close enough for
 * the parentId walk and the usage lookups); SDK calls get `as never` casts,
 * the existing convention in this codebase.
 */
export async function computeFileContextUsage(
  entries: unknown[],
  leafId: string | null | undefined,
  cwd: string,
): Promise<FileContextUsage> {
  const context = buildSessionContext(entries as never, leafId ?? undefined);
  const resolvedWindow = context.model
    ? (await getModelRuntimeForCwd(cwd))?.getModel(context.model.provider, context.model.modelId)?.contextWindow
    : undefined;
  const contextWindow = resolvedWindow && resolvedWindow > 0 ? resolvedWindow : null;

  const localEntries = entries as unknown as SessionEntry[];
  const branch = leafId === null ? [] : sliceActiveBranch(localEntries, leafId ?? null, entries.length);
  const latestCompaction = getLatestCompactionEntry(branch as never);
  if (latestCompaction) {
    const compactionIndex = branch.lastIndexOf(latestCompaction);
    let hasPostCompactionUsage = false;
    for (let i = branch.length - 1; i > compactionIndex; i--) {
      const entry = branch[i];
      if (entry.type === "message" && entry.message.role === "assistant") {
        const assistant = entry.message;
        if (
          assistant.stopReason !== "aborted" &&
          assistant.stopReason !== "error" &&
          calculateContextTokens(assistant.usage as never) > 0
        ) {
          hasPostCompactionUsage = true;
          break;
        }
      }
    }
    if (!hasPostCompactionUsage) {
      return { tokens: null, contextWindow, percent: null };
    }
  }

  const tokens = estimateContextTokensTotal(context.messages);
  return {
    tokens,
    contextWindow,
    percent: contextWindow ? (tokens / contextWindow) * 100 : null,
  };
}
