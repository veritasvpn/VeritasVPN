import { useEffect, useRef, useState, type ReactNode, type RefObject, type TransitionEvent } from "react";
import type { ShieldFlags } from "./shield";

export type StealthChoice = "auto" | "udp" | "stealth";

export type SettingsDrawerProps = {
  open: boolean;
  onClose: () => void;
  returnFocusRef: RefObject<HTMLButtonElement | null>;
  linuxDesktop: boolean;
  onOpenAccount: () => void;
  onOpenNetworkMap: () => void;
  onOpenStealthSettings: () => void;
  onOpenShieldSettings: () => void;
  onOpenTunnelSettings: () => void;
  onSignOutEverywhere: () => void;
  onRequestSignOut: () => void;
};

const FOCUSABLE =
  'button:not([disabled]), [href], input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])';

function IconAccount() {
  return (
    <svg viewBox="0 0 24 24" aria-hidden="true">
      <circle cx="12" cy="8" r="3.2" fill="none" stroke="currentColor" strokeWidth="1.8" />
      <path d="M5.5 19.2c1.2-3 3.4-4.4 6.5-4.4s5.3 1.4 6.5 4.4" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" />
    </svg>
  );
}

function IconMap() {
  return (
    <svg viewBox="0 0 24 24" aria-hidden="true">
      <circle cx="12" cy="12" r="8" fill="none" stroke="currentColor" strokeWidth="1.8" />
      <path d="M4 12h16M12 4c2.4 2.4 3.6 5 3.6 8s-1.2 5.6-3.6 8c-2.4-2.4-3.6-5-3.6-8s1.2-5.6 3.6-8z" fill="none" stroke="currentColor" strokeWidth="1.6" />
    </svg>
  );
}

function IconStealth() {
  return (
    <svg viewBox="0 0 24 24" aria-hidden="true">
      <path d="M4 12s3.2-6 8-6 8 6 8 6-3.2 6-8 6-8-6-8-6z" fill="none" stroke="currentColor" strokeWidth="1.8" />
      <path d="M5 5l14 14" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" />
    </svg>
  );
}

function IconShield() {
  return (
    <svg viewBox="0 0 24 24" aria-hidden="true">
      <path d="M12 3.2 19 6v5.2c0 4.2-2.7 7.4-7 9.1-4.3-1.7-7-4.9-7-9.1V6l7-2.8z" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinejoin="round" />
      <path d="M9.2 12.1 11.1 14l3.8-4.2" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  );
}

function IconSplit() {
  return (
    <svg viewBox="0 0 24 24" aria-hidden="true">
      <path d="M6 5v5c0 3 2.2 5 6 5h2" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" />
      <path d="M14 11l4 4-4 4" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" />
      <path d="M14 5h4v4" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  );
}

function IconDevices() {
  return (
    <svg viewBox="0 0 24 24" aria-hidden="true">
      <rect x="3" y="5" width="12" height="9" rx="1.6" fill="none" stroke="currentColor" strokeWidth="1.8" />
      <rect x="9" y="10" width="12" height="9" rx="1.6" fill="none" stroke="currentColor" strokeWidth="1.8" />
    </svg>
  );
}

function IconLogout() {
  return (
    <svg viewBox="0 0 24 24" aria-hidden="true">
      <path d="M10 6H7.5A1.5 1.5 0 0 0 6 7.5v9A1.5 1.5 0 0 0 7.5 18H10" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" />
      <path d="M11 12h8M16 8.5 19.5 12 16 15.5" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  );
}

function IconChevron() {
  return (
    <svg className="settings-chevron" viewBox="0 0 24 24" aria-hidden="true">
      <path d="M9 6l6 6-6 6" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  );
}

function NavItem({
  label,
  note,
  danger,
  icon,
  onClick,
}: {
  label: string;
  note?: string;
  danger?: boolean;
  icon: ReactNode;
  onClick: () => void;
}) {
  return (
    <button type="button" className={`settings-nav-row${danger ? " is-danger" : ""}`} onClick={onClick}>
      <span className={`settings-nav-icon${danger ? " is-danger" : ""}`}>{icon}</span>
      <span className="settings-nav-copy">
        <span className="settings-nav-label">{label}</span>
        {note && <span className="settings-nav-note">{note}</span>}
      </span>
      {!danger && <IconChevron />}
    </button>
  );
}

export function SettingsDrawer({
  open,
  onClose,
  returnFocusRef,
  linuxDesktop,
  onOpenAccount,
  onOpenNetworkMap,
  onOpenStealthSettings,
  onOpenShieldSettings,
  onOpenTunnelSettings,
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

  const handlePanelTransitionEnd = (event: TransitionEvent<HTMLElement>) => {
    if (event.target !== drawerRef.current || event.propertyName !== "transform") return;
    if (!animOpen && !open) {
      setPresent(false);
      returnFocusRef.current?.focus();
    }
  };

  if (!present) return null;

  return (
    <div className={`settings-drawer-root${animOpen ? " is-open" : ""}`} aria-hidden={!animOpen}>
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
            <svg width="14" height="14" viewBox="0 0 24 24" aria-hidden="true" fill="none" stroke="currentColor" strokeWidth="2.25" strokeLinecap="round">
              <path d="M6 6l12 12M18 6L6 18" />
            </svg>
          </button>
        </header>

        <div className="settings-drawer-body">
          <section className="settings-drawer-section" aria-label="Account and tools">
            <p className="settings-drawer-label">Account &amp; tools</p>
            <div className="settings-group">
              <NavItem label="Account" icon={<IconAccount />} onClick={onOpenAccount} />
              <NavItem label="Network map" icon={<IconMap />} onClick={onOpenNetworkMap} />
            </div>
          </section>

          <section className="settings-drawer-section" aria-label="Connection">
            <p className="settings-drawer-label">Connection</p>
            <div className="settings-group">
              {linuxDesktop && (
                <NavItem
                  label="Stealth"
                  note="Auto · UDP only · Stealth always"
                  icon={<IconStealth />}
                  onClick={onOpenStealthSettings}
                />
              )}
              <NavItem
                label="Veritas Shield"
                note="Ads · malicious · adult"
                icon={<IconShield />}
                onClick={onOpenShieldSettings}
              />
              <NavItem
                label="Split tunnel"
                note="Exclude LAN"
                icon={<IconSplit />}
                onClick={onOpenTunnelSettings}
              />
            </div>
          </section>

          <section className="settings-drawer-section" aria-label="Session">
            <p className="settings-drawer-label">Session</p>
            <div className="settings-group">
              <NavItem
                label="Sign out from all devices"
                danger
                icon={<IconDevices />}
                onClick={() => void onSignOutEverywhere()}
              />
              <NavItem
                label="Sign out from this device"
                danger
                icon={<IconLogout />}
                onClick={onRequestSignOut}
              />
            </div>
          </section>
        </div>
      </aside>
    </div>
  );
}

function BackIcon() {
  return (
    <svg width="20" height="20" viewBox="0 0 24 24" aria-hidden="true" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <path d="M15 18l-6-6 6-6" />
    </svg>
  );
}

export function ScreenTopBar({
  eyebrow,
  title,
  onBack,
  backLabel = "Back",
}: {
  eyebrow: string;
  title: string;
  onBack: () => void;
  backLabel?: string;
}) {
  return (
    <header className="screen-topbar">
      <button type="button" className="glass-icon-button" onClick={onBack} aria-label={backLabel}>
        <BackIcon />
      </button>
      <div>
        <p className="screen-eyebrow">{eyebrow}</p>
        <h2>{title}</h2>
      </div>
    </header>
  );
}

export function ReconnectBanner() {
  return (
    <div className="tunnel-reconnect-banner" role="status">
      <i aria-hidden="true" />
      <span>Reconnect from Home to apply these changes</span>
    </div>
  );
}

export function StealthSettingsScreen({
  choice,
  showReconnectBanner,
  onChange,
  onBack,
}: {
  choice: StealthChoice;
  showReconnectBanner: boolean;
  onChange: (next: StealthChoice) => void;
  onBack: () => void;
}) {
  const options: { id: StealthChoice; title: string; subtitle: string }[] = [
    {
      id: "auto",
      title: "Auto",
      subtitle: "Try UDP first. If that handshake does not complete, switch to Stealth. Recommended.",
    },
    {
      id: "udp",
      title: "UDP only",
      subtitle: "Plain WireGuard over UDP. Never fall back to Stealth.",
    },
    {
      id: "stealth",
      title: "Stealth always",
      subtitle: "Start with WireGuard inside TLS. Use this on networks that block UDP.",
    },
  ];
  return (
    <section className="tunnel-settings" aria-label="Stealth">
      <ScreenTopBar eyebrow="CONNECTION" title="Stealth" onBack={onBack} />
      {showReconnectBanner && <ReconnectBanner />}
      <p className="tunnel-section-label">TRANSPORT</p>
      <p className="tunnel-lead">
        Choose how VeritasVPN carries WireGuard. Auto is the default. Reconnect from Home to apply a change.
      </p>
      {options.map((option) => (
        <button
          key={option.id}
          type="button"
          className={`transport-choice${choice === option.id ? " is-selected" : ""}`}
          aria-pressed={choice === option.id}
          onClick={() => onChange(option.id)}
        >
          <span className={`transport-radio${choice === option.id ? " is-on" : ""}`} aria-hidden="true" />
          <span>
            <strong>{option.title}</strong>
            <span>{option.subtitle}</span>
          </span>
        </button>
      ))}
    </section>
  );
}

export function ShieldSettingsScreen({
  flags,
  isPremium,
  connected,
  error,
  onChange,
  onUpgrade,
  onBack,
}: {
  flags: ShieldFlags;
  isPremium: boolean;
  connected: boolean;
  error: string;
  onChange: (next: ShieldFlags) => void;
  onUpgrade: () => void;
  onBack: () => void;
}) {
  const rows: { key: keyof ShieldFlags; title: string; subtitle: string }[] = [
    {
      key: "block_ads",
      title: "Block ads",
      subtitle: "Stop known ad domains from resolving. A page can still show an ad that is not a separate domain.",
    },
    {
      key: "block_malicious",
      title: "Block malicious sites",
      subtitle: "Malware, phishing, scam, and cryptomining domains.",
    },
    {
      key: "block_adult",
      title: "Block adult sites",
      subtitle: "Known adult domains. Shield does not scan page content.",
    },
  ];
  return (
    <section className="tunnel-settings" aria-label="Veritas Shield">
      <ScreenTopBar eyebrow="CONNECTION" title="Veritas Shield" onBack={onBack} />
      <p className="tunnel-section-label">ADVANCED VPN PROTECTION</p>
      <p className="tunnel-lead">
        Blocks known domains in DNS while you are connected. This does not remove ads inside web pages.
      </p>
      <p className="tunnel-lead">
        {!isPremium
          ? "These filters are Premium. Upgrade to turn them on."
          : connected
            ? "Changes apply to this connection immediately."
            : "Saved on this device. Applied the next time you connect."}
      </p>
      {error && <p className="shield-error" role="alert">{error}</p>}
      {rows.map((row) => (
        <div key={row.key} className={`tunnel-toggle-card${flags[row.key] && isPremium ? " is-on" : ""}`}>
          <div>
            <strong>{row.title}</strong>
            <span>{row.subtitle}</span>
          </div>
          {isPremium ? (
            <button
              type="button"
              className="shield-switch"
              aria-pressed={flags[row.key]}
              aria-label={row.title}
              onClick={() => onChange({ ...flags, [row.key]: !flags[row.key] })}
            >
              <i className={flags[row.key] ? "on" : ""} aria-hidden="true" />
            </button>
          ) : (
            <button type="button" className="shield-upgrade" onClick={onUpgrade}>
              Upgrade
            </button>
          )}
        </div>
      ))}
    </section>
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
      <ScreenTopBar eyebrow="CONNECTION" title="Split tunnel" onBack={onBack} />
      {showReconnectBanner && <ReconnectBanner />}
      <p className="tunnel-section-label">ROUTING</p>
      <div className="tunnel-route-summary">
        <span className="tunnel-route-dot" aria-hidden="true" />
        <div>
          <strong>Protected connection</strong>
          <b>Route your internet through VeritasVPN</b>
          <span>Your browser, apps, and DNS use the encrypted WireGuard tunnel unless you choose a local-network exception below.</span>
        </div>
      </div>
      <button
        type="button"
        className={`tunnel-toggle-card${excludeLan ? " is-on" : ""}`}
        onClick={() => onExcludeLanChange(!excludeLan)}
        aria-pressed={excludeLan}
      >
        <div>
          <strong>Allow local network access</strong>
          <span>Keep devices on your home, office, or hotel network reachable. Internet traffic still uses VeritasVPN.</span>
        </div>
        <i className={excludeLan ? "on" : ""} aria-hidden="true" />
      </button>
    </section>
  );
}
