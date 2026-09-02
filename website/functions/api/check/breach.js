import {
  jsonResponse,
  rateLimit,
  clientIP,
  verifyTurnstile,
  rejectForeignOrigin,
} from "../../_lib/security.js";

function flattenBreaches(data) {
  if (!data) return [];
  if (Array.isArray(data.breaches)) {
    if (data.breaches.length && Array.isArray(data.breaches[0])) {
      return data.breaches[0].map(String);
    }
    return data.breaches.map((item) => {
      if (typeof item === "string") return item;
      if (item && typeof item === "object") {
        return String(item.name || item.breach || item.Name || "");
      }
      return "";
    }).filter(Boolean);
  }
  return [];
}

/**
 * Email breach check. Proxies XposedOrNot so the browser only talks to Veritas.
 * Same-origin only; Turnstile + rate limit required.
 */
export async function onRequestPost(context) {
  const foreign = rejectForeignOrigin(context.request);
  if (foreign) return foreign;

  const limited = await rateLimit(context.request, {
    bucket: "check-breach",
    limit: 10,
    windowSec: 60,
  });
  if (limited) return limited;

  let body;
  try {
    body = await context.request.json();
  } catch {
    return jsonResponse({ error: "Invalid JSON body" }, 400);
  }

  const ip = clientIP(context.request);
  const turnstile = await verifyTurnstile(
    context.env,
    body && body.turnstile_token,
    ip
  );
  if (!turnstile.ok) {
    return jsonResponse({ error: turnstile.error || "Verification failed" }, 403);
  }

  const email = String((body && body.email) || "")
    .trim()
    .toLowerCase();
  if (!email || !email.includes("@") || email.length > 254) {
    return jsonResponse({ error: "Enter a valid email address." }, 400);
  }

  const xo = await fetch(
    `https://api.xposedornot.com/v1/check-email/${encodeURIComponent(email)}`,
    {
      headers: {
        Accept: "application/json",
        "User-Agent": "VeritasVPN-CheckTools",
      },
    }
  );

  if (xo.status === 404) {
    return jsonResponse({
      breached: false,
      breachCount: 0,
      breaches: [],
      provider: "xposedornot",
      notice:
        "Checked via XposedOrNot. VeritasVPN does not store your email. Public corpora can be incomplete.",
      checkedAt: new Date().toISOString(),
    });
  }

  if (!xo.ok) {
    return jsonResponse(
      { error: "Breach check is temporarily unavailable. Try again later." },
      502
    );
  }

  const data = await xo.json();
  const breaches = flattenBreaches(data).slice(0, 50);
  return jsonResponse({
    breached: breaches.length > 0,
    breachCount: breaches.length,
    breaches,
    provider: "xposedornot",
    notice:
      "Checked via XposedOrNot. VeritasVPN does not store your email. Public corpora can be incomplete.",
    checkedAt: new Date().toISOString(),
  });
}

export function onRequest(context) {
  if (context.request.method === "OPTIONS") {
    return new Response(null, {
      status: 204,
      headers: {
        Allow: "POST",
        "Cache-Control": "no-store",
      },
    });
  }
  if (context.request.method === "POST") {
    return onRequestPost(context);
  }
  return jsonResponse({ error: "Method Not Allowed" }, 405, {
    Allow: "POST",
  });
}
