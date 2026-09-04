import { useEffect, useRef, useState, type RefObject } from "react";

export type SettingsDrawerProps = {
  open: boolean;
  onClose: () => void;
  returnFocusRef: RefObject<HTMLButtonElement | null>;
  subscriptionActive: boolean;
  linuxDesktop: boolean;
  autoReconnect: boolean;
  stealthMode: boolean;
  connected: boolean;
  dnsGateway: string | null;
  dnsBlockedThisSession: number | null;
  shieldPreset: string;
  onShieldPresetChange: (preset: string) => void;
  onOpenPlans: () => void;
  onOpenNetworkMap: () => void;
  onOpenDevices: () => void;
  onOpenPortForwards: () => void;
  onOpenTunnelSettings: () => void;
  onToggleAutoReconnect: () => void;
  onToggleStealthMode: () => void;
  onSignOutEverywhere: () => void;
  onRequestSignOut: () => void;
};

const FOCUSABLE =
  'button:not([disabled]), [href], input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])';

export function SettingsDrawer({
  open,
  onClose,
  returnFocusRef,
  subscriptionActive,
  linuxDesktop,
  autoReconnect,
  stealthMode,
  connected,
  dnsGateway,
  dnsBlockedThisSession,
  shieldPreset,
  onShieldPresetChange,
  onOpenPlans,
  onOpenNetworkMap,
  onOpenDevices,
  onOpenPortForwards,
  onOpenTunnelSettings,
  onToggleAutoReconnect,
  onToggleStealthMode,
  onSignOutEverywhere,
  onRequestSignOut,
}: SettingsDrawerProps) {
  const [present, setPresent] = useState(open);
  const [animOpen, setAnimOpen] = useState(false);
  const drawerRef = useRef<HTMLElement>(null);
  const closeBtnRef = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    if (open) {
      setPresent(true);
      const frame = requestAnimationFrame(() => {
        requestAnimationFrame(() => setAnimOpen(true));
      });
      return () => cancelAnimationFrame(frame);
    }
    setAnimOpen(false);
  }, [open]);

  useEffect(() => {
    if (!present || !animOpen) return;

    closeBtnRef.current?.focus();

    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape") {
        event.preventDefault();
        onClose();
        return;
      }
      if (event.key !== "Tab") return;

      const root = drawerRef.current;
      if (!root) return;
      const focusables = Array.from(root.querySelectorAll<HTMLElement>(FOCUSABLE));
      if (focusables.length === 0) return;

      const first = focusables[0];
      const last = focusables[focusables.length - 1];
      const active = document.activeElement as HTMLElement | null;

      if (event.shiftKey && active === first) {
        event.preventDefault();
        last.focus();
      } else if (!event.shiftKey && active === last) {
        event.preventDefault();
        first.focus();
      }
    };

    document.addEventListener("keydown", onKeyDown);
    return () => document.removeEventListener("keydown", onKeyDown);
  }, [present, animOpen, onClose]);

  const handlePanelTransitionEnd = (event: React.TransitionEvent<HTMLElement>) => {
    if (event.target !== drawerRef.current || event.propertyName !== "transform") return;
    if (!animOpen && !open) {
      setPresent(false);
      returnFocusRef.current?.focus();
    }
  };

  if (!present) return null;

  return (
    <div
      className={`settings-drawer-root${animOpen ? " is-open" : ""}`}
      aria-hidden={!animOpen}
    >
      <button
        type="button"
        className="settings-drawer-scrim"
        aria-label="Close settings"
        tabIndex={-1}
        onClick={onClose}
      />
      <aside
        ref={drawerRef}
        id="settings-drawer"
        className="settings-drawer"
        role="dialog"
        aria-modal="true"
        aria-label="Settings"
        onTransitionEnd={handlePanelTransitionEnd}
      >
        <header className="settings-drawer-head">
          <h2>Settings</h2>
          <button
            ref={closeBtnRef}
            type="button"
            className="settings-drawer-close"
            aria-label="Close settings"
            onClick={onClose}
          >
            <svg width="18" height="18" viewBox="0 0 24 24" aria-hidden="true" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round">
              <path d="M6 6l12 12M18 6L6 18" />
            </svg>
          </button>
        </header>

        <div className="settings-drawer-body">
          <section className="settings-drawer-section" aria-label="Account and tools">
            <p className="settings-drawer-label">Account &amp; tools</p>
            <button type="button" className="settings-nav-item" onClick={onOpenPlans}>
              {subscriptionActive ? "Premium" : "Plans"}
            </button>
            <button type="button" className="settings-nav-item" onClick={onOpenNetworkMap}>Network map</button>
            <button type="button" className="settings-nav-item" onClick={onOpenDevices}>Devices</button>
            <button type="button" className="settings-nav-item" onClick={onOpenPortForwards}>Port forwarding</button>
          </section>

          <section className="settings-drawer-section" aria-label="Connection">
            <p className="settings-drawer-label">Connection</p>
            {connected ? (
              <div className="settings-dns-status" role="status">
                <strong>Veritas Shield on</strong>
                <span>
                  {dnsGateway ? `Gateway ${dnsGateway}` : "Tunnel gateway"}
                  {dnsBlockedThisSession !== null ? ` · ${dnsBlockedThisSession} blocked this session` : ""}
                </span>
                <span className="settings-dns-explainer">
                  Threat and tracker hostnames return NXDOMAIN. Lookups use DNS-over-HTTPS upstreams. Well-known public DoH resolvers are blocked; uncommon DoH endpoints may still bypass.
                </span>
                <div className="settings-shield-presets" role="group" aria-label="Veritas Shield preset">
                  {(
                    [
                      ["security", "Security"],
                      ["standard", "Standard"],
                      ["aggressive", "Aggressive"],
                    ] as const
                  ).map(([value, label]) => (
                    <button
                      key={value}
                      type="button"
                      className={`settings-shield-preset${shieldPreset === value ? " is-active" : ""}`}
                      onClick={() => onShieldPresetChange(value)}
                    >
                      {label}
                    </button>
                  ))}
                </div>
                <span className="settings-dns-explainer">
                  Security: threats only · Standard: + trackers · Aggressive: + ads
                </span>
              </div>
            ) : (
              <div className="settings-dns-status is-idle" role="status">
                <strong>Veritas Shield</strong>
                <span>Always on while connected — DNS threat filtering through the tunnel gateway.</span>
              </div>
            )}
            <button type="button" className="settings-nav-item" onClick={onOpenTunnelSettings}>
              <span className="settings-nav-label">Split tunnel</span>
              <span className="settings-nav-note">Exclude LAN · reconnect to apply</span>
            </button>
            <button type="button" className="menu-toggle" onClick={onToggleAutoReconnect}>
              <span>
                Auto-reconnect
                <span className="menu-note">Restore tunnel if link drops</span>
              </span>
              <b className={autoReconnect ? "on" : ""}>{autoReconnect ? "On" : "Off"}</b>
            </button>
            {linuxDesktop && (
              <button
                type="button"
                className="menu-toggle"
                onClick={onToggleStealthMode}
              >
                <span>
                  Stealth mode
                  <span className="menu-note">TLS wrap · reconnect to apply</span>
                </span>
                <b className={stealthMode ? "on" : ""}>{stealthMode ? "On" : "Off"}</b>
              </button>
            )}
          </section>

          <section className="settings-drawer-section settings-drawer-section--session" aria-label="Session">
            <p className="settings-drawer-label">Session</p>
            <button type="button" className="danger" onClick={() => void onSignOutEverywhere()}>
              Sign out from all devices
            </button>
            <button type="button" className="danger" onClick={onRequestSignOut}>
              Sign out from this device
            </button>
          </section>
        </div>
      </aside>
    </div>
  );
}

export function TunnelSettingsScreen({
  excludeLan,
  showReconnectBanner,
  onExcludeLanChange,
  onBack,
}: {
  excludeLan: boolean;
  showReconnectBanner: boolean;
  onExcludeLanChange: (next: boolean) => void;
  onBack: () => void;
}) {
  return (
    <section className="tunnel-settings" aria-label="Split tunnel">
      <header className="tunnel-settings-head">
        <button type="button" className="tunnel-back" onClick={onBack} aria-label="Back">
          <svg width="20" height="20" viewBox="0 0 24 24" aria-hidden="true" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
            <path d="M15 18l-6-6 6-6" />
          </svg>
        </button>
        <div>
          <p className="tunnel-eyebrow">TUNNEL</p>
          <h2>Split tunnel</h2>
        </div>
      </header>

      {showReconnectBanner && (
        <div className="tunnel-reconnect-banner" role="status">
          Reconnect to apply
        </div>
      )}

      <p className="tunnel-section-label">ROUTING</p>

      <button
        type="button"
        className={`tunnel-toggle-card${!excludeLan ? " is-on" : ""}`}
        onClick={() => onExcludeLanChange(false)}
        aria-pressed={!excludeLan}
      >
        <div>
          <strong>Full tunnel</strong>
          <span>Send all traffic through the VPN (AllowedIPs from server, typically 0.0.0.0/0).</span>
        </div>
        <i className={!excludeLan ? "on" : ""} aria-hidden="true" />
      </button>

      <button
        type="button"
        className={`tunnel-toggle-card${excludeLan ? " is-on" : ""}`}
        onClick={() => onExcludeLanChange(true)}
        aria-pressed={excludeLan}
      >
        <div>
          <strong>Exclude private LAN</strong>
          <span>Replace 0.0.0.0/0 with public prefixes that omit RFC1918 (10/8, 172.16/12, 192.168/16) so local network traffic stays off the VPN.</span>
        </div>
        <i className={excludeLan ? "on" : ""} aria-hidden="true" />
      </button>
    </section>
  );
}
