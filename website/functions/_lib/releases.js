/**
 * Single source of truth for which GitHub release each download comes from.
 *
 * sha256 is the expected digest of that exact asset. The download Function
 * refuses to send bytes that do not match, and /downloads/SHA256SUMS publishes
 * these pins rather than the checksum file attached to the release. Replacing
 * a GitHub asset (and its checksum file) therefore cannot change what the site
 * serves or what it tells people to verify. Bumping a release means editing
 * the tag and the digest together.
 */
import { withSecurityHeaders, textResponse } from "./security.js";

const RELEASE_BASE = "https://github.com/veritasvpn/VeritasVPN/releases/download";
// Largest current asset is the AppImage (~90 MiB). Stay under a Worker memory
// budget and fail closed if a future asset grows past this.
const MAX_ASSET_BYTES = 100 * 1024 * 1024;

export const DOWNLOADS = {
  "veritasvpn-android.apk": {
    // android-v0.2.90 is the signed website APK (versionCode 74) with
    // the connect circle pulsing through Protected (#201).
    // Play production stays on the older Play-tracked build.
    tag: "android-v0.2.90",
    sha256: "92b6761136304c6bbbdefc3721f313511afbc0f0a8d488486f79103bb9caf0af",
    contentType: "application/vnd.android.package-archive",
    unavailable: "Android APK is temporarily unavailable.",
  },
  "veritasvpn-linux.deb": {
    // linux-v0.2.90: connect circle pulsing through Protected (#201).
    tag: "linux-v0.2.90",
    sha256: "60baddacc91482e259e71da212468544f81c1fbe94cb8a0166cf44cf4353013d",
    contentType: "application/vnd.debian.binary-package",
    unavailable: "Linux .deb is temporarily unavailable.",
  },
  "veritasvpn-linux.AppImage": {
    tag: "linux-v0.2.90",
    sha256: "eec7029323df556b0efbd783a2f149ecfe6c9777211114485fbe07fc7deee9eb",
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

export async function sha256Hex(bytes) {
  const digest = await crypto.subtle.digest("SHA-256", bytes);
  return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

function digestEqual(actual, expected) {
  if (typeof actual !== "string" || typeof expected !== "string" || actual.length !== expected.length) {
    return false;
  }
  let diff = 0;
  for (let i = 0; i < actual.length; i++) diff |= actual.charCodeAt(i) ^ expected.charCodeAt(i);
  return diff === 0;
}

/** Reads an upstream body and returns it only when it matches the pinned digest. */
export async function verifiedReleaseBody(upstream, expectedSha256) {
  if (!upstream || !upstream.ok || !upstream.body || !expectedSha256) return null;
  const reader = upstream.body.getReader();
  const chunks = [];
  let total = 0;
  while (true) {
    const { done, value } = await reader.read();
    if (done) break;
    total += value.byteLength;
    if (total > MAX_ASSET_BYTES) {
      await reader.cancel();
      return null;
    }
    chunks.push(value);
  }
  const body = new Uint8Array(total);
  let offset = 0;
  for (const chunk of chunks) {
    body.set(chunk, offset);
    offset += chunk.byteLength;
  }
  const got = await sha256Hex(body);
  if (!digestEqual(got, expectedSha256.toLowerCase())) return null;
  return body;
}

/** Checksum file published by the site. Built only from the pins above. */
export function pinnedChecksumDocument() {
  const lines = Object.keys(DOWNLOADS)
    .sort()
    .map((name) => `${DOWNLOADS[name].sha256}  ${name}`);
  return [
    "# VeritasVPN release checksums",
    "# Verify with: sha256sum --check --ignore-missing SHA256SUMS",
    "# These digests are pinned in the site, not copied from the GitHub release checksum file.",
    "",
    ...lines,
    "",
  ].join("\n");
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
  const body = await verifiedReleaseBody(upstream, DOWNLOADS[filename].sha256);
  if (!body) {
    return textResponse(DOWNLOADS[filename].unavailable, 502);
  }
  const headers = downloadHeaders(filename, upstream);
  headers["Content-Length"] = String(body.byteLength);
  return new Response(body, {
    status: 200,
    headers,
  });
}
