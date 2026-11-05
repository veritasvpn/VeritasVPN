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
    // android-v0.2.100 is the signed website APK (versionName 0.2.100, versionCode 84).
    // Digest is the veritasvpn-android.apk line from that release's SHA256SUMS.
    tag: "android-v0.2.100",
    sha256: "fa35911058e4db426fb58d587a9d4fc7194d326b194c0d3a3f88e87e18880599",
    contentType: "application/vnd.android.package-archive",
    unavailable: "Android APK is temporarily unavailable.",
  },
  "veritasvpn-linux.deb": {
    // linux-v0.2.99: reconnect after network changes, long sessions, and Stealth roaming (#261).
    tag: "linux-v0.2.99",
    sha256: "c253eb4fe8902c38b52f35c8e6bcea9b38d9599fb8fcec65a96633923169fe4e",
    contentType: "application/vnd.debian.binary-package",
    unavailable: "Linux .deb is temporarily unavailable.",
  },
  "veritasvpn-linux.AppImage": {
    tag: "linux-v0.2.99",
    sha256: "2e09ab5213c35adb42676fd80f3b9b8455fe646053abafc8b5b48f0766a91b02",
    contentType: "application/octet-stream",
    unavailable: "Linux AppImage is temporarily unavailable.",
  },
};

/**
 * Windows download slot. published stays false until a windows-v* release is
 * approved and tag plus sha256 are filled in together. An empty pin is not a
 * file the site will fetch or list in SHA256SUMS.
 */
export const WINDOWS_INSTALLER = {
  filename: "veritasvpn-windows-setup.exe",
  published: false,
  tag: "",
  sha256: "",
  contentType: "application/vnd.microsoft.portable-executable",
  unavailable: "The Windows installer is not published yet.",
};

export function windowsPinReady() {
  return WINDOWS_INSTALLER.published === true
    && /^windows-v\d+\.\d+\.\d+$/.test(WINDOWS_INSTALLER.tag)
    && /^[0-9a-f]{64}$/.test(WINDOWS_INSTALLER.sha256);
}

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

/** Checksum file published by the site. Built only from live pins. */
export function pinnedChecksumDocument() {
  const pins = { ...DOWNLOADS };
  if (windowsPinReady()) {
    pins[WINDOWS_INSTALLER.filename] = WINDOWS_INSTALLER;
  }
  const lines = Object.keys(pins)
    .sort()
    .map((name) => `${pins[name].sha256}  ${name}`);
  return [
    "# VeritasVPN release checksums",
    "# Verify with: sha256sum --check --ignore-missing SHA256SUMS",
    "# These digests are pinned in the site, not copied from the GitHub release checksum file.",
    "",
    ...lines,
    "",
  ].join("\n");
}

function downloadHeaders(filename, entry, upstream) {
  const headers = withSecurityHeaders({
    "Content-Type": entry.contentType,
    "Content-Disposition": `attachment; filename="${filename}"`,
    "Cache-Control": "public, max-age=300",
  });
  const length = upstream && upstream.headers.get("content-length");
  if (length) headers["Content-Length"] = length;
  return headers;
}

/** Windows route. Refuses to fetch GitHub until the pin is explicitly ready. */
export async function serveWindowsInstaller(request) {
  const method = request.method;
  if (method !== "GET" && method !== "HEAD") {
    return textResponse("Method Not Allowed", 405, { Allow: "GET, HEAD" });
  }
  if (!windowsPinReady()) {
    return textResponse(WINDOWS_INSTALLER.unavailable, 404);
  }
  return servePinned(request, WINDOWS_INSTALLER.filename, WINDOWS_INSTALLER);
}

/** Streams a release asset from GitHub under our own origin. */
export async function serveRelease(request, filename) {
  return servePinned(request, filename, DOWNLOADS[filename]);
}

async function servePinned(request, filename, entry) {
  const method = request.method;
  if (method !== "GET" && method !== "HEAD") {
    return textResponse("Method Not Allowed", 405, { Allow: "GET, HEAD" });
  }
  if (!entry || !entry.tag || !entry.sha256) {
    return textResponse("Not found", 404);
  }

  const url = `${RELEASE_BASE}/${entry.tag}/${filename}`;
  const cf = { cacheEverything: true, cacheTtl: 300 };

  if (method === "HEAD") {
    // GitHub release assets first respond with a redirect to their object store.
    // Pages Functions must explicitly follow it before judging availability.
    const upstream = await fetch(url, { method: "HEAD", redirect: "follow", cf });
    if (!upstream.ok) {
      return textResponse(entry.unavailable, 502);
    }
    return new Response(null, {
      status: 200,
      headers: downloadHeaders(filename, entry, upstream),
    });
  }

  const upstream = await fetch(url, { redirect: "follow", cf });
  const body = await verifiedReleaseBody(upstream, entry.sha256);
  if (!body) {
    return textResponse(entry.unavailable, 502);
  }
  const headers = downloadHeaders(filename, entry, upstream);
  headers["Content-Length"] = String(body.byteLength);
  return new Response(body, {
    status: 200,
    headers,
  });
}
