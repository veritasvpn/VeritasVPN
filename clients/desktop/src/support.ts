/** Matches tauri.conf.json / Cargo.toml. Runtime getVersion() replaces this when Tauri is available. */
export const FALLBACK_APP_VERSION = "0.2.88";
export const CONTACT_EMAIL = "contact@veritasvpn.cloud";
export const CONTACT_SUBJECT = "VeritasVPN support";
export const SUPPORT_URL = "https://veritasvpn.cloud/support";
export const SUPPORT_CONNECT_URL = "https://veritasvpn.cloud/support#stealth";
export const PRIVACY_URL = "https://veritasvpn.cloud/privacy";
export const TERMS_URL = "https://veritasvpn.cloud/terms";

const ALLOWED_HTTPS = [
  SUPPORT_URL,
  PRIVACY_URL,
  TERMS_URL,
  "https://veritasvpn.cloud/contact",
];

const SECRET_MARKER = /private\s*key|preshared\s*key|public\s*key|authorization|bearer\s|token\s*=|password\s*[:=]|secret\s*[:=]/i;
const WG_KEY = /[A-Za-z0-9+/]{42,44}=/g;
const ENDPOINT = /^(\[[0-9A-Fa-f:.]+\]|[A-Za-z0-9](?:[A-Za-z0-9.-]{0,253}[A-Za-z0-9])?|(?:\d{1,3}\.){3}\d{1,3}):(\d{1,5})$/;
const PROGRESS_STATUS = /^(restoring|connecting|reconnecting|disconnecting|creating secure|establishing)\b/i;
const VERSION_NAME = /^[0-9A-Za-z][0-9A-Za-z._+-]{0,31}$/;

export const DESKTOP_NOTICES = [
  { name: "Tauri", license: "Apache License 2.0 OR MIT License" },
  { name: "React", license: "MIT License" },
  { name: "WireGuard", license: "GNU General Public License v2" },
];

export type DiagnosticFields = {
  versionName: string;
  versionCode?: string;
  osVersion: string;
  device?: string;
  connected: boolean;
  connecting: boolean;
  handshakeEpochMs: number;
  nowMs: number;
  transport: string;
  lastError?: string;
  activeEndpoint?: string;
  wanEndpoint?: string;
  stealthEndpoint?: string;
};

export function isAllowedHttps(url: string): boolean {
  return ALLOWED_HTTPS.some((allowed) => url === allowed || url.startsWith(`${allowed}#`) || url.startsWith(`${allowed}?`));
}

export function isRecordableError(raw: string | null | undefined): boolean {
  const text = (raw ?? "").trim();
  if (!text) return false;
  return !PROGRESS_STATUS.test(text);
}

export function sanitizeError(raw: string | null | undefined): string {
  const trimmed = (raw ?? "").trim();
  if (!trimmed) return "none";
  let text = trimmed.replace(/\r\n/g, "\n").replace(/\r/g, "\n");
  if (text.length > 500) text = text.slice(0, 500);
  text = text.replace(WG_KEY, "[redacted]");
  text = text.replace(/(private\s*key|preshared\s*key|public\s*key)\s*=\s*\S+/gi, "$1=[redacted]");
  text = text.replace(/bearer\s+\S+/gi, "Bearer [redacted]");
  text = text.replace(/(authorization\s*[:=]\s*)\S+/gi, "$1[redacted]");
  text = text.replace(/(token|password|secret)\s*[:=]\s*\S+/gi, "$1=[redacted]");
  if (/\[interface\]|\[peer\]|privatekey|presharedkey/i.test(text)) {
    return "An error occurred (details redacted).";
  }
  const flat = text.replace(/\n/g, " ").trim();
  return flat || "none";
}

export function sanitizeEndpoint(raw: string | null | undefined): string | null {
  const trimmed = (raw ?? "").trim();
  if (!trimmed || trimmed.length > 120) return null;
  if (SECRET_MARKER.test(trimmed) || /[A-Za-z0-9+/]{42,44}=/.test(trimmed)) return null;
  if (/[\s/=\\]/.test(trimmed)) return null;
  const match = ENDPOINT.exec(trimmed);
  if (!match) return null;
  const port = Number(match[2]);
  if (!Number.isInteger(port) || port < 1 || port > 65535) return null;
  return trimmed;
}

export function isLoopbackEndpoint(endpoint: string): boolean {
  const host = endpoint.slice(0, endpoint.lastIndexOf(":")).trim().replace(/^\[/, "").replace(/\]$/, "").toLowerCase();
  return host === "localhost" || host === "127.0.0.1" || host === "0.0.0.0" || host === "::1" || host === "0:0:0:0:0:0:0:1" || host.startsWith("127.");
}

export function choosePublicEndpoint(active: string, wan: string, stealth: string, transport: string): string {
  const preferStealth = transport.toLowerCase() === "stealth" || transport.toLowerCase() === "switching";
  const ordered = preferStealth ? [stealth, wan, active] : [active, wan, stealth];
  for (const candidate of ordered) {
    const clean = sanitizeEndpoint(candidate);
    if (!clean || isLoopbackEndpoint(clean)) continue;
    return clean;
  }
  return "—";
}

export function formatHandshakeAge(handshakeEpochMs: number, nowMs: number): string {
  if (!handshakeEpochMs || handshakeEpochMs <= 0) return "—";
  const ageSec = Math.max(0, Math.floor((nowMs - handshakeEpochMs) / 1000));
  if (ageSec < 60) return `${ageSec}s ago`;
  if (ageSec < 3600) return `${Math.floor(ageSec / 60)}m ago`;
  return `${Math.floor(ageSec / 3600)}h ago`;
}

function sanitizeVersionName(raw: string): string {
  const trimmed = raw.trim();
  return VERSION_NAME.test(trimmed) ? trimmed : "unknown";
}

function sanitizePlainField(raw: string | undefined, maxLen = 80): string {
  const trimmed = (raw ?? "").trim().replace(/[\r\n]/g, " ");
  if (!trimmed) return "";
  if (SECRET_MARKER.test(trimmed) || /[A-Za-z0-9+/]{42,44}=/.test(trimmed)) return "";
  return trimmed.slice(0, maxLen);
}

export function formatDiagnosticReport(fields: DiagnosticFields): string {
  const versionName = sanitizeVersionName(fields.versionName);
  const versionCode = (fields.versionCode ?? "").trim();
  const code = versionCode && /^\d{1,12}$/.test(versionCode) ? ` (${versionCode})` : "";
  const device = sanitizePlainField(fields.device);
  const lines = [
    "VeritasVPN diagnostic report",
    `App: ${versionName}${code}`,
    `OS: ${sanitizePlainField(fields.osVersion) || "unknown"}`,
  ];
  if (device) lines.push(`Device: ${device}`);
  lines.push(
    `Connection: ${fields.connected ? "Connected" : fields.connecting ? "Connecting" : "Disconnected"}`,
    `Transport: ${transportLabel(fields.transport)}`,
    `Handshake: ${formatHandshakeAge(fields.handshakeEpochMs, fields.nowMs)}`,
    `Endpoint: ${choosePublicEndpoint(fields.activeEndpoint ?? "", fields.wanEndpoint ?? "", fields.stealthEndpoint ?? "", fields.transport)}`,
    `Last error: ${sanitizeError(fields.lastError)}`,
  );
  const text = lines.join("\n");
  if (SECRET_MARKER.test(text) || /[A-Za-z0-9+/]{42,44}=/.test(text) || text.includes("[Interface]")) {
    return "VeritasVPN diagnostic report\nLast error: details redacted";
  }
  return text;
}

function transportLabel(transport: string): string {
  switch (transport.trim().toLowerCase()) {
    case "udp":
      return "UDP";
    case "stealth":
      return "Stealth";
    case "switching":
      return "Switching to Stealth";
    default:
      return "—";
  }
}

export function formatSafeReportText(reportText: string): string {
  const text = reportText.trim();
  if (!text || SECRET_MARKER.test(text) || /[A-Za-z0-9+/]{42,44}=/.test(text)) {
    return "VeritasVPN diagnostic report\nLast error: details redacted";
  }
  return text;
}

export function contactBody(includeDiagnostics: boolean, reportText: string): string {
  const intro = "Hello VeritasVPN Support,\n\n";
  if (!includeDiagnostics) return intro;
  return `${intro}---\n${formatSafeReportText(reportText)}\n`;
}

export function mailtoUrl(includeDiagnostics: boolean, reportText: string): string {
  const body = contactBody(includeDiagnostics, reportText);
  const params = new URLSearchParams();
  params.set("subject", CONTACT_SUBJECT);
  params.set("body", body);
  return `mailto:${CONTACT_EMAIL}?${params.toString().replace(/\+/g, "%20")}`;
}

const LS_LAST_ERROR = "veritas_last_error";

export function readStoredLastError(): string {
  try {
    return sanitizeError(localStorage.getItem(LS_LAST_ERROR));
  } catch {
    return "none";
  }
}

export function writeStoredLastError(message: string) {
  const safe = sanitizeError(message);
  if (safe === "none") return;
  if (/privatekey|presharedkey|\[interface\]/i.test(safe)) return;
  try {
    localStorage.setItem(LS_LAST_ERROR, safe.slice(0, 300));
  } catch {
    // ignore quota / private mode
  }
}
