/**
 * Redirect www to the apex.
 *
 * website/_redirects contains the same host rule, and Cloudflare Pages does not
 * apply it: https://www.veritasvpn.cloud/ still returned 200. This middleware
 * sees the Host of a request that actually reaches the Pages project.
 */

export function redirectWww(requestUrl) {
  const url = new URL(requestUrl);
  if (url.hostname.toLowerCase() !== "www.veritasvpn.cloud") return null;
  url.hostname = "veritasvpn.cloud";
  url.protocol = "https:";
  return url.toString();
}

export async function onRequest(context) {
  const target = redirectWww(context.request.url);
  if (target) return Response.redirect(target, 301);
  return context.next();
}
