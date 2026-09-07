/**
 * Serves checksums for the downloads this site offers, from the same origin as
 * the downloads themselves.
 *
 * Previously SHA256SUMS was published only on GitHub Releases while the
 * binaries were streamed from veritasvpn.cloud, so a user following the
 * download button had no in-band way to check what they received. Checksums are
 * read from the same release as each asset, so the two cannot drift.
 *
 * This detects corruption and tampering in transit. It is not by itself proof
 * against a compromised deploy of this site, since that could alter both sides;
 * for that, verify against the GitHub release and its build provenance
 * attestation.
 */
import { withSecurityHeaders, textResponse } from "../_lib/security.js";
import { DOWNLOADS, checksumsURL } from "../_lib/releases.js";

const HEADER = [
  "# VeritasVPN release checksums",
  "# Verify with: sha256sum --check --ignore-missing SHA256SUMS",
  "# Cross-check against https://github.com/veritasvpn/VeritasVPN/releases",
  "",
].join("\n");

async function buildChecksums() {
  const tags = [...new Set(Object.values(DOWNLOADS).map((d) => d.tag))];
  const collected = new Map();

  for (const tag of tags) {
    const upstream = await fetch(checksumsURL(tag), {
      cf: { cacheEverything: true, cacheTtl: 300 },
    });
    if (!upstream.ok) return null;

    for (const raw of (await upstream.text()).split("\n")) {
      const line = raw.trim();
      if (!line || line.startsWith("#")) continue;
      const [digest, name] = line.split(/\s+/);
      // Only report files this site serves, and only from the release they are
      // actually served from.
      if (!digest || !name) continue;
      if (DOWNLOADS[name] && DOWNLOADS[name].tag === tag) {
        collected.set(name, `${digest}  ${name}`);
      }
    }
  }

  // A partial list would imply an unlisted file is unverifiable rather than
  // missing, so publish all of them or none.
  if (collected.size !== Object.keys(DOWNLOADS).length) return null;

  return HEADER + [...collected.keys()].sort().map((n) => collected.get(n)).join("\n") + "\n";
}

export async function onRequest(context) {
  const method = context.request.method;
  if (method !== "GET" && method !== "HEAD") {
    return textResponse("Method Not Allowed", 405, { Allow: "GET, HEAD" });
  }

  const body = await buildChecksums();
  if (body === null) {
    return textResponse("Checksums are temporarily unavailable.", 502);
  }

  const headers = withSecurityHeaders({
    "Content-Type": "text/plain; charset=utf-8",
    "Cache-Control": "public, max-age=300",
  });

  if (method === "HEAD") {
    return new Response(null, { status: 200, headers });
  }
  return new Response(body, { status: 200, headers });
}
