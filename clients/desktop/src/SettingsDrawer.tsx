import { useEffect, useRef, useState, type RefObject } from "react";

export type SettingsDrawerProps = {
  open: boolean;
  onClose: () => void;
  returnFocusRef: RefObject<HTMLButtonElement | null>;
  subscriptionActive: boolean;
  linuxDesktop: boolean;
  autoReconnect: boolean;
  excludeLan: boolean;
  stealthMode: boolean;
  onOpenPlans: () => void;
  onOpenNetworkMap: () => void;
  onOpenDevices: () => void;
  onOpenPortForwards: () => void;
  onToggleAutoReconnect: () => void;
  onToggleExcludeLan: () => void;
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
  excludeLan,
  stealthMode,
  onOpenPlans,
  onOpenNetworkMap,
  onOpenDevices,
  onOpenPortForwards,
  onToggleAutoReconnect,
  onToggleExcludeLan,
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
            ×
          </button>
        </header>

        <div className="settings-drawer-body">
          <section className="settings-drawer-section" aria-label="Account and tools">
            <p className="settings-drawer-label">Account &amp; tools</p>
            <button type="button" onClick={onOpenPlans}>
              {subscriptionActive ? "Premium" : "Plans"}
            </button>
            <button type="button" onClick={onOpenNetworkMap}>Network map</button>
            <button type="button" onClick={onOpenDevices}>Devices</button>
            <button type="button" onClick={onOpenPortForwards}>Port forwarding</button>
          </section>

          <section className="settings-drawer-section" aria-label="Connection">
            <p className="settings-drawer-label">Connection</p>
            <button type="button" className="menu-toggle" onClick={onToggleAutoReconnect}>
              <span>Auto-reconnect</span>
              <b className={autoReconnect ? "on" : ""}>{autoReconnect ? "On" : "Off"}</b>
            </button>
            <button type="button" className="menu-toggle" onClick={onToggleExcludeLan}>
              <span>
                Exclude LAN
                <span className="menu-note">Reconnect to apply</span>
              </span>
              <b className={excludeLan ? "on" : ""}>{excludeLan ? "On" : "Off"}</b>
            </button>
            {linuxDesktop && (
              <button
                type="button"
                className="menu-toggle"
                onClick={onToggleStealthMode}
              >
                <span>
                  Stealth mode
                  <span className="menu-note">TLS wrap · reconnect</span>
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
