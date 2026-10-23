import { connect } from "cloudflare:sockets";
import {
  CHECK_TIMEOUT_MS,
  advanceChecks,
  commitUptimeState,
  egressHealthy,
  isTlsHandshakeRecord,
  messageFor,
  tlsClientHello,
} from "./state.mjs";

const STATE_KEY = "checks";

async function fetchText(url) {
  const response = await fetch(url, { signal: AbortSignal.timeout(CHECK_TIMEOUT_MS) });
  return { ok: response.ok, text: await response.text() };
}

function withTimeout(promise) {
  return Promise.race([
    promise,
    new Promise((_, reject) => {
      setTimeout(() => reject(new Error("timeout")), CHECK_TIMEOUT_MS);
    }),
  ]);
}

// The listener certificate is self-signed, so this does not use a verified
// TLS handshake. The socket is plain TCP. After it opens, one ClientHello
// is enough: a handshake record means Stealth answered.
async function checkStealth(ip) {
  if (!ip) return false;
  let socket;
  try {
    socket = connect({ hostname: ip, port: 443 }, { secureTransport: "off" });
    await withTimeout(socket.opened);
    const writer = socket.writable.getWriter();
    await withTimeout(writer.write(tlsClientHello()));
    const reader = socket.readable.getReader();
    const { value, done } = await withTimeout(reader.read());
    if (done) return false;
    return isTlsHandshakeRecord(value);
  } catch {
    return false;
  } finally {
    try {
      socket?.close();
    } catch {
      // The socket may already be closed.
    }
  }
}

async function probe(env, nowMs) {
  const results = {};
  try {
    const site = await fetchText("https://veritasvpn.cloud/");
    results.website = site.ok;
  } catch {
    results.website = false;
  }
  try {
    const ready = await fetchText("https://api.veritasvpn.cloud/readyz");
    results.readyz = ready.ok;
  } catch {
    results.readyz = false;
  }
  try {
    const billing = await fetchText("https://api.veritasvpn.cloud/api/v1/billing/readyz");
    results.billing = billing.ok;
  } catch {
    results.billing = false;
  }
  try {
    const egress = await fetchText("https://api.veritasvpn.cloud/api/v1/status/egress");
    const body = egress.ok ? JSON.parse(egress.text) : null;
    results.egress = egressHealthy(body, nowMs);
  } catch {
    results.egress = false;
  }
  results.stealth = await checkStealth(env.PUBLIC_IP);
  return results;
}

async function loadState(env) {
  const stored = await env.UPTIME_STATE.get(STATE_KEY, "json");
  return stored && typeof stored === "object" ? stored : {};
}

async function sendTelegram(env, text) {
  const token = env.TELEGRAM_BOT_TOKEN;
  const chat = env.TELEGRAM_CHAT_ID;
  if (!token || !chat || !text) return false;
  const response = await fetch(`https://api.telegram.org/bot${token}/sendMessage`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ chat_id: chat, text }),
  });
  return response.ok;
}

async function runChecks(env) {
  const now = Date.now();
  const previous = await loadState(env);
  const probes = await probe(env, now);
  const { next, sends } = advanceChecks(previous, probes, now);
  for (const send of sends) {
    await sendTelegram(env, messageFor(send.check, send.kind));
  }
  // Telegram first, then KV. An unchanged snapshot is not written.
  await commitUptimeState(env.UPTIME_STATE, STATE_KEY, previous, next);
}

async function testAlert(request, env) {
  const expected = env.TEST_ALERT_SECRET || "";
  const provided = request.headers.get("x-uptime-test-secret") || "";
  if (!expected || provided !== expected) {
    return new Response("not found", { status: 404 });
  }
  const sent = await sendTelegram(env, "VeritasVPN uptime: this is a test alert.");
  return new Response(sent ? "sent" : "not sent", { status: sent ? 200 : 503 });
}

export default {
  async scheduled(_event, env, ctx) {
    ctx.waitUntil(runChecks(env));
  },
  async fetch(request, env, ctx) {
    const url = new URL(request.url);
    if (url.pathname === "/test") return testAlert(request, env);
    if (url.pathname === "/run") {
      const expected = env.TEST_ALERT_SECRET || "";
      const provided = request.headers.get("x-uptime-test-secret") || "";
      if (!expected || provided !== expected) {
        return new Response("not found", { status: 404 });
      }
      ctx.waitUntil(runChecks(env));
      return new Response("started", { status: 202 });
    }
    return new Response("ok", { status: 200 });
  },
};
