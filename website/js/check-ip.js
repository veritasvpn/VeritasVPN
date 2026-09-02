import {
  setStatus,
  setCheckBusy,
  setOutcome,
  showResultSkeletons,
  clearResults,
  addResult,
  getPublicIp,
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

async function run() {
  if (!btn) return;
  setCheckBusy(btn, true, "Checking…");
  setOutcome(outcome, null);
  showResultSkeletons(results, 5);
  setStatus(status, "running", "Looking up this connection’s public IP…");
  try {
    const data = await getPublicIp();
    clearResults(results);
    addResult(results, "Public IP", data.ip);
    addResult(results, "Country", data.country);
    addResult(results, "Network / ASN hint", data.asOrganization);
    addResult(results, "City", data.city);
    addResult(results, "Region", data.region);
    addResult(results, "Edge colo", data.colo);
    addResult(results, "Checked at", data.checkedAt);
    setStatus(status, "ok", "Public IP retrieved for this request.");
    setOutcome(outcome, {
      state: "ok",
      title: "What this means",
      body: data.ip
        ? `Sites and networks that see this connection can treat ${data.ip} as the public address for this path. Off a VPN, that is usually an address your ISP (or mobile carrier) assigned to this connection—not your street address. Geo hints are city-level at best. Connect VeritasVPN and re-check — destinations should see the Paraguay exit IP instead.`
        : "We could not determine a public IP for this request. Try again, or check from another network.",
    });

    try {
      setStatus(status, "running", "Loading approximate location map…");
      await waitForLeaflet();
      renderIpMap(mapEl, data, { unavailableEl: mapUnavailable });
      setStatus(status, "ok", "Public IP retrieved for this request.");
    } catch {
      renderIpMap(mapEl, {}, { unavailableEl: mapUnavailable });
      if (mapUnavailable) {
        mapUnavailable.textContent = "Map library failed to load.";
        mapUnavailable.classList.add("is-visible");
      }
      setStatus(status, "ok", "Public IP retrieved for this request.");
    }
  } catch (err) {
    clearResults(results);
    setStatus(status, "bad", err.message || "Check failed");
    setOutcome(outcome, {
      state: "bad",
      title: "What this means",
      body: "The IP lookup failed, so we cannot show what this connection exposes right now. Retry in a moment.",
    });
    renderIpMap(mapEl, {}, { unavailableEl: mapUnavailable });
  } finally {
    setCheckBusy(btn, false);
  }
}

btn?.addEventListener("click", () => void run());
void run();
