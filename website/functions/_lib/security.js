/**
 * Shared security helpers for Cloudflare Pages Functions.
 * Same-origin only — do not set Access-Control-Allow-Origin.
 */

export const securityHeaders = {
  "X-Content-Type-Options": "nosniff",
  "X-Frame-Options": "DENY",
  "Referrer-Policy": "no-referrer",
  "Permissions-Policy": "geolocation=(), microphone=(), camera=(), payment=()",
  "Cross-Origin-Opener-Policy": "same-origin",
  "Cross-Origin-Resource-Policy": "same-origin",
  "X-Permitted-Cross-Domain-Policies": "none",
  "Strict-Transport-Security": "max-age=63072000; includeSubDomains; preload",
};

export function withSecurityHeaders(extra = {}) {
  return { ...securityHeaders, ...extra };
}

export function jsonResponse(data, status = 200, extraHeaders = {}) {
  return new Response(JSON.stringify(data), {
    status,
    headers: withSecurityHeaders({
      "Content-Type": "application/json; charset=utf-8",
      "Cache-Control": "no-store",
      ...extraHeaders,
    }),
  });
}

export function textResponse(text, status = 200, extraHeaders = {}) {
  return new Response(text, {
    status,
    headers: withSecurityHeaders({
      "Content-Type": "text/plain; charset=utf-8",
      ...extraHeaders,
    }),
  });
}

export function clientIP(request) {
  return (request.headers.get("cf-connecting-ip") || "").trim();
}

/**
 * Reject browser cross-origin calls. Requests with no Origin (same-origin
 * navigations, curl, server-to-server) are allowed; mismatched Origin → 403.
 */
export function rejectForeignOrigin(request) {
  const origin = (request.headers.get("Origin") || "").trim();
  if (!origin) return null;
  try {
    const reqOrigin = new URL(request.url).origin;
    if (origin === reqOrigin) return null;
  } catch {
    /* fall through */
  }
  return jsonResponse({ error: "Cross-origin requests are not allowed" }, 403);
}

/** Atomic Redis quotas. Only this authenticated hop may forward a visitor IP. */
export async function rateLimit(request, env, { bucket } = {}) {
  const ip = clientIP(request);
  const secret = env?.TOOLS_RATE_LIMIT_SECRET || "";
  const unavailable = () => jsonResponse({ error: "Rate limit unavailable. Try again shortly." }, 503, { "Retry-After": "60" });
  if (secret.length < 32 || !ip || !["check-ip", "check-dns-session", "check-breach"].includes(bucket)) return unavailable();
  try {
    const timestamp = String(Math.floor(Date.now() / 1000));
    const encoder = new TextEncoder();
    const key = await crypto.subtle.importKey("raw", encoder.encode(secret), { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
    const bytes = await crypto.subtle.sign("HMAC", key, encoder.encode(`${timestamp}\n${bucket}\n${ip}`));
    const signature = [...new Uint8Array(bytes)].map(b => b.toString(16).padStart(2, "0")).join("");
    const response = await fetch("https://api.veritasvpn.cloud/api/v1/auth/tool-limit", {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "User-Agent": "VeritasVPN-CheckTools/1.0",
        "X-Tool-Timestamp": timestamp,
        "X-Tool-Signature": signature,
      },
      body: JSON.stringify({ bucket, ip }),
      signal: AbortSignal.timeout(4000),
      redirect: "error",
    });
    await response.body?.cancel();
    if (response.status === 204) return null;
    if (response.status === 429) return jsonResponse({ error: "Too many requests. Try again shortly." }, 429, { "Retry-After": "60" });
    return unavailable();
  } catch {
    return unavailable();
  }
}

export async function boundedJSON(message, maxBytes = 4096) {
  if (!message.body) throw new Error("Missing body");
  const reader = message.body.getReader();
  let total = 0, text = "";
  const decoder = new TextDecoder();
  try {
    while (true) {
      const { value, done } = await reader.read();
      if (done) break;
      total += value.byteLength;
      if (total > maxBytes) { await reader.cancel(); throw new Error("Body too large"); }
      text += decoder.decode(value, { stream: true });
    }
    return JSON.parse(text + decoder.decode());
  } finally { reader.releaseLock(); }
}

export async function verifyTurnstile(env, token, remoteIP) {
  const trimmed = String(token || "").trim();
  if (!trimmed) {
    return { ok: false, error: "Verification required" };
  }
  const secret = (env && env.TURNSTILE_SECRET_KEY) || "";
  if (!secret) {
    return { ok: false, error: "Verification unavailable" };
  }
  const form = new URLSearchParams();
  form.set("secret", secret);
  form.set("response", trimmed);
  if (remoteIP) form.set("remoteip", remoteIP);

  const resp = await fetch(
    "https://challenges.cloudflare.com/turnstile/v0/siteverify",
    {
      method: "POST",
      headers: { "Content-Type": "application/x-www-form-urlencoded" },
      body: form,
      signal: AbortSignal.timeout(4000),
      redirect: "error",
    }
  );
  if (!resp.ok) {
    return { ok: false, error: "Verification unavailable" };
  }
  const result = await boundedJSON(resp, 16384);
  if (!result.success) {
    return { ok: false, error: "Verification failed" };
  }
  return { ok: true };
}
