import { useState, useEffect, FormEvent, useCallback, useRef } from "react";
import { invoke } from "@tauri-apps/api/core";
import { fetch as nativeFetch } from "@tauri-apps/plugin-http";
import {
  getStoredToken,
  getStoredUser,
  initializeSecureAuth,
  signIn as doSignIn,
  signUp as doSignUp,
  signInWithAccountId as doSignInAccountId,
  registerAnonymous as doRegisterAnonymous,
  signOut as doSignOut,
  deleteAccount as doDeleteAccount,
  resetPassword,
  resendVerification,
  validateSignupPassword,
  passwordStrengthScore,
  VerificationRequiredError,
  AccountAlreadyExistsError,
  isTurnstileRequiredError,
  User,
  validateSessionOnResume,
} from "./auth";
import { fetchWithAuth, SessionExpiredError, SESSION_EXPIRED_EVENT } from "./session";
import {
  readCachedBillingStatus,
  writeCachedBillingStatus,
  clearCachedBillingStatus,
  hasPendingBitcoinConfirmation,
  BillingStatus,
  type PurchaseHistoryItem,
} from "./billing";
import { AUTH_API } from "./config";
import { obtainTurnstileToken, prewarmTurnstile } from "./turnstile";
import { SettingsDrawer, ShieldSettingsScreen, StealthSettingsScreen, TunnelSettingsScreen, type StealthChoice } from "./SettingsDrawer";
import { HelpSupport } from "./HelpSupport";
import { choosePublicEndpoint, isRecordableError, readStoredLastError, sanitizeError, writeStoredLastError } from "./support";
import { readShieldFlags, shieldRequest, writeShieldFlags, type ShieldFlags } from "./shield";
import { HeroConnectControl, type HeroPhase } from "./HeroConnect";
import {
  projectLatLng,
  PARAGUAY_NODE,
  WORLD_MAP_HEIGHT,
  WORLD_MAP_WIDTH,
} from "./connectionMap";
import { statusAfterDisconnectFailure } from "./disconnectStatus";
import { toUserMessage } from "./errorMapper";
import veritasMark from "./assets/veritas-mark.png";
import veritasLogo from "./assets/veritas-logo.png";
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
  server_endpoint_lan?: string;
  server_endpoint_wan?: string;
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
const LS_EXCLUDE_LAN = "veritas_exclude_lan";
const LS_STEALTH = "veritas_stealth_mode";
const LS_DEVICE_ID = "veritas_device_id";

function getOrCreateDeviceId(): string {
  try {
    const existing = localStorage.getItem(LS_DEVICE_ID)?.trim();
    if (existing) return existing;
    const created =
      typeof crypto !== "undefined" && "randomUUID" in crypto
        ? crypto.randomUUID()
        : `dev-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`;
    localStorage.setItem(LS_DEVICE_ID, created);
    return created;
  } catch {
    // localStorage unavailable: still send a UUID-shaped id for this process so the
    // server does not mint a fresh anon-* peer on every connect attempt.
    return typeof crypto !== "undefined" && "randomUUID" in crypto
      ? crypto.randomUUID()
      : `dev-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`;
  }
}

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
  shield_preset?: string;
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

interface DesktopDeviceMetadata {
  device_platform: string;
  device_model: string;
  device_os_version: string;
  client_version: string;
}

function fallbackDevicePlatform(): string {
  if (typeof navigator === "undefined") return "Desktop";
  const ua = navigator.userAgent.toLowerCase();
  if (ua.includes("android")) return "Android";
  if (ua.includes("windows")) return "Windows";
  if (ua.includes("mac os") || ua.includes("macintosh")) return "macOS";
  if (ua.includes("linux")) return "Linux";
  return "Desktop";
}

// Same display fields Android sends. device_name is omitted so a name set on
// the account page is not replaced when this device reconnects.
async function deviceMetadataForPeer(): Promise<DesktopDeviceMetadata> {
  try {
    const reported = await invoke<DesktopDeviceMetadata>("desktop_device_metadata");
    return {
      device_platform: reported.device_platform?.trim() || fallbackDevicePlatform(),
      device_model: reported.device_model?.trim() || "",
      device_os_version: reported.device_os_version?.trim() || "",
      client_version: reported.client_version?.trim() || "",
    };
  } catch {
    return {
      device_platform: fallbackDevicePlatform(),
      device_model: "",
      device_os_version: "",
      client_version: "",
    };
  }
}

function readStealthChoice(): StealthChoice {
  try {
    const raw = localStorage.getItem(LS_STEALTH);
    if (raw === "stealth" || raw === "1") return "stealth";
    if (raw === "udp" || raw === "0") return "udp";
    return "auto";
  } catch {
    return "auto";
  }
}

function writeStealthChoice(choice: StealthChoice) {
  try {
    localStorage.setItem(LS_STEALTH, choice);
  } catch {
    // ignore quota / private mode
  }
}

function errorText(err: unknown): string {
  if (err instanceof Error) return err.message;
  if (typeof err === "string") return err;
  if (err && typeof err === "object" && "message" in err && typeof (err as { message: unknown }).message === "string") {
    return (err as { message: string }).message;
  }
  return "";
}

/** Auto may retry Stealth after a UDP handshake or egress failure. Auth, kill-switch, and user-cancel errors stay put. */
function isAutoFallbackError(err: unknown): boolean {
  if (err instanceof SessionExpiredError) return false;
  const lower = errorText(err).toLowerCase();
  if (
    /kill switch|nftables|iptables|authorization|administrator|cancelled|canceled|dismissed|subscription|device limit|unavailable in this build|not bundled/.test(lower)
  ) {
    return false;
  }
  return /handshake|egress|validation|timed out|timeout|unreachable|connection failed|network/.test(lower);
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
      return "Stealth failed: wstunnel is not bundled in this build. Rebuild with the Linux stealth binary, or choose UDP only.";
    }
    if (/stealth engine missing/.test(lower)) {
      return "Stealth failed: wstunnel binary missing. Bundle it for Linux or choose UDP only.";
    }
    if (/linux desktop|linux only|available on linux/.test(lower)) {
      return "Stealth mode is Linux-only in this build. Choose UDP only to connect with Direct UDP.";
    }
    if (/failed to start stealth|stealth transport/.test(lower)) {
      return "Stealth transport failed to start. Check TLS endpoint reachability, or choose UDP only.";
    }
    if (/path prefix/.test(lower)) {
      return "Stealth failed: server path prefix missing. Try again later or choose UDP only.";
    }
    if (/not available on the (server|vpn node)/.test(lower)) {
      return "Stealth is not available on the VPN node yet. Choose UDP only or try again later.";
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
  if (!lastHandshakeSec || lastHandshakeSec <= 0) return "—";
  const ageSec = Math.max(0, Math.floor(Date.now() / 1000 - lastHandshakeSec));
  if (ageSec < 60) return `${ageSec}s ago`;
  if (ageSec < 3600) return `${Math.floor(ageSec / 60)}m ago`;
  return `${Math.floor(ageSec / 3600)}h ago`;
}

function formatBillingDate(value?: string) {
  if (!value) return "the end of your current billing period";
  const date = new Date(value);
  return Number.isNaN(date.getTime())
    ? value.slice(0, 10)
    : new Intl.DateTimeFormat(undefined, { dateStyle: "medium" }).format(date);
}

function formatPurchaseDate(value?: string) {
  if (!value) return "—";
  const date = new Date(value);
  return Number.isNaN(date.getTime())
    ? value.slice(0, 10)
    : new Intl.DateTimeFormat(undefined, { dateStyle: "medium" }).format(date);
}

function formatPurchaseAmount(cents?: number, currency?: string) {
  if (typeof cents !== "number" || !Number.isFinite(cents)) return "—";
  const sign = cents < 0 ? "-" : "";
  const abs = Math.abs(Math.trunc(cents));
  const dollars = Math.floor(abs / 100);
  const remainder = abs % 100;
  const value = remainder === 0 ? String(dollars) : `${dollars}.${String(remainder).padStart(2, "0")}`;
  if ((currency || "usd").toLowerCase() === "usd") return `${sign}$${value}`;
  return `${sign}${value} ${String(currency).toUpperCase()}`;
}

function purchasePlanLabel(plan?: string) {
  if (plan === "annual") return "Annual";
  if (plan === "monthly") return "Monthly";
  return "—";
}

function purchaseStatusLabel(status?: string) {
  switch (status) {
    case "completed": return "Confirmed";
    case "pending": return "Pending";
    case "failed": return "Failed";
    case "refunded": return "Refunded";
    default: return "Pending";
  }
}

function purchaseStatusClass(status?: string) {
  switch (status) {
    case "completed": return "is-confirmed";
    case "failed": return "is-failed";
    case "refunded": return "is-refunded";
    default: return "is-pending";
  }
}

function PurchaseHistory({ status, loading, error }: { status: BillingStatus | null; loading: boolean; error: string }) {
  const payments = status?.payments;
  let body;
  if (Array.isArray(payments) && payments.length === 0) {
    body = <p className="purchase-empty">No past payments yet.</p>;
  } else if (Array.isArray(payments)) {
    body = (
      <ul className="purchase-history">
        {payments.slice(0, 100).map((payment: PurchaseHistoryItem, index) => (
          <li key={`${payment.created_at}-${payment.amount_cents}-${index}`}>
            <div>
              <strong>{formatPurchaseDate(payment.created_at)}</strong>
              <span>{purchasePlanLabel(payment.plan)} · {formatPurchaseAmount(payment.amount_cents, payment.currency)}</span>
            </div>
            <b className={`purchase-status ${purchaseStatusClass(payment.status)}`}>{purchaseStatusLabel(payment.status)}</b>
          </li>
        ))}
      </ul>
    );
  } else if (loading && !error) {
    body = <p className="purchase-empty">Loading purchase history…</p>;
  } else if (error || !status) {
    body = <p className="purchase-empty">Purchase history could not be loaded.</p>;
  } else {
    body = <p className="purchase-empty">This billing service has not sent purchase history yet.</p>;
  }
  return (
    <div className="purchase-history-block">
      <h4>Purchase history</h4>
      <p>Bitcoin payments recorded for this account.</p>
      {body}
    </div>
  );
}

function routeLabelStyle(point: { x: number; y: number }): { left: string; top: string } {
  return {
    left: `${(point.x / WORLD_MAP_WIDTH) * 100}%`,
    top: `${(point.y / WORLD_MAP_HEIGHT) * 100}%`,
  };
}

function ConnectionMap({
  connected,
  connecting,
}: {
  connected: boolean;
  connecting: boolean;
}) {
  const server = projectLatLng(PARAGUAY_NODE.lat, PARAGUAY_NODE.lng);
  return (
    <section className={`connection-map ${connected ? "is-connected" : ""}`} aria-label="VeritasVPN server in Paraguay">
      <div className="map-topline"><span>SERVER LOCATION</span><span className="map-latency">{connected ? "ENCRYPTED" : connecting ? "CONNECTING" : "READY"}</span></div>
      <img className="world-map" src="/world-map.svg" alt="World map" />
      <svg className="route-overlay" viewBox={`0 0 ${WORLD_MAP_WIDTH} ${WORLD_MAP_HEIGHT}`} preserveAspectRatio="xMidYMid meet" aria-hidden="true">
        <g className="map-destination" transform={`translate(${server.x.toFixed(1)} ${server.y.toFixed(1)})`}><circle className="map-pulse" r="24"/><circle r="10"/></g>
      </svg>
      <div className="route-label is-below" style={routeLabelStyle(server)}><span>SERVER</span><strong>Asunción, Paraguay</strong></div>
    </section>
  );
}

function PasswordStrength({ password }: { password: string }) {
  if (!password) return null;
  const score = passwordStrengthScore(password);
  const label = score === 4 ? "Strong" : score === 3 ? "Good" : score === 2 ? "Fair" : "Weak";
  const tone = score === 4 ? "strong" : score === 3 ? "good" : score === 2 ? "fair" : "weak";
  return (
    <div className={`password-strength score-${tone}`} aria-live="polite">
      <div className="password-strength-head">
        <span>Password strength</span>
        <strong>{label}</strong>
      </div>
      <div
        className="password-strength-track"
        role="progressbar"
        aria-valuenow={score}
        aria-valuemin={0}
        aria-valuemax={4}
      >
        <i style={{ width: `${(score / 4) * 100}%` }} />
      </div>
      <p className="password-strength-hint">10+ characters · uppercase · lowercase · number</p>
    </div>
  );
}

function AccountScreen({
  email,
  accountId,
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
  deletingAccount,
  deleteError,
  onDeleteAccount,
}: {
  email?: string;
  accountId: string;
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
  deletingAccount: boolean;
  deleteError: string;
  onDeleteAccount: (password: string) => void;
}) {
  const [showDeleteConfirmation, setShowDeleteConfirmation] = useState(false);
  const [deletePassword, setDeletePassword] = useState("");
  const premium = billingStatus?.is_premium === true;
  const paymentPending = hasPendingBitcoinConfirmation(billingStatus);
  const price = selectedPlan === "premium_annual" ? "$30" : "$3";
  const suffix = selectedPlan === "premium_annual" ? "/year" : "/month";
  const shownEmail = email?.trim() || "";
  const shownAccountId = accountId.trim();
  const requiresPassword = shownEmail.length > 0;
  return (
    <section className="plans-screen" aria-label="Account">
      <div className="plans-head">
        <button type="button" className="glass-icon-button" onClick={onBack} aria-label="Back">
          <svg width="20" height="20" viewBox="0 0 24 24" aria-hidden="true" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
            <path d="M15 18l-6-6 6-6" />
          </svg>
        </button>
        <div>
          <p className="screen-eyebrow">SIGNED IN</p>
          <h2>Account</h2>
        </div>
      </div>
      <p className="screen-sub">Your account, plan, and Bitcoin payments.</p>
      <p className="account-disclosure">VeritasVPN stores your account email if you use one, an Account ID, device details for connected peers, and payment records. The Linux device name may be this computer's hostname.</p>
      {(shownEmail || shownAccountId) && (
        <div className="account-identity">
          {shownEmail && (
            <div>
              <span>EMAIL</span>
              <strong>{shownEmail}</strong>
            </div>
          )}
          {shownAccountId && (
            <div>
              <span>ACCOUNT ID</span>
              <strong className="account-id-value">{shownAccountId}</strong>
            </div>
          )}
        </div>
      )}

      <div className="billing-current">
        <div>
          <span>CURRENT PLAN</span>
          <strong className={premium ? "premium" : ""}>
            {billingLoading && !billingStatus ? "Checking subscription…" : premium ? "Premium" : paymentPending ? "Payment pending" : "No active subscription"}
          </strong>
          {!billingLoading && premium && billingStatus?.current_period_end && (
            <small className="billing-period-end">
              <span>PREMIUM ACCESS EXPIRES</span>
              <time dateTime={billingStatus.current_period_end}>Expires on {formatBillingDate(billingStatus.current_period_end)}</time>
            </small>
          )}
        </div>
        <button type="button" disabled={billingLoading} onClick={onRefresh}>
          {billingLoading ? <i className="button-spinner" /> : "Refresh"}
        </button>
      </div>

      {billingError && <div className="billing-error">{billingError}</div>}
      {paymentPending && (
        <div className="billing-cancellation-scheduled" role="status">
          <strong>{billingStatus?.payment_state === "awaiting_confirmation" ? "Payment received" : billingStatus?.payment_state === "awaiting_payment" ? "Waiting for payment" : "Checking payment"}</strong>
          <span>{billingStatus?.payment_message || "Premium activates automatically after Bitcoin confirms."}</span>
        </div>
      )}

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

      {!premium && !paymentPending && (
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

      {!premium && !paymentPending ? (
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
      ) : premium ? (
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
      ) : null}
      <PurchaseHistory status={billingStatus} loading={billingLoading} error={billingError} />
      <div className="account-delete">
        <h4>Delete account</h4>
        <p>Permanently deletes this account, signs you out, and removes the data we store for it. This cannot be undone.</p>
        {!showDeleteConfirmation ? (
          <button type="button" className="account-delete-button" onClick={() => setShowDeleteConfirmation(true)} disabled={deletingAccount}>
            Delete account
          </button>
        ) : (
          <div className="billing-cancel-confirm" role="alertdialog" aria-modal="true">
            <strong>Delete this account?</strong>
            <p>{requiresPassword ? "Enter your password to confirm. You will be signed out of this device." : "Complete the security check to confirm. You will be signed out of this device."}</p>
            {requiresPassword && (
              <label className="account-delete-password">
                Password
                <input
                  type="password"
                  autoComplete="current-password"
                  value={deletePassword}
                  onChange={(event) => setDeletePassword(event.target.value)}
                  disabled={deletingAccount}
                />
              </label>
            )}
            {deleteError && <div className="billing-error">{deleteError}</div>}
            <div>
              <button type="button" disabled={deletingAccount} onClick={() => { setShowDeleteConfirmation(false); setDeletePassword(""); }}>Keep my account</button>
              <button
                type="button"
                disabled={deletingAccount || (requiresPassword && deletePassword.trim().length === 0)}
                onClick={() => onDeleteAccount(deletePassword)}
              >
                {deletingAccount ? "Deleting…" : "Delete account permanently"}
              </button>
            </div>
          </div>
        )}
      </div>
    </section>
  );
}

function PaymentCheckoutScreen({
  checkoutUrl,
  onClose,
  onRefreshPlan,
  onCompleted,
}: {
  checkoutUrl: string;
  onClose: () => void;
  onRefreshPlan: () => void;
  onCompleted: () => void;
}) {
  const [loading, setLoading] = useState(true);
  useEffect(() => {
    const onMessage = (event: MessageEvent) => {
      if (event.origin !== "https://veritasvpn.cloud") return;
      const data = event.data as { source?: string; status?: string } | null;
      if (data?.source === "veritas-billing" && data.status === "settled") {
        onCompleted();
      }
    };
    window.addEventListener("message", onMessage);
    return () => window.removeEventListener("message", onMessage);
  }, [onCompleted]);
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

function transportLabel(transport: string): string | null {
  if (transport === "udp") return "Direct UDP";
  if (transport === "stealth") return "Stealth";
  if (transport === "switching") return "Switching to Stealth…";
  return null;
}

function connectingCopy(transport: string): { title: string; body: string } {
  if (transport === "switching") {
    return {
      title: "SWITCHING TO STEALTH",
      body: "UDP did not complete a handshake. Switching to Stealth.",
    };
  }
  if (transport === "stealth") {
    return {
      title: "CONNECTING OVER STEALTH",
      body: "Connecting over Stealth. WireGuard stays inside the VPN.",
    };
  }
  return {
    title: "ESTABLISHING SECURE CONNECTION",
    body: "Creating secure keys and validating encrypted internet access.",
  };
}

function HomeStage({
  connected,
  connecting,
  subscriptionChecked,
  subscriptionActive,
  transport,
  wgStats,
  dnsBlockedThisSession,
  dnsGateway,
  statusMsg,
  statusSticky,
  onConnect,
  onDisconnect,
  onGetPremium,
  onDismissStatus,
}: {
  connected: boolean;
  connecting: boolean;
  subscriptionChecked: boolean;
  subscriptionActive: boolean;
  transport: string;
  wgStats: WgTransferStats | null;
  dnsBlockedThisSession: number | null;
  dnsGateway: string | null;
  statusMsg: string;
  statusSticky: boolean;
  onConnect: () => void;
  onDisconnect: () => void;
  onGetPremium: () => void;
  onDismissStatus: () => void;
}) {
  const phase: HeroPhase = connected
    ? "protected"
    : connecting
      ? "connecting"
      : !subscriptionChecked
        ? "checking"
        : subscriptionActive
          ? "ready"
          : "upsell";
  const onClick = connecting || !subscriptionChecked
    ? null
    : connected
      ? onDisconnect
      : subscriptionActive
        ? onConnect
        : onGetPremium;
  const copy = connectingCopy(transport);
  const label = transportLabel(transport);
  const hideStatus = connecting && /^(connecting|reconnecting|creating secure)/i.test(statusMsg);
  return (
    <section className="home-stage">
      {(connected || connecting) && (
        <div className="home-status">
          {connected ? (
            <>
              <p className="home-kicker">CONNECTION SECURED</p>
              {label && (
                <span className={`transport-chip${transport === "switching" ? " is-switching" : ""}`}>{label}</span>
              )}
            </>
          ) : (
            <>
              <p className="home-kicker is-connecting">{copy.title}</p>
              <p className="home-status-body">{copy.body}</p>
            </>
          )}
        </div>
      )}
      <HeroConnectControl phase={phase} onClick={onClick} />
      {connected && wgStats && (
        <div className="live-stats" aria-label="Live tunnel statistics">
          <span className="live-stats-label">LIVE STATS</span>
          <div className="live-stats-row">
            <div><strong>{formatBytes(wgStats.rx_bytes)}</strong><span>Download</span></div>
            <div><strong>{formatBytes(wgStats.tx_bytes)}</strong><span>Upload</span></div>
            <div><strong>{formatHandshakeAge(wgStats.last_handshake_sec)}</strong><span>Handshake</span></div>
          </div>
          {dnsBlockedThisSession !== null && (
            <div className="live-stats-dns">
              <span>Shield blocked this session</span>
              <strong>{dnsBlockedThisSession}</strong>
            </div>
          )}
          <div className="live-stats-dns-status" role="status">
            <strong>Veritas Shield on</strong>
            <span>
              {dnsGateway ? `Gateway ${dnsGateway}` : "Tunnel gateway"}
              {" · DNS blocks for the filters that are on. Ads or trackers inside a page can still run. Well-known public DoH resolvers are blocked."}
            </span>
          </div>
          <p className="killswitch-status">Kill switch on. Non-VPN traffic stays blocked until you disconnect.</p>
        </div>
      )}
      {statusMsg && !hideStatus && (
        <div className={`status-msg ${connected ? "ok" : connecting ? "info" : "warn"} ${statusSticky ? "sticky" : ""}`}>
          <span>{statusMsg}</span>
          {statusSticky && (
            <button type="button" className="status-dismiss" onClick={onDismissStatus} aria-label="Dismiss">
              Dismiss
            </button>
          )}
        </div>
      )}
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
  const [subscriptionActive, setSubscriptionActive] = useState(false);
  const [subscriptionChecked, setSubscriptionChecked] = useState(false);
  const [billingStatus, setBillingStatus] = useState<BillingStatus | null>(null);
  const [showPlans, setShowPlans] = useState(false);
  const [billingBusy, setBillingBusy] = useState(false);
  const [billingLoading, setBillingLoading] = useState(false);
  const [billingError, setBillingError] = useState("");
  const [selectedPlan, setSelectedPlan] = useState<"premium_monthly" | "premium_annual">("premium_monthly");
  const [checkoutUrl, setCheckoutUrl] = useState<string | null>(null);
  const [checkoutSettlementPending, setCheckoutSettlementPending] = useState(false);
  const [checkoutMethod, setCheckoutMethod] = useState<string | null>(null);
  const [showCancelConfirmation, setShowCancelConfirmation] = useState(false);
  const [showSettings, setShowSettings] = useState(false);
  const [showNetworkMap, setShowNetworkMap] = useState(false);
  const [showSignOutConfirm, setShowSignOutConfirm] = useState(false);
  const [showTunnelSettings, setShowTunnelSettings] = useState(false);
  const [showShieldSettings, setShowShieldSettings] = useState(false);
  const [shieldFlags, setShieldFlags] = useState<ShieldFlags>(() => readShieldFlags());
  const [shieldError, setShieldError] = useState("");
  const [showStealthSettings, setShowStealthSettings] = useState(false);
  const [showHelp, setShowHelp] = useState(false);
  const [diagEndpoint, setDiagEndpoint] = useState("");
  const [lastError, setLastError] = useState(() => {
    const stored = readStoredLastError();
    return stored === "none" ? "" : stored;
  });
  const [excludeLan, setExcludeLan] = useState(() => readLocalFlag(LS_EXCLUDE_LAN, "0"));
  const [stealthMode, setStealthMode] = useState<StealthChoice>(() => (isLinuxDesktop() ? readStealthChoice() : "udp"));
  const [transport, setTransport] = useState<"" | "udp" | "stealth" | "switching">("");
  const [linuxDesktop] = useState(() => isLinuxDesktop());
  const [reconnectToApply, setReconnectToApply] = useState(false);
  const [statusSticky, setStatusSticky] = useState(false);
  const [wgStats, setWgStats] = useState<WgTransferStats | null>(null);
  const [dnsBlockedCount, setDnsBlockedCount] = useState<number | null>(null);
  const [dnsBlockedBaseline, setDnsBlockedBaseline] = useState<number | null>(null);
  const [dnsGateway, setDnsGateway] = useState<string | null>(null);
  const [reconnecting, setReconnecting] = useState(false);
  const [deletingAccount, setDeletingAccount] = useState(false);
  const [deleteAccountError, setDeleteAccountError] = useState("");
  const connectPeerRef = useRef("");
  const authBootstrapGenerationRef = useRef(0);
  const userDisconnectedRef = useRef(false);
  const handleDisconnectRef = useRef<() => Promise<void>>(async () => {});
  const clearReconnectTimerRef = useRef<() => void>(() => {});
  const hadGoodHandshakeRef = useRef(false);
  const hadInterfaceUpRef = useRef(false);
  const reconnectAttemptRef = useRef(0);
  const reconnectingRef = useRef(false);
  const reconnectTimerRef = useRef<number | null>(null);
  const settingsCogRef = useRef<HTMLButtonElement>(null);
  const peerIdRef = useRef("");
  const subscriptionActiveRef = useRef(subscriptionActive);
  const connectedRef = useRef(connected);
  const connectingRef = useRef(connecting);
  const excludeLanRef = useRef(excludeLan);
  const stealthModeRef = useRef(stealthMode);
  const shieldFlagsRef = useRef(shieldFlags);
  const shieldWriteGen = useRef(0);

  useEffect(() => {
    let cancelled = false;
    const bootstrapGeneration = authBootstrapGenerationRef.current;
    initializeSecureAuth()
      .then(() => {
        if (!cancelled && bootstrapGeneration === authBootstrapGenerationRef.current) {
          setUser(getStoredUser());
        }
      })
      .catch(() => {
        if (!cancelled && bootstrapGeneration === authBootstrapGenerationRef.current) setUser(null);
      });
    return () => { cancelled = true; };
  }, []);

  useEffect(() => { peerIdRef.current = peerId; }, [peerId]);
  useEffect(() => { subscriptionActiveRef.current = subscriptionActive; }, [subscriptionActive]);
  useEffect(() => { connectedRef.current = connected; }, [connected]);
  useEffect(() => { connectingRef.current = connecting; }, [connecting]);
  useEffect(() => { excludeLanRef.current = excludeLan; }, [excludeLan]);
  useEffect(() => { stealthModeRef.current = stealthMode; }, [stealthMode]);
  useEffect(() => { shieldFlagsRef.current = shieldFlags; }, [shieldFlags]);

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
    setShowStealthSettings(false);
    setShowTunnelSettings(false);
    setShowShieldSettings(false);
    setShowSettings(false);
    // Tear down tunnel + kill switch like manual sign-out (expiry previously left VPN up).
    userDisconnectedRef.current = true;
    clearReconnectTimerRef.current();
    if (connectedRef.current || connectingRef.current) {
      void handleDisconnectRef.current();
    }
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
        setBillingError(toUserMessage(err));
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
    if (!linuxDesktop && stealthMode !== "udp") {
      setStealthMode("udp");
      writeStealthChoice("udp");
    }
  }, [linuxDesktop, stealthMode]);

  useEffect(() => {
    if (!isRecordableError(statusMsg)) return;
    const safe = sanitizeError(statusMsg);
    if (safe === "none") return;
    setLastError(safe);
    writeStoredLastError(safe);
  }, [statusMsg]);

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
      await invoke<ConnectResult>("disconnect_wireguard", { soft: true }).catch(() => undefined);
      if (timedOutPeer) {
        await fetchWithAuth(`${AUTH_API}/api/v1/wg/peers/${timedOutPeer}`, {
          method: "DELETE",
        }).catch(() => undefined);
      }
      setConnecting(false);
      setConnected(false);
      setTunnelMode("");
      setTransport("");
      setPeerId("");
      setStatusMsg("Connection timed out. Check your network and try again.");
    }, CONNECT_TIMEOUT_MS);
    return () => window.clearTimeout(timer);
  }, [connecting, transport]);

  useEffect(() => {
    if ((!checkoutUrl && !checkoutSettlementPending) || !user) return;
    const timer = window.setInterval(() => {
      refreshBillingStatus().then((status) => {
        if (status?.is_premium) {
          setCheckoutUrl(null);
          setCheckoutSettlementPending(false);
          setShowPlans(checkoutSettlementPending);
          setBillingError("");
        }
      }).catch(() => undefined);
    }, 3000);
    return () => window.clearInterval(timer);
  }, [checkoutUrl, checkoutSettlementPending, user, refreshBillingStatus]);

  useEffect(() => {
    if (!user) prewarmTurnstile();
  }, [user]);

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
          let u: User;
          try {
            u = await doSignInAccountId(accountId);
          } catch (err) {
            if (!isTurnstileRequiredError(err)) throw err;
            const turnstileToken = await obtainTurnstileToken();
            u = await doSignInAccountId(accountId, turnstileToken);
          }
          setUser(u);
          setAccountId("");
        } else {
          const turnstileToken = await obtainTurnstileToken();
          const u = await doRegisterAnonymous(turnstileToken);
          setNewAccountId(u.account_id);
          setUser(u);
        }
      } else if (mode === "signin") {
        let u: User;
        try {
          u = await doSignIn(email, password);
        } catch (err) {
          if (!isTurnstileRequiredError(err)) throw err;
          const turnstileToken = await obtainTurnstileToken();
          u = await doSignIn(email, password, turnstileToken);
        }
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
        setError(toUserMessage(err));
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
      setError(toUserMessage(err));
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
      setError(toUserMessage(err));
    } finally {
      setResendLoading(false);
    }
  }, [verificationResendEmail]);

  const closeSettings = useCallback(() => setShowSettings(false), []);

  const openNetworkMap = useCallback(() => {
    setShowSettings(false);
    setShowNetworkMap(true);
    setShowPlans(false);
    setShowTunnelSettings(false);
    setShowStealthSettings(false);
    setShowShieldSettings(false);
    setShowHelp(false);
  }, []);

  const openHelp = useCallback(() => {
    setShowSettings(false);
    setShowHelp(true);
    setShowPlans(false);
    setShowTunnelSettings(false);
    setShowStealthSettings(false);
    setShowShieldSettings(false);
    setShowNetworkMap(false);
  }, []);

  const openAccount = useCallback(() => {
    setShowSettings(false);
    setShowHelp(false);
    setShowPlans(true);
    setShowTunnelSettings(false);
    setShowStealthSettings(false);
    setShowShieldSettings(false);
    setShowCancelConfirmation(false);
    setBillingError("");
    refreshBillingStatus().catch((err) => setBillingError(toUserMessage(err)));
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
        body: JSON.stringify({
          tier: "premium",
          payment_method: "btcpay",
          plan_id: selectedPlan,
          return_target: "desktop",
        }),
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
      setBillingError(toUserMessage(err));
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
      setBillingError(toUserMessage(err));
    } finally {
      setBillingBusy(false);
    }
  }, [billingBusy, refreshBillingStatus, expireAndReturnToSignIn]);

  const clearReconnectTimer = useCallback(() => {
    if (reconnectTimerRef.current !== null) {
      window.clearTimeout(reconnectTimerRef.current);
      reconnectTimerRef.current = null;
    }
  }, []);
  clearReconnectTimerRef.current = clearReconnectTimer;

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

    const choice: StealthChoice = linuxDesktop ? stealthModeRef.current : "udp";
    const wantedStealth = choice === "stealth";
    showStatus("", false);
    connectingRef.current = true;
    setConnecting(true);
    setTransport(choice === "stealth" ? "stealth" : "");
    connectPeerRef.current = "";

    let createdPeerId = "";
    let triedStealth = choice === "stealth";
    try {
      // Drop any previous peer before registering a new one (reconnect + stale sessions).
      const priorPeer = peerIdRef.current;
      if (priorPeer) {
        await deletePeer(priorPeer);
        if (peerIdRef.current === priorPeer) {
          setPeerId("");
        }
      }

      if (choice === "stealth" && !linuxDesktop) {
        throw new Error("Stealth mode is Linux-only in this build. Choose UDP only to connect with Direct UDP.");
      }

      const billingResponse = await fetchWithAuth(`${AUTH_API}/api/v1/billing/status`);
      const billing = (await billingResponse.json()) as BillingStatus;
      if (!billingResponse.ok || !billing.is_premium) {
        setSubscriptionActive(false);
        setSubscriptionChecked(true);
        throw new Error("An active subscription is required. Open Account to subscribe.");
      }
      setSubscriptionActive(true);
      setSubscriptionChecked(true);
      if (user) writeCachedBillingStatus(user.account_id, billing);

      const available = await invoke<boolean>("wireguard_available");
      if (!available) throw new Error("WireGuard is unavailable in this build.");

      const keys = await invoke<KeyPair>("generate_wg_keys");
      const deviceId = getOrCreateDeviceId();
      const metadata = await deviceMetadataForPeer();
      const res = await fetchWithAuth(`${AUTH_API}/api/v1/wg/peers`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          public_key: keys.public_key,
          device_id: deviceId,
          device_platform: metadata.device_platform,
          device_model: metadata.device_model,
          device_os_version: metadata.device_os_version,
          client_version: metadata.client_version,
          ...shieldRequest(shieldFlagsRef.current),
        }),
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
      const stealthUsable = linuxDesktop && !!peer.stealth_available && !!peer.stealth_endpoint && !!peer.stealth_path_prefix;
      if (choice === "stealth" && !stealthUsable) {
        throw new Error("Stealth is not available on the VPN node yet. Choose UDP only or try again later.");
      }
      const gatewayDns = (peer.dns_server || "").trim();
      if (!gatewayDns) {
        throw new Error("Server did not provide a DNS gateway; connect aborted to avoid unfiltered public DNS.");
      }
      const attempts: boolean[] = choice === "stealth" ? [true] : choice === "auto" && stealthUsable ? [false, true] : [false];
      let usedStealth = false;
      let lastConnectError: unknown = null;
      for (let attempt = 0; attempt < attempts.length; attempt += 1) {
        const useStealth = attempts[attempt];
        if (useStealth) triedStealth = true;
        if (useStealth && attempt > 0) setTransport("switching");
        setDiagEndpoint(choosePublicEndpoint(
          peer.server_endpoint || "",
          peer.server_endpoint_wan || "",
          useStealth ? peer.stealth_endpoint || "" : "",
          useStealth ? "stealth" : "udp",
        ));
        try {
          const result = await invoke<ConnectResult>("connect_wireguard", {
            config: {
              private_key: keys.private_key,
              address: peer.assigned_ip,
              dns: gatewayDns,
              server_public_key: peer.server_public_key,
              endpoint: peer.server_endpoint,
              endpoint_lan: peer.server_endpoint_lan || "",
              endpoint_wan: peer.server_endpoint_wan || "",
              allowed_ips: allowed,
              peer_id: peer.peer_id,
              preshared_key: peer.preshared_key || "",
              stealth_endpoint: useStealth ? peer.stealth_endpoint || "" : "",
              stealth_path_prefix: useStealth ? peer.stealth_path_prefix || "" : "",
            },
          });
          if (!result.success) throw new Error(result.message || "WireGuard connection failed");
          usedStealth = useStealth;
          lastConnectError = null;
          break;
        } catch (err) {
          lastConnectError = err;
          const canFallback = attempt === 0 && attempts.length > 1 && isAutoFallbackError(err);
          if (!canFallback) break;
        }
      }
      if (lastConnectError) throw lastConnectError;
      setTransport(usedStealth ? "stealth" : "udp");

      connectPeerRef.current = "";
      userDisconnectedRef.current = false;
      hadGoodHandshakeRef.current = false;
      hadInterfaceUpRef.current = false;
      reconnectAttemptRef.current = 0;
      setConnected(true);
      setTunnelMode("wireguard");
      setPeerId(peer.peer_id);
      setReconnectToApply(false);
      setStatusSticky(false);
      setStatusMsg("");
      setWgStats(null);
      setDnsBlockedCount(null);
      setDnsBlockedBaseline(null);
      setDnsGateway(gatewayDns);
      return true;
    } catch (err) {
      connectPeerRef.current = "";
      setTransport("");
      if (createdPeerId) {
        await deletePeer(createdPeerId);
      }
      if (err instanceof SessionExpiredError) {
        expireAndReturnToSignIn();
        return false;
      }
      const message = formatConnectError(err, wantedStealth || triedStealth);
      showStatus(message, isStickyStatusMessage(message));
      return false;
    } finally {
      connectingRef.current = false;
      setConnecting(false);
    }
  }, [user, deletePeer, linuxDesktop, showStatus, expireAndReturnToSignIn]);

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
      setTransport("");
      setPeerId("");
      setWgStats(null);
      setDnsBlockedCount(null);
      setDnsBlockedBaseline(null);
      setDnsGateway(null);
      setReconnectToApply(false);
      hadGoodHandshakeRef.current = false;
      hadInterfaceUpRef.current = false;
    };
    const oldPeer = peerId;
    let teardownFailedMsg = "";
    try {
      if (tunnelMode === "wireguard" || peerId) {
        const result = await invoke<ConnectResult>("disconnect_wireguard");
        if (!result.success) {
          teardownFailedMsg = result.message || "kill switch teardown may still be incomplete";
        }
      }
    } catch (err) {
      teardownFailedMsg = err instanceof Error ? err.message : "kill switch teardown may still be incomplete";
    }
    // Always revoke the server peer on intentional disconnect, even when local
    // teardown fails — otherwise the kernel peer lingers until PEER_STALE_AFTER.
    if (oldPeer) {
      try {
        await deletePeer(oldPeer);
      } catch {
        // Peer revoke is best-effort. It must not surface as a home-screen error.
      }
    }
    clearUi();
    setStatusSticky(false);
    setStatusMsg(statusAfterDisconnectFailure(teardownFailedMsg));
  }, [tunnelMode, peerId, clearReconnectTimer, deletePeer]);
  handleDisconnectRef.current = handleDisconnect;

  const attemptReconnect = useCallback(async () => {
    if (reconnectingRef.current) return;
    if (userDisconnectedRef.current || !subscriptionActiveRef.current) return;
    if (connectingRef.current) return;

    reconnectingRef.current = true;
    setReconnecting(true);
    setStatusMsg("Reconnecting…");

    const attempt = reconnectAttemptRef.current;
    const delay = RECONNECT_BACKOFF_MS[Math.min(attempt, RECONNECT_BACKOFF_MS.length - 1)];

    clearReconnectTimer();
    reconnectTimerRef.current = window.setTimeout(async () => {
      reconnectTimerRef.current = null;
      if (userDisconnectedRef.current || !subscriptionActiveRef.current) {
        reconnectingRef.current = false;
        setReconnecting(false);
        return;
      }

      const oldPeer = peerIdRef.current;
      try {
        await invoke<ConnectResult>("disconnect_wireguard", { soft: true }).catch(() => undefined);
      } catch {
        // continue teardown
      }
      setConnected(false);
      setTunnelMode("");
      setTransport("");
      setPeerId("");
      setWgStats(null);
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
        if (!userDisconnectedRef.current && subscriptionActiveRef.current) {
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
        setDnsBlockedBaseline(null);
        setDnsGateway(null);
      }
      return;
    }

    let cancelled = false;
    const pollStats = async () => {
      try {
        const stats = await invoke<WgTransferStats>("wireguard_stats");
        if (cancelled) return;
        setWgStats(stats);

        // Soft path adapt and soft reconnect are done only in the backend
        // watcher. Never call elevated recovery from the UI poll — that freezes
        // the app with "veritasvpn is not responding".

        const nowSec = Math.floor(Date.now() / 1000);
        const handshakeAge =
          stats.last_handshake_sec > 0 ? Math.max(0, nowSec - stats.last_handshake_sec) : Number.POSITIVE_INFINITY;

        if (stats.interface_up) {
          hadInterfaceUpRef.current = true;
        }

        if (stats.interface_up && stats.last_handshake_sec > 0 && handshakeAge <= HANDSHAKE_HEALTHY_SEC) {
          hadGoodHandshakeRef.current = true;
        }

        // Network-switch / tunnel flaps are handled by the backend soft-recovery
        // watcher (passwordless). Never call UI hard-reconnect here — that used
        // interactive pkexec for teardown.sh and spammed auth dialogs.
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
  }, [connected, reconnecting]);

  // Poll peers for DNS blocked count of current peer
  useEffect(() => {
    if (!connected || !peerId) {
      setDnsBlockedCount(null);
      setDnsBlockedBaseline(null);
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
          setDnsBlockedBaseline((prev) => (prev === null ? match.dns_blocked_count! : prev));
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

  const dnsBlockedThisSession =
    dnsBlockedCount !== null && dnsBlockedBaseline !== null
      ? Math.max(0, dnsBlockedCount - dnsBlockedBaseline)
      : null;

  const openTunnelSettings = useCallback(() => {
    setShowSettings(false);
    setShowTunnelSettings(true);
    setShowPlans(false);
    setShowStealthSettings(false);
    setShowShieldSettings(false);
    setShowNetworkMap(false);
    setShowHelp(false);
  }, []);

  const openStealthSettings = useCallback(() => {
    setShowSettings(false);
    setShowStealthSettings(true);
    setShowPlans(false);
    setShowTunnelSettings(false);
    setShowShieldSettings(false);
    setShowNetworkMap(false);
    setShowHelp(false);
  }, []);

  const openShieldSettings = useCallback(() => {
    setShowSettings(false);
    setShowShieldSettings(true);
    setShowPlans(false);
    setShowTunnelSettings(false);
    setShowStealthSettings(false);
    setShowNetworkMap(false);
    setShowHelp(false);
  }, []);

  const applyShieldFlags = useCallback(async (next: ShieldFlags) => {
    const previous = shieldFlagsRef.current;
    const generation = ++shieldWriteGen.current;
    setShieldFlags(next);
    writeShieldFlags(next);
    setShieldError("");
    const id = peerIdRef.current;
    if (!connectedRef.current || !id) return;
    try {
      const res = await fetchWithAuth(`${AUTH_API}/api/v1/wg/peers/${id}`, {
        method: "PATCH",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(shieldRequest(next)),
      });
      if (!res.ok) {
        const body = (await res.json().catch(() => ({}))) as { error?: string };
        throw new Error(body.error || "Could not update Veritas Shield. Try again.");
      }
    } catch (err) {
      if (generation !== shieldWriteGen.current || err instanceof SessionExpiredError) return;
      writeShieldFlags(previous);
      setShieldFlags(previous);
      setShieldError(toUserMessage(err));
    }
  }, []);

  const setExcludeLanValue = useCallback((next: boolean) => {
    if (excludeLan === next) return;
    writeLocalFlag(LS_EXCLUDE_LAN, next);
    setExcludeLan(next);
    setReconnectToApply(true);
  }, [excludeLan]);

  const setStealthChoice = useCallback((next: StealthChoice) => {
    if (!linuxDesktop || stealthMode === next) return;
    writeStealthChoice(next);
    setStealthMode(next);
    setReconnectToApply(true);
  }, [linuxDesktop, stealthMode]);

  const handleSignOut = useCallback(() => {
    // Make the app unauthenticated first. Disconnecting the tunnel and deleting
    // secure credentials can require privileged/native work and must not delay
    // the visible sign-out transition.
    authBootstrapGenerationRef.current += 1;
    if (user) clearCachedBillingStatus(user.account_id);
    setSubscriptionActive(false);
    setSubscriptionChecked(false);
    setShowPlans(false);
    setShowStealthSettings(false);
    setShowTunnelSettings(false);
    setShowShieldSettings(false);
    setShowHelp(false);
    setCheckoutUrl(null);
    userDisconnectedRef.current = true;
    clearReconnectTimer();
    if (connected || connecting) void handleDisconnect();
    setUser(null);
    setNewAccountId("");
    setShowSignOutConfirm(false);
    void doSignOut().catch(() => undefined);
  }, [connected, connecting, handleDisconnect, user, clearReconnectTimer]);

  const handleDeleteAccount = useCallback(async (password: string) => {
    if (!user || deletingAccount) return;
    setDeletingAccount(true);
    setDeleteAccountError("");
    try {
      const turnstileToken = user.email?.trim() ? "" : await obtainTurnstileToken();
      await doDeleteAccount({ password, turnstileToken });
      localStorage.removeItem("veritas_last_error");
      setLastError("");
      setDeletingAccount(false);
      handleSignOut();
    } catch (err) {
      setDeletingAccount(false);
      setDeleteAccountError(toUserMessage(err));
    }
  }, [user, deletingAccount, handleSignOut]);

  const handleSignOutEverywhere = useCallback(() => {
    setShowSettings(false);
    // Capture the current token before local sign-out erases it. The remote
    // request intentionally does not block the local logout or VPN teardown.
    const token = getStoredToken();
    if (token) {
      void nativeFetch(`${AUTH_API}/api/v1/auth/logout-all`, {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          Authorization: `Bearer ${token}`,
        },
        body: "{}",
      }).catch(() => undefined);
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
            <div className="auth-mark"><img className="brand-logo auth-logo" src={veritasMark} alt="VeritasVPN" /></div>
            <h1>VeritasVPN</h1>
          </div>
          <div className="auth-card">
            <h2>Verify your email</h2>
            <p className="auth-subcopy">
              Confirm your account creation by clicking the verification link we sent to <strong>{pendingVerificationEmail}</strong>.
            </p>
            {notice && <p className="notice-text">{notice}</p>}
            {error && <p className="error-text">{error}</p>}
            <button type="button" className="btn-primary btn-primary--rect" disabled={resendLoading} onClick={() => handleResendVerification(pendingVerificationEmail)}>
              {resendLoading ? "Sending verification email…" : "Resend verification email"}
            </button>
            <button type="button" className="auth-switch-link" onClick={() => { setPendingVerificationEmail(null); setError(""); setNotice(""); switchMode("signin"); }}>Back to sign in</button>
          </div>
        </div>
      );
    }
    if (forgotPassword) {
      return (
        <div className="app auth-screen">
          <div className="brand">
            <div className="auth-mark"><img className="brand-logo auth-logo" src={veritasMark} alt="VeritasVPN" /></div>
            <h1>VeritasVPN</h1>
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
            {error && <p className="error-text">{error}</p>}
            <input type="email" placeholder="Email" value={email} onChange={(e) => { setEmail(e.target.value); setError(""); setResetSent(false); }} autoComplete="email" />
            <button type="button" className="btn-primary btn-primary--rect" disabled={loading || resetCooldown > 0} onClick={handleResetPassword}>
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
          <div className="auth-mark"><img className="brand-logo auth-logo" src={veritasMark} alt="VeritasVPN" /></div>
          <h1>VeritasVPN</h1>
          <p>The truth about online privacy</p>
        </div>
        {!showingNewId && (
          <div className="auth-tabs">
            <button className={mode === "signin" ? "active" : ""} onClick={() => switchMode("signin")} type="button">Sign in</button>
            <button className={mode === "signup" ? "active" : ""} onClick={() => switchMode("signup")} type="button">Sign up</button>
          </div>
        )}
        <form className="auth-card" onSubmit={handleAuth}>
          {notice && <p className="notice-text">{notice}</p>}
          {error && <p className="error-text">{error}</p>}
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
                <span className="account-warning-icon" aria-hidden="true">⚠</span>
                <span>Save this ID in a password manager or another secure place now. If you lose it, the account and its access cannot be recovered.</span>
              </div>
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
              <input type="text" placeholder="Account ID" value={accountId} onChange={(e) => setAccountId(e.target.value)} required autoComplete="off" spellCheck={false} aria-label="Account ID" />
              <button type="submit" disabled={loading} className="btn-primary">{loading ? "Please wait…" : "Sign in"}</button>
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
              : "Use email instead"}
          </button>
        )}
      </div>
    );
  }

  if (checkoutUrl) {
    return (
      <div className="app app-dashboard">
        <PaymentCheckoutScreen
          checkoutUrl={checkoutUrl}
          onClose={() => setCheckoutUrl(null)}
          onRefreshPlan={() => refreshBillingStatus().catch(() => undefined)}
          onCompleted={() => {
            setCheckoutUrl(null);
            setCheckoutSettlementPending(true);
            setShowPlans(true);
            void refreshBillingStatus();
          }}
        />
      </div>
    );
  }

  const onHome = !showPlans && !showTunnelSettings && !showStealthSettings && !showShieldSettings && !showNetworkMap && !showHelp;
  return (
    <div className="app app-dashboard">
      {onHome && <header className="app-header blueprint-header">
        <img className="brand-logo" src={veritasLogo} alt="VeritasVPN" />
        <div className="header-actions">
          <button
            type="button"
            className="glass-icon-button"
            onClick={openHelp}
            aria-label="Help and support"
          >
            <svg width="21" height="21" viewBox="0 0 24 24" aria-hidden="true" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round">
              <circle cx="12" cy="12" r="8" />
              <path d="M9.4 9.3a2.6 2.6 0 1 1 3.4 2.5c-.7.3-1.2.8-1.2 1.6V14" />
              <circle cx="12" cy="16.8" r="0.8" fill="currentColor" stroke="none" />
            </svg>
          </button>
          <button
            ref={settingsCogRef}
            type="button"
            className="glass-icon-button"
            onClick={() => setShowSettings((open) => !open)}
            aria-label="Open settings"
            aria-expanded={showSettings}
            aria-controls="settings-drawer"
          >
            <svg width="21" height="21" viewBox="0 0 24 24" aria-hidden="true" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
              <circle cx="12" cy="12" r="3" />
              <path d="M19.4 15a1.7 1.7 0 0 0 .3 1.8l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.7 1.7 0 0 0-1.8-.3 1.7 1.7 0 0 0-1 1.5V21a2 2 0 1 1-4 0v-.1a1.7 1.7 0 0 0-1.1-1.5 1.7 1.7 0 0 0-1.8.3l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1a1.7 1.7 0 0 0 .3-1.8 1.7 1.7 0 0 0-1.5-1H3a2 2 0 1 1 0-4h.1a1.7 1.7 0 0 0 1.5-1.1 1.7 1.7 0 0 0-.3-1.8l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1.7 1.7 0 0 0 1.8.3H9a1.7 1.7 0 0 0 1-1.5V3a2 2 0 1 1 4 0v.1a1.7 1.7 0 0 0 1 1.5 1.7 1.7 0 0 0 1.8-.3l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.7 1.7 0 0 0-.3 1.8V9c.3.6.9 1 1.5 1H21a2 2 0 1 1 0 4h-.1a1.7 1.7 0 0 0-1.5 1z" />
            </svg>
          </button>
        </div>
      </header>}

      <main className="blueprint-main">
        {showHelp ? (
          <HelpSupport
            connected={connected}
            connecting={connecting || reconnecting}
            handshakeEpochSec={wgStats?.last_handshake_sec ?? 0}
            transport={transport}
            endpoint={diagEndpoint}
            lastError={lastError}
            onBack={() => setShowHelp(false)}
          />
        ) : showPlans ? (
          <AccountScreen
            email={user.email}
            accountId={user.account_id}
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
            deletingAccount={deletingAccount}
            deleteError={deleteAccountError}
            onDeleteAccount={(password) => { void handleDeleteAccount(password); }}
            onCancelConfirm={() => cancelSubscription()}
            onCancelDismiss={() => setShowCancelConfirmation(false)}
          />
        ) : showShieldSettings ? (
          <ShieldSettingsScreen
            flags={shieldFlags}
            isPremium={subscriptionActive}
            connected={connected}
            error={shieldError}
            onChange={(next) => { void applyShieldFlags(next); }}
            onUpgrade={openAccount}
            onBack={() => setShowShieldSettings(false)}
          />
        ) : showStealthSettings ? (
          <StealthSettingsScreen
            choice={stealthMode}
            showReconnectBanner={reconnectToApply && (connected || reconnecting)}
            onChange={setStealthChoice}
            onBack={() => setShowStealthSettings(false)}
          />
        ) : showTunnelSettings ? (
          <TunnelSettingsScreen
            excludeLan={excludeLan}
            showReconnectBanner={reconnectToApply && (connected || reconnecting)}
            onExcludeLanChange={setExcludeLanValue}
            onBack={() => setShowTunnelSettings(false)}
          />
        ) : showNetworkMap ? (
          <section className="network-map-view">
            <div className="map-view-head"><div><span>NETWORK MAP</span><h2>Server location</h2></div><button type="button" onClick={() => setShowNetworkMap(false)}>Back</button></div>
            <ConnectionMap
              connected={connected}
              connecting={connecting || reconnecting}
            />
            <div className="map-summary">
              <div><span>CONNECTION</span><strong>{connected ? "Encrypted route active" : connecting || reconnecting ? "Establishing route…" : "No secure route"}</strong></div>
              <b className={connected ? "on" : connecting || reconnecting ? "connecting" : ""}>{connected ? "SECURED" : connecting || reconnecting ? "CONNECTING" : "OFFLINE"}</b>
            </div>
          </section>
        ) : (
          <HomeStage
            connected={connected}
            connecting={connecting || reconnecting}
            subscriptionChecked={subscriptionChecked}
            subscriptionActive={subscriptionActive}
            transport={transport}
            wgStats={wgStats}
            dnsBlockedThisSession={dnsBlockedThisSession}
            dnsGateway={dnsGateway}
            statusMsg={statusMsg}
            statusSticky={statusSticky || isStickyStatusMessage(statusMsg)}
            onConnect={() => { void handleConnect(); }}
            onDisconnect={() => { void handleDisconnect(); }}
            onGetPremium={openAccount}
            onDismissStatus={dismissStatus}
          />
        )}
      </main>

      <SettingsDrawer
        open={showSettings}
        onClose={closeSettings}
        returnFocusRef={settingsCogRef}
        linuxDesktop={linuxDesktop}
        onOpenAccount={openAccount}
        onOpenNetworkMap={openNetworkMap}
        onOpenStealthSettings={openStealthSettings}
        onOpenShieldSettings={openShieldSettings}
        onOpenTunnelSettings={openTunnelSettings}
        onOpenHelp={openHelp}
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
