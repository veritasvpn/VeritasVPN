import {
  setStatus,
  setCheckBusy,
  setOutcome,
  showResultSkeletons,
  clearResults,
  addResult,
  fetchJson,
} from "/js/check-common.js";

const status = document.getElementById("status");
const results = document.getElementById("results");
const outcome = document.getElementById("outcome");
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
  setCheckBusy(btn, true, "Probing…");
  setOutcome(outcome, null);
  showResultSkeletons(results, 6);
  setStatus(status, "running", "Creating DNS probe session…");
  try {
    const session = await fetchJson("/api/check/dns/session", { method: "POST" });
    const count = session.probes || 6;
    const baseUrl = session.probeUrl || "https://edns.ip-api.com/json";
    const answers = [];
    for (let i = 0; i < count; i += 1) {
      setStatus(status, "running", "Probing recursive resolvers…", {
        progress: i,
        total: count,
      });
      answers.push(await probeOnce(baseUrl, i));
      setStatus(status, "running", "Probing recursive resolvers…", {
        progress: i + 1,
        total: count,
      });
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
    clearResults(results);
    if (!resolvers.length) {
      setStatus(
        status,
        "warn",
        "No resolver identities returned."
      );
      addResult(results, "Session", session.sessionId);
      addResult(results, "Notice", session.notice || "");
      setOutcome(outcome, {
        state: "warn",
        title: "What this means",
        body: "We could not identify which DNS resolvers answered. An ad blocker, strict DNS setting, or blocked probe host may have stopped the test — or your resolver path is hard to fingerprint. Try again after disabling blockers, or re-run while connected to VeritasVPN.",
      });
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
      "Resolver identities were detected for this session."
    );
    setOutcome(outcome, {
      state: "warn",
      title: "What this means",
      body: "These resolvers learn the hostnames this browser looks up. If you expected only Veritas Shield, seeing ISP, Google, Cloudflare, or other third-party resolvers means DNS may be leaking outside the tunnel. Connect VeritasVPN and re-run — the list should change away from your normal ISP/public DNS path.",
    });
  } catch (err) {
    clearResults(results);
    setStatus(status, "bad", err.message || "DNS leak test failed");
    setOutcome(outcome, {
      state: "bad",
      title: "What this means",
      body: "The DNS probe failed, so we cannot tell which resolvers see your lookups right now. Retry in a moment.",
    });
  } finally {
    setCheckBusy(btn, false);
  }
}

btn?.addEventListener("click", () => void run());
