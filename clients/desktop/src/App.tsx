import { useState, useEffect, FormEvent, useCallback, useRef } from "react";
import { invoke } from "@tauri-apps/api/core";
import { fetch } from "@tauri-apps/plugin-http";
import {
  getStoredUser,
  initializeSecureAuth,
  signIn as doSignIn,
  signUp as doSignUp,
  signInWithAccountId as doSignInAccountId,
  registerAnonymous as doRegisterAnonymous,
  signOut as doSignOut,
  resetPassword,
  resendVerification,
  downloadAccountFile,
  validateSignupPassword,
  passwordStrengthScore,
  VerificationRequiredError,
  AccountAlreadyExistsError,
  User,
  validateSessionOnResume,
} from "./auth";
import { fetchWithAuth, SessionExpiredError, SESSION_EXPIRED_EVENT } from "./session";
import {
  readCachedBillingStatus,
  writeCachedBillingStatus,
  clearCachedBillingStatus,
  BillingStatus,
} from "./billing";
import { AUTH_API } from "./config";
import { obtainTurnstileToken } from "./turnstile";
import { SettingsDrawer } from "./SettingsDrawer";
import veritasMark from "./assets/veritas-mark.png";
import "./App.css";

type AuthMode = "signin" | "signup";
type AuthMethod = "email" | "accountId";
type TunnelMode = "wireguard" | "";

/** Production BTCPay checkout hosts (mainnet). Reject anything else. */
const ALLOWED_BTCPAY_CHECKOUT_PREFIXES = [
  "https://btcpay-mainnet.veritasvpn.cloud/",
] as const;

function isAllowedBtcpayCheckoutUrl(url: string | undefined): boolean {
  if (!url) return false;
  return ALLOWED_BTCPAY_CHECKOUT_PREFIXES.some((prefix) => url.startsWith(prefix));
}

interface ConnectResult {
  success: boolean;
  message: string;
  mode: string;
  peer_id: string;
}

interface KeyPair {
  private_key: string;
  public_key: string;
}

interface PeerResponse {
  peer_id: string;
  server_public_key: string;
  server_endpoint: string;
  stealth_endpoint?: string;
  stealth_available?: boolean;
  stealth_path_prefix?: string;
  assigned_ip: string;
  dns_server: string;
  preshared_key?: string;
  allowed_ips?: string[];
  client_allowed_ips?: string[];
  error?: string;
}

const CONNECT_TIMEOUT_MS = 25_000;
const STATS_POLL_MS = 1_500;
const PEERS_POLL_MS = 5_000;
const HANDSHAKE_HEALTHY_SEC = 180;
const RECONNECT_BACKOFF_MS = [2_000, 5_000, 15_000, 30_000];
const LS_AUTO_RECONNECT = "veritas_auto_reconnect";
const LS_EXCLUDE_LAN = "veritas_exclude_lan";
const LS_STEALTH = "veritas_stealth_mode";
const EGRESS_ENDPOINTS = [
  "https://api.ipify.org",
  "https://ifconfig.me/ip",
  "https://icanhazip.com",
];

/** Practical AllowedIPs covering the public internet while excluding RFC1918. */
const EXCLUDE_LAN_ALLOWED_IPS = [
  "0.0.0.0/5",
  "8.0.0.0/7",
  "11.0.0.0/8",
  "12.0.0.0/6",
  "16.0.0.0/4",
  "32.0.0.0/3",
  "64.0.0.0/2",
  "128.0.0.0/3",
  "160.0.0.0/5",
  "168.0.0.0/6",
  "172.0.0.0/12",
  "172.32.0.0/11",
  "172.64.0.0/10",
  "172.128.0.0/9",
  "173.0.0.0/8",
  "174.0.0.0/7",
  "176.0.0.0/4",
  "192.0.0.0/9",
  "192.128.0.0/11",
  "192.160.0.0/13",
  "192.169.0.0/16",
  "192.170.0.0/15",
  "192.172.0.0/14",
  "192.176.0.0/12",
  "192.192.0.0/10",
  "193.0.0.0/8",
  "194.0.0.0/7",
  "196.0.0.0/6",
  "200.0.0.0/5",
  "208.0.0.0/4",
];

interface WgTransferStats {
  rx_bytes: number;
  tx_bytes: number;
  last_handshake_sec: number;
  interface_up: boolean;
}

interface PeerInfo {
  id: string;
  assigned_ip?: string;
  status?: string;
  created_at?: number;
  dns_blocked_count?: number;
}

interface PortForwardInfo {
  id: string;
  peer_id: string;
  protocol: string;
  external_port: number;
  internal_port?: number;
  status?: string;
  assigned_ip?: string;
  egress_endpoint?: string;
  created_at?: number;
}

function readLocalFlag(key: string, defaultValue: "0" | "1"): boolean {
  try {
    const raw = localStorage.getItem(key);
    return (raw ?? defaultValue) === "1";
  } catch {
    return defaultValue === "1";
  }
}

function writeLocalFlag(key: string, enabled: boolean) {
  try {
    localStorage.setItem(key, enabled ? "1" : "0");
  } catch {
    // ignore quota / private mode
  }
}

/** Stealth (wstunnel) is Linux-desktop only in this build. */
function isLinuxDesktop(): boolean {
  if (typeof navigator === "undefined") return false;
  const ua = navigator.userAgent.toLowerCase();
  return ua.includes("linux") && !ua.includes("android");
}

function formatConnectError(err: unknown, wantedStealth: boolean): string {
  let raw = "Connection failed";
  if (err instanceof Error) raw = err.message;
  else if (typeof err === "string") raw = err;
  else if (err && typeof err === "object" && "message" in err && typeof (err as { message: unknown }).message === "string") {
    raw = (err as { message: string }).message;
  }
  const lower = raw.toLowerCase();
  if (
    wantedStealth ||
    /stealth|wstunnel/.test(lower)
  ) {
    if (/wstunnel|stealth engine/.test(lower) && /missing|not found|not bundled/.test(lower)) {
      return "Stealth failed: wstunnel is not bundled in this build. Rebuild with the Linux stealth binary, or turn Stealth off.";
    }
    if (/stealth engine missing/.test(lower)) {
      return "Stealth failed: wstunnel binary missing. Bundle it for Linux or turn Stealth off.";
    }
    if (/linux desktop|linux only|available on linux/.test(lower)) {
      return "Stealth mode is Linux-only in this build. Turn it off to connect with Direct UDP.";
    }
    if (/failed to start stealth|stealth transport/.test(lower)) {
      return "Stealth transport failed to start. Check TLS endpoint reachability, or turn Stealth off.";
    }
    if (/path prefix/.test(lower)) {
      return "Stealth failed: server path prefix missing. Try again later or turn Stealth off.";
    }
    if (/not available on the (server|vpn node)/.test(lower)) {
      return "Stealth is not available on the VPN node yet. Turn Stealth off or try again later.";
    }
  }
  if (/firewall kill switch|kill switch/.test(lower)) {
    if (/nftables|iptables/.test(lower)) {
      return "Kill switch required: install nftables or iptables, then connect again. There is no off option.";
    }
    return "Kill switch could not be enabled. Connect was cancelled so traffic stays unprotected only while you fix it.";
  }
  return raw || "Connection failed";
}

function isStickyStatusMessage(msg: string): boolean {
  return /stealth|kill switch/i.test(msg);
}

function applyExcludeLan(allowed: string[], excludeLan: boolean): string[] {
  if (!excludeLan || !allowed.includes("0.0.0.0/0")) return allowed;
  return [...allowed.filter((ip) => ip !== "0.0.0.0/0"), ...EXCLUDE_LAN_ALLOWED_IPS];
}

function formatBytes(bytes: number): string {
  if (!Number.isFinite(bytes) || bytes < 0) return "0 B";
  if (bytes < 1024) return `${Math.floor(bytes)} B`;
  const kb = bytes / 1024;
  if (kb < 1024) return `${kb.toFixed(1)} KB`;
  const mb = kb / 1024;
  if (mb < 1024) return `${mb.toFixed(1)} MB`;
  return `${(mb / 1024).toFixed(2)} GB`;
}

function formatHandshakeAge(lastHandshakeSec: number): string {
  if (!lastHandshakeSec || lastHandshakeSec <= 0) return "never";
  const ageSec = Math.max(0, Math.floor(Date.now() / 1000 - lastHandshakeSec));
  if (ageSec < 60) return `${ageSec}s ago`;
  if (ageSec < 3600) return `${Math.floor(ageSec / 60)}m ago`;
  return `${Math.floor(ageSec / 3600)}h ago`;
}

function shortPeerId(id: string): string {
  if (!id) return "—";
  return id.length <= 12 ? id : `${id.slice(0, 8)}…`;
}

function formatBillingDate(value?: string) {
  if (!value) return "the end of your current billing period";
  const date = new Date(value);
  return Number.isNaN(date.getTime())
    ? value.slice(0, 10)
    : new Intl.DateTimeFormat(undefined, { dateStyle: "medium" }).format(date);
}

function ConnectionMap({
  connected,
  connecting,
  deviceLabel,
}: {
  connected: boolean;
  connecting: boolean;
  deviceLabel: string;
}) {
  return (
    <section className={`connection-map ${connected ? "is-connected" : ""}`} aria-label="VPN route to Paraguay">
      <div className="map-topline"><span>LIVE ROUTE</span><span className="map-latency">{connected ? "ENCRYPTED" : connecting ? "CONNECTING" : "READY"}</span></div>
      <img className="world-map" src="/world-map.svg" alt="World map" />
      <svg className="route-overlay" viewBox="0 0 900 430" aria-hidden="true">
        <defs><linearGradient id="routeGradient" x1="0" x2="1"><stop offset="0" stopColor="#09C7F5"/><stop offset="1" stopColor="#0756D9"/></linearGradient><filter id="routeGlow"><feGaussianBlur stdDeviation="4" result="blur"/><feMerge><feMergeNode in="blur"/><feMergeNode in="SourceGraphic"/></feMerge></filter></defs>
        <path className="route-shadow" d="M134 135C274 79 391 154 523 294S592 326 621 322"/>
        <path className="route-line" d="M134 135C274 79 391 154 523 294S592 326 621 322"/>
        <circle className="route-particle" r="4"><animateMotion dur="2.8s" repeatCount="indefinite" path="M134 135C274 79 391 154 523 294S592 326 621 322"/></circle>
        <g className="map-origin" transform="translate(134 135)"><circle r="6"/><circle className="map-pulse" r="14"/></g>
        <g className="map-destination" transform="translate(621 322)"><circle className="map-pulse" r="19"/><circle r="8"/></g>
      </svg>
      <div className="route-label route-label-origin"><span>YOUR DEVICE</span><strong>{connected ? "Encrypted route" : deviceLabel}</strong></div>
      <div className="route-label route-label-destination"><span>PARAGUAY</span><strong>Asunción metro</strong></div>
    </section>
  );
}

function PrivacyScene({ encrypted }: { encrypted: boolean }) {
  const activities = encrypted
    ? ["Your activity is private", "Your IP address is hidden", "Trackers cannot inspect traffic"]
    : ["Sites you visit", "Your IP address", "Searches and activity"];
  return (
    <section className={`privacy-exposure ${encrypted ? "is-encrypted" : ""}`} aria-label={encrypted ? "Encrypted traffic visualization" : "Visible traffic visualization"}>
      <div className="exposure-topline"><strong>{encrypted ? "ENCRYPTED TRAFFIC" : "UNENCRYPTED TRAFFIC"}</strong><b>{encrypted ? "IP HIDDEN" : "IP VISIBLE"}</b></div>
      <svg className="exposure-routes" viewBox="0 0 600 330" aria-hidden="true">
        <g className="exposure-lines"><path d="M300 285L300 68"/><path d="M300 285L95 170"/><path d="M300 285L505 170"/></g>
        {["M300 285L300 68", "M300 285L95 170", "M300 285L505 170"].map((path, index) => (
          <g key={path}><circle className={`data-particle particle-${index + 1}`} r="5"><animateMotion dur={`${2.2 + index * .25}s`} repeatCount="indefinite" path={path}/></circle><circle className="data-particle small" r="3"><animateMotion begin={`${.65 + index * .2}s`} dur={`${2.2 + index * .25}s`} repeatCount="indefinite" path={path}/></circle></g>
        ))}
      </svg>
      <div className="observer observer-isp"><i>ISP</i><strong>YOUR ISP</strong><span>{encrypted ? "Sees encrypted data" : "Can observe traffic"}</span></div>
      <div className="observer observer-site"><i>WEB</i><strong>WEBSITE</strong><span>{encrypted ? "Sees the VPN IP" : "Sees your IP"}</span></div>
      <div className="observer observer-trackers"><i>WEB</i><strong>TRACKERS</strong><span>{encrypted ? "Traffic is obscured" : "Build a profile"}</span></div>
      <div className="activity-carousel">{activities.map((activity, index) => <span key={activity} style={{ animationDelay: `${index * 1.9}s` }}>{activity}</span>)}</div>
      <div className="device-node"><span className="mini-lock"/><strong>YOU</strong></div>
      <span className="scene-scan"/>
    </section>
  );
}

function PasswordStrength({ password }: { password: string }) {
  const score = passwordStrengthScore(password);
  if (!password) return null;
  const labels = ["Weak", "Fair", "Good", "Strong", "Excellent"];
  const label = labels[Math.min(score, labels.length) - 1] || "Weak";
  return (
    <div className="password-strength" aria-live="polite">
      <div className="password-strength-bars">
        {[1, 2, 3, 4, 5].map((level) => (
          <span key={level} className={score >= level ? "on" : ""} />
        ))}
      </div>
      <span>{label}</span>
    </div>
  );
}

function PlansScreen({
  billingStatus,
  billingLoading,
  billingBusy,
  checkoutMethod,
  billingError,
  selectedPlan,
  showCancelConfirmation,
  onBack,
  onRefresh,
  onSelectPlan,
  onCheckout,
  onCancelClick,
  onCancelConfirm,
  onCancelDismiss,
}: {
  billingStatus: BillingStatus | null;
  billingLoading: boolean;
  billingBusy: boolean;
  checkoutMethod: string | null;
  billingError: string;
  selectedPlan: "premium_monthly" | "premium_annual";
  showCancelConfirmation: boolean;
  onBack: () => void;
  onRefresh: () => void;
  onSelectPlan: (plan: "premium_monthly" | "premium_annual") => void;
  onCheckout: () => void;
  onCancelClick: () => void;
  onCancelConfirm: () => void;
  onCancelDismiss: () => void;
}) {
  const premium = billingStatus?.is_premium === true;
  const price = selectedPlan === "premium_annual" ? "$30" : "$3";
  const suffix = selectedPlan === "premium_annual" ? "/year" : "/month";
  return (
    <section className="plans-screen">
      <div className="plans-head">
        <button type="button" className="plans-back" onClick={onBack} aria-label="Back">←</button>
        <div>
          <h2>Plans & billing</h2>
          <p>Choose the privacy plan that fits you</p>
        </div>
      </div>

      <div className="billing-current">
        <div>
          <span>CURRENT PLAN</span>
          <strong className={premium ? "premium" : ""}>
            {billingLoading && !billingStatus ? "Checking subscription…" : premium ? "Premium" : "No active subscription"}
          </strong>
          {!billingLoading && premium && billingStatus?.current_period_end && (
            <small className="billing-period-end">
              {billingStatus.cancel_at_period_end ? "Premium ends" : "Current access ends"}{" "}
              <time dateTime={billingStatus.current_period_end}>{formatBillingDate(billingStatus.current_period_end)}</time>
            </small>
          )}
        </div>
        <button type="button" disabled={billingLoading} onClick={onRefresh}>
          {billingLoading ? <i className="button-spinner" /> : "Refresh"}
        </button>
      </div>

      {billingError && <div className="billing-error">{billingError}</div>}

      <div className="plan-card">
        <div className="plan-card-top">
          <h3>Premium</h3>
          {premium && <span className="plan-current-pill">CURRENT</span>}
        </div>
        <div className="plan-price">{price}<small>{suffix}</small></div>
        <ul className="plan-features">
          <li>Paraguay WireGuard egress</li>
          <li>Up to 5 VPN devices</li>
          <li>Private Bitcoin checkout</li>
          <li>Chrome, Android, and Linux access</li>
        </ul>
      </div>

      {!premium && (
        <div className="billing-plan-options">
          <button type="button" className={selectedPlan === "premium_monthly" ? "selected" : ""} onClick={() => onSelectPlan("premium_monthly")}>
            <strong>Monthly</strong>
            <span>$3 / 30 days</span>
          </button>
          <button type="button" className={selectedPlan === "premium_annual" ? "selected" : ""} onClick={() => onSelectPlan("premium_annual")}>
            <strong>Annual</strong>
            <span>$30 / 365 days</span>
          </button>
        </div>
      )}

      {!premium ? (
        <>
          <div className="billing-pay-copy">
            <h4>Pay privately</h4>
            <p>Complete checkout securely inside VeritasVPN. Premium activates automatically after confirmation.</p>
          </div>
          <div className="billing-actions">
            <button type="button" disabled={billingBusy || checkoutMethod !== null} onClick={onCheckout}>
              {checkoutMethod === "btcpay" ? "Opening Bitcoin checkout…" : "Pay with Bitcoin"}
            </button>
          </div>
        </>
      ) : (
        <>
          <div className="billing-active">Premium is active</div>
          {billingStatus?.cancel_at_period_end ? (
            <div className="billing-cancellation-scheduled" role="status">
              <strong>Cancellation scheduled</strong>
              <span>
                Your VPN remains active until {formatBillingDate(billingStatus.current_period_end)}. After that, Premium ends automatically. You can purchase another period whenever you want.
              </span>
            </div>
          ) : (
            <button type="button" className="billing-cancel" disabled={billingBusy} onClick={onCancelClick}>
              Cancel at period end
            </button>
          )}
          {showCancelConfirmation && (
            <div className="billing-cancel-confirm" role="alertdialog" aria-modal="true">
              <strong>Schedule cancellation?</strong>
              <p>Your VPN will stay active until {formatBillingDate(billingStatus?.current_period_end)}. After that date, Premium will end and you will not be charged again.</p>
              <div>
                <button type="button" disabled={billingBusy} onClick={onCancelDismiss}>Keep Premium</button>
                <button type="button" disabled={billingBusy} onClick={onCancelConfirm}>{billingBusy ? "Scheduling…" : "Confirm cancellation"}</button>
              </div>
            </div>
          )}
        </>
      )}
    </section>
  );
}

function PaymentCheckoutScreen({
  checkoutUrl,
  onClose,
  onRefreshPlan,
}: {
  checkoutUrl: string;
  onClose: () => void;
  onRefreshPlan: () => void;
}) {
  const [loading, setLoading] = useState(true);
  return (
    <section className="checkout-screen">
      <div className="checkout-head">
        <button type="button" className="plans-back" onClick={onClose}>← Back</button>
        <div>
          <strong>Secure crypto checkout</strong>
          <span>Payment is processed by BTCPay Server</span>
        </div>
        <button type="button" className="checkout-check" onClick={onRefreshPlan}>Check payment</button>
      </div>
      {loading && <div className="checkout-progress" role="status">Loading checkout…</div>}
      <iframe
        className="checkout-frame"
        title="VeritasVPN secure checkout"
        src={checkoutUrl}
        onLoad={() => setLoading(false)}
        sandbox="allow-scripts allow-same-origin allow-forms allow-popups"
      />
    </section>
  );
}

function DevicesScreen({
  peers,
  loading,
  error,
  currentPeerId,
  revokingId,
  onBack,
  onRefresh,
  onRevoke,
}: {
  peers: PeerInfo[];
  loading: boolean;
  error: string;
  currentPeerId: string;
  revokingId: string | null;
  onBack: () => void;
  onRefresh: () => void;
  onRevoke: (peer: PeerInfo) => void;
}) {
  return (
    <section className="devices-screen">
      <div className="plans-head">
        <button type="button" className="plans-back" onClick={onBack} aria-label="Back">←</button>
        <div>
          <h2>Devices</h2>
          <p>Active WireGuard peers on your account</p>
        </div>
        <button type="button" className="devices-refresh" disabled={loading || !!revokingId} onClick={onRefresh}>
          {loading ? "…" : "Refresh"}
        </button>
      </div>
      {error && <div className="billing-error">{error}</div>}
      {loading && peers.length === 0 ? (
        <div className="billing-loading">Loading devices…</div>
      ) : peers.length === 0 ? (
        <p className="devices-empty">No devices registered.</p>
      ) : (
        <ul className="devices-list">
          {peers.map((peer) => {
            const isCurrent = peer.id === currentPeerId;
            return (
              <li key={peer.id} className={`device-card ${isCurrent ? "current" : ""}`}>
                <div>
                  <strong>{shortPeerId(peer.id)}</strong>
                  {isCurrent && <span className="device-current-pill">THIS DEVICE</span>}
                  <span className="device-meta">{peer.assigned_ip || "—"} · {peer.status || "unknown"}</span>
                  {typeof peer.dns_blocked_count === "number" && (
                    <span className="device-meta">DNS blocked: {peer.dns_blocked_count}</span>
                  )}
                </div>
                <button
                  type="button"
                  className="device-revoke"
                  disabled={!!revokingId}
                  onClick={() => onRevoke(peer)}
                >
                  {revokingId === peer.id ? "Revoking…" : "Revoke"}
                </button>
              </li>
            );
          })}
        </ul>
      )}
    </section>
  );
}

function PortForwardsScreen({
  forwards,
  peers,
  loading,
  creating,
  deletingId,
  error,
  currentPeerId,
  onBack,
  onRefresh,
  onCreate,
  onDelete,
}: {
  forwards: PortForwardInfo[];
  peers: PeerInfo[];
  loading: boolean;
  creating: boolean;
  deletingId: string | null;
  error: string;
  currentPeerId: string;
  onBack: () => void;
  onRefresh: () => void;
  onCreate: (input: { peerId: string; protocol: string; externalPort: number; internalPort?: number }) => void;
  onDelete: (id: string) => void;
}) {
  const [peerId, setPeerId] = useState(currentPeerId || "");
  const [protocol, setProtocol] = useState<"tcp" | "udp">("tcp");
  const [externalPort, setExternalPort] = useState("");
  const [internalPort, setInternalPort] = useState("");

  useEffect(() => {
    if (currentPeerId) setPeerId(currentPeerId);
  }, [currentPeerId]);

  useEffect(() => {
    if (!peerId && peers.length === 1) setPeerId(peers[0].id);
  }, [peers, peerId]);

  const atLimit = forwards.length >= 2;
  const busy = loading || creating || !!deletingId;

  return (
    <section className="devices-screen port-forwards-screen">
      <div className="plans-head">
        <button type="button" className="plans-back" onClick={onBack} aria-label="Back">←</button>
        <div>
          <h2>Port forwarding</h2>
          <p>Premium inbound DNAT on your VPN node (max 2)</p>
        </div>
        <button type="button" className="devices-refresh" disabled={busy} onClick={onRefresh}>
          {loading ? "…" : "Refresh"}
        </button>
      </div>
      <p className="pf-help">
        Premium only. Traffic hits the node public IP (not Cloudflare). Open matching ports on your router toward your VPN node.
        Recommended external ports: <strong>40000–49999</strong>.
      </p>
      {error && <div className="billing-error">{error}</div>}
      {loading && forwards.length === 0 ? (
        <div className="billing-loading">Loading port forwards…</div>
      ) : forwards.length === 0 ? (
        <p className="devices-empty">No port forwards yet.</p>
      ) : (
        <ul className="devices-list">
          {forwards.map((pf) => {
            const endpoint = `${pf.egress_endpoint || "—"}:${pf.external_port}`;
            return (
              <li key={pf.id} className="device-card">
                <div>
                  <strong>{endpoint}</strong>
                  <span className="device-meta">
                    → {shortPeerId(pf.peer_id)} · {(pf.protocol || "").toUpperCase()} · internal {pf.internal_port ?? "—"} · {pf.status || "—"}
                  </span>
                </div>
                <button
                  type="button"
                  className="device-revoke"
                  disabled={busy}
                  onClick={() => onDelete(pf.id)}
                >
                  {deletingId === pf.id ? "Deleting…" : "Delete"}
                </button>
              </li>
            );
          })}
        </ul>
      )}
      <form
        className="pf-create"
        onSubmit={(e) => {
          e.preventDefault();
          const ext = Number(externalPort);
          if (!peerId || !ext) return;
          const internal = internalPort.trim() ? Number(internalPort) : undefined;
          onCreate({ peerId, protocol, externalPort: ext, internalPort: internal });
          setExternalPort("");
          setInternalPort("");
        }}
      >
        <h3>Create forward</h3>
        <label>
          Device
          <select value={peerId} onChange={(e) => setPeerId(e.target.value)} disabled={busy || peers.length === 0} required>
            <option value="">{peers.length ? "Select a device…" : "No devices — connect first"}</option>
            {peers.map((p) => (
              <option key={p.id} value={p.id}>
                {shortPeerId(p.id)}{p.id === currentPeerId ? " (this device)" : ""} · {p.assigned_ip || "—"}
              </option>
            ))}
          </select>
        </label>
        <div className="pf-create-row">
          <label>
            Protocol
            <select value={protocol} onChange={(e) => setProtocol(e.target.value as "tcp" | "udp")} disabled={busy}>
              <option value="tcp">TCP</option>
              <option value="udp">UDP</option>
            </select>
          </label>
          <label>
            External port
            <input
              type="number"
              min={1}
              max={65535}
              placeholder="40000–49999"
              value={externalPort}
              onChange={(e) => setExternalPort(e.target.value)}
              disabled={busy}
              required
            />
          </label>
        </div>
        <label>
          Internal port (optional)
          <input
            type="number"
            min={1}
            max={65535}
            placeholder="Same as external"
            value={internalPort}
            onChange={(e) => setInternalPort(e.target.value)}
            disabled={busy}
          />
        </label>
        <button type="submit" className="pf-create-btn" disabled={busy || atLimit || !peers.length || !peerId}>
          {creating ? "Creating…" : atLimit ? "Limit reached (2)" : "Create"}
        </button>
      </form>
    </section>
  );
}

function App() {
  const [user, setUser] = useState<User | null>(null);
  const [mode, setMode] = useState<AuthMode>("signin");
  const [method, setMethod] = useState<AuthMethod>("email");
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [confirmPassword, setConfirmPassword] = useState("");
  const [passwordVisible, setPasswordVisible] = useState(false);
  const [confirmVisible, setConfirmVisible] = useState(false);
  const [accountId, setAccountId] = useState("");
  const [newAccountId, setNewAccountId] = useState("");
  const [accountIdCopied, setAccountIdCopied] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [verificationResendEmail, setVerificationResendEmail] = useState("");
  const [pendingVerificationEmail, setPendingVerificationEmail] = useState<string | null>(null);
  const [resendLoading, setResendLoading] = useState(false);
  const [forgotPassword, setForgotPassword] = useState(false);
  const [resetSent, setResetSent] = useState(false);
  const [resetCooldown, setResetCooldown] = useState(0);
  const [loading, setLoading] = useState(false);
  const [connected, setConnected] = useState(false);
  const [tunnelMode, setTunnelMode] = useState<TunnelMode>("");
  const [peerId, setPeerId] = useState("");
  const [statusMsg, setStatusMsg] = useState("");
  const [connecting, setConnecting] = useState(false);
  const [egressIp, setEgressIp] = useState("");
  const [subscriptionActive, setSubscriptionActive] = useState(false);
  const [subscriptionChecked, setSubscriptionChecked] = useState(false);
  const [billingStatus, setBillingStatus] = useState<BillingStatus | null>(null);
  const [showPlans, setShowPlans] = useState(false);
  const [billingBusy, setBillingBusy] = useState(false);
  const [billingLoading, setBillingLoading] = useState(false);
  const [billingError, setBillingError] = useState("");
  const [selectedPlan, setSelectedPlan] = useState<"premium_monthly" | "premium_annual">("premium_monthly");
  const [checkoutUrl, setCheckoutUrl] = useState<string | null>(null);
  const [checkoutMethod, setCheckoutMethod] = useState<string | null>(null);
  const [showCancelConfirmation, setShowCancelConfirmation] = useState(false);
  const [showSettings, setShowSettings] = useState(false);
  const [showNetworkMap, setShowNetworkMap] = useState(false);
  const [showSignOutConfirm, setShowSignOutConfirm] = useState(false);
  const [showDevices, setShowDevices] = useState(false);
  const [showPortForwards, setShowPortForwards] = useState(false);
  const [autoReconnect, setAutoReconnect] = useState(() => readLocalFlag(LS_AUTO_RECONNECT, "1"));
  const [excludeLan, setExcludeLan] = useState(() => readLocalFlag(LS_EXCLUDE_LAN, "0"));
  const [stealthMode, setStealthMode] = useState(() => {
    const enabled = readLocalFlag(LS_STEALTH, "0");
    return enabled && isLinuxDesktop();
  });
  const [linuxDesktop] = useState(() => isLinuxDesktop());
  const [activeTransport, setActiveTransport] = useState<"direct" | "stealth" | "">("");
  const [reconnectToApply, setReconnectToApply] = useState(false);
  const [statusSticky, setStatusSticky] = useState(false);
  const [wgStats, setWgStats] = useState<WgTransferStats | null>(null);
  const [dnsBlockedCount, setDnsBlockedCount] = useState<number | null>(null);
  const [reconnecting, setReconnecting] = useState(false);
  const [peers, setPeers] = useState<PeerInfo[]>([]);
  const [devicesLoading, setDevicesLoading] = useState(false);
  const [devicesError, setDevicesError] = useState("");
  const [revokingId, setRevokingId] = useState<string | null>(null);
  const [portForwards, setPortForwards] = useState<PortForwardInfo[]>([]);
  const [portForwardsLoading, setPortForwardsLoading] = useState(false);
  const [portForwardsError, setPortForwardsError] = useState("");
  const [portForwardCreating, setPortForwardCreating] = useState(false);
  const [deletingForwardId, setDeletingForwardId] = useState<string | null>(null);
  const [deviceLabel, setDeviceLabel] = useState("Current location");
  const connectPeerRef = useRef("");
  const userDisconnectedRef = useRef(false);
  const hadGoodHandshakeRef = useRef(false);
  const hadInterfaceUpRef = useRef(false);
  const reconnectAttemptRef = useRef(0);
  const reconnectingRef = useRef(false);
  const reconnectTimerRef = useRef<number | null>(null);
  const settingsCogRef = useRef<HTMLButtonElement>(null);
  const peerIdRef = useRef("");
  const autoReconnectRef = useRef(autoReconnect);
  const subscriptionActiveRef = useRef(subscriptionActive);
  const connectedRef = useRef(connected);
  const connectingRef = useRef(connecting);
  const excludeLanRef = useRef(excludeLan);
  const stealthModeRef = useRef(stealthMode);

  useEffect(() => {
    let cancelled = false;
    initializeSecureAuth()
      .then(() => {
        if (!cancelled) setUser(getStoredUser());
      })
      .catch(() => {
        if (!cancelled) setUser(null);
      });
    return () => { cancelled = true; };
  }, []);

  useEffect(() => { peerIdRef.current = peerId; }, [peerId]);
  useEffect(() => { autoReconnectRef.current = autoReconnect; }, [autoReconnect]);
  useEffect(() => { subscriptionActiveRef.current = subscriptionActive; }, [subscriptionActive]);
  useEffect(() => { connectedRef.current = connected; }, [connected]);
  useEffect(() => { connectingRef.current = connecting; }, [connecting]);
  useEffect(() => { excludeLanRef.current = excludeLan; }, [excludeLan]);
  useEffect(() => { stealthModeRef.current = stealthMode; }, [stealthMode]);

  useEffect(() => {
    if (resetCooldown <= 0) return;
    const timer = window.setTimeout(() => setResetCooldown((value) => value - 1), 1000);
    return () => window.clearTimeout(timer);
  }, [resetCooldown]);

  const expireAndReturnToSignIn = useCallback(() => {
    if (user) clearCachedBillingStatus(user.account_id);
    setSubscriptionActive(false);
    setSubscriptionChecked(false);
    setBillingStatus(null);
    setCheckoutUrl(null);
    setShowPlans(false);
    setShowDevices(false);
    setShowPortForwards(false);
    setShowSettings(false);
    void doSignOut();
    setUser(null);
  }, [user]);

  useEffect(() => {
    const onExpired = () => expireAndReturnToSignIn();
    window.addEventListener(SESSION_EXPIRED_EVENT, onExpired);
    return () => window.removeEventListener(SESSION_EXPIRED_EVENT, onExpired);
  }, [expireAndReturnToSignIn]);

  useEffect(() => {
    const onVisible = () => {
      if (document.visibilityState !== "visible" || !user) return;
      void validateSessionOnResume().then((ok) => {
        if (!ok) expireAndReturnToSignIn();
      });
    };
    document.addEventListener("visibilitychange", onVisible);
    return () => document.removeEventListener("visibilitychange", onVisible);
  }, [user, expireAndReturnToSignIn]);

  const refreshBillingStatus = useCallback(async () => {
    if (!user) return null;
    setBillingLoading(true);
    try {
      const response = await fetchWithAuth(`${AUTH_API}/api/v1/billing/status`);
      const status = (await response.json()) as BillingStatus;
      if (!response.ok) throw new Error(status.error || "Could not load your subscription.");
      setBillingStatus(status);
      setSubscriptionActive(status.is_premium === true);
      setSubscriptionChecked(true);
      writeCachedBillingStatus(user.account_id, status);
      setBillingError("");
      return status;
    } catch (err) {
      if (err instanceof SessionExpiredError) {
        expireAndReturnToSignIn();
        return null;
      }
      const hadCached = billingStatus !== null || readCachedBillingStatus(user.account_id) !== null;
      if (!hadCached) {
        setBillingStatus(null);
        setSubscriptionActive(false);
        setBillingError(err instanceof Error ? err.message : "Could not load your subscription.");
      }
      setSubscriptionChecked(true);
      return billingStatus;
    } finally {
      setBillingLoading(false);
    }
  }, [user, billingStatus, expireAndReturnToSignIn]);

  useEffect(() => {
    if (!user) {
      setSubscriptionActive(false);
      setSubscriptionChecked(false);
      setBillingStatus(null);
      return;
    }
    const cached = readCachedBillingStatus(user.account_id);
    if (cached) {
      setBillingStatus(cached);
      setSubscriptionActive(cached.is_premium === true);
      setSubscriptionChecked(true);
    } else {
      setSubscriptionChecked(false);
    }
    refreshBillingStatus().catch(() => undefined);
  }, [user]); // eslint-disable-line react-hooks/exhaustive-deps

  useEffect(() => {
    if (!user) return;
    if (!navigator.geolocation) return;
    navigator.geolocation.getCurrentPosition(
      () => setDeviceLabel("Your location"),
      () => setDeviceLabel("Current location"),
      { maximumAge: 600_000, timeout: 5000 }
    );
  }, [user]);

  useEffect(() => {
    if (!linuxDesktop && stealthMode) {
      setStealthMode(false);
      writeLocalFlag(LS_STEALTH, false);
    }
  }, [linuxDesktop, stealthMode]);

  useEffect(() => {
    if (!statusMsg) return;
    if (statusSticky || isStickyStatusMessage(statusMsg)) return;
    if (/^reconnecting/i.test(statusMsg)) return;
    const t = window.setTimeout(() => setStatusMsg(""), 8000);
    return () => window.clearTimeout(t);
  }, [statusMsg, statusSticky]);

  const dismissStatus = useCallback(() => {
    setStatusMsg("");
    setStatusSticky(false);
  }, []);

  const showStatus = useCallback((msg: string, sticky = false) => {
    setStatusSticky(sticky || isStickyStatusMessage(msg));
    setStatusMsg(msg);
  }, []);

  useEffect(() => {
    if (!connecting) return;
    const timer = window.setTimeout(async () => {
      if (!connecting) return;
      const timedOutPeer = connectPeerRef.current;
      connectPeerRef.current = "";
      await invoke<ConnectResult>("disconnect_wireguard").catch(() => undefined);
      if (timedOutPeer) {
        await fetchWithAuth(`${AUTH_API}/api/v1/wg/peers/${timedOutPeer}`, {
          method: "DELETE",
        }).catch(() => undefined);
      }
      setConnecting(false);
      setConnected(false);
      setTunnelMode("");
      setPeerId("");
      setEgressIp("");
      setStatusMsg("Connection timed out. Check your network and try again.");
    }, CONNECT_TIMEOUT_MS);
    return () => window.clearTimeout(timer);
  }, [connecting]);

  useEffect(() => {
    if (!checkoutUrl || !user) return;
    const timer = window.setInterval(() => {
      refreshBillingStatus().then((status) => {
        if (status?.is_premium) {
          setCheckoutUrl(null);
          setShowPlans(false);
          setBillingError("");
        }
      }).catch(() => undefined);
    }, 3000);
    return () => window.clearInterval(timer);
  }, [checkoutUrl, user, refreshBillingStatus]);

  const switchMode = useCallback((next: AuthMode) => {
    setMode(next);
    setMethod("email");
    setError("");
    setNotice("");
    setVerificationResendEmail("");
    setPendingVerificationEmail(null);
    setNewAccountId("");
    setForgotPassword(false);
  }, []);

  const switchMethod = useCallback((next: AuthMethod) => {
    setMethod(next);
    setError("");
    setNotice("");
    setNewAccountId("");
  }, []);

  const handleAuth = useCallback(async (e: FormEvent) => {
    e.preventDefault();
    setError("");
    setNotice("");
    setVerificationResendEmail("");
    if (method === "email" && mode === "signup") {
      const validationError = validateSignupPassword(password, confirmPassword);
      if (validationError) {
        setError(validationError);
        return;
      }
    }
    setLoading(true);
    try {
      if (method === "accountId") {
        if (mode === "signin") {
          const u = await doSignInAccountId(accountId);
          setUser(u);
          setAccountId("");
        } else {
          const turnstileToken = await obtainTurnstileToken();
          const u = await doRegisterAnonymous(turnstileToken);
          setNewAccountId(u.account_id);
          setUser(u);
        }
      } else if (mode === "signin") {
        const u = await doSignIn(email, password);
        setUser(u);
        setEmail("");
        setPassword("");
      } else {
        const turnstileToken = await obtainTurnstileToken();
        const u = await doSignUp(email, password, turnstileToken);
        setUser(u);
        setEmail("");
        setPassword("");
        setConfirmPassword("");
      }
    } catch (err) {
      if (err instanceof VerificationRequiredError) {
        if (mode === "signup" && method === "email") {
          setPendingVerificationEmail(err.email);
          setError("");
        } else {
          setVerificationResendEmail(err.email);
          setError(err.message);
        }
      } else if (err instanceof AccountAlreadyExistsError) {
        setVerificationResendEmail(err.email);
        setError(err.message);
      } else {
        setError(err instanceof Error ? err.message : "Auth failed");
      }
    } finally {
      setLoading(false);
    }
  }, [email, password, confirmPassword, accountId, mode, method]);

  const handleResetPassword = useCallback(async () => {
    if (!email.trim()) {
      setError("Enter your email address.");
      return;
    }
    setLoading(true);
    setError("");
    try {
      await resetPassword(email);
      setResetSent(true);
      setResetCooldown(30);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Could not send the reset link. Try again.");
    } finally {
      setLoading(false);
    }
  }, [email]);

  const handleResendVerification = useCallback(async (targetEmail?: string) => {
    const emailToResend = targetEmail || verificationResendEmail;
    if (!emailToResend) return;
    setResendLoading(true);
    setNotice("");
    try {
      await resendVerification(emailToResend);
      setError("");
      if (!targetEmail) setVerificationResendEmail("");
      setNotice(`A new verification link was sent to ${emailToResend}.`);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Could not resend the verification email. Try again.");
    } finally {
      setResendLoading(false);
    }
  }, [verificationResendEmail]);

  const closeSettings = useCallback(() => setShowSettings(false), []);

  const openNetworkMap = useCallback(() => {
    setShowSettings(false);
    setShowNetworkMap(true);
    setShowPlans(false);
    setShowDevices(false);
    setShowPortForwards(false);
  }, []);

  const openPlans = useCallback(() => {
    setShowSettings(false);
    setShowPlans(true);
    setShowDevices(false);
    setShowPortForwards(false);
    setShowCancelConfirmation(false);
    setBillingError("");
    refreshBillingStatus().catch((err) => setBillingError(err instanceof Error ? err.message : "Could not load your subscription."));
  }, [refreshBillingStatus]);

  const startCheckout = useCallback(async () => {
    if (billingBusy || checkoutMethod) return;
    setCheckoutMethod("btcpay");
    setBillingBusy(true);
    setBillingError("");
    try {
      const response = await fetchWithAuth(`${AUTH_API}/api/v1/billing/subscribe`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ tier: "premium", payment_method: "btcpay", plan_id: selectedPlan }),
      });
      const data = await response.json() as { checkout_url?: string; error?: string };
      if (!response.ok || !isAllowedBtcpayCheckoutUrl(data.checkout_url)) {
        throw new Error(data.error || "The billing server returned an invalid checkout.");
      }
      setCheckoutUrl(data.checkout_url!);
    } catch (err) {
      if (err instanceof SessionExpiredError) {
        expireAndReturnToSignIn();
        return;
      }
      setBillingError(err instanceof Error ? err.message : "Could not start checkout.");
    } finally {
      setBillingBusy(false);
      setCheckoutMethod(null);
    }
  }, [billingBusy, checkoutMethod, selectedPlan, expireAndReturnToSignIn]);

  const cancelSubscription = useCallback(async () => {
    if (billingBusy) return;
    setBillingBusy(true);
    setBillingError("");
    try {
      const response = await fetchWithAuth(`${AUTH_API}/api/v1/billing/cancel`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: "{}",
      });
      const data = await response.json() as { error?: string };
      if (!response.ok) throw new Error(data.error || "Could not cancel your subscription.");
      await refreshBillingStatus();
      setShowCancelConfirmation(false);
    } catch (err) {
      if (err instanceof SessionExpiredError) {
        expireAndReturnToSignIn();
        return;
      }
      setBillingError(err instanceof Error ? err.message : "Could not cancel your subscription.");
    } finally {
      setBillingBusy(false);
    }
  }, [billingBusy, refreshBillingStatus, expireAndReturnToSignIn]);

  const fetchEgressIp = useCallback(async () => {
    for (const endpoint of EGRESS_ENDPOINTS) {
      try {
        const response = await fetch(endpoint, { method: "GET" });
        const text = (await response.text()).trim();
        if (response.ok && text) {
          setEgressIp(text);
          return;
        }
      } catch {
        // try next endpoint
      }
    }
  }, []);

  const clearReconnectTimer = useCallback(() => {
    if (reconnectTimerRef.current !== null) {
      window.clearTimeout(reconnectTimerRef.current);
      reconnectTimerRef.current = null;
    }
  }, []);

  const loadDevices = useCallback(async () => {
    setDevicesLoading(true);
    setDevicesError("");
    try {
      const response = await fetchWithAuth(`${AUTH_API}/api/v1/wg/peers`);
      const data = (await response.json()) as { peers?: PeerInfo[]; error?: string };
      if (!response.ok) throw new Error(data.error || "Could not load devices.");
      setPeers(Array.isArray(data.peers) ? data.peers : []);
    } catch (err) {
      if (err instanceof SessionExpiredError) {
        expireAndReturnToSignIn();
        return;
      }
      setDevicesError(err instanceof Error ? err.message : "Could not load devices.");
    } finally {
      setDevicesLoading(false);
    }
  }, [expireAndReturnToSignIn]);

  const deletePeer = useCallback(async (id: string) => {
    if (!id) return;
    const controller = new AbortController();
    const timeout = window.setTimeout(() => controller.abort(), 5000);
    try {
      await fetchWithAuth(`${AUTH_API}/api/v1/wg/peers/${id}`, {
        method: "DELETE",
        signal: controller.signal,
      });
    } catch {
      // best effort cleanup
    } finally {
      window.clearTimeout(timeout);
    }
  }, []);

  const connectVpn = useCallback(async (opts?: { isReconnect?: boolean }): Promise<boolean> => {
    if (connectingRef.current) return false;
    if (connectedRef.current && !opts?.isReconnect) return false;

    const wantedStealth = stealthModeRef.current && linuxDesktop;
    showStatus(opts?.isReconnect ? "Reconnecting…" : "", false);
    setEgressIp("");
    setConnecting(true);
    connectPeerRef.current = "";

    let createdPeerId = "";
    try {
      // Drop any previous peer before registering a new one (reconnect + stale sessions).
      const priorPeer = peerIdRef.current;
      if (priorPeer) {
        await deletePeer(priorPeer);
        if (peerIdRef.current === priorPeer) {
          setPeerId("");
        }
      }

      if (wantedStealth && !linuxDesktop) {
        throw new Error("Stealth mode is Linux-only in this build. Turn it off to connect with Direct UDP.");
      }

      if (!opts?.isReconnect) showStatus("Creating secure keys…", false);

      const billingResponse = await fetchWithAuth(`${AUTH_API}/api/v1/billing/status`);
      const billing = (await billingResponse.json()) as BillingStatus;
      if (!billingResponse.ok || !billing.is_premium) {
        setSubscriptionActive(false);
        setSubscriptionChecked(true);
        throw new Error("An active subscription is required. Open Plans to subscribe.");
      }
      setSubscriptionActive(true);
      setSubscriptionChecked(true);
      if (user) writeCachedBillingStatus(user.account_id, billing);

      const available = await invoke<boolean>("wireguard_available");
      if (!available) throw new Error("WireGuard is unavailable in this build.");

      const keys = await invoke<KeyPair>("generate_wg_keys");
      const res = await fetchWithAuth(`${AUTH_API}/api/v1/wg/peers`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ public_key: keys.public_key }),
      });
      const peer = (await res.json()) as PeerResponse & { code?: string };
      if (!res.ok) {
        if (peer.code?.startsWith("plan_device_limit")) {
          throw new Error(peer.error || "Device limit reached. Disconnect another device or upgrade your plan.");
        }
        throw new Error(peer.error || "Failed to create WireGuard peer");
      }
      createdPeerId = peer.peer_id;
      connectPeerRef.current = peer.peer_id;

      const allowedRaw = peer.client_allowed_ips || peer.allowed_ips || ["0.0.0.0/0", "::/0"];
      const allowed = applyExcludeLan(allowedRaw, excludeLanRef.current);
      const useStealth = wantedStealth && !!peer.stealth_available && !!peer.stealth_endpoint;
      if (wantedStealth && !useStealth) {
        throw new Error("Stealth is not available on the VPN node yet. Turn Stealth off or try again later.");
      }
      const result = await invoke<ConnectResult>("connect_wireguard", {
        config: {
          private_key: keys.private_key,
          address: peer.assigned_ip,
          dns: peer.dns_server || "10.0.0.1",
          server_public_key: peer.server_public_key,
          endpoint: peer.server_endpoint,
          allowed_ips: allowed,
          peer_id: peer.peer_id,
          preshared_key: peer.preshared_key || "",
          stealth_endpoint: useStealth ? peer.stealth_endpoint || "" : "",
          stealth_path_prefix: useStealth ? peer.stealth_path_prefix || "" : "",
        },
      });

      if (!result.success) throw new Error(result.message || "WireGuard connection failed");

      connectPeerRef.current = "";
      userDisconnectedRef.current = false;
      hadGoodHandshakeRef.current = false;
      hadInterfaceUpRef.current = false;
      reconnectAttemptRef.current = 0;
      setConnected(true);
      setTunnelMode("wireguard");
      setPeerId(peer.peer_id);
      setActiveTransport(useStealth ? "stealth" : "direct");
      setReconnectToApply(false);
      setStatusSticky(false);
      setStatusMsg("");
      setWgStats(null);
      setDnsBlockedCount(null);
      void fetchEgressIp();
      return true;
    } catch (err) {
      connectPeerRef.current = "";
      if (createdPeerId) {
        await deletePeer(createdPeerId);
      }
      if (err instanceof SessionExpiredError) {
        expireAndReturnToSignIn();
        return false;
      }
      const message = formatConnectError(err, wantedStealth);
      showStatus(message, isStickyStatusMessage(message));
      return false;
    } finally {
      setConnecting(false);
    }
  }, [user, fetchEgressIp, deletePeer, linuxDesktop, showStatus, expireAndReturnToSignIn]);

  const handleConnect = useCallback(async () => {
    userDisconnectedRef.current = false;
    clearReconnectTimer();
    reconnectingRef.current = false;
    setReconnecting(false);
    setStatusSticky(false);
    await connectVpn();
  }, [clearReconnectTimer, connectVpn]);

  const handleDisconnect = useCallback(async () => {
    userDisconnectedRef.current = true;
    clearReconnectTimer();
    reconnectingRef.current = false;
    setReconnecting(false);
    setStatusMsg("Disconnecting…");
    connectPeerRef.current = "";
    const clearUi = () => {
      setConnected(false);
      setTunnelMode("");
      setPeerId("");
      setEgressIp("");
      setWgStats(null);
      setDnsBlockedCount(null);
      setActiveTransport("");
      setReconnectToApply(false);
      hadGoodHandshakeRef.current = false;
      hadInterfaceUpRef.current = false;
    };
    try {
      if (tunnelMode === "wireguard" || peerId) {
        const result = await invoke<ConnectResult>("disconnect_wireguard");
        const oldPeer = peerId;
        clearUi();
        if (!result.success) {
          setStatusMsg(result.message || "Disconnect incomplete — approve the admin prompt, or run: sudo bash ~/.veritasvpn/teardown.sh");
          return;
        }
        if (oldPeer) await deletePeer(oldPeer);
      }
      clearUi();
      setStatusMsg("");
    } catch (err) {
      clearUi();
      setStatusMsg(err instanceof Error ? err.message : "Disconnect failed");
    }
  }, [tunnelMode, peerId, clearReconnectTimer, deletePeer]);

  const attemptReconnect = useCallback(async () => {
    if (reconnectingRef.current) return;
    if (!autoReconnectRef.current || userDisconnectedRef.current || !subscriptionActiveRef.current) return;
    if (connectingRef.current) return;

    reconnectingRef.current = true;
    setReconnecting(true);
    setStatusMsg("Reconnecting…");

    const attempt = reconnectAttemptRef.current;
    const delay = RECONNECT_BACKOFF_MS[Math.min(attempt, RECONNECT_BACKOFF_MS.length - 1)];

    clearReconnectTimer();
    reconnectTimerRef.current = window.setTimeout(async () => {
      reconnectTimerRef.current = null;
      if (userDisconnectedRef.current || !autoReconnectRef.current || !subscriptionActiveRef.current) {
        reconnectingRef.current = false;
        setReconnecting(false);
        return;
      }

      const oldPeer = peerIdRef.current;
      try {
        await invoke<ConnectResult>("disconnect_wireguard").catch(() => undefined);
      } catch {
        // continue teardown
      }
      setConnected(false);
      setTunnelMode("");
      setPeerId("");
      setEgressIp("");
      setWgStats(null);
      setActiveTransport("");
      hadGoodHandshakeRef.current = false;
      hadInterfaceUpRef.current = false;
      if (oldPeer) await deletePeer(oldPeer);

      const ok = await connectVpn({ isReconnect: true });
      if (ok) {
        reconnectAttemptRef.current = 0;
        reconnectingRef.current = false;
        setReconnecting(false);
        setStatusMsg("");
      } else {
        reconnectAttemptRef.current = Math.min(attempt + 1, RECONNECT_BACKOFF_MS.length - 1);
        reconnectingRef.current = false;
        if (!userDisconnectedRef.current && autoReconnectRef.current && subscriptionActiveRef.current) {
          setStatusMsg("Reconnecting…");
          void attemptReconnect();
        } else {
          setReconnecting(false);
        }
      }
    }, delay);
  }, [clearReconnectTimer, connectVpn, deletePeer]);

  // Live WireGuard stats + auto-reconnect while connected
  useEffect(() => {
    if (!connected) {
      if (!reconnecting) {
        setWgStats(null);
        setDnsBlockedCount(null);
      }
      return;
    }

    let cancelled = false;
    const pollStats = async () => {
      try {
        const stats = await invoke<WgTransferStats>("wireguard_stats");
        if (cancelled) return;
        setWgStats(stats);

        const nowSec = Math.floor(Date.now() / 1000);
        const handshakeAge =
          stats.last_handshake_sec > 0 ? Math.max(0, nowSec - stats.last_handshake_sec) : Number.POSITIVE_INFINITY;

        if (stats.interface_up) {
          hadInterfaceUpRef.current = true;
        }

        if (stats.interface_up && stats.last_handshake_sec > 0 && handshakeAge <= HANDSHAKE_HEALTHY_SEC) {
          hadGoodHandshakeRef.current = true;
        }

        // Only reconnect when the tunnel interface is actually down.
        // A handshake age past WireGuard's natural rekey (~2m) is normal while
        // interface_up; tearing down for that alone drops the session briefly.
        const down = hadInterfaceUpRef.current && !stats.interface_up;

        if (
          down &&
          autoReconnectRef.current &&
          !userDisconnectedRef.current &&
          subscriptionActiveRef.current &&
          !reconnectingRef.current &&
          !connectingRef.current
        ) {
          void attemptReconnect();
        }
      } catch {
        // ignore transient stats errors
      }
    };

    void pollStats();
    const timer = window.setInterval(pollStats, STATS_POLL_MS);
    return () => {
      cancelled = true;
      window.clearInterval(timer);
    };
  }, [connected, reconnecting, attemptReconnect]);

  // Poll peers for DNS blocked count of current peer
  useEffect(() => {
    if (!connected || !peerId) {
      setDnsBlockedCount(null);
      return;
    }
    let cancelled = false;
    const pollPeers = async () => {
      try {
        const response = await fetchWithAuth(`${AUTH_API}/api/v1/wg/peers`);
        if (!response.ok || cancelled) return;
        const data = (await response.json()) as { peers?: PeerInfo[] };
        if (cancelled || !Array.isArray(data.peers)) return;
        const match = data.peers.find((p) => p.id === peerIdRef.current);
        if (match && typeof match.dns_blocked_count === "number") {
          setDnsBlockedCount(match.dns_blocked_count);
        }
      } catch (err) {
        if (err instanceof SessionExpiredError) expireAndReturnToSignIn();
      }
    };
    void pollPeers();
    const timer = window.setInterval(pollPeers, PEERS_POLL_MS);
    return () => {
      cancelled = true;
      window.clearInterval(timer);
    };
  }, [connected, peerId, expireAndReturnToSignIn]);

  const loadPortForwards = useCallback(async () => {
    setPortForwardsLoading(true);
    setPortForwardsError("");
    try {
      const [peersRes, pfRes] = await Promise.all([
        fetchWithAuth(`${AUTH_API}/api/v1/wg/peers`),
        fetchWithAuth(`${AUTH_API}/api/v1/wg/port-forwards`),
      ]);
      const peersData = (await peersRes.json()) as { peers?: PeerInfo[]; error?: string };
      const pfData = (await pfRes.json()) as { port_forwards?: PortForwardInfo[]; error?: string };
      if (!peersRes.ok) throw new Error(peersData.error || "Could not load devices.");
      if (!pfRes.ok) throw new Error(pfData.error || "Could not load port forwards.");
      setPeers(Array.isArray(peersData.peers) ? peersData.peers : []);
      setPortForwards(Array.isArray(pfData.port_forwards) ? pfData.port_forwards : []);
    } catch (err) {
      if (err instanceof SessionExpiredError) {
        expireAndReturnToSignIn();
        return;
      }
      setPortForwardsError(err instanceof Error ? err.message : "Could not load port forwards.");
    } finally {
      setPortForwardsLoading(false);
    }
  }, [expireAndReturnToSignIn]);

  const openDevices = useCallback(() => {
    setShowSettings(false);
    setShowDevices(true);
    setShowPortForwards(false);
    setShowPlans(false);
    setShowNetworkMap(false);
    void loadDevices();
  }, [loadDevices]);

  const openPortForwards = useCallback(() => {
    setShowSettings(false);
    setShowPortForwards(true);
    setShowDevices(false);
    setShowPlans(false);
    setShowNetworkMap(false);
    void loadPortForwards();
  }, [loadPortForwards]);

  const createPortForward = useCallback(async (input: {
    peerId: string;
    protocol: string;
    externalPort: number;
    internalPort?: number;
  }) => {
    if (portForwardCreating) return;
    setPortForwardCreating(true);
    setPortForwardsError("");
    try {
      const body: Record<string, unknown> = {
        peer_id: input.peerId,
        protocol: input.protocol,
        external_port: input.externalPort,
      };
      if (input.internalPort != null && !Number.isNaN(input.internalPort)) {
        body.internal_port = input.internalPort;
      }
      const response = await fetchWithAuth(`${AUTH_API}/api/v1/wg/port-forwards`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(body),
      });
      const data = (await response.json().catch(() => ({}))) as PortForwardInfo & { error?: string };
      if (!response.ok) throw new Error(data.error || "Could not create port forward.");
      setPortForwards((list) => [data, ...list.filter((pf) => pf.id !== data.id)]);
    } catch (err) {
      if (err instanceof SessionExpiredError) {
        expireAndReturnToSignIn();
        return;
      }
      setPortForwardsError(err instanceof Error ? err.message : "Could not create port forward.");
    } finally {
      setPortForwardCreating(false);
    }
  }, [portForwardCreating, expireAndReturnToSignIn]);

  const deletePortForward = useCallback(async (id: string) => {
    if (!id || deletingForwardId) return;
    setDeletingForwardId(id);
    setPortForwardsError("");
    try {
      const response = await fetchWithAuth(`${AUTH_API}/api/v1/wg/port-forwards/${id}`, {
        method: "DELETE",
      });
      if (!response.ok) {
        const data = (await response.json().catch(() => ({}))) as { error?: string };
        throw new Error(data.error || "Could not delete port forward.");
      }
      setPortForwards((list) => list.filter((pf) => pf.id !== id));
    } catch (err) {
      if (err instanceof SessionExpiredError) {
        expireAndReturnToSignIn();
        return;
      }
      setPortForwardsError(err instanceof Error ? err.message : "Could not delete port forward.");
    } finally {
      setDeletingForwardId(null);
    }
  }, [deletingForwardId, expireAndReturnToSignIn]);

  const revokePeer = useCallback(async (peer: PeerInfo) => {
    if (!peer.id || revokingId) return;
    setRevokingId(peer.id);
    setDevicesError("");
    try {
      if (peer.id === peerIdRef.current && (connectedRef.current || connectingRef.current)) {
        await handleDisconnect();
      }
      const response = await fetchWithAuth(`${AUTH_API}/api/v1/wg/peers/${peer.id}`, {
        method: "DELETE",
      });
      if (!response.ok) {
        const data = (await response.json().catch(() => ({}))) as { error?: string };
        throw new Error(data.error || "Could not revoke device.");
      }
      setPeers((list) => list.filter((p) => p.id !== peer.id));
    } catch (err) {
      if (err instanceof SessionExpiredError) {
        expireAndReturnToSignIn();
        return;
      }
      setDevicesError(err instanceof Error ? err.message : "Could not revoke device.");
    } finally {
      setRevokingId(null);
    }
  }, [revokingId, handleDisconnect, expireAndReturnToSignIn]);

  const toggleAutoReconnect = useCallback(() => {
    setAutoReconnect((prev) => {
      const next = !prev;
      writeLocalFlag(LS_AUTO_RECONNECT, next);
      return next;
    });
  }, []);

  const toggleExcludeLan = useCallback(() => {
    setExcludeLan((prev) => {
      const next = !prev;
      writeLocalFlag(LS_EXCLUDE_LAN, next);
      return next;
    });
    setReconnectToApply(true);
  }, []);

  const toggleStealthMode = useCallback(() => {
    if (!linuxDesktop) return;
    setStealthMode((prev) => {
      const next = !prev;
      writeLocalFlag(LS_STEALTH, next);
      return next;
    });
    setReconnectToApply(true);
  }, [linuxDesktop]);

  const handleSignOut = useCallback(() => {
    if (user) clearCachedBillingStatus(user.account_id);
    setSubscriptionActive(false);
    setSubscriptionChecked(false);
    setShowPlans(false);
    setShowDevices(false);
    setShowPortForwards(false);
    setCheckoutUrl(null);
    userDisconnectedRef.current = true;
    clearReconnectTimer();
    if (connected || connecting) void handleDisconnect();
    doSignOut();
    setUser(null);
    setNewAccountId("");
    setShowSignOutConfirm(false);
  }, [connected, connecting, handleDisconnect, user, clearReconnectTimer]);

  const handleSignOutEverywhere = useCallback(async () => {
    setShowSettings(false);
    try {
      await fetchWithAuth(`${AUTH_API}/api/v1/auth/logout-all`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: "{}",
      }).catch(() => undefined);
    } catch {
      // still sign out locally
    }
    handleSignOut();
  }, [handleSignOut]);

  const requestSignOut = useCallback(() => {
    setShowSettings(false);
    if (connected || connecting) setShowSignOutConfirm(true);
    else handleSignOut();
  }, [connected, connecting, handleSignOut]);

  const copyAccountId = useCallback(async () => {
    if (!newAccountId) return;
    try {
      await navigator.clipboard.writeText(newAccountId);
      setAccountIdCopied(true);
    } catch {
      setError("Could not copy to clipboard.");
    }
  }, [newAccountId]);

  if (!user || (newAccountId && method === "accountId" && mode === "signup")) {
    const showingNewId = Boolean(newAccountId);
    if (pendingVerificationEmail) {
      return (
        <div className="app auth-screen">
          <div className="brand">
            <img className="brand-logo auth-logo" src={veritasMark} alt="VeritasVPN" />
            <h1>Veritas<span>VPN</span></h1>
          </div>
          <div className="auth-card">
            <h2>Verify your email</h2>
            <p className="auth-subcopy">
              Confirm your account creation by clicking the verification link we sent to <strong>{pendingVerificationEmail}</strong>.
            </p>
            {notice && <div className="notice">{notice}</div>}
            {error && <div className="error">{error}</div>}
            <button type="button" className="btn-primary" disabled={resendLoading} onClick={() => handleResendVerification(pendingVerificationEmail)}>
              {resendLoading ? "Sending verification email…" : "Resend verification email"}
            </button>
            <button type="button" className="auth-switch-link" onClick={() => { setPendingVerificationEmail(null); setError(""); setNotice(""); switchMode("signin"); }}>← Back to sign in</button>
          </div>
        </div>
      );
    }
    if (forgotPassword) {
      return (
        <div className="app auth-screen">
          <div className="brand">
            <img className="brand-logo auth-logo" src={veritasMark} alt="VeritasVPN" />
            <h1>Veritas<span>VPN</span></h1>
          </div>
          <div className="auth-card">
            <h2>Reset your password</h2>
            <p className="auth-subcopy">
              {resetCooldown > 0
                ? `Check your inbox for a secure reset link. You can request another in ${resetCooldown} seconds.`
                : resetSent
                  ? "Check your inbox for a secure reset link."
                  : "Enter your email and we'll send you a secure reset link."}
            </p>
            {error && <div className="error">{error}</div>}
            <input type="email" placeholder="Email" value={email} onChange={(e) => { setEmail(e.target.value); setError(""); setResetSent(false); }} autoComplete="email" />
            <button type="button" className="btn-primary" disabled={loading || resetCooldown > 0} onClick={handleResetPassword}>
              {loading ? "Sending…" : resetCooldown > 0 ? `Try again in ${resetCooldown} s` : "Send reset link"}
            </button>
            <button type="button" className="auth-switch-link" onClick={() => { setForgotPassword(false); setError(""); setResetSent(false); }}>Back to sign in</button>
          </div>
        </div>
      );
    }

    return (
      <div className="app auth-screen">
        <div className="brand">
          <img className="brand-logo auth-logo" src={veritasMark} alt="VeritasVPN" />
          <h1>Veritas<span>VPN</span></h1>
          <p>The truth about online privacy</p>
        </div>
        {!showingNewId && (
          <div className="auth-tabs">
            <button className={mode === "signin" ? "active" : ""} onClick={() => switchMode("signin")} type="button">Sign in</button>
            <button className={mode === "signup" ? "active" : ""} onClick={() => switchMode("signup")} type="button">Sign up</button>
          </div>
        )}
        <form className="auth-card" onSubmit={handleAuth}>
          {notice && <div className="notice">{notice}</div>}
          {error && <div className="error">{error}</div>}
          {verificationResendEmail && (
            <button type="button" className="btn-outline" disabled={resendLoading} onClick={() => handleResendVerification()}>
              {resendLoading ? "Sending verification email…" : "Resend verification email"}
            </button>
          )}
          {showingNewId ? (
            <>
              <h2>Your Account ID</h2>
              <p className="auth-hint success">This is the only credential that can restore access to your anonymous account.</p>
              <div className="account-id-row">
                <code className="account-id-display">{newAccountId}</code>
                <button type="button" className="btn-icon" onClick={copyAccountId} aria-label="Copy Account ID">⧉</button>
              </div>
              {accountIdCopied && <p className="auth-hint success">Copied to clipboard</p>}
              <div className="account-warning">
                <strong>Save this ID now</strong>
                <span>Store it in a password manager or another secure place. If you lose it, the account cannot be recovered.</span>
              </div>
              <button type="button" className="btn-primary" onClick={() => downloadAccountFile().catch(() => undefined)}>Download account file</button>
              <button type="button" className="btn-primary" onClick={() => setNewAccountId("")}>Continue</button>
            </>
          ) : method === "email" ? (
            <>
              <input type="email" placeholder="Email" value={email} onChange={(e) => { setEmail(e.target.value); setError(""); setNotice(""); setVerificationResendEmail(""); }} required autoComplete="email" />
              <div className="password-field">
                <input type={passwordVisible ? "text" : "password"} placeholder="Password" value={password} onChange={(e) => { setPassword(e.target.value); setError(""); }} required minLength={10} autoComplete={mode === "signin" ? "current-password" : "new-password"} />
                <button type="button" className="password-toggle" onClick={() => setPasswordVisible((v) => !v)} aria-label={passwordVisible ? "Hide password" : "Show password"}>{passwordVisible ? "Hide" : "Show"}</button>
              </div>
              {mode === "signup" && (
                <>
                  <div className="password-field">
                    <input type={confirmVisible ? "text" : "password"} placeholder="Confirm password" value={confirmPassword} onChange={(e) => { setConfirmPassword(e.target.value); setError(""); }} required autoComplete="new-password" />
                    <button type="button" className="password-toggle" onClick={() => setConfirmVisible((v) => !v)} aria-label={confirmVisible ? "Hide confirmed password" : "Show confirmed password"}>{confirmVisible ? "Hide" : "Show"}</button>
                  </div>
                  <PasswordStrength password={password} />
                </>
              )}
              {mode === "signin" && (
                <button type="button" className="auth-forgot" onClick={() => { setForgotPassword(true); setError(""); }}>Forgot password?</button>
              )}
              <button type="submit" disabled={loading} className="btn-primary">
                {loading ? "Please wait…" : mode === "signin" ? "Sign in" : "Create account"}
              </button>
            </>
          ) : mode === "signin" ? (
            <>
              <p className="auth-hint">Anonymous Account ID only — email accounts must use password.</p>
              <input type="text" placeholder="Anonymous Account ID" value={accountId} onChange={(e) => setAccountId(e.target.value)} required autoComplete="off" spellCheck={false} />
              <button type="submit" disabled={loading} className="btn-primary">{loading ? "Please wait…" : "Sign in with Account ID"}</button>
            </>
          ) : (
            <>
              <p className="auth-hint">Creates an anonymous account. You'll get an Account ID to save — no email required.</p>
              <button type="submit" disabled={loading} className="btn-primary">{loading ? "Please wait…" : "Create anonymous account"}</button>
            </>
          )}
        </form>
        {!showingNewId && (
          <button type="button" className="auth-switch-link" onClick={() => switchMethod(method === "email" ? "accountId" : "email")}>
            {method === "email"
              ? mode === "signin" ? "Sign in with Account ID instead" : "Skip email — create anonymous account"
              : mode === "signin" ? "Sign in with email instead" : "Use email instead"}
          </button>
        )}
      </div>
    );
  }

  if (checkoutUrl) {
    return (
      <div className="app app-dashboard">
        <PaymentCheckoutScreen checkoutUrl={checkoutUrl} onClose={() => setCheckoutUrl(null)} onRefreshPlan={() => refreshBillingStatus().catch(() => undefined)} />
      </div>
    );
  }

  return (
    <div className="app app-dashboard">
      <header className="app-header blueprint-header">
        <img className="brand-logo" src={veritasMark} alt="VeritasVPN" />
        {!showPlans && !showDevices && !showPortForwards && (
          <button
            ref={settingsCogRef}
            className="blueprint-cog"
            onClick={() => setShowSettings((open) => !open)}
            aria-label="Open settings"
            aria-expanded={showSettings}
            aria-controls="settings-drawer"
          >
            ⚙
          </button>
        )}
      </header>

      <main className="blueprint-main">
        {showPlans ? (
          <PlansScreen
            billingStatus={billingStatus}
            billingLoading={billingLoading}
            billingBusy={billingBusy}
            checkoutMethod={checkoutMethod}
            billingError={billingError}
            selectedPlan={selectedPlan}
            showCancelConfirmation={showCancelConfirmation}
            onBack={() => setShowPlans(false)}
            onRefresh={() => refreshBillingStatus().catch((err) => setBillingError(err instanceof Error ? err.message : "Could not load your subscription."))}
            onSelectPlan={setSelectedPlan}
            onCheckout={() => startCheckout()}
            onCancelClick={() => setShowCancelConfirmation(true)}
            onCancelConfirm={() => cancelSubscription()}
            onCancelDismiss={() => setShowCancelConfirmation(false)}
          />
        ) : showDevices ? (
          <DevicesScreen
            peers={peers}
            loading={devicesLoading}
            error={devicesError}
            currentPeerId={peerId}
            revokingId={revokingId}
            onBack={() => setShowDevices(false)}
            onRefresh={() => void loadDevices()}
            onRevoke={(peer) => void revokePeer(peer)}
          />
        ) : showPortForwards ? (
          <PortForwardsScreen
            forwards={portForwards}
            peers={peers}
            loading={portForwardsLoading}
            creating={portForwardCreating}
            deletingId={deletingForwardId}
            error={portForwardsError}
            currentPeerId={peerId}
            onBack={() => setShowPortForwards(false)}
            onRefresh={() => void loadPortForwards()}
            onCreate={(input) => void createPortForward(input)}
            onDelete={(id) => void deletePortForward(id)}
          />
        ) : showNetworkMap ? (
          <section className="network-map-view">
            <div className="map-view-head"><div><span>NETWORK MAP</span><h2>Your secure route</h2></div><button type="button" onClick={() => setShowNetworkMap(false)}>Back</button></div>
            <ConnectionMap connected={connected} connecting={connecting || reconnecting} deviceLabel={deviceLabel} />
            <div className="map-summary">
              <div><span>CONNECTION</span><strong>{connected ? "Encrypted route active" : connecting || reconnecting ? "Establishing route…" : "No secure route"}</strong></div>
              <b className={connected ? "on" : connecting || reconnecting ? "connecting" : ""}>{connected ? "SECURED" : connecting || reconnecting ? "CONNECTING" : "OFFLINE"}</b>
            </div>
          </section>
        ) : (
          <>
            <PrivacyScene encrypted={connected} />
            <section className="blueprint-status">
              {!connected && !reconnecting ? (
                <>
                  <div className={`blueprint-badge ${connecting ? "connecting" : ""}`}>
                    <i />
                    {connecting ? "ESTABLISHING SECURE CONNECTION" : "VPN DISCONNECTED"}
                  </div>
                  <h2>{"Your online activity\nis visible"}</h2>
                  <p>
                    {connecting
                      ? "Creating secure keys and validating encrypted internet access."
                      : "Hide your IP address and encrypt your connection."}
                  </p>
                  <button
                    className="blueprint-primary"
                    disabled={connecting || !subscriptionChecked}
                    onClick={subscriptionActive ? handleConnect : openPlans}
                  >
                    {(connecting || !subscriptionChecked) ? (
                      <i className="button-spinner" />
                    ) : (
                      <span className={`cta-lock ${subscriptionActive ? "open" : ""}`} aria-hidden="true" />
                    )}
                    {connecting
                      ? "Connecting…"
                      : !subscriptionChecked
                        ? "Checking plan…"
                        : subscriptionActive
                          ? "Connect now"
                          : "Get Premium"}
                    {!connecting && subscriptionChecked && <b>→</b>}
                  </button>
                </>
              ) : (
                <>
                  <div className="blueprint-secured">
                    {reconnecting && !connected ? "RECONNECTING…" : "CONNECTION SECURED"}
                  </div>
                  {connected && activeTransport && (
                    <div
                      className={`transport-badge ${activeTransport === "stealth" ? "stealth" : "direct"}`}
                      aria-label="Transport mode"
                    >
                      {activeTransport === "stealth" ? "Stealth TLS" : "Direct UDP"}
                    </div>
                  )}
                  <h2 className="connected-title">{reconnecting && !connected ? "Reconnecting…" : "You're protected"}</h2>
                  {reconnecting && !connected && (
                    <p>Restoring your encrypted WireGuard tunnel.</p>
                  )}
                  {(connected || reconnecting) && (
                    <button className="blueprint-disconnect" type="button" onClick={handleDisconnect} disabled={connecting && !reconnecting}>
                      Disconnect
                    </button>
                  )}
                  {reconnectToApply && (connected || reconnecting) && (
                    <div className="reconnect-banner" role="status">
                      Reconnect to apply
                    </div>
                  )}
                  {connected && wgStats && (
                    <div className="live-stats" aria-label="Live tunnel statistics">
                      <span className="live-stats-label">LIVE STATS</span>
                      <div className="live-stats-row">
                        <div><strong>{formatBytes(wgStats.rx_bytes)}</strong><span>Download</span></div>
                        <div><strong>{formatBytes(wgStats.tx_bytes)}</strong><span>Upload</span></div>
                        <div><strong>{formatHandshakeAge(wgStats.last_handshake_sec)}</strong><span>Handshake</span></div>
                      </div>
                      {dnsBlockedCount !== null && (
                        <div className="live-stats-dns">
                          <span>DNS threats blocked</span>
                          <strong>{dnsBlockedCount}</strong>
                        </div>
                      )}
                    </div>
                  )}
                  {connected && egressIp && (
                    <div className="protected-meta">
                      <i />
                      {`Connected · ${egressIp}`}
                    </div>
                  )}
                </>
              )}
              {reconnectToApply && !connected && !reconnecting && !connecting && (
                <div className="reconnect-banner" role="status">
                  Reconnect to apply
                </div>
              )}
              {statusMsg && !(connecting && /^connecting|creating secure/i.test(statusMsg)) && (
                <div className={`status-msg ${connected ? "ok" : connecting || reconnecting ? "info" : "warn"} ${statusSticky || isStickyStatusMessage(statusMsg) ? "sticky" : ""}`}>
                  <span>{statusMsg}</span>
                  {(statusSticky || isStickyStatusMessage(statusMsg)) && (
                    <button type="button" className="status-dismiss" onClick={dismissStatus} aria-label="Dismiss">
                      Dismiss
                    </button>
                  )}
                </div>
              )}
            </section>
          </>
        )}
      </main>

      <SettingsDrawer
        open={showSettings}
        onClose={closeSettings}
        returnFocusRef={settingsCogRef}
        subscriptionActive={subscriptionActive}
        linuxDesktop={linuxDesktop}
        autoReconnect={autoReconnect}
        excludeLan={excludeLan}
        stealthMode={stealthMode}
        onOpenPlans={openPlans}
        onOpenNetworkMap={openNetworkMap}
        onOpenDevices={openDevices}
        onOpenPortForwards={openPortForwards}
        onToggleAutoReconnect={toggleAutoReconnect}
        onToggleExcludeLan={toggleExcludeLan}
        onToggleStealthMode={toggleStealthMode}
        onSignOutEverywhere={handleSignOutEverywhere}
        onRequestSignOut={requestSignOut}
      />

      {showSignOutConfirm && (
        <div className="dialog-overlay" role="presentation" onClick={() => setShowSignOutConfirm(false)}>
          <div className="dialog-card" role="alertdialog" aria-modal="true" onClick={(e) => e.stopPropagation()}>
            <strong>Sign out from this device?</strong>
            <p>Signing out will disconnect your VPN. Continue?</p>
            <div className="dialog-actions">
              <button type="button" onClick={() => setShowSignOutConfirm(false)}>Cancel</button>
              <button type="button" className="danger-solid" onClick={handleSignOut}>Sign out from this device</button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

export default App;
