import { useEffect, useState, type ReactNode } from "react";
import { invoke } from "@tauri-apps/api/core";
import { getVersion } from "@tauri-apps/api/app";
import { ScreenTopBar } from "./SettingsDrawer";
import veritasMark from "./assets/veritas-mark.png";
import {
  CONTACT_EMAIL,
  FALLBACK_APP_VERSION,
  DESKTOP_NOTICES,
  SUPPORT_CONNECT_URL,
  SUPPORT_URL,
  PRIVACY_URL,
  TERMS_URL,
  formatDiagnosticReport,
  isAllowedHttps,
  mailtoUrl,
} from "./support";

type HelpPage = "help" | "diagnostics" | "licenses";

const SHARE_KEY = "veritas_share_diagnostics";

type HelpSupportProps = {
  connected: boolean;
  connecting: boolean;
  handshakeEpochSec: number;
  transport: string;
  endpoint: string;
  lastError: string;
  onBack: () => void;
};

function readShareDiagnostics(): boolean {
  try {
    return localStorage.getItem(SHARE_KEY) === "1";
  } catch {
    return false;
  }
}

function writeShareDiagnostics(enabled: boolean) {
  try {
    localStorage.setItem(SHARE_KEY, enabled ? "1" : "0");
  } catch {
    // ignore quota / private mode
  }
}

async function openExternal(url: string): Promise<void> {
  const mailto = url.startsWith(`mailto:${CONTACT_EMAIL}`);
  if (!mailto && !isAllowedHttps(url)) {
    throw new Error("That link is not available.");
  }
  await invoke("plugin:opener|open_url", { url });
}

async function copyText(text: string): Promise<void> {
  if (navigator.clipboard?.writeText) {
    await navigator.clipboard.writeText(text);
    return;
  }
  const area = document.createElement("textarea");
  area.value = text;
  area.setAttribute("readonly", "");
  area.style.position = "fixed";
  area.style.left = "-9999px";
  document.body.appendChild(area);
  area.select();
  const ok = document.execCommand("copy");
  document.body.removeChild(area);
  if (!ok) throw new Error("Could not copy the report.");
}

function IconMail() {
  return (
    <svg viewBox="0 0 24 24" aria-hidden="true">
      <rect x="3.5" y="5.5" width="17" height="13" rx="2" fill="none" stroke="currentColor" strokeWidth="1.8" />
      <path d="M4 7l8 6 8-6" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinejoin="round" />
    </svg>
  );
}

function IconBook() {
  return (
    <svg viewBox="0 0 24 24" aria-hidden="true">
      <path d="M5 5.5h6.2A2.8 2.8 0 0 1 14 8.3V19a2.4 2.4 0 0 0-2.2-1.4H5V5.5z" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinejoin="round" />
      <path d="M19 5.5h-6.2A2.8 2.8 0 0 0 10 8.3V19a2.4 2.4 0 0 1 2.2-1.4H19V5.5z" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinejoin="round" />
    </svg>
  );
}

function IconPower() {
  return (
    <svg viewBox="0 0 24 24" aria-hidden="true">
      <path d="M12 3.5v7" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" />
      <path d="M7.2 6.8a7 7 0 1 0 9.6 0" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" />
    </svg>
  );
}

function IconAlert() {
  return (
    <svg viewBox="0 0 24 24" aria-hidden="true">
      <circle cx="12" cy="12" r="8" fill="none" stroke="currentColor" strokeWidth="1.8" />
      <path d="M12 8v5" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" />
      <circle cx="12" cy="16.2" r="0.8" fill="currentColor" />
    </svg>
  );
}

function IconWrench() {
  return (
    <svg viewBox="0 0 24 24" aria-hidden="true">
      <path d="M14.5 6.2a3.4 3.4 0 0 0-4.6 4.4L4.6 16a1.6 1.6 0 0 0 2.2 2.2l5.3-5.3a3.4 3.4 0 0 0 4.4-4.6L14.2 10l-1.6-1.6 1.9-2.2z" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinejoin="round" />
    </svg>
  );
}

function IconDoc() {
  return (
    <svg viewBox="0 0 24 24" aria-hidden="true">
      <path d="M7 3.8h7l4 4V20a1.2 1.2 0 0 1-1.2 1.2H7A1.2 1.2 0 0 1 5.8 20V5A1.2 1.2 0 0 1 7 3.8z" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinejoin="round" />
      <path d="M14 3.8V8h4.2M8.5 12.5h7M8.5 16h5" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" />
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

function HelpRow({
  label,
  note,
  icon,
  onClick,
}: {
  label: string;
  note?: string;
  icon: ReactNode;
  onClick: () => void;
}) {
  return (
    <button type="button" className="help-row" onClick={onClick}>
      <span className="help-row-icon">{icon}</span>
      <span className="help-row-copy">
        <strong>{label}</strong>
        {note && <span>{note}</span>}
      </span>
      <IconChevron />
    </button>
  );
}

export function HelpSupport({
  connected,
  connecting,
  handshakeEpochSec,
  transport,
  endpoint,
  lastError,
  onBack,
}: HelpSupportProps) {
  const [page, setPage] = useState<HelpPage>("help");
  const [shareDiagnostics, setShareDiagnostics] = useState(readShareDiagnostics);
  const [appVersion, setAppVersion] = useState(FALLBACK_APP_VERSION);
  const [osVersion, setOsVersion] = useState("Linux");
  const [nowMs, setNowMs] = useState(() => Date.now());
  const [notice, setNotice] = useState("");
  const [actionError, setActionError] = useState("");

  useEffect(() => {
    let cancelled = false;
    void (async () => {
      try {
        const version = await getVersion();
        if (!cancelled && version.trim()) setAppVersion(version.trim());
      } catch {
        // Keep the package fallback when Tauri is unavailable.
      }
      try {
        const meta = await invoke<{ device_os_version?: string }>("desktop_device_metadata");
        const os = meta.device_os_version?.trim();
        if (!cancelled && os) setOsVersion(os);
      } catch {
        if (!cancelled) setOsVersion("Linux");
      }
    })();
    return () => {
      cancelled = true;
    };
  }, []);

  useEffect(() => {
    if (page !== "diagnostics") return;
    const timer = window.setInterval(() => setNowMs(Date.now()), 1000);
    return () => window.clearInterval(timer);
  }, [page]);

  const reportText = formatDiagnosticReport({
    versionName: appVersion || "unknown",
    osVersion,
    connected,
    connecting,
    handshakeEpochMs: handshakeEpochSec > 0 ? handshakeEpochSec * 1000 : 0,
    nowMs,
    transport,
    lastError,
    activeEndpoint: endpoint,
  });
  const versionLabel = `v${appVersion}`;

  const fail = (err: unknown, fallback: string) => {
    setNotice("");
    setActionError(err instanceof Error && err.message ? err.message : fallback);
  };

  const openLink = async (url: string, fallback: string) => {
    setNotice("");
    setActionError("");
    try {
      await openExternal(url);
    } catch (err) {
      fail(err, fallback);
    }
  };

  const back = () => {
    if (page !== "help") {
      setPage("help");
      setNotice("");
      setActionError("");
      return;
    }
    onBack();
  };

  if (page === "licenses") {
    return (
      <section className="tunnel-settings help-screen" aria-label="Acknowledgements">
        <ScreenTopBar eyebrow="LEGAL" title="Acknowledgements" onBack={back} />
        <p className="help-note">
          VeritasVPN is licensed under the Business Source License 1.1. This build also includes the open-source components below.
        </p>
        {DESKTOP_NOTICES.map((noticeItem) => (
          <div key={noticeItem.name} className="help-license">
            <strong>{noticeItem.name}</strong>
            <span>{noticeItem.license}</span>
          </div>
        ))}
      </section>
    );
  }

  if (page === "diagnostics") {
    return (
      <section className="tunnel-settings help-screen" aria-label="Diagnostic information">
        <ScreenTopBar eyebrow="DIAGNOSTICS" title="Diagnostic information" onBack={back} />
        <p className="help-note">
          Connection state, app version, and the public endpoint. Private keys, preshared keys, and auth tokens are not included.
        </p>
        <pre className="help-report">{reportText}</pre>
        <div className="help-actions">
          <button
            type="button"
            className="btn-outline"
            onClick={() => {
              void copyText(reportText).then(
                () => {
                  setActionError("");
                  setNotice("Copied");
                },
                (err) => fail(err, "Could not copy the report."),
              );
            }}
          >
            Copy
          </button>
          <button
            type="button"
            className="btn-outline"
            onClick={() => {
              setActionError("");
              setNotice("");
              const share = navigator.share?.bind(navigator);
              if (!share) {
                void copyText(reportText).then(
                  () => setNotice("Copied. Paste it into the app you want to share with."),
                  (err) => fail(err, "Could not copy the report."),
                );
                return;
              }
              void share({ title: "VeritasVPN diagnostic report", text: reportText }).catch((err: unknown) => {
                if (err instanceof DOMException && err.name === "AbortError") return;
                void copyText(reportText).then(
                  () => setNotice("Copied. Paste it into the app you want to share with."),
                  () => fail(err, "Could not share the report."),
                );
              });
            }}
          >
            Share
          </button>
          <button
            type="button"
            className="btn-primary"
            onClick={() => {
              void openLink(mailtoUrl(true, reportText), `No email app found. Write to ${CONTACT_EMAIL}.`);
            }}
          >
            Email to support
          </button>
        </div>
        {notice && <p className="help-notice">{notice}</p>}
        {actionError && <p className="help-error" role="alert">{actionError}</p>}
        <p className="help-note">Email from this screen always includes the report, even when sharing is off.</p>
      </section>
    );
  }

  return (
    <section className="tunnel-settings help-screen" aria-label="Help and Support">
      <ScreenTopBar eyebrow="HELP" title="Help and Support" onBack={back} />
      <p className="help-note">Diagnostic details stay on this device unless you choose to share them.</p>
      {actionError && <p className="help-error" role="alert">{actionError}</p>}

      <p className="tunnel-section-label">CONTACT</p>
      <div className="help-group">
        <HelpRow
          label="Contact us"
          note={CONTACT_EMAIL}
          icon={<IconMail />}
          onClick={() => {
            const bodyIncluded = shareDiagnostics;
            void openLink(
              mailtoUrl(bodyIncluded, reportText),
              `No email app found. Write to ${CONTACT_EMAIL}.`,
            );
          }}
        />
      </div>
      <p className="help-note">
        Contact opens your email app. A redacted diagnostic report is included only when sharing is on.
      </p>

      <p className="tunnel-section-label">GUIDES</p>
      <div className="help-group">
        <HelpRow label="How to use the VPN" note="Open the support guides" icon={<IconBook />} onClick={() => { void openLink(SUPPORT_URL, "Could not open the support page."); }} />
        <HelpRow label="I can't get connected" note="Connection and Stealth" icon={<IconPower />} onClick={() => { void openLink(SUPPORT_CONNECT_URL, "Could not open the support page."); }} />
        <HelpRow label="I can connect but there is a problem" note="Still protected, something else is wrong" icon={<IconAlert />} onClick={() => { void openLink(SUPPORT_URL, "Could not open the support page."); }} />
      </div>

      <p className="tunnel-section-label">DIAGNOSTICS</p>
      <button
        type="button"
        className={`tunnel-toggle-card${shareDiagnostics ? " is-on" : ""}`}
        aria-pressed={shareDiagnostics}
        onClick={() => {
          const next = !shareDiagnostics;
          setShareDiagnostics(next);
          writeShareDiagnostics(next);
        }}
      >
        <div className="tunnel-toggle-copy">
          <strong>Share diagnostics</strong>
          <span>Off by default. When on, Contact us includes a redacted report. Keys and tokens stay out.</span>
        </div>
        <i className={shareDiagnostics ? "on" : ""} aria-hidden="true" />
      </button>
      <div className="help-group">
        <HelpRow
          label="Diagnostic information"
          note="Connection, version, and endpoint"
          icon={<IconWrench />}
          onClick={() => {
            setNowMs(Date.now());
            setNotice("");
            setActionError("");
            setPage("diagnostics");
          }}
        />
      </div>

      <p className="tunnel-section-label">LEGAL</p>
      <div className="help-group">
        <HelpRow label="Privacy Policy" icon={<IconDoc />} onClick={() => { void openLink(PRIVACY_URL, "Could not open the privacy policy."); }} />
        <HelpRow label="Terms of Service" icon={<IconDoc />} onClick={() => { void openLink(TERMS_URL, "Could not open the terms of service."); }} />
        <HelpRow
          label="Acknowledgements"
          note="Open-source licenses"
          icon={<IconDoc />}
          onClick={() => {
            setNotice("");
            setActionError("");
            setPage("licenses");
          }}
        />
      </div>

      <div className="help-app-details">
        <img src={veritasMark} alt="" width={28} height={28} />
        <strong>App details</strong>
        <b>{versionLabel}</b>
      </div>
    </section>
  );
}
