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
    tag: "v0.2.77",
    sha256: "21022ce7ca78488e42393855b8c2b894c9990735e3d35437b58d83247ea716e9",
    contentType: "application/vnd.android.package-archive",
    unavailable: "Android APK is temporarily unavailable.",
  },
  "veritasvpn-linux.deb": {
    tag: "v0.2.77",
    sha256: "656f2e0620d8e6bcaaa8ac0e136d8e2d33af2ba9d0af1384b2f8d27500358972",
    contentType: "application/vnd.debian.binary-package",
    unavailable: "Linux .deb is temporarily unavailable.",
  },
  "veritasvpn-linux.AppImage": {
    tag: "v0.2.77",
    sha256: "31bbccf3aa18c10ddad5bf98708aa1da1a2a1f5c23db4dbe829a6cc454071059",
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
