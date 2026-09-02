import { setStatus, clearResults, addResult, fetchJson } from "/js/check-common.js";

const status = document.getElementById("status");
const results = document.getElementById("results");
const btn = document.getElementById("runCheck");

async function probeOnce(baseUrl, n) {
  const ctrl = new AbortController();
  const timer = window.setTimeout(() => ctrl.abort(), 8000);
  try {
    const url = `${baseUrl}${baseUrl.includes("?") ? "&" : "?"}_=${Date.now()}-${n}`;
    const res = await fetch(url, {
      signal: ctrl.signal,
      cache: "no-store",
      mode: "cors",
      redirect: "follow",
    });
    window.clearTimeout(timer);
    if (!res.ok) return null;
    return await res.json();
  } catch {
    window.clearTimeout(timer);
    return null;
  }
}

async function run() {
  if (!btn) return;
  btn.disabled = true;
  clearResults(results);
  setStatus(status, "running", "Creating session and probing resolvers…");
  try {
    const session = await fetchJson("/api/check/dns/session", { method: "POST" });
    const count = session.probes || 6;
    const baseUrl = session.probeUrl || "https://edns.ip-api.com/json";
    const answers = [];
    for (let i = 0; i < count; i += 1) {
      answers.push(await probeOnce(baseUrl, i));
    }
    const resolverMap = new Map();
    for (const ans of answers) {
      const dns = ans && ans.dns;
      const ip = dns && dns.ip;
      if (!ip) continue;
      const geo = dns.geo || "";
      if (!resolverMap.has(ip)) {
        resolverMap.set(ip, { ip, geo, hits: 0 });
      }
      resolverMap.get(ip).hits += 1;
    }
    const resolvers = [...resolverMap.values()].sort((a, b) => b.hits - a.hits);
    if (!resolvers.length) {
      setStatus(
        status,
        "warn",
        "No resolver identities returned. Ad blockers or strict DNS settings may have blocked the probe."
      );
      addResult(results, "Session", session.sessionId);
      addResult(results, "Notice", session.notice || "");
      return;
    }
    resolvers.forEach((r, idx) => {
      addResult(
        results,
        `Resolver ${idx + 1}`,
        `${r.ip}${r.geo ? ` (${r.geo})` : ""} · seen ${r.hits}×`
      );
    });
    addResult(results, "Session", session.sessionId);
    addResult(results, "Method", session.notice || session.provider);
    setStatus(
      status,
      "warn",
      "Resolvers were detected for this session. If you expected only a VPN DNS path, you may be leaking DNS."
    );
  } catch (err) {
    setStatus(status, "bad", err.message || "DNS leak test failed");
  } finally {
    btn.disabled = false;
  }
}

btn?.addEventListener("click", () => void run());
