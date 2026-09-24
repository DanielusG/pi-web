export interface RunStatusSnapshot {
  sessionListVersion: number;
  runningSessionIds: string[];
  completionNotificationSuppressedSessionIds: string[];
  waitingSessionIds: string[];
}

const CHECK_INTERVAL_MS = 1_000;
const HEARTBEAT_INTERVAL_MS = 30_000;

/** Sorted ids, so an unchanged state always serializes to the same string. */
function serialize(status: RunStatusSnapshot): string {
  return JSON.stringify({
    sessionListVersion: status.sessionListVersion,
    runningSessionIds: [...status.runningSessionIds].sort(),
    completionNotificationSuppressedSessionIds: [...status.completionNotificationSuppressedSessionIds].sort(),
    waitingSessionIds: [...status.waitingSessionIds].sort(),
  });
}

/**
 * Server-wide run state as SSE, for clients that must not poll (the native
 * app's notification service): the snapshot is sent on connect and then only
 * when it changes. Changes are detected by an in-memory check once a second,
 * so an idle server sends nothing but the heartbeat.
 */
export function createRunStatusStream(
  req: Request,
  readStatus: () => RunStatusSnapshot,
  { checkIntervalMs = CHECK_INTERVAL_MS, heartbeatIntervalMs = HEARTBEAT_INTERVAL_MS } = {},
): ReadableStream<Uint8Array> {
  let cleanup: (closeController: boolean) => void = () => {};

  return new ReadableStream<Uint8Array>({
    start(controller) {
      const encoder = new TextEncoder();
      let closed = false;
      let last: string | null = null;
      const timers: ReturnType<typeof setInterval>[] = [];
      const abortHandler = () => cleanup(true);

      cleanup = (closeController: boolean) => {
        if (closed) return;
        closed = true;
        timers.forEach(clearInterval);
        req.signal.removeEventListener("abort", abortHandler);
        if (closeController) {
          try { controller.close(); } catch { /* stream already closed */ }
        }
      };

      const enqueueText = (text: string) => {
        if (closed) return;
        try {
          controller.enqueue(encoder.encode(text));
        } catch {
          cleanup(false);
        }
      };

      const publishIfChanged = () => {
        let data: string;
        try {
          data = serialize(readStatus());
        } catch (error) {
          console.error("[pi-web] run status read failed:", error instanceof Error ? error.message : error);
          return;
        }
        if (data === last) return;
        last = data;
        enqueueText(`data: ${data}\n\n`);
      };

      if (req.signal.aborted) {
        cleanup(true);
        return;
      }
      req.signal.addEventListener("abort", abortHandler, { once: true });

      publishIfChanged();
      timers.push(setInterval(publishIfChanged, checkIntervalMs));
      timers.push(setInterval(() => enqueueText(":\n\n"), heartbeatIntervalMs));
    },
    cancel() {
      cleanup(false);
    },
  });
}
