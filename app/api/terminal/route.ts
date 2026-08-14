import { NextResponse } from "next/server";
import { getWettyAvailability } from "@/lib/wetty-manager";

// GET /api/terminal → { enabled, reason? }
export async function GET() {
  const status = getWettyAvailability();
  return NextResponse.json({
    enabled: status.available,
    reason: status.reason ?? null,
  });
}
