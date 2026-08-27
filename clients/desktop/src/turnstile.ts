/** Obtain a Cloudflare Turnstile token via the hosted mobile page (iframe). */
const TURNSTILE_PAGE = "https://veritasvpn.cloud/turnstile-mobile";

export function obtainTurnstileToken(timeoutMs = 120_000): Promise<string> {
  return new Promise((resolve, reject) => {
    const overlay = document.createElement("div");
    overlay.setAttribute("role", "dialog");
    overlay.setAttribute("aria-modal", "true");
    overlay.style.cssText =
      "position:fixed;inset:0;z-index:10000;background:rgba(6,16,28,0.92);display:flex;align-items:center;justify-content:center;padding:16px;";

    const panel = document.createElement("div");
    panel.style.cssText =
      "width:min(360px,100%);background:#0b1726;border:1px solid #1e2f45;border-radius:12px;padding:12px;display:grid;gap:10px;";

    const title = document.createElement("p");
    title.textContent = "Complete verification to continue";
    title.style.cssText = "margin:0;color:#9db0c7;font-size:14px;text-align:center;";

    const frame = document.createElement("iframe");
    frame.src = TURNSTILE_PAGE;
    frame.title = "Cloudflare Turnstile";
    frame.style.cssText = "width:100%;height:120px;border:0;border-radius:8px;background:#06101c;";

    const cancel = document.createElement("button");
    cancel.type = "button";
    cancel.textContent = "Cancel";
    cancel.style.cssText =
      "appearance:none;border:0;background:transparent;color:#9db0c7;padding:8px;cursor:pointer;";

    panel.append(title, frame, cancel);
    overlay.append(panel);
    document.body.appendChild(overlay);

    let settled = false;
    const timer = window.setTimeout(() => finish(new Error("Verification timed out.")), timeoutMs);

    function cleanup() {
      window.clearTimeout(timer);
      window.removeEventListener("message", onMessage);
      overlay.remove();
    }

    function finish(err?: Error, token?: string) {
      if (settled) return;
      settled = true;
      cleanup();
      if (err) reject(err);
      else resolve(token || "");
    }

    function onMessage(event: MessageEvent) {
      const data = event.data;
      if (!data || data.source !== "veritas-turnstile") return;
      if (data.type === "token" && typeof data.token === "string" && data.token) {
        finish(undefined, data.token);
        return;
      }
      if (data.type === "error" || data.type === "expired") {
        finish(new Error("Verification failed; please try again."));
      }
    }

    cancel.addEventListener("click", () => finish(new Error("Verification cancelled.")));
    window.addEventListener("message", onMessage);
  });
}
