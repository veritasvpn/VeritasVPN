/**
 * Serves checksums for the downloads this site offers, from the same origin as
 * the downloads themselves.
 *
 * The digests are the pins in releases.js. They are not read from the GitHub
 * release's SHA256SUMS file, so replacing that file cannot change the checksums
 * published here. The download Functions refuse to send an asset whose bytes
 * do not match the same pins.
 */
import { withSecurityHeaders, textResponse } from "../_lib/security.js";
import { pinnedChecksumDocument } from "../_lib/releases.js";

export async function onRequest(context) {
  const method = context.request.method;
  if (method !== "GET" && method !== "HEAD") {
    return textResponse("Method Not Allowed", 405, { Allow: "GET, HEAD" });
  }

  const body = pinnedChecksumDocument();
  const headers = withSecurityHeaders({
    "Content-Type": "text/plain; charset=utf-8",
    "Cache-Control": "public, max-age=300",
  });

  if (method === "HEAD") {
    return new Response(null, { status: 200, headers });
  }
  return new Response(body, { status: 200, headers });
}
