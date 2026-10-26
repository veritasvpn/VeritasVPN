/** Mirrors Android's peer-check outcome. Only a real 404 or 410 is peer-gone. */
export type PeerCheckKind = "alive" | "gone" | "network" | "refresh";

export function classifyPeerCheck(status: number | null | undefined, networkError = false): PeerCheckKind {
  if (status === 200) return "alive";
  if (status === 401) return "refresh";
  if (status === 404 || status === 410) return "gone";
  if (networkError || status == null || status === 0) return "network";
  return "network";
}

/** "Reconnecting…" keeps the session. "Tap to reconnect" means auto-reconnect gave up. */
export function underlayNoticeKeepsSession(notice: string | null | undefined): boolean {
  return /^reconnecting\b/i.test((notice ?? "").trim());
}

/** Recovery opens the API exception on every try. Stop after this many. */
export const RECOVERY_MAX_ATTEMPTS = 3;

/** `attempt` is the zero-based index of the try that just failed. */
export function recoveryRetryAction(attempt: number): "retry" | "stop" {
  if (!Number.isFinite(attempt) || attempt < 0) return "stop";
  if (attempt + 1 >= RECOVERY_MAX_ATTEMPTS) return "stop";
  return "retry";
}

/**
 * The latch stays until this peer answers 200 or the user connects.
 * A newer handshake does not release it: a recreated peer that still 404s
 * must not be created again.
 */
export function peerGoneLatchHeld(latched: boolean, peerAlive: boolean, userConnect: boolean): boolean {
  if (!latched || userConnect || peerAlive) return false;
  return true;
}

/**
 * One automatic recreate per connection. Anything except a real peer-gone waits.
 */
export function peerGoneAction(
  peerGone: boolean,
  inFlight: boolean,
  latched: boolean,
): "create" | "in_flight" | "latched" | "ignore" {
  if (!peerGone) return "ignore";
  if (latched) return "latched";
  if (inFlight) return "in_flight";
  return "create";
}

const GIVE_UP = "Couldn't reconnect on this network. Check your connection and try again.";
const TUNNEL_RETRY = "Couldn't start the tunnel. Try connecting again.";

/**
 * The control plane returns a bare host when it reuses this device's peer,
 * and a prefixed address on a fresh allocation. The tunnel needs the prefix.
 */
export function tunnelAddress(raw: string | null | undefined): string {
  const value = (raw ?? "").trim();
  if (!value || value.includes("/")) return value;
  if (isIpv4Host(value)) return `${value}/32`;
  if (isIpv6Host(value)) return `${value}/128`;
  return value;
}

function isIpv4Host(value: string): boolean {
  const parts = value.split(".");
  if (parts.length !== 4) return false;
  return parts.every((part) => {
    if (!/^\d{1,3}$/.test(part)) return false;
    const n = Number(part);
    return n <= 255;
  });
}

function isIpv6Host(value: string): boolean {
  if (!value.includes(":") || value.includes("/")) return false;
  if (!/^[0-9A-Fa-f:]+$/.test(value)) return false;
  const halves = value.split("::");
  if (halves.length > 2) return false;
  const groups = value.split(":").filter((part) => part.length > 0);
  return groups.length >= 1 && groups.length <= 8 && groups.every((part) => part.length <= 4);
}

/**
 * Raw timeout and exception text never reach the status line.
 * A sentence that already tells the user what to do is left as written.
 */
export function displayStatus(raw: string | null | undefined): string {
  if (raw == null) return "";
  const text = raw.trim();
  if (!text) return "";
  if (isRawTransportError(text)) return GIVE_UP;
  if (isRawAddressError(text)) return TUNNEL_RETRY;
  return text;
}

function isRawAddressError(text: string): boolean {
  const lower = text.toLowerCase();
  if (lower.includes("cidr notation")) return true;
  if (lower.includes("prefix is expected")) return true;
  if (lower === "invalid address" || lower === "invalid address prefix") return true;
  if (lower.startsWith("invalid allowed ip")) return true;
  if (lower === "missing assigned address") return true;
  return false;
}

function isRawTransportError(text: string): boolean {
  const lower = text.toLowerCase();
  if (lower === "timeout" || lower === "timed out") return true;
  if (lower.includes("aborterror")) return true;
  if (lower.includes("etimedout")) return true;
  if (lower.includes("error sending request")) return true;
  if (lower.includes("os error")) return true;
  if (lower.includes("exception")) return true;
  if (lower.includes("timed out") && !/try again|check your/i.test(text)) return true;
  return false;
}
