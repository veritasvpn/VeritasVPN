/** Mirrors Android's peer-check outcome. Only a real 404 or 410 is peer-gone. */
export type PeerCheckKind = "alive" | "gone" | "network" | "refresh";

export function classifyPeerCheck(status: number | null | undefined, networkError = false): PeerCheckKind {
  if (status === 200) return "alive";
  if (status === 401) return "refresh";
  if (status === 404 || status === 410) return "gone";
  if (networkError || status == null || status === 0) return "network";
  return "network";
}

/** One create while a reconnect is already running. Anything except peer-gone waits. */
export function peerGoneAction(peerGone: boolean, inFlight: boolean): "create" | "in_flight" | "ignore" {
  if (!peerGone) return "ignore";
  if (inFlight) return "in_flight";
  return "create";
}

const GIVE_UP = "Couldn't reconnect on this network. Check your connection and try again.";

/**
 * Raw timeout and exception text never reach the status line.
 * A sentence that already tells the user what to do is left as written.
 */
export function displayStatus(raw: string | null | undefined): string {
  if (raw == null) return "";
  const text = raw.trim();
  if (!text) return "";
  if (isRawTransportError(text)) return GIVE_UP;
  return text;
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
