import {
  setStatus,
  setCheckBusy,
  setOutcome,
  showResultSkeletons,
  clearResults,
  addResult,
  collectBrowserFacts,
} from "/js/check-common.js";

const status = document.getElementById("status");
const results = document.getElementById("results");
const outcome = document.getElementById("outcome");
const btn = document.getElementById("runCheck");

function run() {
  if (!btn) return;
  setCheckBusy(btn, true, "Reading…");
  setOutcome(outcome, null);
  showResultSkeletons(results, 5);
  setStatus(status, "running", "Reading local browser signals…");
  window.requestAnimationFrame(() => {
    window.setTimeout(() => {
      clearResults(results);
      const facts = collectBrowserFacts();
      addResult(results, "User agent", facts.userAgent);
      addResult(results, "Language", facts.language);
      addResult(results, "Languages", facts.languages);
      addResult(results, "Platform", facts.platform);
      addResult(results, "Timezone", facts.timezone);
      addResult(results, "Timezone offset (min)", facts.timezoneOffsetMin);
      addResult(results, "Screen", facts.screen);
      addResult(results, "Viewport", facts.viewport);
      addResult(results, "CPU cores (reported)", facts.hardwareConcurrency);
      addResult(results, "Device memory (reported)", facts.deviceMemory);
      addResult(results, "Touch points", facts.touchPoints);
      addResult(results, "Cookies enabled", facts.cookieEnabled);
      addResult(results, "Do Not Track", facts.doNotTrack || "unset");
      addResult(results, "Network info API", facts.connection || "unavailable");
      setStatus(status, "ok", "Local browser snapshot ready.");
      setOutcome(outcome, {
        state: "ok",
        title: "What this means",
        body: "Sites can read many of these fields without asking. Together they help fingerprint a browser even when your IP changes. A VPN hides the network path; it does not erase these signals. Reduce extensions/unique setups and use privacy-minded browser settings when that matters.",
      });
      setCheckBusy(btn, false);
    }, 180);
  });
}

btn?.addEventListener("click", run);
run();
