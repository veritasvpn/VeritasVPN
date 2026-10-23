//! Linux network-switch recovery (Phase 1–3).
//!
//! Soft recovery is background-only and non-interactive.
//! Soft recovery never freezes the UI and never prompts for a password.

use std::fs;
use std::sync::Mutex;
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

// Underlay fingerprint: gateway|iface|src. Gateway IP alone misses Wi-Fi→Wi-Fi
// switches that keep 192.168.1.1 (Android rebinds on any network change).
static LAST_FINGERPRINT: Mutex<Option<String>> = Mutex::new(None);

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

fn recover_network_switch_linux() -> Result<NetworkRecoverResult, String> {
    let meta_path = privileged_state_dir()?.join("iface.meta");
    if !meta_path.exists() {
        return Ok(NetworkRecoverResult {
            changed: false,
            message: "not connected".into(),
            action: "noop".into(),
        });
    }

    let fp = detect_underlay_fingerprint();
    let healthy = tunnel_is_healthy().unwrap_or(false);
    let mut last_fp = LAST_FINGERPRINT.lock().map_err(|e| e.to_string())?;
    let underlay_changed = match (fp.as_ref(), last_fp.as_ref()) {
        (Some(now), Some(prev)) => now != prev,
        // Underlay vanished (DHCP/association gap) — still rebind so the
        // helper can wait for the new path.
        (None, Some(_)) => true,
        (Some(_), None) => false,
        (None, None) => false,
    };
    if let Some(now) = fp.clone() {
        *last_fp = Some(now);
    }
    drop(last_fp);

    if healthy && !underlay_changed {
        return Ok(NetworkRecoverResult {
            changed: false,
            message: "underlay unchanged; tunnel healthy".into(),
            action: "noop".into(),
        });
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

    // Android keeps the VPN session and rebinds the underlay. Do the same:
    // refresh the endpoint host route + handshake. Never cleanup/teardown —
    // that dropped the tunnel and could not rebuild it without a password.
    let endpoint = refresh_endpoint_route_linux();
    let dns = reapply_dns_from_saved();
    recovery_lock_release(owner);

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

/// Detect underlay path as `gateway|iface|src` (best-effort, non-elevated).
fn detect_underlay_fingerprint() -> Option<String> {
    let out = std::process::Command::new("bash")
        .arg("-c")
        .arg(
            r#"
IFACE="veritas0"
gw=""; gif=""; src=""
if command -v nmcli >/dev/null 2>&1; then
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
if [ -z "$gw" ]; then
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
    use super::{
        recovery_lock_abandon, recovery_lock_acquire, recovery_lock_acquire_owned,
        recovery_lock_release, recovery_lock_release_owned,
    };

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
