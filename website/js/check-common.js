/* Shared helpers for /check tools */

/**
 * @param {HTMLElement | null} el
 * @param {string} state
 * @param {string} text
 * @param {{ progress?: number, total?: number }} [opts]
 */
export function setStatus(el, state, text, opts = {}) {
  if (!el) return;
  el.dataset.state = state || "";
  el.setAttribute("role", "status");
  el.setAttribute("aria-live", "polite");

  if (state === "running") {
    el.replaceChildren();
    const wrap = document.createElement("div");
    wrap.className = "check-loading";

    const spinner = document.createElement("span");
    spinner.className = "check-spinner";
    spinner.setAttribute("aria-hidden", "true");

    const copy = document.createElement("div");
    copy.className = "check-loading-copy";

    const msg = document.createElement("span");
    msg.className = "check-loading-text";
    msg.textContent = text || "Working…";
    copy.append(msg);

    const total = Number(opts.total);
    const progress = Number(opts.progress);
    if (Number.isFinite(total) && total > 0 && Number.isFinite(progress)) {
      const clamped = Math.max(0, Math.min(progress, total));
      const meta = document.createElement("span");
      meta.className = "check-loading-meta";
      meta.textContent = `${clamped} of ${total}`;
      copy.append(meta);

      const bar = document.createElement("div");
      bar.className = "check-progress";
      bar.setAttribute("role", "progressbar");
      bar.setAttribute("aria-valuemin", "0");
      bar.setAttribute("aria-valuemax", String(total));
      bar.setAttribute("aria-valuenow", String(clamped));
      bar.setAttribute("aria-label", text || "Progress");
      const fill = document.createElement("div");
      fill.className = "check-progress-fill";
      fill.style.width = `${(clamped / total) * 100}%`;
      bar.append(fill);
      wrap.append(spinner, copy, bar);
    } else {
      wrap.append(spinner, copy);
    }

    el.append(wrap);
    return;
  }

  el.textContent = text || "";
}

/**
 * Toggle primary check button busy state with inline spinner.
 * @param {HTMLButtonElement | null} btn
 * @param {boolean} busy
 * @param {string} [busyLabel]
 */
export function setCheckBusy(btn, busy, busyLabel = "Working…") {
  if (!btn) return;
  if (!btn.dataset.idleLabel) {
    btn.dataset.idleLabel = btn.textContent.trim() || "Run check";
  }
  btn.disabled = Boolean(busy);
  btn.setAttribute("aria-busy", busy ? "true" : "false");
  btn.classList.toggle("is-busy", Boolean(busy));

  if (busy) {
    btn.replaceChildren();
    const spinner = document.createElement("span");
    spinner.className = "check-spinner check-spinner--btn";
    spinner.setAttribute("aria-hidden", "true");
    const label = document.createElement("span");
    label.textContent = busyLabel;
    btn.append(spinner, label);
  } else {
    btn.textContent = btn.dataset.idleLabel;
  }
}

/**
 * Placeholder result rows while a check is in flight.
 * @param {HTMLElement | null} listEl
 * @param {number} [count]
 */
export function showResultSkeletons(listEl, count = 4) {
  if (!listEl) return;
  listEl.classList.add("is-loading");
  listEl.replaceChildren();
  for (let i = 0; i < count; i += 1) {
    const li = document.createElement("li");
    li.className = "check-skeleton";
    li.setAttribute("aria-hidden", "true");
    const lineSm = document.createElement("span");
    lineSm.className = "check-skeleton-line check-skeleton-line--sm";
    const line = document.createElement("span");
    line.className = "check-skeleton-line";
    li.append(lineSm, line);
    listEl.append(li);
  }
}

export function clearResults(listEl) {
  if (!listEl) return;
  listEl.classList.remove("is-loading");
  listEl.replaceChildren();
}

export function addResult(listEl, label, value) {
  if (!listEl) return;
  listEl.classList.remove("is-loading");
  const li = document.createElement("li");
  const lab = document.createElement("span");
  lab.className = "label";
  lab.textContent = label;
  const val = document.createElement("span");
  val.className = "value";
  val.textContent = value == null || value === "" ? "—" : String(value);
  li.append(lab, val);
  listEl.appendChild(li);
}

export async function fetchJson(url, options = {}) {
  const res = await fetch(url, {
    cache: "no-store",
    ...options,
  });
  const text = await res.text();
  let data = null;
  try {
    data = text ? JSON.parse(text) : null;
  } catch {
    data = { raw: text };
  }
  if (!res.ok) {
    const err = new Error((data && data.error) || `Request failed (${res.status})`);
    err.status = res.status;
    err.data = data;
    throw err;
  }
  return data;
}

export async function getPublicIp() {
  return fetchJson("/api/check/ip");
}

export function collectBrowserFacts() {
  const nav = navigator;
  const screenObj = window.screen || {};
  const conn = nav.connection || nav.mozConnection || nav.webkitConnection;
  return {
    userAgent: nav.userAgent || "",
    language: nav.language || "",
    languages: Array.isArray(nav.languages) ? nav.languages.join(", ") : "",
    platform: nav.platform || "",
    hardwareConcurrency: nav.hardwareConcurrency || "",
    deviceMemory: nav.deviceMemory || "",
    cookieEnabled: nav.cookieEnabled,
    doNotTrack: nav.doNotTrack || nav.msDoNotTrack || window.doNotTrack || "",
    timezone: Intl.DateTimeFormat().resolvedOptions().timeZone || "",
    timezoneOffsetMin: new Date().getTimezoneOffset(),
    screen: `${screenObj.width || "?"}×${screenObj.height || "?"} @${screenObj.colorDepth || "?"}bit`,
    viewport: `${window.innerWidth}×${window.innerHeight}`,
    touchPoints: nav.maxTouchPoints || 0,
    connection: conn
      ? `${conn.effectiveType || "unknown"}${conn.downlink != null ? ` · ${conn.downlink}Mbps` : ""}`
      : "",
  };
}

export function detectWebRtcIPs() {
  return new Promise((resolve) => {
    const ips = new Set();
    const pc = new RTCPeerConnection({
      iceServers: [{ urls: "stun:stun.l.google.com:19302" }],
    });
    const done = () => {
      try {
        pc.close();
      } catch {
        /* ignore */
      }
      resolve([...ips]);
    };
    const timer = window.setTimeout(done, 2500);
    try {
      pc.createDataChannel("veritas-check");
      pc.onicecandidate = (event) => {
        const cand = event.candidate && event.candidate.candidate;
        if (!cand) return;
        const match = cand.match(
          /([0-9]{1,3}(?:\.[0-9]{1,3}){3}|[a-fA-F0-9:]+)/
        );
        if (!match) return;
        const ip = match[1];
        if (ip.includes(".") || ip.includes(":")) ips.add(ip);
      };
      pc.createOffer()
        .then((offer) => pc.setLocalDescription(offer))
        .catch(() => {
          window.clearTimeout(timer);
          done();
        });
    } catch {
      window.clearTimeout(timer);
      done();
    }
  });
}

export async function detectIPv6() {
  try {
    const ctrl = new AbortController();
    const t = window.setTimeout(() => ctrl.abort(), 3500);
    const res = await fetch("https://api64.ipify.org?format=json", {
      signal: ctrl.signal,
      cache: "no-store",
    });
    window.clearTimeout(t);
    if (!res.ok) return null;
    const data = await res.json();
    const ip = data && data.ip;
    return ip && ip.includes(":") ? ip : null;
  } catch {
    return null;
  }
}

export function isPrivateIP(ip) {
  if (!ip) return false;
  if (ip.includes(":")) {
    const lower = ip.toLowerCase();
    return (
      lower === "::1" ||
      lower.startsWith("fc") ||
      lower.startsWith("fd") ||
      lower.startsWith("fe80:")
    );
  }
  return /^(10\.|127\.|192\.168\.|169\.254\.|172\.(1[6-9]|2\d|3[0-1])\.)/.test(
    ip
  );
}
