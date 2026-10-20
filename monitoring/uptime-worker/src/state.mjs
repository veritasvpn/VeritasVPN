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

// A short ClientHello. The Stealth listener uses a self-signed certificate, so
// the worker never verifies it. TLS 1.3 servers that want a key share answer
// with HelloRetryRequest, which is still a handshake record (first byte 0x16).
export function tlsClientHello() {
  const random = new Uint8Array(32);
  for (let i = 0; i < random.length; i++) random[i] = i + 1;
  const ciphers = [0x13, 0x01, 0x13, 0x02, 0xc0, 0x2f, 0xc0, 0x2b];
  // RFC 7748 X25519 public key. The private key is not needed: the probe
  // stops at the server's first handshake record.
  const x25519 = [
    0x85, 0x20, 0xf0, 0x09, 0x89, 0x30, 0xa7, 0x54, 0x74, 0x8b, 0x7d, 0xdc, 0xb4, 0x3e, 0xf7, 0x5a,
    0x0d, 0xbf, 0x3a, 0x0d, 0x26, 0x38, 0x1a, 0xf4, 0xeb, 0xa4, 0xa9, 0x8e, 0xaa, 0x9b, 0x4e, 0x6a,
  ];
  const extensions = concatBytes(
    tlsExtension(0x002b, [0x04, 0x03, 0x04, 0x03, 0x03]),
    tlsExtension(0x000a, [0x00, 0x04, 0x00, 0x1d, 0x00, 0x17]),
    tlsExtension(0x000d, [0x00, 0x06, 0x04, 0x03, 0x08, 0x04, 0x04, 0x01]),
    tlsExtension(0x0033, [...u16(2 + 2 + x25519.length), ...u16(0x001d), ...u16(x25519.length), ...x25519]),
  );
  const body = concatBytes(
    [0x03, 0x03],
    random,
    [0x00],
    [0x00, ciphers.length],
    ciphers,
    [0x01, 0x00],
    u16(extensions.length),
    extensions,
  );
  const handshake = concatBytes([0x01], u24(body.length), body);
  return concatBytes([0x16, 0x03, 0x01], u16(handshake.length), handshake);
}

export function isTlsHandshakeRecord(bytes) {
  return bytes != null && bytes.length > 0 && bytes[0] === 0x16;
}

function tlsExtension(type, data) {
  return concatBytes(u16(type), u16(data.length), data);
}

function u16(value) {
  return [(value >> 8) & 0xff, value & 0xff];
}

function u24(value) {
  return [(value >> 16) & 0xff, (value >> 8) & 0xff, value & 0xff];
}

function concatBytes(...parts) {
  const chunks = parts.map((part) => (part instanceof Uint8Array ? part : Uint8Array.from(part)));
  const out = new Uint8Array(chunks.reduce((sum, chunk) => sum + chunk.length, 0));
  let offset = 0;
  for (const chunk of chunks) {
    out.set(chunk, offset);
    offset += chunk.length;
  }
  return out;
}

export function egressHealthy(body, nowMs) {
  if (!body || typeof body !== "object" || body.ok !== true) return false;
  const raw = body.checked_at ?? body.timestamp ?? body.updated_at;
  if (raw == null || raw === "") return true;
  const parsed = Date.parse(String(raw));
  if (Number.isNaN(parsed)) return false;
  return nowMs - parsed <= EGRESS_STALE_MS;
}
