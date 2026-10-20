export const FAIL_THRESHOLD = 2;
export const REPEAT_MS = 4 * 60 * 60 * 1000;
export const EGRESS_STALE_MS = 15 * 60 * 1000;
export const CHECK_TIMEOUT_MS = 8_000;

export const CHECKS = ["website", "readyz", "billing", "egress", "stealth"];

const NAMES = {
  website: "the website",
  readyz: "the API",
  billing: "billing",
  egress: "the VPN egress check",
  stealth: "Stealth",
};

export function emptyCheck() {
  return { consecutiveFailures: 0, alerted: false, lastAlertMs: 0 };
}

export function stepCheck(prev, ok, nowMs) {
  const state = {
    consecutiveFailures: Number(prev?.consecutiveFailures) || 0,
    alerted: Boolean(prev?.alerted),
    lastAlertMs: Number(prev?.lastAlertMs) || 0,
  };
  if (ok) {
    return {
      next: emptyCheck(),
      send: state.alerted ? "recovery" : null,
    };
  }
  const failures = state.consecutiveFailures + 1;
  const firstAlert = failures >= FAIL_THRESHOLD && !state.alerted;
  const dueRepeat = state.alerted && nowMs - state.lastAlertMs >= REPEAT_MS;
  const send = firstAlert || dueRepeat;
  return {
    next: {
      consecutiveFailures: failures,
      alerted: state.alerted || send,
      lastAlertMs: send ? nowMs : state.lastAlertMs,
    },
    send: send ? (firstAlert ? "alert" : "repeat") : null,
  };
}

export function messageFor(check, kind) {
  const name = NAMES[check] || "a check";
  const label = name.charAt(0).toUpperCase() + name.slice(1);
  if (kind === "recovery") return `VeritasVPN is back. ${label} is working again.`;
  if (kind === "repeat") return `VeritasVPN is still unreachable. ${label} has been failing for several hours.`;
  return `VeritasVPN may be down. ${label} failed twice in a row.`;
}

export function egressHealthy(body, nowMs) {
  if (!body || typeof body !== "object" || body.ok !== true) return false;
  const raw = body.checked_at ?? body.timestamp ?? body.updated_at;
  if (raw == null || raw === "") return true;
  const parsed = Date.parse(String(raw));
  if (Number.isNaN(parsed)) return false;
  return nowMs - parsed <= EGRESS_STALE_MS;
}
