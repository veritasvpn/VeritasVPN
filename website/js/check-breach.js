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
const form = document.getElementById("breachForm");
const btn = document.getElementById("runCheck");

form?.addEventListener("submit", async (event) => {
  event.preventDefault();
  const email = document.getElementById("email")?.value.trim() || "";
  setCheckBusy(btn, true, "Checking…");
  setOutcome(outcome, null);
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
    if (data.breached) {
      setStatus(status, "warn", "This address appears in at least one public corpus.");
      setOutcome(outcome, {
        state: "warn",
        title: "What this means",
        body: "Public breach data lists this email (and often a password used with it) from one or more past incidents. Change any reused passwords, enable 2FA where you can, and treat this as a credential-hygiene alert — not a VeritasVPN outage. Network privacy is separate: still protect DNS and IP with a tunnel.",
      });
    } else {
      setStatus(status, "ok", "No match in the checked corpus.");
      setOutcome(outcome, {
        state: "ok",
        title: "What this means",
        body: "This address was not found in the corpora we queried. That is good news, but public dumps are incomplete — it is not a full security audit. Keep unique passwords and 2FA anyway, and protect your connection path with VeritasVPN.",
      });
    }
  } catch (err) {
    clearResults(results);
    setStatus(status, "bad", err.message || "Breach check failed");
    setOutcome(outcome, {
      state: "bad",
      title: "What this means",
      body: "The breach lookup failed, so we cannot say whether this address appears in public dumps right now. Retry in a moment.",
    });
  } finally {
    setCheckBusy(btn, false);
  }
});
