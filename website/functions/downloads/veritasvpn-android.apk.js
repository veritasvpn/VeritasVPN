import { withSecurityHeaders, textResponse } from "../_lib/security.js";

const APK_URL =
  "https://github.com/veritasvpn/VeritasVPN/releases/download/v0.2.52/veritasvpn-android.apk";

function downloadHeaders(upstream) {
  const headers = withSecurityHeaders({
    "Content-Type": "application/vnd.android.package-archive",
    "Content-Disposition": 'attachment; filename="veritasvpn-android.apk"',
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
    const upstream = await fetch(APK_URL, {
      method: "HEAD",
      cf: { cacheEverything: true, cacheTtl: 300 },
    });
    if (!upstream.ok) {
      return textResponse("Android APK is temporarily unavailable.", 502);
    }
    return new Response(null, { status: 200, headers: downloadHeaders(upstream) });
  }

  const upstream = await fetch(APK_URL, {
    cf: { cacheEverything: true, cacheTtl: 300 },
  });
  if (!upstream.ok || !upstream.body) {
    return textResponse("Android APK is temporarily unavailable.", 502);
  }
  return new Response(upstream.body, {
    status: 200,
    headers: downloadHeaders(upstream),
  });
}
