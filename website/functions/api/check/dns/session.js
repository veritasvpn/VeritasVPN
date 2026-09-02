const cors = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
  "Access-Control-Allow-Headers": "Content-Type",
};

function json(data, status = 200) {
  return new Response(JSON.stringify(data), {
    status,
    headers: {
      "Content-Type": "application/json; charset=utf-8",
      "Cache-Control": "no-store",
      ...cors,
    },
  });
}

function randomToken(bytes = 8) {
  const arr = new Uint8Array(bytes);
  crypto.getRandomValues(arr);
  return [...arr].map((b) => b.toString(16).padStart(2, "0")).join("");
}

/**
 * DNS leak sessions for the browser probe.
 * The browser repeatedly fetches https://edns.ip-api.com/json (following
 * redirects to unique hostnames) so recursive resolvers become visible.
 */
export async function onRequestPost() {
  const id = randomToken(8);
  const probes = 6;
  return json({
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
    return new Response(null, { status: 204, headers: cors });
  }
  if (context.request.method === "POST") {
    return onRequestPost(context);
  }
  return new Response("Method Not Allowed", {
    status: 405,
    headers: { Allow: "POST, OPTIONS", ...cors },
  });
}
