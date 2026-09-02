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
 * Sliding-window rate limit using the Cache API (best-effort per isolate).
 * @returns {Promise<Response|null>} 429 response or null if allowed
 */
export async function rateLimit(request, { bucket, limit = 30, windowSec = 60 } = {}) {
  const ip = clientIP(request) || "unknown";
  const keyUrl = `https://rate-limit.veritasvpn.internal/${bucket}/${ip}`;
  const now = Date.now();
  let count = 1;
  try {
    const cache = caches.default;
    const hit = await cache.match(keyUrl);
    if (hit) {
      const prev = Number(await hit.text());
      count = Number.isFinite(prev) ? prev + 1 : 1;
    }
    const res = new Response(String(count), {
      headers: {
        "Cache-Control": `max-age=${windowSec}`,
        "Content-Type": "text/plain",
      },
    });
    await cache.put(keyUrl, res);
  } catch {
    /* cache unavailable — allow */
    return null;
  }
  if (count > limit) {
    return jsonResponse(
      { error: "Too many requests. Try again shortly." },
      429,
      { "Retry-After": String(windowSec) }
    );
  }
  return null;
}

export async function verifyTurnstile(env, token, remoteIP) {
  const secret = (env && env.TURNSTILE_SECRET_KEY) || "";
  if (!secret) {
    // Fail closed when secret is expected in production Pages.
    if (env && env.ENVIRONMENT === "production") {
      return { ok: false, error: "Verification unavailable" };
    }
    return { ok: true };
  }
  const trimmed = String(token || "").trim();
  if (!trimmed) {
    return { ok: false, error: "Verification required" };
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
    }
  );
  if (!resp.ok) {
    return { ok: false, error: "Verification unavailable" };
  }
  const result = await resp.json();
  if (!result.success) {
    return { ok: false, error: "Verification failed" };
  }
  return { ok: true };
}
