import { useState, useEffect, FormEvent, useCallback, useRef } from "react";
import { invoke } from "@tauri-apps/api/core";
import { fetch } from "@tauri-apps/plugin-http";
import {
  getStoredUser,
  getStoredToken,
  refreshSession,
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
} from "./auth";
import {
  readCachedBillingStatus,
  writeCachedBillingStatus,
  clearCachedBillingStatus,
  BillingStatus,
} from "./billing";
import { AUTH_API } from "./config";
import veritasMark from "./assets/veritas-mark.png";
import "./App.css";

type AuthMode = "signin" | "signup";
type AuthMethod = "email" | "accountId";
type TunnelMode = "wireguard" | "";

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
  assigned_ip: string;
  dns_server: string;
  preshared_key?: string;
  allowed_ips?: string[];
  client_allowed_ips?: string[];
  error?: string;
}

const CONNECT_TIMEOUT_MS = 25_000;
const EGRESS_ENDPOINTS = [
  "https://api.ipify.org",
  "https://ifconfig.me/ip",
  "https://icanhazip.com",
];

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
  return (
    <section className="plans-screen">
      <div className="plans-head">
        <button type="button" className="plans-back" onClick={onBack}>← Back</button>
        <div>
          <h2>Plans & billing</h2>
          <p>Choose the privacy plan that fits you</p>
        </div>
      </div>
      <div className="billing-current">
        <div>
          <span>CURRENT PLAN</span>
          <strong>{billingLoading ? "Checking subscription…" : premium ? "Premium" : "No active subscription"}</strong>
          {!billingLoading && premium && billingStatus?.current_period_end && (
            <small className="billing-period-end">
              {billingStatus.cancel_at_period_end ? "Premium ends" : "Current access ends"}{" "}
              <time dateTime={billingStatus.current_period_end}>{formatBillingDate(billingStatus.current_period_end)}</time>
            </small>
          )}
        </div>
        <button type="button" disabled={billingLoading} onClick={onRefresh}>{billingLoading ? "Checking…" : "Refresh"}</button>
      </div>
      <div className="billing-plan-card">
        {billingLoading ? (
          <div className="billing-loading" role="status">Confirming your current plan…</div>
        ) : (
          <>
            <div className="billing-price"><span>$</span>{selectedPlan === "premium_annual" ? "30" : "3"}<small>/{selectedPlan === "premium_annual" ? "365 days" : "30 days"}</small></div>
            {!premium && (
              <div className="billing-plan-options">
                <button type="button" className={selectedPlan === "premium_monthly" ? "selected" : ""} onClick={() => onSelectPlan("premium_monthly")}>Monthly · $3</button>
                <button type="button" className={selectedPlan === "premium_annual" ? "selected" : ""} onClick={() => onSelectPlan("premium_annual")}>Annual · $30 · save $6</button>
              </div>
            )}
            <p>Complete VeritasVPN access on up to five devices.</p>
            <ul><li>✓ Paraguay WireGuard connection</li><li>✓ Chrome, Android, and Linux</li><li>✓ Anonymous account support</li><li>✓ Private Bitcoin payment</li></ul>
            {!premium ? (
              <>
                <p className="billing-choice">Choose a payment method</p>
                <div className="billing-actions"><button type="button" disabled={billingBusy || checkoutMethod !== null} onClick={onCheckout}>₿ Pay with Bitcoin</button></div>
                <p className="billing-note">Checkout opens inside VeritasVPN. Premium activates automatically after confirmation.</p>
              </>
            ) : (
              <>
                <div className="billing-active">● Premium access is active</div>
                {billingStatus?.cancel_at_period_end ? (
                  <div className="billing-cancellation-scheduled" role="status">
                    <strong>Cancellation scheduled</strong>
                    <span>Your VPN remains active until {formatBillingDate(billingStatus.current_period_end)}. After that, Premium ends automatically.</span>
                  </div>
                ) : (
                  <button type="button" className="billing-cancel" disabled={billingBusy} onClick={onCancelClick}>Cancel at period end</button>
                )}
                {showCancelConfirmation && (
                  <div className="billing-cancel-confirm" role="alertdialog" aria-modal="true">
                    <strong>Schedule cancellation?</strong>
                    <p>Your VPN will stay active until {formatBillingDate(billingStatus?.current_period_end)}. After that date, Premium will end and you will not be charged again.</p>
                    <div><button type="button" disabled={billingBusy} onClick={onCancelDismiss}>Keep Premium</button><button type="button" disabled={billingBusy} onClick={onCancelConfirm}>{billingBusy ? "Scheduling…" : "Confirm cancellation"}</button></div>
                  </div>
                )}
              </>
            )}
            {billingError && <div className="billing-error">{billingError}</div>}
          </>
        )}
      </div>
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

function App() {
  const [user, setUser] = useState<User | null>(getStoredUser);
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
  const [deviceLabel, setDeviceLabel] = useState("Current location");
  const connectPeerRef = useRef("");

  useEffect(() => {
    if (resetCooldown <= 0) return;
    const timer = window.setTimeout(() => setResetCooldown((value) => value - 1), 1000);
    return () => window.clearTimeout(timer);
  }, [resetCooldown]);

  const refreshBillingStatus = useCallback(async () => {
    if (!user) return null;
    setBillingLoading(true);
    try {
      await refreshSession();
      const token = getStoredToken();
      if (!token) throw new Error("Your session expired. Sign in again.");
      const response = await fetch(`${AUTH_API}/api/v1/billing/status`, { headers: { Authorization: `Bearer ${token}` } });
      const status = (await response.json()) as BillingStatus;
      if (!response.ok) throw new Error(status.error || "Could not load your subscription.");
      setBillingStatus(status);
      setSubscriptionActive(status.is_premium === true);
      setSubscriptionChecked(true);
      writeCachedBillingStatus(user.account_id, status);
      setBillingError("");
      return status;
    } catch (err) {
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
  }, [user, billingStatus]);

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
    if (!statusMsg) return;
    const t = window.setTimeout(() => setStatusMsg(""), 8000);
    return () => window.clearTimeout(t);
  }, [statusMsg]);

  useEffect(() => {
    if (!connecting) return;
    const timer = window.setTimeout(async () => {
      if (!connecting) return;
      const timedOutPeer = connectPeerRef.current;
      connectPeerRef.current = "";
      await invoke<ConnectResult>("disconnect_wireguard").catch(() => undefined);
      if (timedOutPeer) {
        const token = getStoredToken();
        if (token) {
          await fetch(`${AUTH_API}/api/v1/wg/peers/${timedOutPeer}`, {
            method: "DELETE",
            headers: { Authorization: `Bearer ${token}` },
          }).catch(() => undefined);
        }
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
          const u = await doRegisterAnonymous();
          setNewAccountId(u.account_id);
          setUser(u);
        }
      } else if (mode === "signin") {
        const u = await doSignIn(email, password);
        setUser(u);
        setEmail("");
        setPassword("");
      } else {
        const u = await doSignUp(email, password);
        setUser(u);
        setEmail("");
        setPassword("");
        setConfirmPassword("");
      }
    } catch (err) {
      if (err instanceof VerificationRequiredError) {
        setVerificationResendEmail(err.email);
        setError(err.message);
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

  const handleResendVerification = useCallback(async () => {
    if (!verificationResendEmail) return;
    setResendLoading(true);
    setNotice("");
    try {
      await resendVerification(verificationResendEmail);
      setError("");
      setVerificationResendEmail("");
      setNotice(`A new verification link was sent to ${verificationResendEmail}.`);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Could not resend the verification email. Try again.");
    } finally {
      setResendLoading(false);
    }
  }, [verificationResendEmail]);

  const openPlans = useCallback(() => {
    setShowSettings(false);
    setShowPlans(true);
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
      await refreshSession();
      const token = getStoredToken();
      if (!token) throw new Error("Your session expired. Sign in again.");
      const response = await fetch(`${AUTH_API}/api/v1/billing/subscribe`, {
        method: "POST",
        headers: { "Content-Type": "application/json", Authorization: `Bearer ${token}` },
        body: JSON.stringify({ tier: "premium", payment_method: "btcpay", plan_id: selectedPlan }),
      });
      const data = await response.json() as { checkout_url?: string; error?: string };
      if (!response.ok || !data.checkout_url?.startsWith("https://btcpay.veritasvpn.cloud/")) {
        throw new Error(data.error || "The billing server returned an invalid checkout.");
      }
      setCheckoutUrl(data.checkout_url);
    } catch (err) {
      setBillingError(err instanceof Error ? err.message : "Could not start checkout.");
    } finally {
      setBillingBusy(false);
      setCheckoutMethod(null);
    }
  }, [billingBusy, checkoutMethod, selectedPlan]);

  const cancelSubscription = useCallback(async () => {
    if (billingBusy) return;
    setBillingBusy(true);
    setBillingError("");
    try {
      await refreshSession();
      const token = getStoredToken();
      if (!token) throw new Error("Your session expired. Sign in again.");
      const response = await fetch(`${AUTH_API}/api/v1/billing/cancel`, {
        method: "POST",
        headers: { Authorization: `Bearer ${token}`, "Content-Type": "application/json" },
        body: "{}",
      });
      const data = await response.json() as { error?: string };
      if (!response.ok) throw new Error(data.error || "Could not cancel your subscription.");
      await refreshBillingStatus();
      setShowCancelConfirmation(false);
    } catch (err) {
      setBillingError(err instanceof Error ? err.message : "Could not cancel your subscription.");
    } finally {
      setBillingBusy(false);
    }
  }, [billingBusy, refreshBillingStatus]);

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

  const handleConnect = useCallback(async () => {
    if (connecting || connected) return;
    setStatusMsg("");
    setEgressIp("");
    setConnecting(true);
    connectPeerRef.current = "";

    let token = "";
    let createdPeerId = "";
    try {
      setStatusMsg("Creating secure keys…");
      await refreshSession();
      token = getStoredToken() || "";
      if (!token) throw new Error("Not signed in");

      const billingResponse = await fetch(`${AUTH_API}/api/v1/billing/status`, {
        headers: { Authorization: `Bearer ${token}` },
      });
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
      const res = await fetch(`${AUTH_API}/api/v1/wg/peers`, {
        method: "POST",
        headers: { "Content-Type": "application/json", Authorization: `Bearer ${token}` },
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

      const allowed = peer.client_allowed_ips || peer.allowed_ips || ["0.0.0.0/0"];
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
        },
      });

      if (!result.success) throw new Error(result.message || "WireGuard connection failed");

      connectPeerRef.current = "";
      setConnected(true);
      setTunnelMode("wireguard");
      setPeerId(peer.peer_id);
      setStatusMsg("");
      void fetchEgressIp();
    } catch (err) {
      connectPeerRef.current = "";
      if (createdPeerId && token) {
        await fetch(`${AUTH_API}/api/v1/wg/peers/${createdPeerId}`, {
          method: "DELETE",
          headers: { Authorization: `Bearer ${token}` },
        }).catch(() => undefined);
      }
      setStatusMsg(err instanceof Error ? err.message : "Connection failed");
    } finally {
      setConnecting(false);
    }
  }, [connecting, connected, user, fetchEgressIp]);

  const handleDisconnect = useCallback(async () => {
    setStatusMsg("Disconnecting…");
    connectPeerRef.current = "";
    const clearUi = () => {
      setConnected(false);
      setTunnelMode("");
      setPeerId("");
      setEgressIp("");
    };
    try {
      if (tunnelMode === "wireguard" || peerId) {
        const token = getStoredToken();
        const result = await invoke<ConnectResult>("disconnect_wireguard");
        clearUi();
        if (!result.success) {
          setStatusMsg(result.message || "Disconnect incomplete — approve the admin prompt, or run: sudo bash ~/.veritasvpn/teardown.sh");
          return;
        }
        if (token && peerId) {
          const controller = new AbortController();
          const timeout = window.setTimeout(() => controller.abort(), 5000);
          await fetch(`${AUTH_API}/api/v1/wg/peers/${peerId}`, {
            method: "DELETE",
            headers: { Authorization: `Bearer ${token}` },
            signal: controller.signal,
          }).catch(() => undefined).finally(() => window.clearTimeout(timeout));
        }
      }
      clearUi();
      setStatusMsg("");
    } catch (err) {
      clearUi();
      setStatusMsg(err instanceof Error ? err.message : "Disconnect failed");
    }
  }, [tunnelMode, peerId]);

  const handleSignOut = useCallback(() => {
    if (user) clearCachedBillingStatus(user.account_id);
    setSubscriptionActive(false);
    setSubscriptionChecked(false);
    setShowPlans(false);
    setCheckoutUrl(null);
    if (connected || connecting) void handleDisconnect();
    doSignOut();
    setUser(null);
    setNewAccountId("");
    setShowSignOutConfirm(false);
  }, [connected, connecting, handleDisconnect, user]);

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
            <button type="button" className="btn-outline" disabled={resendLoading} onClick={handleResendVerification}>
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
              <input type="text" placeholder="Account ID" value={accountId} onChange={(e) => setAccountId(e.target.value)} required autoComplete="off" spellCheck={false} />
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
        {!showPlans && (
          <div className="blueprint-settings-wrap">
            <button className="blueprint-cog" onClick={() => setShowSettings((open) => !open)} aria-label="Open settings" aria-expanded={showSettings}>⚙</button>
            {showSettings && (
              <div className="blueprint-menu">
                <button type="button" onClick={openPlans}>{subscriptionActive ? "Premium" : "Plans"}</button>
                <button type="button" onClick={() => { setShowSettings(false); setShowNetworkMap(true); }}>Network map</button>
                <hr />
                <button type="button" className="danger" onClick={requestSignOut}>Sign out</button>
              </div>
            )}
          </div>
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
        ) : showNetworkMap ? (
          <section className="network-map-view">
            <div className="map-view-head"><div><span>NETWORK MAP</span><h2>Your secure route</h2></div><button type="button" onClick={() => setShowNetworkMap(false)}>Back</button></div>
            <ConnectionMap connected={connected} connecting={connecting} deviceLabel={deviceLabel} />
            <div className="map-summary">
              <div><span>CONNECTION</span><strong>{connected ? "Encrypted route active" : connecting ? "Establishing route…" : "No secure route"}</strong></div>
              <b className={connected ? "on" : ""}>{connected ? "SECURED" : connecting ? "CONNECTING" : "OFFLINE"}</b>
            </div>
          </section>
        ) : (
          <>
            <PrivacyScene encrypted={connected} />
            <section className="blueprint-status">
              <div className={`blueprint-badge ${connecting ? "connecting" : ""} ${connected ? "connected" : ""}`}>
                <i />{connected ? "CONNECTION SECURED" : connecting ? "ESTABLISHING SECURE CONNECTION" : "VPN DISCONNECTED"}
              </div>
              <h2>{connected ? "You're protected" : connecting ? "Establishing secure connection" : "Your online activity is visible"}</h2>
              <p>
                {connected
                  ? "Your internet traffic is encrypted and routed through VeritasVPN."
                  : connecting
                    ? "Creating secure keys and validating encrypted internet access."
                    : "Hide your IP address and encrypt your connection."}
              </p>
              {!connected ? (
                <button
                  className="blueprint-primary"
                  disabled={connecting || !subscriptionChecked}
                  onClick={subscriptionActive ? handleConnect : openPlans}
                >
                  <span>{connecting ? <i className="button-spinner" /> : subscriptionActive ? "◔" : "●"}</span>
                  {connecting ? "Connecting…" : !subscriptionChecked ? "Checking plan…" : subscriptionActive ? "Connect now" : "Get Premium"}
                  <b>{connecting || !subscriptionChecked ? "" : "→"}</b>
                </button>
              ) : (
                <button className="blueprint-disconnect solid" type="button" onClick={handleDisconnect}>Disconnect</button>
              )}
              {connected && (
                <div className="protected-meta">
                  <i />
                  {egressIp ? `Connected · ${egressIp}` : "Protected · WireGuard tunnel active"}
                </div>
              )}
              {statusMsg && !(connecting && /^connecting/i.test(statusMsg)) && (
                <div className={`status-msg ${connected ? "ok" : connecting ? "info" : "warn"}`}>{statusMsg}</div>
              )}
            </section>
          </>
        )}
      </main>

      {showSignOutConfirm && (
        <div className="dialog-overlay" role="presentation" onClick={() => setShowSignOutConfirm(false)}>
          <div className="dialog-card" role="alertdialog" aria-modal="true" onClick={(e) => e.stopPropagation()}>
            <strong>Sign out?</strong>
            <p>Signing out will disconnect your VPN. Continue?</p>
            <div className="dialog-actions">
              <button type="button" onClick={() => setShowSignOutConfirm(false)}>Cancel</button>
              <button type="button" className="danger-solid" onClick={handleSignOut}>Sign out</button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

export default App;
