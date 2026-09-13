/** Keep one hosted Turnstile iframe warm while the signed-out app is visible. */
const TURNSTILE_ORIGIN = "https://veritasvpn.cloud";
const TURNSTILE_PAGE = `${TURNSTILE_ORIGIN}/turnstile-mobile?return_origin=${encodeURIComponent(window.location.origin)}`;

let frame: HTMLIFrameElement | null = null;
let warmHost: HTMLDivElement | null = null;
let token = "";
let pending: {
  resolve: (value: string) => void;
  reject: (reason: Error) => void;
  timer: number;
  revealTimer: number;
  overlay: HTMLDivElement;
  panel: HTMLDivElement;
} | null = null;

function resetFrame() {
  token = "";
  try {
    frame?.contentWindow?.postMessage({ source: "veritas-turnstile-host", type: "reset" }, TURNSTILE_ORIGIN);
  } catch (_) {}
}

function returnToWarmHost() {
  if (frame && warmHost && frame.parentElement !== warmHost) warmHost.appendChild(frame);
}

function complete(err?: Error, value?: string) {
  if (!pending) return;
  const current = pending;
  pending = null;
  window.clearTimeout(current.timer);
  window.clearTimeout(current.revealTimer);
  current.overlay.remove();
  returnToWarmHost();
  if (err) current.reject(err);
  else current.resolve(value || "");
}

function handleMessage(event: MessageEvent) {
  if (event.origin !== TURNSTILE_ORIGIN) return;
  const data = event.data;
  if (!data || data.source !== "veritas-turnstile") return;
  if (data.type === "token" && typeof data.token === "string" && data.token) {
    token = data.token;
    if (pending) {
      const consumed = token;
      resetFrame();
      complete(undefined, consumed);
    }
    return;
  }
  if ((data.type === "error" || data.type === "expired") && pending) {
    complete(new Error("Security check failed. Complete the check and try again."));
  }
}

/** Start the challenge before a person presses a sign-in button. */
export function prewarmTurnstile(): void {
  if (frame) return;
  warmHost = document.createElement("div");
  warmHost.setAttribute("aria-hidden", "true");
  warmHost.style.cssText = "position:fixed;left:-10000px;top:0;width:360px;height:170px;opacity:0;pointer-events:none;overflow:hidden;";
  frame = document.createElement("iframe");
  frame.src = TURNSTILE_PAGE;
  frame.title = "Cloudflare Turnstile";
  frame.style.cssText = "width:100%;height:160px;border:0;border-radius:8px;background:#06101c;";
  warmHost.appendChild(frame);
  document.body.appendChild(warmHost);
  window.addEventListener("message", handleMessage);
}

function showPendingCheck(): void {
  if (!pending || !frame) return;
  pending.overlay.style.display = "flex";
  const cancel = pending.panel.querySelector("button");
  pending.panel.insertBefore(frame, cancel);
}

/** Use a prewarmed token, revealing the existing iframe only when needed. */
export function obtainTurnstileToken(timeoutMs = 120_000): Promise<string> {
  prewarmTurnstile();
  if (token) {
    const consumed = token;
    resetFrame();
    return Promise.resolve(consumed);
  }
  if (pending) return Promise.reject(new Error("Security check is already in progress."));

  const overlay = document.createElement("div");
  overlay.setAttribute("role", "dialog");
  overlay.setAttribute("aria-modal", "true");
  overlay.style.cssText = "position:fixed;inset:0;z-index:10000;background:rgba(6,16,28,0.92);display:none;align-items:center;justify-content:center;padding:16px;";
  const panel = document.createElement("div");
  panel.style.cssText = "width:min(360px,100%);background:#0b1726;border:1px solid #1e2f45;border-radius:12px;padding:12px;display:grid;gap:10px;";
  const title = document.createElement("p");
  title.textContent = "Finishing the security check…";
  title.style.cssText = "margin:0;color:#9db0c7;font-size:14px;text-align:center;";
  const status = document.createElement("p");
  status.textContent = "This normally takes a moment.";
  status.style.cssText = "margin:0;color:#6b7f96;font-size:12px;text-align:center;";
  const cancel = document.createElement("button");
  cancel.type = "button";
  cancel.textContent = "Cancel";
  cancel.style.cssText = "appearance:none;border:0;background:transparent;color:#9db0c7;padding:8px;cursor:pointer;";
  panel.append(title, status, cancel);
  overlay.append(panel);
  document.body.appendChild(overlay);

  return new Promise((resolve, reject) => {
    const timeout = window.setTimeout(() => complete(new Error("Security check timed out. Try signing in again.")), timeoutMs);
    const reveal = window.setTimeout(showPendingCheck, 600);
    pending = { resolve, reject, timer: timeout, revealTimer: reveal, overlay, panel };
    cancel.addEventListener("click", () => complete(new Error("Security check cancelled.")));
  });
}
