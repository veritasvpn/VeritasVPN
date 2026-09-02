const cors = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
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

function flattenBreaches(data) {
  if (!data) return [];
  if (Array.isArray(data.breaches)) {
    if (data.breaches.length && Array.isArray(data.breaches[0])) {
      return data.breaches[0].map(String);
    }
    return data.breaches.map((item) => {
      if (typeof item === "string") return item;
      if (item && typeof item === "object") {
        return String(item.name || item.breach || item.Name || "");
      }
      return "";
    }).filter(Boolean);
  }
  return [];
}

/**
 * Email breach check. Proxies XposedOrNot so the browser only talks to Veritas.
 * Email is used for the upstream request only — not stored by VeritasVPN.
 */
export async function onRequestPost(context) {
  let body;
  try {
    body = await context.request.json();
  } catch {
    return json({ error: "Invalid JSON body" }, 400);
  }
  const email = String((body && body.email) || "")
    .trim()
    .toLowerCase();
  if (!email || !email.includes("@") || email.length > 254) {
    return json({ error: "Enter a valid email address." }, 400);
  }

  const xo = await fetch(
    `https://api.xposedornot.com/v1/check-email/${encodeURIComponent(email)}`,
    {
      headers: {
        Accept: "application/json",
        "User-Agent": "VeritasVPN-CheckTools",
      },
    }
  );

  if (xo.status === 404) {
    return json({
      breached: false,
      breachCount: 0,
      breaches: [],
      provider: "xposedornot",
      notice:
        "Checked via XposedOrNot. VeritasVPN does not store your email. Public corpora can be incomplete.",
      checkedAt: new Date().toISOString(),
    });
  }

  if (!xo.ok) {
    return json(
      { error: "Breach check is temporarily unavailable. Try again later." },
      502
    );
  }

  const data = await xo.json();
  const breaches = flattenBreaches(data).slice(0, 50);
  return json({
    breached: breaches.length > 0,
    breachCount: breaches.length,
    breaches,
    provider: "xposedornot",
    notice:
      "Checked via XposedOrNot. VeritasVPN does not store your email. Public corpora can be incomplete.",
    checkedAt: new Date().toISOString(),
  });
}

export function onRequest(context) {
  if (context.request.method === "OPTIONS") {
    return new Response(null, { status: 204, headers: cors });
  }
  if (context.request.method === "POST") {
    return onRequestPost(context);
  }
  return new Response("Method Not Allowed", {
    status: 405,
    headers: { Allow: "POST, OPTIONS", ...cors },
  });
}
