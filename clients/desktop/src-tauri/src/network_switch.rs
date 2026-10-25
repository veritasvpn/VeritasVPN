//! Linux network-switch recovery (Phase 1–3).
//!
//! Soft recovery is background-only and non-interactive.
//! Soft recovery never freezes the UI and never prompts for a password.

use std::fs;
use std::sync::{Condvar, Mutex};
use std::time::{Duration, Instant};

use serde::{Deserialize, Serialize};

use super::{
    privileged_state_dir, reapply_dns_from_saved, refresh_endpoint_route_linux, state_dir,
    tunnel_is_healthy, write_last_config_json,
};

/// Last successful tunnel config so soft reconnect can re-bring the peer up.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SavedTunnelConfig {
    pub private_key: String,
    pub address: String,
    pub dns: String,
    pub server_public_key: String,
    pub endpoint: String,
    pub allowed_ips: Vec<String>,
    pub peer_id: String,
    #[serde(default)]
    pub preshared_key: String,
    #[serde(default)]
    pub stealth_endpoint: String,
    #[serde(default)]
    pub stealth_path_prefix: String,
    #[serde(default)]
    pub endpoint_lan: String,
    #[serde(default)]
    pub endpoint_wan: String,
}

/// Result of one recovery pass.
#[derive(Debug, Clone, Serialize)]
pub struct NetworkRecoverResult {
    pub changed: bool,
    pub message: String,
    pub action: String,
}

/// Persist the last tunnel config for soft reconnect.
pub fn save_last_config(config: &SavedTunnelConfig) -> Result<(), String> {
    let dir = state_dir().map_err(|e| e)?;
    write_last_config_json(&dir, config)
}

/// Soft recovery for a network switch. Call repeatedly while connected.
pub fn recover_network_switch() -> NetworkRecoverResult {
    match recover_network_switch_linux() {
        Ok(r) => r,
        Err(e) => NetworkRecoverResult {
            changed: false,
            message: e,
            action: "error".into(),
        },
    }
}

// Rebind throttle — keep the tunnel up; never tear it down on a network switch.
static LAST_REBIND: Mutex<Option<Instant>> = Mutex::new(None);
const REBIND_MIN_INTERVAL: Duration = Duration::from_secs(3);

static REBIND_IN_FLIGHT: Mutex<Option<(Instant, u64)>> = Mutex::new(None);
static REBIND_OWNER_SEQ: Mutex<u64> = Mutex::new(0);
/// Owner token for the recovery_open that the UI must close. A second close
/// sees None and cannot drop a path-adapt lock taken after the first release.
static RECOVERY_OWNER: Mutex<Option<u64>> = Mutex::new(None);
const REBIND_LOCK_STALE: Duration = Duration::from_secs(45);

fn rebind_lock_held(slot: &Option<(Instant, u64)>) -> bool {
    match slot {
        Some((started, _)) => started.elapsed() < REBIND_LOCK_STALE,
        None => false,
    }
}

fn next_lock_token() -> u64 {
    let mut seq = REBIND_OWNER_SEQ.lock().unwrap_or_else(|e| e.into_inner());
    *seq = seq.wrapping_add(1);
    if *seq == 0 {
        *seq = 1;
    }
    *seq
}

/// One lock for path-adapt and the outside-tunnel recovery read.
/// The token is the only handle that can release this hold.
pub(crate) fn recovery_lock_acquire() -> Option<u64> {
    let mut slot = REBIND_IN_FLIGHT.lock().unwrap_or_else(|e| e.into_inner());
    if rebind_lock_held(&slot) {
        return None;
    }
    let token = next_lock_token();
    *slot = Some((Instant::now(), token));
    Some(token)
}

pub(crate) fn recovery_lock_release(token: u64) {
    let mut slot = REBIND_IN_FLIGHT.lock().unwrap_or_else(|e| e.into_inner());
    if let Some((_, held)) = *slot {
        if held == token {
            *slot = None;
        }
    }
}

pub(crate) fn recovery_lock_acquire_owned() -> Option<u64> {
    let token = recovery_lock_acquire()?;
    *RECOVERY_OWNER.lock().unwrap_or_else(|e| e.into_inner()) = Some(token);
    Some(token)
}

/// Release the recovery_open hold once. A repeated close is a no-op.
pub(crate) fn recovery_lock_release_owned() {
    let token = RECOVERY_OWNER.lock().unwrap_or_else(|e| e.into_inner()).take();
    if let Some(token) = token {
        recovery_lock_release(token);
    }
}

pub(crate) fn recovery_lock_abandon(token: u64) {
    let mut owner = RECOVERY_OWNER.lock().unwrap_or_else(|e| e.into_inner());
    if *owner == Some(token) {
        *owner = None;
    }
    drop(owner);
    recovery_lock_release(token);
}

/// How long a diverged underlay may stay behind the kill switch.
/// Past this, release the tunnel (no purge, no password) so the internet works.
pub(crate) const UNDERLAY_RELEASE_AFTER: Duration = Duration::from_secs(18);

pub(crate) const UNDERLAY_RELEASED_NOTICE: &str =
    "The network changed and the VPN couldn't follow it. Internet is back. Tap to reconnect.";
pub(crate) const UNDERLAY_BLOCKED_NOTICE: &str =
    "The network changed and the VPN couldn't follow it. Tap Restore internet, then connect again.";

static DIVERGED_SINCE: Mutex<Option<Instant>> = Mutex::new(None);
static RELEASE_LATCHED: Mutex<bool> = Mutex::new(false);
static UNDERLAY_NOTICE: Mutex<String> = Mutex::new(String::new());

pub(crate) fn clear_underlay_watch() {
    *DIVERGED_SINCE.lock().unwrap_or_else(|e| e.into_inner()) = None;
    *RELEASE_LATCHED.lock().unwrap_or_else(|e| e.into_inner()) = false;
}

pub(crate) fn clear_underlay_notice() {
    *UNDERLAY_NOTICE.lock().unwrap_or_else(|e| e.into_inner()) = String::new();
}

pub(crate) fn take_underlay_notice() -> String {
    std::mem::take(&mut *UNDERLAY_NOTICE.lock().unwrap_or_else(|e| e.into_inner()))
}

fn set_underlay_notice(msg: &str) {
    *UNDERLAY_NOTICE.lock().unwrap_or_else(|e| e.into_inner()) = msg.to_string();
}

/// `recorded` and `detected` are `gateway|iface|src`.
/// A missing detection counts as a change once a tunnel underlay was recorded,
/// so an interface that disappeared is retried instead of ignored.
pub(crate) fn underlay_fingerprint_diverged(recorded: &str, detected: Option<&str>) -> bool {
    let recorded = recorded.trim();
    match detected.map(str::trim).filter(|s| !s.is_empty()) {
        None => !recorded.is_empty(),
        Some(now) => recorded.is_empty() || now != recorded,
    }
}

pub(crate) fn underlay_release_due(diverged_for: Duration) -> bool {
    diverged_for >= UNDERLAY_RELEASE_AFTER
}

fn note_divergence(diverged: bool) -> Option<Duration> {
    let mut since = DIVERGED_SINCE.lock().unwrap_or_else(|e| e.into_inner());
    if !diverged {
        *since = None;
        return None;
    }
    let start = since.get_or_insert_with(Instant::now);
    Some(start.elapsed())
}

fn release_latched() -> bool {
    *RELEASE_LATCHED.lock().unwrap_or_else(|e| e.into_inner())
}

fn latch_release() -> bool {
    let mut latched = RELEASE_LATCHED.lock().unwrap_or_else(|e| e.into_inner());
    if *latched {
        return false;
    }
    *latched = true;
    true
}

fn recorded_underlay_fingerprint() -> String {
    let Ok(dir) = privileged_state_dir() else {
        return String::new();
    };
    let meta = fs::read_to_string(dir.join("iface.meta")).unwrap_or_default();
    let mut gw = String::new();
    let mut gif = String::new();
    let mut src = String::new();
    for line in meta.lines() {
        let Some((k, v)) = line.split_once('=') else {
            continue;
        };
        match k.trim() {
            "gateway" => gw = v.trim().to_string(),
            "gw_if" => gif = v.trim().to_string(),
            "underlay_src" => src = v.trim().to_string(),
            _ => {}
        }
    }
    if gw.is_empty() && gif.is_empty() {
        String::new()
    } else {
        format!("{gw}|{gif}|{src}")
    }
}

fn release_diverged_tunnel() -> NetworkRecoverResult {
    if !latch_release() {
        return NetworkRecoverResult {
            changed: false,
            message: "underlay release already attempted".into(),
            action: "released".into(),
        };
    }
    let cleared = super::soft_release_tunnel();
    let notice = if cleared {
        UNDERLAY_RELEASED_NOTICE
    } else {
        UNDERLAY_BLOCKED_NOTICE
    };
    set_underlay_notice(notice);
    NetworkRecoverResult {
        changed: true,
        message: notice.to_string(),
        action: "released".into(),
    }
}

fn recover_network_switch_linux() -> Result<NetworkRecoverResult, String> {
    let meta_path = privileged_state_dir()?.join("iface.meta");
    if !meta_path.exists() {
        clear_underlay_watch();
        return Ok(NetworkRecoverResult {
            changed: false,
            message: "not connected".into(),
            action: "noop".into(),
        });
    }

    let recorded = recorded_underlay_fingerprint();
    let detected = detect_underlay_fingerprint();
    let underlay_changed = underlay_fingerprint_diverged(&recorded, detected.as_deref());
    let healthy = tunnel_is_healthy().unwrap_or(false);

    if !underlay_changed {
        let _ = note_divergence(false);
        if healthy {
            return Ok(NetworkRecoverResult {
                changed: false,
                message: "underlay unchanged; tunnel healthy".into(),
                action: "noop".into(),
            });
        }
    } else if release_latched() {
        return Ok(NetworkRecoverResult {
            changed: false,
            message: "underlay release already attempted".into(),
            action: "released".into(),
        });
    } else if underlay_release_due(note_divergence(true).unwrap_or_default()) {
        return Ok(release_diverged_tunnel());
    }

    let owner = {
        let mut in_flight = REBIND_IN_FLIGHT.lock().map_err(|e| e.to_string())?;
        if rebind_lock_held(&in_flight) {
            return Ok(NetworkRecoverResult {
                changed: false,
                message: "path rebind already in progress".into(),
                action: "in_progress".into(),
            });
        }
        let mut last = LAST_REBIND.lock().map_err(|e| e.to_string())?;
        let now = Instant::now();
        if !underlay_changed {
            if let Some(t) = *last {
                if now.duration_since(t) < REBIND_MIN_INTERVAL {
                    return Ok(NetworkRecoverResult {
                        changed: false,
                        message: "path rebind throttled".into(),
                        action: "throttle".into(),
                    });
                }
            }
        }
        *last = Some(now);
        let token = next_lock_token();
        *in_flight = Some((now, token));
        token
    };

    // Keep the same tunnel and follow the OS default route. A rebind that
    // still has not landed after UNDERLAY_RELEASE_AFTER drops the kill switch
    // without --purge so the internet works. That fallback is not a reconnect.
    let endpoint = refresh_endpoint_route_linux();
    let dns = reapply_dns_from_saved();
    recovery_lock_release(owner);

    let still = underlay_fingerprint_diverged(
        &recorded_underlay_fingerprint(),
        detect_underlay_fingerprint().as_deref(),
    );
    if !still {
        let _ = note_divergence(false);
    } else if underlay_release_due(note_divergence(true).unwrap_or_default()) {
        return Ok(release_diverged_tunnel());
    }

    let mut msg = endpoint.message.clone();
    if let Ok(dns) = dns {
        if dns.refreshed {
            msg.push_str("; DNS re-applied");
        }
    }
    Ok(NetworkRecoverResult {
        changed: endpoint.refreshed || underlay_changed,
        message: msg,
        action: if endpoint.refreshed {
            "soft_path".into()
        } else {
            "soft_path_pending".into()
        },
    })
}

/// One privileged helper at a time. Teardown and recover-close coalesce so a
/// burst of callers shares the in-flight sudo instead of starting another.
const FLIGHT_COALESCE: Duration = Duration::from_secs(3);

#[derive(Clone, Copy, PartialEq, Eq)]
pub(crate) enum FlightKind {
    Close,
    Teardown,
    Rebind,
}

struct FlightGate {
    busy: Option<FlightKind>,
    finished: Option<(FlightKind, Instant)>,
}

static FLIGHT: Mutex<FlightGate> = Mutex::new(FlightGate {
    busy: None,
    finished: None,
});
static FLIGHT_CV: Condvar = Condvar::new();

enum FlightRole {
    Run,
    Skip,
}

fn flight_enter(kind: FlightKind) -> FlightRole {
    let mut gate = FLIGHT.lock().unwrap_or_else(|e| e.into_inner());
    loop {
        if gate.busy.is_some() {
            gate = FLIGHT_CV.wait(gate).unwrap_or_else(|e| e.into_inner());
            continue;
        }
        if kind != FlightKind::Rebind {
            if let Some((done, at)) = gate.finished {
                if at.elapsed() < FLIGHT_COALESCE && (done == FlightKind::Teardown || done == kind)
                {
                    return FlightRole::Skip;
                }
            }
        }
        gate.busy = Some(kind);
        return FlightRole::Run;
    }
}

fn flight_exit(kind: FlightKind) {
    let mut gate = FLIGHT.lock().unwrap_or_else(|e| e.into_inner());
    if gate.busy == Some(kind) {
        gate.busy = None;
        gate.finished = Some((kind, Instant::now()));
    }
    FLIGHT_CV.notify_all();
}

pub(crate) struct FlightGuard {
    kind: FlightKind,
}

impl FlightGuard {
    pub(crate) fn enter(kind: FlightKind) -> Option<Self> {
        match flight_enter(kind) {
            FlightRole::Run => Some(Self { kind }),
            FlightRole::Skip => None,
        }
    }
}

impl Drop for FlightGuard {
    fn drop(&mut self) {
        flight_exit(self.kind);
    }
}

/// Detect underlay path as `gateway|iface|src` (best-effort, non-elevated).
fn detect_underlay_fingerprint() -> Option<String> {
    let out = std::process::Command::new("bash")
        .arg("-c")
        .arg(
            r#"
IFACE="veritas0"
gw=""; gif=""; src=""
# The OS default route is the underlay. nmcli device order is not.
gw="$(ip -4 route show default 2>/dev/null | awk -v iface="$IFACE" '
  /blackhole/ { next }
  /via/ {
    for (i = 1; i <= NF; i++) if ($i == "dev") { d=$(i+1); break }
    if (d == "" || d != iface) { print $3; exit }
  }
')"
gif="$(ip -4 route show default 2>/dev/null | awk -v iface="$IFACE" '
  /blackhole/ { next }
  /via/ {
    for (i = 1; i <= NF; i++) if ($i == "dev") { d=$(i+1); break }
    if (d == "" || d != iface) { print d; exit }
  }
')"
if [ -z "$gw" ] && command -v nmcli >/dev/null 2>&1; then
  while IFS=: read -r dev type state; do
    [ "$state" = "connected" ] || continue
    case "$type" in wifi|ethernet|802-11-wireless|802-3-ethernet) ;; *) continue ;; esac
    [ -n "$dev" ] && [ "$dev" != "$IFACE" ] && [ "$dev" != "lo" ] || continue
    gw="$(nmcli -g IP4.GATEWAY device show "$dev" 2>/dev/null | head -1)"
    src="$(nmcli -g IP4.ADDRESS device show "$dev" 2>/dev/null | head -1)"
    src="${src%%/*}"
    gif="$dev"
    [ -n "$gw" ] && break
  done <<EOF
$(nmcli -t -f DEVICE,TYPE,STATE device 2>/dev/null)
EOF
fi
if [ -z "$src" ] && [ -n "$gif" ]; then
  src="$(ip -4 -o addr show dev "$gif" 2>/dev/null | awk '{print $4; exit}')"
  src="${src%%/*}"
fi
[ -n "$gw" ] || exit 0
printf '%s|%s|%s\n' "$gw" "$gif" "$src"
"#,
        )
        .output()
        .ok()?;
    let s = String::from_utf8_lossy(&out.stdout);
    let fp = s.trim();
    if fp.is_empty() {
        None
    } else {
        Some(fp.to_string())
    }
}

/// Load the last tunnel config for soft reconnect.
pub fn load_last_config() -> Result<SavedTunnelConfig, String> {
    let dir = state_dir().map_err(|e| e)?;
    let path = dir.join("last-config.json");
    let raw = fs::read_to_string(&path).map_err(|e| format!("read last-config: {e}"))?;
    serde_json::from_str(&raw).map_err(|e| format!("parse last-config: {e}"))
}

#[cfg(test)]
mod tests {
    use std::time::Duration;

    use super::{
        recovery_lock_abandon, recovery_lock_acquire, recovery_lock_acquire_owned,
        recovery_lock_release, recovery_lock_release_owned, underlay_fingerprint_diverged,
        underlay_release_due, FlightGuard, FlightKind, UNDERLAY_RELEASE_AFTER,
    };

    #[test]
    fn underlay_switch_retries_then_releases() {
        let wifi = "192.0.2.1|wlx90de802153e1|192.0.2.20";
        let wired = "198.51.100.1|eno1|198.51.100.10";
        assert!(!underlay_fingerprint_diverged(wired, Some(wired)));
        assert!(underlay_fingerprint_diverged(wifi, Some(wired)));
        assert!(
            underlay_fingerprint_diverged(wifi, None),
            "a disappeared underlay must be retried"
        );
        assert!(!underlay_fingerprint_diverged("", None));
        assert!(!underlay_release_due(Duration::from_secs(17)));
        assert!(underlay_release_due(Duration::from_secs(18)));
        assert!(underlay_release_due(UNDERLAY_RELEASE_AFTER));
    }

    #[test]
    fn teardown_flight_is_single() {
        use std::sync::atomic::{AtomicUsize, Ordering};
        use std::sync::Arc;
        use std::thread;

        let ran = Arc::new(AtomicUsize::new(0));
        let mut handles = Vec::new();
        for _ in 0..8 {
            let ran = ran.clone();
            handles.push(thread::spawn(move || {
                if FlightGuard::enter(FlightKind::Teardown).is_some() {
                    ran.fetch_add(1, Ordering::SeqCst);
                    thread::sleep(Duration::from_millis(40));
                }
            }));
        }
        for handle in handles {
            handle.join().expect("flight thread");
        }
        assert_eq!(ran.load(Ordering::SeqCst), 1);
    }

    #[test]
    fn recovery_lock_release_matches_its_owner() {
        let first = recovery_lock_acquire().expect("acquire");
        assert!(recovery_lock_acquire().is_none());
        recovery_lock_release(first.wrapping_add(9));
        assert!(
            recovery_lock_acquire().is_none(),
            "a mismatched token released the lock"
        );
        recovery_lock_release(first);

        let _owned = recovery_lock_acquire_owned().expect("recovery owner");
        recovery_lock_release_owned();
        let path = recovery_lock_acquire().expect("path-adapt after recovery close");
        recovery_lock_release_owned();
        assert!(
            recovery_lock_acquire().is_none(),
            "a second recovery close dropped the path-adapt lock"
        );
        recovery_lock_release(path);

        let abandoned = recovery_lock_acquire_owned().expect("abandon");
        recovery_lock_abandon(abandoned);
        let after = recovery_lock_acquire().expect("free after abandon");
        recovery_lock_release(after);
    }
}
