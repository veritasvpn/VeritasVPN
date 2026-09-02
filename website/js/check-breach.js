import {
  setStatus,
  setCheckBusy,
  showResultSkeletons,
  clearResults,
  addResult,
  fetchJson,
} from "/js/check-common.js";

const status = document.getElementById("status");
const results = document.getElementById("results");
const form = document.getElementById("breachForm");
const btn = document.getElementById("runCheck");

form?.addEventListener("submit", async (event) => {
  event.preventDefault();
  const email = document.getElementById("email")?.value.trim() || "";
  setCheckBusy(btn, true, "Checking…");
  showResultSkeletons(results, 3);
  setStatus(status, "running", "Checking public breach corpora…");
  try {
    const data = await fetchJson("/api/check/breach", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ email }),
    });
    clearResults(results);
    addResult(results, "Appears breached", data.breached ? "Yes" : "No matches found");
    addResult(results, "Breach count", data.breachCount);
    if (data.breaches && data.breaches.length) {
      addResult(results, "Named sources", data.breaches.join(", "));
    }
    addResult(results, "Notice", data.notice);
    setStatus(
      status,
      data.breached ? "warn" : "ok",
      data.breached
        ? "This address appears in at least one public corpus."
        : "No match in the checked corpus."
    );
  } catch (err) {
    clearResults(results);
    setStatus(status, "bad", err.message || "Breach check failed");
  } finally {
    setCheckBusy(btn, false);
  }
});
