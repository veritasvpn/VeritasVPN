/** What this desktop build can actually do. Linux keeps every capability it has today. */

export type DesktopOs = "linux" | "windows" | "macos" | "other";

export type PlatformCapabilities = {
  os: DesktopOs;
  /** Direct-mode tunnel. Stealth and the kill switch are separate. */
  tunnel: boolean;
  /** Stealth (wstunnel) can be chosen. */
  stealth: boolean;
  /** Kill switch, recovery, and the long elevated connect watchdog. */
  killSwitch: boolean;
};

export function desktopOs(userAgent: string): DesktopOs {
  const ua = userAgent.toLowerCase();
  if (ua.includes("android")) return "other";
  if (ua.includes("windows")) return "windows";
  if (ua.includes("mac os") || ua.includes("macintosh")) return "macos";
  if (ua.includes("linux")) return "linux";
  return "other";
}

export function platformCapabilities(userAgent?: string): PlatformCapabilities {
  const ua = userAgent ?? (typeof navigator === "undefined" ? "" : navigator.userAgent);
  const os = desktopOs(ua);
  if (os === "linux") {
    return { os, tunnel: true, stealth: true, killSwitch: true };
  }
  if (os === "windows") {
    return { os, tunnel: true, stealth: false, killSwitch: true };
  }
  return { os, tunnel: false, stealth: false, killSwitch: false };
}
