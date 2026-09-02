import {
  setStatus,
  setCheckBusy,
  setOutcome,
  showResultSkeletons,
  clearResults,
  addResult,
  getPublicIp,
  detectWebRtcIPs,
  detectIPv6,
  collectBrowserFacts,
  fetchJson,
  isPrivateIP,
} from "/js/check-common.js";
import { renderIpMap } from "/js/check-map.js";

const status = document.getElementById("status");
const results = document.getElementById("results");
const outcome = document.getElementById("outcome");
const btn = document.getElementById("runCheck");
const mapEl = document.getElementById("ipMap");
const mapUnavailable = document.getElementById("ipMapUnavailable");

function waitForLeaflet(timeoutMs = 4000) {
  if (typeof window.L !== "undefined") return Promise.resolve();
  return new Promise((resolve, reject) => {
    const started = Date.now();
    const timer = window.setInterval(() => {
      if (typeof window.L !== "undefined") {
        window.clearInterval(timer);
        resolve();
      } else if (Date.now() - started > timeoutMs) {
        window.clearInterval(timer);
        reject(new Error("Map library failed to load"));
      }
    }, 50);
  });
}

async function probeDns(onProgress) {
  try {
    const session = await fetchJson("/api/check/dns/session", { method: "POST" });
    const baseUrl = session.probeUrl || "https://edns.ip-api.com/json";
    const count = session.probes || 6;
    const ips = new Set();
    for (let i = 0; i < count; i += 1) {
      onProgress?.(i, count);
      try {
        const url = `${baseUrl}?_=${Date.now()}-${i}`;
        const res = await fetch(url, { cache: "no-store", mode: "cors", redirect: "follow" });
        if (!res.ok) continue;
        const ans = await res.json();
        if (ans && ans.dns && ans.dns.ip) ips.add(ans.dns.ip);
      } catch {
        /* continue */
      }
      onProgress?.(i + 1, count);
    }
    return [...ips];
  } catch {
    return [];
  }
}

async function run() {
  if (!btn) return;
  setCheckBusy(btn, true, "Running…");
  setOutcome(outcome, null);
  showResultSkeletons(results, 6);
  setStatus(status, "running", "Starting full connection report…");
  try {
    setStatus(status, "running", "Collecting IP, WebRTC, and IPv6…");
    const [ipInfo, rtcIps, v6] = await Promise.all([
      getPublicIp(),
      detectWebRtcIPs(),
      detectIPv6(),
    ]);

    const dnsIps = await probeDns((done, total) => {
      setStatus(status, "running", "Probing DNS resolvers…", {
        progress: done,
        total,
      });
    });

    setStatus(status, "running", "Assembling report…");
    const facts = collectBrowserFacts();
    const publicRtc = rtcIps.filter((ip) => !isPrivateIP(ip));
    const findings = [];
    if (dnsIps.length) findings.push(`DNS resolvers visible: ${dnsIps.join(", ")}`);
    else findings.push("DNS resolver probe returned no identities (may be blocked)");
    if (publicRtc.length) findings.push(`WebRTC public candidates: ${publicRtc.join(", ")}`);
    if (v6) findings.push(`IPv6 observed: ${v6}`);

    clearResults(results);
    addResult(results, "Public IP", ipInfo.ip);
    addResult(results, "Network hint", ipInfo.asOrganization || ipInfo.country || "—");
    addResult(results, "DNS resolvers", dnsIps.length ? dnsIps.join(", ") : "None detected");
    addResult(results, "WebRTC", rtcIps.length ? rtcIps.join(", ") : "None detected");
    addResult(results, "IPv6", v6 || "None detected");
    addResult(results, "Timezone", facts.timezone);
    addResult(results, "Language", facts.language);
    addResult(results, "Platform", facts.platform);
    addResult(results, "Summary", findings.join(" · "));

    try {
      setStatus(status, "running", "Loading approximate location map…");
      await waitForLeaflet();
      renderIpMap(mapEl, ipInfo, { unavailableEl: mapUnavailable });
    } catch {
      renderIpMap(mapEl, {}, { unavailableEl: mapUnavailable });
      if (mapUnavailable) {
        mapUnavailable.textContent = "Map library failed to load.";
        mapUnavailable.classList.add("is-visible");
      }
    }

    const warn = Boolean(dnsIps.length || publicRtc.length);
    setStatus(
      status,
      warn ? "warn" : "ok",
      warn
        ? "This session shows network or DNS signals worth protecting."
        : "Quick checks look quiet for this session."
    );
    setOutcome(outcome, {
      state: warn ? "warn" : "ok",
      title: "What this means",
      body: warn
        ? "Taken together, these signals show how this session can be placed (IP), which resolvers see your lookups (DNS), and whether the browser leaks extra addresses (WebRTC/IPv6). Connect VeritasVPN and re-run — you want the public IP to become the VPN exit and DNS to leave your ISP/public resolver path."
        : "Nothing loud stood out in this quick pass. Re-run after connecting VeritasVPN to confirm the IP and DNS path actually change — that before/after comparison is the best proof the tunnel is doing its job.",
    });
  } catch (err) {
    clearResults(results);
    setStatus(status, "bad", err.message || "Report failed");
    setOutcome(outcome, {
      state: "bad",
      title: "What this means",
      body: "The full report did not finish, so we cannot summarize this session’s exposure right now. Retry in a moment.",
    });
    renderIpMap(mapEl, {}, { unavailableEl: mapUnavailable });
  } finally {
    setCheckBusy(btn, false);
  }
}

btn?.addEventListener("click", () => void run());
