const APPIMAGE_URL =
  "https://github.com/veritasvpn/VeritasVPN/releases/download/v0.2.29/veritasvpn-linux.AppImage";

export async function onRequestGet() {
  const upstream = await fetch(APPIMAGE_URL, {
    cf: { cacheEverything: true, cacheTtl: 300 },
  });
  if (!upstream.ok || !upstream.body) {
    return new Response("Linux AppImage is temporarily unavailable.", {
      status: 502,
      headers: { "Content-Type": "text/plain; charset=utf-8" },
    });
  }

  const headers = new Headers();
  headers.set("Content-Type", "application/x-executable");
  headers.set("Content-Disposition", 'attachment; filename="veritasvpn-linux.AppImage"');
  headers.set("Cache-Control", "public, max-age=300");
  headers.set("X-Content-Type-Options", "nosniff");
  const length = upstream.headers.get("content-length");
  if (length) headers.set("Content-Length", length);
  return new Response(upstream.body, { status: 200, headers });
}

export function onRequest(context) {
  if (context.request.method === "GET" || context.request.method === "HEAD") {
    return onRequestGet(context);
  }
  return new Response("Method Not Allowed", {
    status: 405,
    headers: { Allow: "GET, HEAD" },
  });
}
