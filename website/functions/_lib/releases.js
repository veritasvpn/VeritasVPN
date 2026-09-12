/**
 * Single source of truth for which GitHub release each download comes from.
 *
 * The per-file download Functions and /downloads/SHA256SUMS all read this map,
 * so the checksums the site publishes always describe the exact bytes the site
 * serves. Bumping a release means editing one entry here.
 */
import { withSecurityHeaders, textResponse } from "./security.js";

const RELEASE_BASE = "https://github.com/veritasvpn/VeritasVPN/releases/download";

export const DOWNLOADS = {
  "veritasvpn-android.apk": {
    tag: "v0.2.67",
    contentType: "application/vnd.android.package-archive",
    unavailable: "Android APK is temporarily unavailable.",
  },
  "veritasvpn-linux.deb": {
    tag: "v0.2.67",
    contentType: "application/vnd.debian.binary-package",
    unavailable: "Linux .deb is temporarily unavailable.",
  },
  "veritasvpn-linux.AppImage": {
    tag: "v0.2.67",
    contentType: "application/octet-stream",
    unavailable: "Linux AppImage is temporarily unavailable.",
  },
};

export function releaseAssetURL(filename) {
  return `${RELEASE_BASE}/${DOWNLOADS[filename].tag}/${filename}`;
}

export function checksumsURL(tag) {
  return `${RELEASE_BASE}/${tag}/SHA256SUMS`;
}

function downloadHeaders(filename, upstream) {
  const headers = withSecurityHeaders({
    "Content-Type": DOWNLOADS[filename].contentType,
    "Content-Disposition": `attachment; filename="${filename}"`,
    "Cache-Control": "public, max-age=300",
  });
  const length = upstream && upstream.headers.get("content-length");
  if (length) headers["Content-Length"] = length;
  return headers;
}

/** Streams a release asset from GitHub under our own origin. */
export async function serveRelease(request, filename) {
  const method = request.method;
  if (method !== "GET" && method !== "HEAD") {
    return textResponse("Method Not Allowed", 405, { Allow: "GET, HEAD" });
  }

  const url = releaseAssetURL(filename);
  const cf = { cacheEverything: true, cacheTtl: 300 };

  if (method === "HEAD") {
    // GitHub release assets first respond with a redirect to their object store.
    // Pages Functions must explicitly follow it before judging availability.
    const upstream = await fetch(url, { method: "HEAD", redirect: "follow", cf });
    if (!upstream.ok) {
      return textResponse(DOWNLOADS[filename].unavailable, 502);
    }
    return new Response(null, {
      status: 200,
      headers: downloadHeaders(filename, upstream),
    });
  }

  const upstream = await fetch(url, { redirect: "follow", cf });
  if (!upstream.ok || !upstream.body) {
    return textResponse(DOWNLOADS[filename].unavailable, 502);
  }
  return new Response(upstream.body, {
    status: 200,
    headers: downloadHeaders(filename, upstream),
  });
}
