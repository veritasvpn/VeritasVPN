import {
  jsonResponse,
  rateLimit,
  clientIP,
  rejectForeignOrigin,
} from "../../_lib/security.js";

export async function onRequestGet(context) {
  const foreign = rejectForeignOrigin(context.request);
  if (foreign) return foreign;

  const limited = await rateLimit(context.request, {
    bucket: "check-ip",
    limit: 60,
    windowSec: 60,
  });
  if (limited) return limited;

  const req = context.request;
  const cf = req.cf || {};
  const ip = clientIP(req);

  const latitude = Number.parseFloat(cf.latitude);
  const longitude = Number.parseFloat(cf.longitude);

  return jsonResponse({
    ip: ip || null,
    country: cf.country || null,
    colo: cf.colo || null,
    asOrganization: cf.asOrganization || null,
    city: cf.city || null,
    region: cf.region || null,
    latitude: Number.isFinite(latitude) ? latitude : null,
    longitude: Number.isFinite(longitude) ? longitude : null,
    timezone: cf.timezone || null,
    httpProtocol: cf.httpProtocol || null,
    tlsVersion: cf.tlsVersion || null,
    checkedAt: new Date().toISOString(),
  });
}

export function onRequest(context) {
  if (context.request.method === "OPTIONS") {
    return new Response(null, {
      status: 204,
      headers: { Allow: "GET, HEAD", "Cache-Control": "no-store" },
    });
  }
  if (context.request.method === "GET" || context.request.method === "HEAD") {
    return onRequestGet(context);
  }
  return jsonResponse({ error: "Method Not Allowed" }, 405, {
    Allow: "GET, HEAD",
  });
}
