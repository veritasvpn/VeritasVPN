import { withSecurityHeaders, textResponse } from "../_lib/security.js";

const DEB_URL =
  "https://github.com/veritasvpn/VeritasVPN/releases/download/v0.2.39/veritasvpn-linux.deb";

function downloadHeaders(upstream) {
  const headers = withSecurityHeaders({
    "Content-Type": "application/vnd.debian.binary-package",
    "Content-Disposition": 'attachment; filename="veritasvpn-linux.deb"',
    "Cache-Control": "public, max-age=300",
  });
  const length = upstream && upstream.headers.get("content-length");
  if (length) headers["Content-Length"] = length;
  return headers;
}

export async function onRequest(context) {
  const method = context.request.method;
  if (method !== "GET" && method !== "HEAD") {
    return textResponse("Method Not Allowed", 405, { Allow: "GET, HEAD" });
  }
  if (method === "HEAD") {
    const upstream = await fetch(DEB_URL, {
      method: "HEAD",
      cf: { cacheEverything: true, cacheTtl: 300 },
    });
    if (!upstream.ok) {
      return textResponse("Linux .deb is temporarily unavailable.", 502);
    }
    return new Response(null, { status: 200, headers: downloadHeaders(upstream) });
  }
  const upstream = await fetch(DEB_URL, {
    cf: { cacheEverything: true, cacheTtl: 300 },
  });
  if (!upstream.ok || !upstream.body) {
    return textResponse("Linux .deb is temporarily unavailable.", 502);
  }
  return new Response(upstream.body, {
    status: 200,
    headers: downloadHeaders(upstream),
  });
}
