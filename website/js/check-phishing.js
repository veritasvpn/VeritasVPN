import { addResult, clearResults, fetchJson, setCheckBusy, setOutcome, setStatus, showResultSkeletons } from "/js/check-common.js";
import { TURNSTILE_SITE_KEY } from "/js/config.js";

const form = document.getElementById("phishingForm");
const input = document.getElementById("url");
const button = document.getElementById("runCheck");
const status = document.getElementById("status");
const outcome = document.getElementById("outcome");
const results = document.getElementById("results");
const turnstileEl = document.getElementById("phishingTurnstile");

let turnstileWidgetId = null;
let turnstileToken = "";
let turnstileScriptPromise = null;

function loadTurnstile() {
  if (window.turnstile) return Promise.resolve();
  if (turnstileScriptPromise) return turnstileScriptPromise;
  turnstileScriptPromise = new Promise((resolve, reject) => {
    const script = document.createElement("script");
    script.src = "https://challenges.cloudflare.com/turnstile/v0/api.js?render=explicit";
    script.async = true;
    script.onload = () => resolve();
    script.onerror = () => reject(new Error("Could not load verification"));
    document.head.appendChild(script);
  });
  return turnstileScriptPromise;
}

async function ensureTurnstile() {
  if (!turnstileEl || !TURNSTILE_SITE_KEY) return;
  await loadTurnstile();
  turnstileEl.hidden = false;
  if (turnstileWidgetId != null) return;
  turnstileWidgetId = window.turnstile.render(turnstileEl, {
    sitekey: TURNSTILE_SITE_KEY,
    callback: (token) => {
      turnstileToken = token;
    },
    "expired-callback": () => {
      turnstileToken = "";
    },
    "error-callback": () => {
      turnstileToken = "";
    },
  });
}

function resetTurnstile() {
  turnstileToken = "";
  if (turnstileWidgetId != null && window.turnstile) {
    try {
      window.turnstile.reset(turnstileWidgetId);
    } catch {
      /* ignore */
    }
  }
}

void ensureTurnstile().catch(() => {
  /* widget stays hidden until the next attempt reports the error */
});

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
    await ensureTurnstile();
    if (TURNSTILE_SITE_KEY && !turnstileToken) {
      throw new Error("Complete the verification challenge, then try again.");
    }
    const data = await fetchJson("https://api.veritasvpn.cloud/api/v1/phishing/check", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ url, turnstile_token: turnstileToken || undefined }),
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
    resetTurnstile();
    setCheckBusy(button, false);
  }
});
