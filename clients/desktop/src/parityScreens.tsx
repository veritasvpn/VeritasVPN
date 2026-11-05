import { useEffect, useRef, type ReactNode } from "react";
import veritasLogo from "./assets/veritas-logo.png";
import { HeroConnectControl, type HeroPhase } from "./HeroConnect";
import { HelpSupport } from "./HelpSupport";
import { SettingsDrawer, ShieldSettingsScreen } from "./SettingsDrawer";
import type { ShieldFlags, ShieldNoticePrefs } from "./shield";
import { shieldCategoryRows, type ShieldBlocks } from "./shieldNotices";

export const PARITY_SCREENS = [
  "home",
  "connecting",
  "protected",
  "reconnecting",
  "settings",
  "shield",
  "help",
] as const;

export type ParityScreen = (typeof PARITY_SCREENS)[number];

export function parityScreenFromLocation(search: string): ParityScreen | null {
  const value = new URLSearchParams(search).get("parity");
  return PARITY_SCREENS.find((screen) => screen === value) ?? null;
}

const FLAGS: ShieldFlags = {
  block_malicious: true,
  block_ads: false,
  block_adult: false,
  block_trackers: true,
};

const NOTICES: ShieldNoticePrefs = {
  malicious: true,
  trackers: false,
  ads: false,
  adult: false,
};

const BLOCKS: ShieldBlocks = {
  today: { malware: 2, phishing: 1, scam: 0, crypto: 0, trackers: 4, ads: 0, adult: 0 },
  week: { malware: 6, phishing: 3, scam: 1, crypto: 0, trackers: 11, ads: 0, adult: 0 },
};

function HomeChrome({ children }: { children: ReactNode }) {
  return (
    <div className="app app-dashboard">
      <header className="app-header blueprint-header">
        <img className="brand-logo" src={veritasLogo} alt="VeritasVPN" />
        <div className="header-actions">
          <button type="button" className="glass-icon-button" aria-label="Help and support">
            <svg width="21" height="21" viewBox="0 0 24 24" aria-hidden="true" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round">
              <circle cx="12" cy="12" r="8" />
              <path d="M9.4 9.3a2.6 2.6 0 1 1 3.4 2.5c-.7.3-1.2.8-1.2 1.6V14" />
              <circle cx="12" cy="16.8" r="0.8" fill="currentColor" stroke="none" />
            </svg>
          </button>
          <button type="button" className="glass-icon-button" aria-label="Open settings">
            <svg width="21" height="21" viewBox="0 0 24 24" aria-hidden="true" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
              <circle cx="12" cy="12" r="3" />
              <path d="M19.4 15a1.7 1.7 0 0 0 .3 1.8l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.7 1.7 0 0 0-1.8-.3 1.7 1.7 0 0 0-1 1.5V21a2 2 0 1 1-4 0v-.1a1.7 1.7 0 0 0-1.1-1.5 1.7 1.7 0 0 0-1.8.3l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1a1.7 1.7 0 0 0 .3-1.8 1.7 1.7 0 0 0-1.5-1H3a2 2 0 1 1 0-4h.1a1.7 1.7 0 0 0 1.5-1.1 1.7 1.7 0 0 0-.3-1.8l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1.7 1.7 0 0 0 1.8.3H9a1.7 1.7 0 0 0 1-1.5V3a2 2 0 1 1 4 0v.1a1.7 1.7 0 0 0 1 1.5 1.7 1.7 0 0 0 1.8-.3l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.7 1.7 0 0 0-.3 1.8V9c.3.6.9 1 1.5 1H21a2 2 0 1 1 0 4h-.1a1.7 1.7 0 0 0-1.5 1z" />
            </svg>
          </button>
        </div>
      </header>
      <main className="blueprint-main">{children}</main>
    </div>
  );
}

export function ParityScreens({ screen }: { screen: ParityScreen }) {
  const focusRef = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    document.title = `VeritasVPN parity ${screen}`;
    let inner = 0;
    const outer = window.requestAnimationFrame(() => {
      inner = window.requestAnimationFrame(() => {
        if (document.activeElement instanceof HTMLElement) document.activeElement.blur();
        document.documentElement.dataset.parityReady = "1";
      });
    });
    return () => {
      window.cancelAnimationFrame(outer);
      window.cancelAnimationFrame(inner);
    };
  }, [screen]);

  if (screen === "help") {
    return (
      <div className="app app-dashboard">
        <main className="blueprint-main">
          <HelpSupport
            connected={false}
            connecting={false}
            handshakeEpochSec={0}
            transport=""
            endpoint=""
            lastError=""
            onBack={() => undefined}
          />
        </main>
      </div>
    );
  }

  if (screen === "shield") {
    return (
      <div className="app app-dashboard">
        <main className="blueprint-main">
          <ShieldSettingsScreen
            flags={FLAGS}
            noticePrefs={NOTICES}
            rows={shieldCategoryRows(FLAGS, BLOCKS)}
            isPremium
            connected
            error=""
            onChange={() => undefined}
            onNoticeChange={() => undefined}
            onUpgrade={() => undefined}
            onBack={() => undefined}
          />
        </main>
      </div>
    );
  }

  const phase: HeroPhase = screen === "reconnecting"
    ? "reconnecting"
    : screen === "protected" || screen === "settings"
      ? "protected"
      : screen === "connecting"
        ? "connecting"
        : "ready";

  return (
    <HomeChrome>
      <section className="home-stage">
        {(phase === "protected" || phase === "connecting" || phase === "reconnecting") && (
          <div className="home-status">
            {phase === "protected" ? (
              <p className="home-kicker">CONNECTION SECURED</p>
            ) : (
              <>
                <p className="home-kicker is-connecting">
                  {phase === "reconnecting" ? "Reconnecting…" : "ESTABLISHING SECURE CONNECTION"}
                </p>
                <p className="home-status-body">
                  {phase === "reconnecting"
                    ? "The network changed. The VPN will reconnect on its own."
                    : "Creating secure keys and validating encrypted internet access."}
                </p>
              </>
            )}
          </div>
        )}
        <HeroConnectControl phase={phase} onClick={phase === "connecting" ? null : () => undefined} />
        {phase === "protected" && (
          <div className="live-stats" aria-label="Live tunnel statistics">
            <span className="live-stats-label">LIVE STATS</span>
            <div className="live-stats-row">
              <div><strong>1.1 MB</strong><span>Download</span></div>
              <div><strong>340 KB</strong><span>Upload</span></div>
              <div><strong>1m ago</strong><span>Handshake</span></div>
            </div>
            <p className="killswitch-status">Kill switch on. Non-VPN traffic stays blocked until you disconnect.</p>
          </div>
        )}
      </section>
      <SettingsDrawer
        open={screen === "settings"}
        onClose={() => undefined}
        returnFocusRef={focusRef}
        showStealth
        showAutostart
        autostart={false}
        onToggleAutostart={() => undefined}
        onOpenAccount={() => undefined}
        onOpenNetworkMap={() => undefined}
        onOpenStealthSettings={() => undefined}
        onOpenShieldSettings={() => undefined}
        onOpenTunnelSettings={() => undefined}
        onOpenHelp={() => undefined}
        onSignOutEverywhere={() => undefined}
        onRequestSignOut={() => undefined}
      />
    </HomeChrome>
  );
}
