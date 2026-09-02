/* Shared helpers for /check tools */

export function setStatus(el, state, text) {
  if (!el) return;
  el.dataset.state = state || "";
  el.textContent = text || "";
}

export function clearResults(listEl) {
  if (listEl) listEl.innerHTML = "";
}

export function addResult(listEl, label, value) {
  if (!listEl) return;
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
