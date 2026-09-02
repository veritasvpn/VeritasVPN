const cors = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Methods": "GET, HEAD, OPTIONS",
  "Access-Control-Allow-Headers": "Content-Type",
};

function json(data, status = 200) {
  return new Response(JSON.stringify(data), {
    status,
    headers: {
      "Content-Type": "application/json; charset=utf-8",
      "Cache-Control": "no-store",
      ...cors,
    },
  });
}

export async function onRequestGet(context) {
  const req = context.request;
  const cf = req.cf || {};
  const ip =
    req.headers.get("cf-connecting-ip") ||
    (req.headers.get("x-forwarded-for") || "").split(",")[0].trim() ||
    "";

  const latitude = Number.parseFloat(cf.latitude);
  const longitude = Number.parseFloat(cf.longitude);

  return json({
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
    return new Response(null, { status: 204, headers: cors });
  }
  if (context.request.method === "GET" || context.request.method === "HEAD") {
    return onRequestGet(context);
  }
  return new Response("Method Not Allowed", {
    status: 405,
    headers: { Allow: "GET, HEAD, OPTIONS", ...cors },
  });
}
