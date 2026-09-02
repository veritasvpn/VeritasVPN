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
  isPrivateIP,
} from "/js/check-common.js";

const status = document.getElementById("status");
const results = document.getElementById("results");
const outcome = document.getElementById("outcome");
const btn = document.getElementById("runCheck");

async function run() {
  if (!btn) return;
  setCheckBusy(btn, true, "Checking…");
  setOutcome(outcome, null);
  showResultSkeletons(results, 4);
  setStatus(status, "running", "Collecting IP, WebRTC, and IPv6 signals…");
  try {
    const [ipInfo, rtcIps, v6] = await Promise.all([
      getPublicIp(),
      detectWebRtcIPs(),
      detectIPv6(),
    ]);
    clearResults(results);
    addResult(results, "Public IPv4/IPv6 (HTTPS)", ipInfo.ip);
    addResult(results, "Network hint", ipInfo.asOrganization || ipInfo.country);
    addResult(
      results,
      "WebRTC candidates",
      rtcIps.length ? rtcIps.join(", ") : "None detected (blocked or unsupported)"
    );
    addResult(results, "IPv6 via api64.ipify", v6 || "No IPv6 address detected");

    const publicRtc = rtcIps.filter((ip) => !isPrivateIP(ip));
    const warnings = [];
    if (publicRtc.length) {
      warnings.push("WebRTC exposed at least one non-private address");
    }
    if (v6 && ipInfo.ip && v6 !== ipInfo.ip) {
      warnings.push("IPv6 path differs from the primary HTTPS IP");
    }
    if (warnings.length) {
      addResult(results, "Findings", warnings.join(" · "));
      setStatus(status, "warn", "Possible leak signals in this browser session.");
      setOutcome(outcome, {
        state: "warn",
        title: "What this means",
        body: "At least one path may reveal an address outside what you expect from a single VPN exit. If you are connected to a VPN, treat this as a leak to investigate (WebRTC permissions, IPv6, or split routing). If you are not on a VPN, these signals are normal for an unprotected browser.",
      });
    } else {
      addResult(results, "Findings", "No obvious WebRTC/IPv6 mismatch in this quick check");
      setStatus(status, "ok", "Basic leak signals look quiet for this session.");
      setOutcome(outcome, {
        state: "ok",
        title: "What this means",
        body: "This quick check did not find an obvious WebRTC or IPv6 mismatch. That is a good sign for this browser, but it is not a full guarantee. Connect VeritasVPN and re-run to confirm the public IP matches the VPN exit.",
      });
    }
  } catch (err) {
    clearResults(results);
    setStatus(status, "bad", err.message || "VPN leak test failed");
    setOutcome(outcome, {
      state: "bad",
      title: "What this means",
      body: "The leak test did not finish, so we cannot score WebRTC/IPv6 exposure right now. Retry in a moment.",
    });
  } finally {
    setCheckBusy(btn, false);
  }
}

btn?.addEventListener("click", () => void run());
