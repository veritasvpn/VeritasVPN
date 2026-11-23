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
