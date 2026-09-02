import { setStatus, clearResults, addResult, collectBrowserFacts } from "/js/check-common.js";

const status = document.getElementById("status");
const results = document.getElementById("results");
const btn = document.getElementById("runCheck");

function run() {
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
}

btn?.addEventListener("click", run);
run();
