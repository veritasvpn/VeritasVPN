import { useState, useEffect, FormEvent, useCallback } from "react";
import { invoke } from "@tauri-apps/api/core";
import { WebviewWindow } from "@tauri-apps/api/webviewWindow";
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
  type User,
} from "./auth";
import { AUTH_API } from "./config";
import veritasMark from "./assets/veritas-mark.png";
import "./App.css";

type AuthMode = "signin" | "signup";
/** Email/password vs Account ID (anonymous) path. */
type AuthMethod = "email" | "accountId";
type TunnelMode = "wireguard" | "";

interface ConnectResult {
  success: boolean;
  message: string;
  mode: string;
  peer_id: string;
}

interface BillingStatus {
  is_premium: boolean;
  tier?: string;
  status?: string;
  payment_method?: string;
  current_period_end?: string;
  cancel_at_period_end?: boolean;
  error?: string;
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

function formatBillingDate(value?: string) {
  if (!value) return "the end of your current billing period";
  const date = new Date(value);
  return Number.isNaN(date.getTime())
    ? value.slice(0, 10)
    : new Intl.DateTimeFormat(undefined, { dateStyle: "medium" }).format(date);
}

function ConnectionMap({ connected }: { connected: boolean }) {
  return (
    <section className={`connection-map ${connected ? "is-connected" : ""}`} aria-label="VPN route to Paraguay">
      <div className="map-topline"><span>LIVE ROUTE</span><span className="map-latency">{connected ? "ENCRYPTED" : "READY"}</span></div>
      <img className="world-map" src="/world-map.svg" alt="World map" />
      <svg className="route-overlay" viewBox="0 0 900 430" aria-hidden="true">
        <defs><linearGradient id="routeGradient" x1="0" x2="1"><stop offset="0" stopColor="#09C7F5"/><stop offset="1" stopColor="#0756D9"/></linearGradient><filter id="routeGlow"><feGaussianBlur stdDeviation="4" result="blur"/><feMerge><feMergeNode in="blur"/><feMergeNode in="SourceGraphic"/></feMerge></filter></defs>
        <path className="route-shadow" d="M134 135C274 79 391 154 523 294S592 326 621 322"/>
        <path className="route-line" d="M134 135C274 79 391 154 523 294S592 326 621 322"/>
        <circle className="route-particle" r="4"><animateMotion dur="2.8s" repeatCount="indefinite" path="M134 135C274 79 391 154 523 294S592 326 621 322"/></circle>
        <g className="map-origin" transform="translate(134 135)"><circle r="6"/><circle className="map-pulse" r="14"/></g>
        <g className="map-destination" transform="translate(621 322)"><circle className="map-pulse" r="19"/><circle r="8"/></g>
      </svg>
      <div className="route-label route-label-origin"><span>YOUR DEVICE</span><strong>{connected ? "Encrypted route" : "Current location"}</strong></div>
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

function App() {
  const [user, setUser] = useState<User | null>(getStoredUser);
  const [mode, setMode] = useState<AuthMode>("signin");
  const [method, setMethod] = useState<AuthMethod>("email");
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [accountId, setAccountId] = useState("");
  const [newAccountId, setNewAccountId] = useState("");
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(false);
  const [connected, setConnected] = useState(false);
  const [tunnelMode, setTunnelMode] = useState<TunnelMode>("");
  const [peerId, setPeerId] = useState("");
  const [statusMsg, setStatusMsg] = useState("");
  const [connecting, setConnecting] = useState(false);
  const [subscriptionActive, setSubscriptionActive] = useState(false);
  const [subscriptionChecked, setSubscriptionChecked] = useState(false);
  const [billingStatus, setBillingStatus] = useState<BillingStatus | null>(null);
  const [showBilling, setShowBilling] = useState(false);
  const [billingBusy, setBillingBusy] = useState(false);
  const [billingError, setBillingError] = useState("");
  const [selectedPlan, setSelectedPlan] = useState<"premium_monthly" | "premium_annual">("premium_monthly");
  const [checkoutActive, setCheckoutActive] = useState(false);
  const [showCancelConfirmation, setShowCancelConfirmation] = useState(false);
  const [showSettings, setShowSettings] = useState(false);
  const [showNetworkMap, setShowNetworkMap] = useState(false);

  useEffect(() => {
    let cancelled = false;
    if (!user) {
      setSubscriptionActive(false);
      setSubscriptionChecked(false);
      return () => { cancelled = true; };
    }
    setSubscriptionChecked(false);
    (async () => {
      await refreshSession();
      const token = getStoredToken();
      if (!token) throw new Error("Not signed in");
      const response = await fetch(`${AUTH_API}/api/v1/billing/status`, {
        headers: { Authorization: `Bearer ${token}` },
      });
      const status = (await response.json()) as BillingStatus;
      if (!response.ok) throw new Error(status.error || "Could not verify subscription");
      if (!cancelled) {
        setBillingStatus(status);
        setSubscriptionActive(status.is_premium === true);
      }
    })().catch(() => {
      if (!cancelled) { setSubscriptionActive(false); setBillingStatus(null); }
    }).finally(() => {
      if (!cancelled) setSubscriptionChecked(true);
    });
    return () => { cancelled = true; };
  }, [user]);

  useEffect(() => {
    if (!statusMsg) return;
    const t = setTimeout(() => setStatusMsg(""), 5000);
    return () => clearTimeout(t);
  }, [statusMsg]);

  const switchMode = useCallback((next: AuthMode) => {
    setMode(next);
    setMethod("email");
    setError("");
    setNewAccountId("");
    setSubscriptionActive(false);
    setSubscriptionChecked(false);
  }, []);

  const switchMethod = useCallback((next: AuthMethod) => {
    setMethod(next);
    setError("");
    setNewAccountId("");
  }, []);

  const handleAuth = useCallback(
    async (e: FormEvent) => {
      e.preventDefault();
      setError("");
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
        } else {
          const fn = mode === "signin" ? doSignIn : doSignUp;
          const u = await fn(email, password);
          setUser(u);
          setEmail("");
          setPassword("");
        }
      } catch (err) {
        setError(err instanceof Error ? err.message : "Auth failed");
      } finally {
        setLoading(false);
      }
    },
    [email, password, accountId, mode, method]
  );

  const refreshBillingStatus = useCallback(async () => {
    await refreshSession();
    const token = getStoredToken();
    if (!token) throw new Error("Your session expired. Sign in again.");
    const response = await fetch(`${AUTH_API}/api/v1/billing/status`, { headers: { Authorization: `Bearer ${token}` } });
    const status = (await response.json()) as BillingStatus;
    if (!response.ok) throw new Error(status.error || "Could not load your subscription.");
    setBillingStatus(status);
    setSubscriptionActive(status.is_premium === true);
    setSubscriptionChecked(true);
    return status;
  }, []);

  const startCheckout = useCallback(async (paymentMethod: "btcpay", planId: "premium_monthly" | "premium_annual") => {
    if (billingBusy || checkoutActive) return;
    setBillingBusy(true); setBillingError("");
    try {
      await refreshSession();
      const token = getStoredToken();
      if (!token) throw new Error("Your session expired. Sign in again.");
      const response = await fetch(`${AUTH_API}/api/v1/billing/subscribe`, {
        method: "POST", headers: { "Content-Type": "application/json", Authorization: `Bearer ${token}` },
        body: JSON.stringify({ tier: "premium", payment_method: paymentMethod, plan_id: planId }),
      });
      const data = await response.json() as { checkout_url?: string; error?: string };
      if (!response.ok || !data.checkout_url?.startsWith("https://btcpay.veritasvpn.cloud/")) {
        throw new Error(data.error || "The billing server returned an invalid checkout.");
      }
      const existing = await WebviewWindow.getByLabel("billing-checkout");
      if (existing) await existing.close();
      const checkout = new WebviewWindow("billing-checkout", { url: data.checkout_url, title: "VeritasVPN secure checkout", width: 900, height: 760, center: true });
      setCheckoutActive(true);
      await checkout.once("tauri://destroyed", () => setCheckoutActive(false));
    } catch (err) { setBillingError(err instanceof Error ? err.message : "Could not start checkout."); }
    finally { setBillingBusy(false); }
  }, [billingBusy, checkoutActive]);

  const cancelSubscription = useCallback(async () => {
    if (billingBusy) return;
    setBillingBusy(true); setBillingError("");
    try {
      await refreshSession(); const token = getStoredToken();
      if (!token) throw new Error("Your session expired. Sign in again.");
      const response = await fetch(`${AUTH_API}/api/v1/billing/cancel`, { method: "POST", headers: { Authorization: `Bearer ${token}`, "Content-Type": "application/json" }, body: "{}" });
      const data = await response.json() as { error?: string };
      if (!response.ok) throw new Error(data.error || "Could not cancel your subscription.");
      await refreshBillingStatus();
      setShowCancelConfirmation(false);
    } catch (err) { setBillingError(err instanceof Error ? err.message : "Could not cancel your subscription."); }
    finally { setBillingBusy(false); }
  }, [billingBusy, refreshBillingStatus]);

  useEffect(() => {
    if (!checkoutActive) return;
    const timer = window.setInterval(() => {
      refreshBillingStatus().then((status) => {
        if (status.is_premium) {
          WebviewWindow.getByLabel("billing-checkout").then((window) => window?.close()).catch(() => undefined);
          setCheckoutActive(false); setShowBilling(true);
        }
      }).catch(() => undefined);
    }, 3000);
    return () => window.clearInterval(timer);
  }, [checkoutActive, refreshBillingStatus]);

  const handleConnect = useCallback(async () => {
    if (connecting) return;
    setStatusMsg("");
    setConnecting(true);

    let token = "";
    let createdPeerId = "";
    try {
      await refreshSession();
      token = getStoredToken() || "";
      if (!token) {
        throw new Error("Not signed in");
      }
      const billingResponse = await fetch(`${AUTH_API}/api/v1/billing/status`, {
        headers: { Authorization: `Bearer ${token}` },
      });
      const billing = (await billingResponse.json()) as BillingStatus;
      if (!billingResponse.ok || !billing.is_premium) {
        setSubscriptionActive(false);
        setSubscriptionChecked(true);
        throw new Error("An active subscription is required. Open Plans & billing in VeritasVPN to subscribe.");
      }
      setSubscriptionActive(true);
      setSubscriptionChecked(true);

      const available = await invoke<boolean>("wireguard_available");
      if (!available) {
        throw new Error(
          "WireGuard is unavailable in this build. No proxy fallback was activated."
        );
      }

      const keys = await invoke<KeyPair>("generate_wg_keys");
      const res = await fetch(`${AUTH_API}/api/v1/wg/peers`, {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          Authorization: `Bearer ${token}`,
        },
        body: JSON.stringify({ public_key: keys.public_key }),
      });
      const peer = (await res.json()) as PeerResponse & { code?: string };
      if (!res.ok) {
        if (peer.code?.startsWith("plan_device_limit")) {
          throw new Error(
            peer.error ||
              "Device limit reached. Upgrade to Premium for more devices, or disconnect another device first."
          );
        }
        throw new Error(peer.error || "Failed to create WireGuard peer");
      }
      createdPeerId = peer.peer_id;

      const allowed =
        peer.client_allowed_ips ||
        peer.allowed_ips ||
        ["0.0.0.0/0"];

      const result = await invoke<ConnectResult>("connect_wireguard", {
        config: {
          private_key: keys.private_key,
          address: peer.assigned_ip,
          dns: peer.dns_server || "1.1.1.1",
          server_public_key: peer.server_public_key,
          endpoint: peer.server_endpoint,
          allowed_ips: allowed,
          peer_id: peer.peer_id,
          preshared_key: peer.preshared_key || "",
        },
      });

      if (result.success) {
        setConnected(true);
        setTunnelMode("wireguard");
        setPeerId(peer.peer_id);
        setStatusMsg("Connected via WireGuard");
      } else {
        throw new Error(result.message || "WireGuard connection failed");
      }
    } catch (err) {
      // Peer creation precedes privileged local bring-up. Roll it back when
      // bring-up fails so retries do not leave stale keys or allocated IPs.
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
  }, [connecting]);

  const handleDisconnect = useCallback(async () => {
    setStatusMsg("Disconnecting…");
    // Always clear local tunnel UI state so a failed privileged teardown
    // cannot leave the app stuck showing Connected.
    const clearUi = () => {
      setConnected(false);
      setTunnelMode("");
      setPeerId("");
    };
    try {
      if (tunnelMode === "wireguard" || peerId) {
        const token = getStoredToken();
        // Restore local routing and DNS before making any network request.
        // Otherwise a degraded tunnel can block disconnect indefinitely.
        const result = await invoke<ConnectResult>("disconnect_wireguard");
        clearUi();
        if (!result.success) {
          setStatusMsg(
            result.message ||
              "Disconnect incomplete — approve the admin prompt, or run the teardown script from your app config directory"
          );
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
      setStatusMsg("Disconnected");
    } catch (err) {
      clearUi();
      setStatusMsg(
        err instanceof Error ? err.message : "Disconnect failed — approve the macOS admin prompt"
      );
    }
  }, [tunnelMode, peerId]);

  const handleSignOut = useCallback(() => {
    if (connected && !window.confirm("Signing out will disconnect your VPN. Continue?")) return;
    setSubscriptionActive(false);
    setSubscriptionChecked(false);
    if (connected) {
      handleDisconnect();
    }
    doSignOut();
    setUser(null);
    setNewAccountId("");
  }, [connected, handleDisconnect]);

  if (!user || (newAccountId && method === "accountId" && mode === "signup")) {
    const showingNewId = Boolean(newAccountId);
    return (
      <div className="app">
        <div className="brand">
          <img className="brand-logo auth-logo" src={veritasMark} alt="VeritasVPN" />
          <h1>Veritas<span>VPN</span></h1>
          <p>The truth about online privacy</p>
        </div>
        {!showingNewId && (
          <div className="auth-tabs">
            <button
              className={mode === "signin" ? "active" : ""}
              onClick={() => switchMode("signin")}
              type="button"
            >
              Sign in
            </button>
            <button
              className={mode === "signup" ? "active" : ""}
              onClick={() => switchMode("signup")}
              type="button"
            >
              Sign up
            </button>
          </div>
        )}
        <form onSubmit={handleAuth}>
          {error && <div className="error">{error}</div>}
          {showingNewId ? (
            <>
              <p className="auth-hint success">
                Your Account ID (copy it now — it cannot be recovered):
              </p>
              <code className="account-id-display">{newAccountId}</code>
              <button
                type="button"
                className="btn-primary"
                onClick={() => {
                  setNewAccountId("");
                }}
              >
                Continue
              </button>
            </>
          ) : method === "email" ? (
            <>
              <input
                type="email"
                placeholder="Email"
                value={email}
                onChange={(e) => setEmail(e.target.value)}
                required
                autoComplete="email"
              />
              <input
                type="password"
                placeholder="Password"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                required
                minLength={10}
                autoComplete={mode === "signin" ? "current-password" : "new-password"}
              />
              <button type="submit" disabled={loading} className="btn-primary">
                {loading
                  ? "Please wait..."
                  : mode === "signin"
                    ? "Sign in"
                    : "Create account"}
              </button>
            </>
          ) : mode === "signin" ? (
            <>
              <input
                type="text"
                placeholder="Account ID"
                value={accountId}
                onChange={(e) => setAccountId(e.target.value)}
                required
                autoComplete="off"
                spellCheck={false}
              />
              <button type="submit" disabled={loading} className="btn-primary">
                {loading ? "Please wait..." : "Sign in with Account ID"}
              </button>
            </>
          ) : (
            <>
              <p className="auth-hint">
                Creates an anonymous account. You’ll get an Account ID to save —
                no email required.
              </p>
              <button type="submit" disabled={loading} className="btn-primary">
                {loading ? "Please wait..." : "Create anonymous account"}
              </button>
            </>
          )}
        </form>
        {!showingNewId && (
          <button
            type="button"
            className="auth-switch-link"
            onClick={() =>
              switchMethod(method === "email" ? "accountId" : "email")
            }
          >
            {method === "email"
              ? mode === "signin"
                ? "Sign in with Account ID instead"
                : "Skip email — create anonymous account"
              : mode === "signin"
                ? "Sign in with email instead"
                : "Use email instead"}
          </button>
        )}
      </div>
    );
  }

  return (
    <div className="app app-dashboard">
      <header className="app-header blueprint-header">
        <img className="brand-logo" src={veritasMark} alt="VeritasVPN" />
        <div className="blueprint-settings-wrap">
          <button className="blueprint-cog" onClick={() => setShowSettings((open) => !open)} aria-label="Open settings" aria-expanded={showSettings}>⚙</button>
          {showSettings && <div className="blueprint-menu">
            <button onClick={() => { setShowSettings(false); setShowBilling(true); refreshBillingStatus().catch((err) => setBillingError(err.message)); }}>{subscriptionActive ? "Premium" : "Plans"}</button>
            <button onClick={() => { setShowSettings(false); setShowNetworkMap(true); }}>Network map</button>
            <hr/><button className="danger" onClick={() => { setShowSettings(false); handleSignOut(); }}>Sign out</button>
          </div>}
        </div>
      </header>

      <main className="blueprint-main">
        {showNetworkMap ? <section className="network-map-view"><div className="map-view-head"><div><span>NETWORK MAP</span><h2>Your secure route</h2></div><button onClick={() => setShowNetworkMap(false)}>Back</button></div><ConnectionMap connected={connected}/><div className="map-summary"><div><span>CONNECTION</span><strong>{connected ? "Encrypted route active" : connecting ? "Establishing route…" : "No secure route"}</strong></div><b className={connected ? "on" : ""}>{connected ? "SECURED" : connecting ? "CONNECTING" : "OFFLINE"}</b></div></section> : <>
          <PrivacyScene encrypted={connected}/>
          <section className="blueprint-status">
            <div className={`blueprint-badge ${connecting ? "connecting" : ""} ${connected ? "connected" : ""}`}><i/>{connected ? "CONNECTION SECURED" : connecting ? "ESTABLISHING SECURE CONNECTION" : "VPN DISCONNECTED"}</div>
            <h2>{connected ? "You’re protected" : "Your online activity is visible"}</h2>
            <p>{connected ? "Your internet traffic is encrypted and routed through VeritasVPN." : connecting ? "Creating secure keys and validating encrypted internet access." : "Hide your IP address and encrypt your connection."}</p>
            {!connected ? <button className="blueprint-primary" disabled={connecting || !subscriptionChecked} onClick={subscriptionActive ? handleConnect : () => setShowBilling(true)}><span>{connecting ? <i className="button-spinner"/> : subscriptionActive ? "◔" : "●"}</span>{connecting ? "Connecting…" : subscriptionActive ? "Connect now" : "Get Premium"}<b>{connecting ? "" : "→"}</b></button> : <button className="blueprint-disconnect" onClick={handleDisconnect}>Disconnect</button>}
            {connected && <div className="protected-meta"><i/>Protected · WireGuard tunnel active</div>}
            {statusMsg && !/^(Connected|Disconnected|Connecting|Disconnecting)/i.test(statusMsg) && <div className="status-msg">{statusMsg}</div>}
          </section>
        </>}
      </main>
      {showBilling && (
        <div className="billing-overlay" role="dialog" aria-modal="true" aria-label="Plans and billing">
          <section className="billing-panel">
            <div className="billing-panel-head"><div><span>SUBSCRIPTION</span><h2>Plans & billing</h2></div><button onClick={() => { setShowCancelConfirmation(false); setShowBilling(false); }} aria-label="Close billing">×</button></div>
            <div className="billing-current"><div><span>CURRENT PLAN</span><strong>{billingStatus?.is_premium ? "Premium" : "No active subscription"}</strong></div><button onClick={() => refreshBillingStatus().catch((err) => setBillingError(err.message))}>Refresh</button></div>
            <div className="billing-plan-card">
              <div className="billing-price"><span>$</span>{selectedPlan === "premium_annual" ? "30" : "3"}<small>/{selectedPlan === "premium_annual" ? "365 days" : "30 days"}</small></div>
              {!billingStatus?.is_premium && <div className="billing-plan-options"><button className={selectedPlan === "premium_monthly" ? "selected" : ""} onClick={() => setSelectedPlan("premium_monthly")}>Monthly · $3</button><button className={selectedPlan === "premium_annual" ? "selected" : ""} onClick={() => setSelectedPlan("premium_annual")}>Annual · $30 · save $6</button></div>}
              <p>Complete VeritasVPN access on up to five devices.</p>
              <ul><li>✓ Paraguay WireGuard connection</li><li>✓ Chrome, Android, and Linux</li><li>✓ Anonymous account support</li><li>✓ Private Bitcoin payment</li></ul>
              {!billingStatus?.is_premium ? <>
                <p className="billing-choice">Choose a payment method</p>
                <div className="billing-actions"><button disabled={billingBusy || checkoutActive} onClick={() => startCheckout("btcpay", selectedPlan)}>₿ Pay with Bitcoin</button></div>
                <p className="billing-note">Checkout opens in a secure VeritasVPN payment window. Premium activates automatically after confirmation.</p>
              </> : <>
                <div className="billing-active">● Premium access is active</div>
                {billingStatus.cancel_at_period_end ? (
                  <div className="billing-cancellation-scheduled" role="status">
                    <strong>Cancellation scheduled</strong>
                    <span>Your VPN remains active until {formatBillingDate(billingStatus.current_period_end)}. After that, Premium ends automatically. You can purchase another period whenever you want.</span>
                  </div>
                ) : (
                  <button className="billing-cancel" disabled={billingBusy} onClick={() => setShowCancelConfirmation(true)}>Cancel at period end</button>
                )}
                {showCancelConfirmation && (
                  <div className="billing-cancel-confirm" role="alertdialog" aria-modal="true" aria-labelledby="cancel-title">
                    <strong id="cancel-title">Schedule cancellation?</strong>
                    <p>Your VPN will stay active until {formatBillingDate(billingStatus.current_period_end)}. After that date, Premium will end and you will not be charged again.</p>
                    <div><button disabled={billingBusy} onClick={() => setShowCancelConfirmation(false)}>Keep Premium</button><button disabled={billingBusy} onClick={cancelSubscription}>{billingBusy ? "Scheduling…" : "Confirm cancellation"}</button></div>
                  </div>
                )}
              </>}
              {checkoutActive && <div className="billing-waiting">Waiting for payment confirmation…</div>}
              {billingError && <div className="billing-error">{billingError}</div>}
            </div>
          </section>
        </div>
      )}
    </div>
  );
}

export default App;
