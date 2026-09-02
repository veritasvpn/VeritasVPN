import {
  setStatus,
  clearResults,
  addResult,
  getPublicIp,
  detectWebRtcIPs,
  detectIPv6,
  collectBrowserFacts,
  fetchJson,
  isPrivateIP,
} from "/js/check-common.js";

const status = document.getElementById("status");
const results = document.getElementById("results");
const btn = document.getElementById("runCheck");

async function probeDns() {
  try {
    const session = await fetchJson("/api/check/dns/session", { method: "POST" });
    const baseUrl = session.probeUrl || "https://edns.ip-api.com/json";
    const count = session.probes || 6;
    const ips = new Set();
    for (let i = 0; i < count; i += 1) {
      try {
        const url = `${baseUrl}?_=${Date.now()}-${i}`;
        const res = await fetch(url, { cache: "no-store", mode: "cors", redirect: "follow" });
        if (!res.ok) continue;
        const ans = await res.json();
        if (ans && ans.dns && ans.dns.ip) ips.add(ans.dns.ip);
      } catch {
        /* continue */
      }
    }
    return [...ips];
  } catch {
    return [];
  }
}

async function run() {
  if (!btn) return;
  btn.disabled = true;
  clearResults(results);
  setStatus(status, "running", "Running IP, DNS, VPN, and browser checks…");
  try {
    const [ipInfo, rtcIps, v6, dnsIps] = await Promise.all([
      getPublicIp(),
      detectWebRtcIPs(),
      detectIPv6(),
      probeDns(),
    ]);
    const facts = collectBrowserFacts();
    const publicRtc = rtcIps.filter((ip) => !isPrivateIP(ip));
    const findings = [];
    if (dnsIps.length) findings.push(`DNS resolvers visible: ${dnsIps.join(", ")}`);
    else findings.push("DNS resolver probe returned no identities (may be blocked)");
    if (publicRtc.length) findings.push(`WebRTC public candidates: ${publicRtc.join(", ")}`);
    if (v6) findings.push(`IPv6 observed: ${v6}`);

    addResult(results, "Public IP", ipInfo.ip);
    addResult(results, "Network hint", ipInfo.asOrganization || ipInfo.country || "—");
    addResult(results, "DNS resolvers", dnsIps.length ? dnsIps.join(", ") : "None detected");
    addResult(results, "WebRTC", rtcIps.length ? rtcIps.join(", ") : "None detected");
    addResult(results, "IPv6", v6 || "None detected");
    addResult(results, "Timezone", facts.timezone);
    addResult(results, "Language", facts.language);
    addResult(results, "Platform", facts.platform);
    addResult(results, "Summary", findings.join(" · "));

    const warn = Boolean(dnsIps.length || publicRtc.length);
    setStatus(
      status,
      warn ? "warn" : "ok",
      warn
        ? "This session shows network or DNS signals worth protecting."
        : "Quick checks look quiet. Re-run after connecting a VPN to compare."
    );
  } catch (err) {
    setStatus(status, "bad", err.message || "Report failed");
  } finally {
    btn.disabled = false;
  }
}

btn?.addEventListener("click", () => void run());
