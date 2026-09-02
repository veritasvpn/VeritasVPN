import {
  setStatus,
  setCheckBusy,
  setOutcome,
  showResultSkeletons,
  clearResults,
  addResult,
  fetchJson,
} from "/js/check-common.js";
import { TURNSTILE_SITE_KEY } from "/js/config.js";

const status = document.getElementById("status");
const results = document.getElementById("results");
const outcome = document.getElementById("outcome");
const form = document.getElementById("breachForm");
const btn = document.getElementById("runCheck");
const turnstileEl = document.getElementById("breachTurnstile");

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
  /* widget optional until submit */
});

form?.addEventListener("submit", async (event) => {
  event.preventDefault();
  const email = document.getElementById("email")?.value.trim() || "";
  setCheckBusy(btn, true, "Checking…");
  setOutcome(outcome, null);
  showResultSkeletons(results, 3);
  setStatus(status, "running", "Checking public breach corpora…");
  try {
    await ensureTurnstile();
    if (TURNSTILE_SITE_KEY && !turnstileToken) {
      throw new Error("Complete the verification challenge, then try again.");
    }
    const data = await fetchJson("/api/check/breach", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ email, turnstile_token: turnstileToken || undefined }),
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
    resetTurnstile();
    setCheckBusy(btn, false);
  }
});
