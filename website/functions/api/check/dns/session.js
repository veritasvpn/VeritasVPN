import { jsonResponse, rateLimit } from "../../../_lib/security.js";

function randomToken(bytes = 8) {
  const arr = new Uint8Array(bytes);
  crypto.getRandomValues(arr);
  return [...arr].map((b) => b.toString(16).padStart(2, "0")).join("");
}

/**
 * DNS leak sessions for the browser probe.
 * Same-origin only; rate-limited.
 */
export async function onRequestPost(context) {
  const limited = await rateLimit(context.request, {
    bucket: "check-dns-session",
    limit: 20,
    windowSec: 60,
  });
  if (limited) return limited;

  const id = randomToken(8);
  const probes = 6;
  return jsonResponse({
    sessionId: id,
    probes,
    probeUrl: "https://edns.ip-api.com/json",
    expiresInSec: 120,
    provider: "edns.ip-api.com",
    notice:
      "DNS resolver detection uses short-lived unique hostnames on edns.ip-api.com. VeritasVPN does not receive your recursive DNS queries directly in this version.",
  });
}

export function onRequest(context) {
  if (context.request.method === "OPTIONS") {
    return new Response(null, {
      status: 204,
      headers: { Allow: "POST", "Cache-Control": "no-store" },
    });
  }
  if (context.request.method === "POST") {
    return onRequestPost(context);
  }
  return jsonResponse({ error: "Method Not Allowed" }, 405, {
    Allow: "POST",
  });
}
