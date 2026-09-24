import { createRunStatusStream } from "@/lib/run-status-stream";
import { getSessionListVersion } from "@/lib/session-reader";
import {
  getCompletionNotificationSuppressedRpcSessionIds,
  getRunningRpcSessionIds,
  getWaitingRpcSessionIds,
} from "@/lib/rpc-manager";

export const dynamic = "force-dynamic";

// GET /api/agent/running/events - SSE of /api/agent/running plus waitingSessionIds,
// sent on connect and on every change. Used by the native Android client instead of polling.
export async function GET(req: Request) {
  if (req.signal.aborted) return new Response(null, { status: 204 });

  const stream = createRunStatusStream(req, () => ({
    sessionListVersion: getSessionListVersion(),
    runningSessionIds: getRunningRpcSessionIds(),
    completionNotificationSuppressedSessionIds: getCompletionNotificationSuppressedRpcSessionIds(),
    waitingSessionIds: getWaitingRpcSessionIds(),
  }));

  return new Response(stream, {
    headers: {
      "Content-Type": "text/event-stream",
      "Cache-Control": "no-cache, no-transform",
      Connection: "keep-alive",
      "X-Accel-Buffering": "no",
    },
  });
}
