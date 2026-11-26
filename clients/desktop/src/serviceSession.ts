// A session the Windows service already runs when this window opens: after a
// reinstall, a crash of the window, or the service's boot resume. The window
// adopts it and shows Connected, like Android reattaching to its VPN service.

export type AttachedSession = {
  active: boolean;
  peer_id: string;
  stealth: boolean;
  dns: string;
};

export type AdoptGate = {
  os: string;
  signedIn: boolean;
  connected: boolean;
  connecting: boolean;
  disconnecting: boolean;
  restoring: boolean;
};

/** How often a window that shows Not connected asks the service again. */
export const ADOPT_POLL_MS = 5_000;

export function canAskService(gate: AdoptGate): boolean {
  return (
    gate.os === "windows" &&
    gate.signedIn &&
    !gate.connected &&
    !gate.connecting &&
    !gate.disconnecting &&
    !gate.restoring
  );
}

export function shouldAdoptSession(session: AttachedSession | null | undefined, gate: AdoptGate): boolean {
  if (!session || !session.active || !canAskService(gate)) return false;
  return session.peer_id.trim() !== "" && session.dns.trim() !== "";
}

export function adoptedTransport(session: AttachedSession): "stealth" | "udp" {
  return session.stealth ? "stealth" : "udp";
}

/**
 * A Connect from Not connected first ends anything the service still runs.
 * Registering the new key revokes the old one on the server, and the old
 * adapter would otherwise keep every route while the new connect waits.
 */
export function releaseBeforeConnect(os: string, connected: boolean): boolean {
  return os === "windows" && !connected;
}

/** Polls in a row (1.5 s apart) before a lost service session ends Protected. */
export const SESSION_GONE_POLLS = 2;

export type ReconcileAction = "keep" | "leftover" | "disconnected";

export const SESSION_ENDED_BLOCKED =
  "The VPN connection ended. Your internet stays blocked until you tap Restore internet.";
export const SESSION_ENDED = "The VPN connection ended. Connect again when you're ready.";

/**
 * While the window shows Protected or Reconnecting, check it against the
 * service. When the service was restarted without its saved session there is
 * no tunnel: with the kill switch still armed the window shows the leftover
 * banner with Restore internet, otherwise Not connected. It never reconnects
 * or changes mode by itself.
 */
export function reconcileServiceSession(input: {
  os: string;
  sessionGone: boolean;
  killSwitchArmed: boolean;
  goneStreak: number;
}): { action: ReconcileAction; streak: number } {
  if (input.os !== "windows" || !input.sessionGone) return { action: "keep", streak: 0 };
  const streak = input.goneStreak + 1;
  if (streak < SESSION_GONE_POLLS) return { action: "keep", streak };
  return { action: input.killSwitchArmed ? "leftover" : "disconnected", streak };
}

export function sessionEndedMessage(action: ReconcileAction): string {
  if (action === "leftover") return SESSION_ENDED_BLOCKED;
  if (action === "disconnected") return SESSION_ENDED;
  return "";
}

/** After Restore internet: drop the "stays blocked" line once the lock is gone. */
export function statusAfterRestore(cleared: boolean): string {
  return cleared ? SESSION_ENDED : SESSION_ENDED_BLOCKED;
}
