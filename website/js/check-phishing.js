import { addResult, clearResults, fetchJson, setCheckBusy, setOutcome, setStatus, showResultSkeletons } from "/js/check-common.js";

const form = document.getElementById("phishingForm");
const input = document.getElementById("url");
const button = document.getElementById("runCheck");
const status = document.getElementById("status");
const outcome = document.getElementById("outcome");
const results = document.getElementById("results");

const verdicts = {
  high_risk: {
    state: "bad",
    label: "High risk signals found",
    body: "This link has several warning signs. Do not sign in, pay, download files, or give it information unless you independently verify the destination.",
  },
  suspicious: {
    state: "warn",
    label: "Some caution signals found",
    body: "This link has one or more patterns worth checking. Confirm the real destination through a known bookmark, official app, or independently typed address.",
  },
  no_obvious_signals: {
    state: "ok",
    label: "No obvious phishing signals found",
    body: "The link did not trigger this tool’s checks. That is not a safety guarantee—new scams and compromised sites can still look normal.",
  },
};

function titleCase(value) {
  return String(value || "").replaceAll("_", " ").replace(/\b\w/g, (letter) => letter.toUpperCase());
}

form?.addEventListener("submit", async (event) => {
  event.preventDefault();
  const url = input.value.trim();
  clearResults(results);
  setOutcome(outcome, null);
  setStatus(status, "running", "Checking the link without opening the page…");
  showResultSkeletons(results, 3);
  setCheckBusy(button, true, "Analyzing…");
  try {
    const data = await fetchJson("https://api.veritasvpn.cloud/api/v1/phishing/check", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ url }),
    });
    const verdict = verdicts[data.verdict] || verdicts.suspicious;
    clearResults(results);
    addResult(results, "Assessment", verdict.label);
    addResult(results, "Checked host", data.hostname);
    addResult(results, "Resolved public addresses", (data.resolvedIps || []).join(", ") || "Not available");
    if (data.tls) {
      addResult(results, "HTTPS certificate", data.tls.verified ? `Verified${data.tls.issuer ? ` — ${data.tls.issuer}` : ""}` : "Could not be verified");
      if (data.tls.expiresAt) addResult(results, "Certificate expires", new Date(data.tls.expiresAt).toLocaleString());
    }
    for (const finding of data.findings || []) addResult(results, titleCase(finding.severity), finding.message);
    addResult(results, "Privacy", data.notice);
    setStatus(status, verdict.state, verdict.label);
    setOutcome(outcome, { state: verdict.state, title: "What this means", body: verdict.body });
  } catch (error) {
    clearResults(results);
    setStatus(status, "bad", error.message || "The link check failed.");
    setOutcome(outcome, { state: "bad", title: "No result", body: "The link could not be checked. Do not treat that as a safe result—try again later or verify the destination through an official channel." });
  } finally {
    setCheckBusy(button, false);
  }
});
