import { displayStatus } from "./reconnectPolicy";

/** Fixed codes returned by the desktop `connect_wireguard` command. */
export const CONNECT_STEALTH_UNAVAILABLE = "connect.stealth_unavailable";
export const CONNECT_INVALID_CONFIG = "connect.invalid_config";

export const STEALTH_UNAVAILABLE_MESSAGE =
  "Stealth isn't available on this server right now. Try again later or choose UDP only.";
export const INVALID_CONFIG_MESSAGE =
  "We couldn't set up the VPN with this server's settings. Try again in a moment.";

/**
 * Validation strings from `WgTunnelConfig::validate` and key decoding. They
 * should only arrive as fixed codes, but older backends or other paths may
 * still pass them through, so never show them verbatim.
 */
function isRawValidationError(lower: string): boolean {
  return (
    /^invalid (key|dns address|peer id|address|allowed ip|endpoint|lan endpoint|wan endpoint|stealth endpoint|stealth path prefix)\b/.test(lower) ||
    /must be cidr notation|must include a port|too many allowed ip ranges|wireguard key must be 32 bytes/.test(lower) ||
    /path prefix/.test(lower)
  );
}

function isStealthValidationError(lower: string): boolean {
  return /stealth (endpoint|path prefix)|path prefix/.test(lower);
}

export function formatConnectError(err: unknown, wantedStealth: boolean): string {
  let raw = "Connection failed";
  if (err instanceof Error) raw = err.message;
  else if (typeof err === "string") raw = err;
  else if (err && typeof err === "object" && "message" in err && typeof (err as { message: unknown }).message === "string") {
    raw = (err as { message: string }).message;
  }
  const trimmed = raw.trim();
  if (trimmed === CONNECT_STEALTH_UNAVAILABLE) return STEALTH_UNAVAILABLE_MESSAGE;
  if (trimmed === CONNECT_INVALID_CONFIG) {
    return wantedStealth ? STEALTH_UNAVAILABLE_MESSAGE : INVALID_CONFIG_MESSAGE;
  }
  const lower = trimmed.toLowerCase();
  if (isRawValidationError(lower)) {
    return wantedStealth || isStealthValidationError(lower) ? STEALTH_UNAVAILABLE_MESSAGE : INVALID_CONFIG_MESSAGE;
  }
  if (
    wantedStealth ||
    /stealth|wstunnel/.test(lower)
  ) {
    if (/wstunnel|stealth engine/.test(lower) && /missing|not found|not bundled/.test(lower)) {
      return "Stealth failed: wstunnel is not bundled in this build. Rebuild with the Linux stealth binary, or choose UDP only.";
    }
    if (/stealth engine missing/.test(lower)) {
      return "Stealth failed: wstunnel binary missing. Bundle it for Linux or choose UDP only.";
    }
    if (/linux desktop|linux only|available on linux/.test(lower)) {
      return "Stealth mode is Linux-only in this build. Choose UDP only to connect with Direct UDP.";
    }
    if (/failed to start stealth|stealth transport/.test(lower)) {
      return "Stealth transport failed to start. Check TLS endpoint reachability, or choose UDP only.";
    }
    if (/not available on the (server|vpn node)/.test(lower)) {
      return "Stealth is not available on the VPN node yet. Choose UDP only or try again later.";
    }
  }
  if (/firewall kill switch|kill switch/.test(lower)) {
    if (/nftables|iptables/.test(lower)) {
      return "Kill switch required: install nftables or iptables, then connect again. There is no off option.";
    }
    return "Kill switch could not be enabled. Connect was cancelled so traffic stays unprotected only while you fix it.";
  }
  return displayStatus(raw) || "Connection failed";
}
