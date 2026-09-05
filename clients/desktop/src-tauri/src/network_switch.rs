//! Linux network-switch recovery (Phase 1–3).
//!
//! Soft recovery is background-only and non-interactive.
//! Soft recovery never freezes the UI.
//! Soft reconnect is detached and non-blocking.

use std::fs;
use std::sync::Mutex;
use std::time::{Duration, Instant};

use serde::{Deserialize, Serialize};

use super::{
    bring_up_from_saved_config_soft, cleanup_kill_switch_noninteractive, reapply_dns_from_saved,
    refresh_endpoint_route_linux, state_dir, tunnel_is_healthy, write_last_config_json,
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

// Soft reconnect throttle — avoid thrashing if soft reconnect keeps failing.
static LAST_SOFT_RECONNECT: Mutex<Option<Instant>> = Mutex::new(None);
const SOFT_RECONNECT_MIN_INTERVAL: Duration = Duration::from_secs(30);

// Soft path adapt throttle — only re-run when the gateway changes.
static LAST_GATEWAY: Mutex<Option<String>> = Mutex::new(None);

// Soft reconnect in-flight so concurrent watcher ticks do not thrash.
static SOFT_RECONNECT_IN_FLIGHT: Mutex<bool> = Mutex::new(false);

// Soft reconnect is detached so soft recovery never freezes the watcher.
// Soft recovery is best-effort and non-blocking.
static SOFT_RECONNECT_HANDLE: Mutex<Option<std::thread::JoinHandle<()>>> = Mutex::new(None);

fn recover_network_switch_linux() -> Result<NetworkRecoverResult, String> {
    let dir = state_dir()?;
    let meta_path = dir.join("iface.meta");
    if !meta_path.exists() {
        return Ok(NetworkRecoverResult {
            changed: false,
            message: "not connected".into(),
            action: "noop".into(),
        });
    }

    // Soft path adapt only when underlay gateway changes.
    let mut soft_path_msg = String::from("soft path adapt ok");
    let mut soft_path_changed = false;
    {
        let detect = detect_current_gateway();
        let mut last = LAST_GATEWAY.lock().map_err(|e| e.to_string())?;
        let gateway_changed = match detect.as_deref() {
            Some(gw) if last.as_deref() != Some(gw) => true,
            None => false,
            Some(_) => false,
        };
        if gateway_changed {
            *last = detect;
            // Soft path: only elevated work when gateway changed.
            // Soft recovery uses noninteractive elevated only.
            let endpoint = refresh_endpoint_route_linux();
            let dns = reapply_dns_from_saved();
            if endpoint.refreshed {
                soft_path_msg.push_str("; endpoint host route refreshed");
                soft_path_changed = true;
            }
            if let Ok(dns) = dns {
                if dns.refreshed {
                    soft_path_msg.push_str("; DNS re-applied");
                    soft_path_changed = true;
                }
            }
        }
    }

    // Soft recovery: tunnel health check.
    let healthy = tunnel_is_healthy().unwrap_or(false);
    if healthy {
        return Ok(NetworkRecoverResult {
            changed: soft_path_changed,
            message: soft_path_msg,
            action: if soft_path_changed {
                "soft_path".into()
            } else {
                "noop".into()
            },
        });
    }

    // Soft recovery: tunnel is dead/unhealthy after underlay change.
    // Soft reconnect is throttled so we do not thrash every second.
    {
        let mut last = LAST_SOFT_RECONNECT.lock().map_err(|e| e.to_string())?;
        let now = Instant::now();
        if let Some(t) = *last {
            if now.duration_since(t) < SOFT_RECONNECT_MIN_INTERVAL {
                return Ok(NetworkRecoverResult {
                    changed: soft_path_changed,
                    message: format!(
                        "{soft_path_msg}; soft reconnect throttled (wait for retry)"
                    ),
                    action: "throttle".into(),
                });
            }
        }
        // Mark soft reconnect in-flight so concurrent ticks do not thrash.
        {
            let mut in_flight = SOFT_RECONNECT_IN_FLIGHT.lock().map_err(|e| e.to_string())?;
            if *in_flight {
                return Ok(NetworkRecoverResult {
                    changed: soft_path_changed,
                    message: format!(
                        "{soft_path_msg}; soft reconnect already in progress"
                    ),
                    action: "in_progress".into(),
                });
            }
            *in_flight = true;
        }
        *last = Some(now);
    }

    // Soft reconnect is the only path that can restore clearnet when kill
    // switch is still blackholing. Soft reconnect is best-effort and must
    // not hang the UI — so we never block on elevated for soft path.
    // Soft recovery uses noninteractive elevated only.
    // Soft recovery always cleans kill switch first so clearnet is restored
    // even if soft reconnect fails.
    let kill_cleaned = cleanup_kill_switch_noninteractive().unwrap_or(false);

    // Soft reconnect is detached so soft recovery never freezes the watcher.
    // Soft recovery is best-effort and non-blocking.
    let handle = std::thread::spawn(move || {
        match bring_up_from_saved_config_soft() {
            Ok(msg) => {
                eprintln!(
                    "[veritas] soft reconnect after network switch (kill_switch_cleaned={kill_cleaned}): {msg}"
                );
                *SOFT_RECONNECT_IN_FLIGHT.lock().unwrap_or_else(|e| e.into_inner()) = false;
            }
            Err(e) => {
                eprintln!(
                    "[veritas] soft reconnect failed after network switch (kill_switch_cleaned={kill_cleaned}): {e}"
                );
                *SOFT_RECONNECT_IN_FLIGHT.lock().unwrap_or_else(|e| e.into_inner()) = false;
            }
        }
    });
    {
        let mut handles = SOFT_RECONNECT_HANDLE.lock().map_err(|e| e.to_string())?;
        *handles = Some(handle);
    }

    Ok(NetworkRecoverResult {
        changed: true,
        message: format!(
            "soft reconnect scheduled after network switch (kill_switch_cleaned={kill_cleaned}); soft path adapt: {soft_path_msg}"
        ),
        action: "soft_reconnect_scheduled".into(),
    })
}

/// Detect the current underlay gateway (best-effort, non-elevated).
fn detect_current_gateway() -> Option<String> {
    let out = std::process::Command::new("bash")
        .arg("-c")
        .arg(
            r#"
IFACE="$(cat ~/.veritasvpn/iface 2>/dev/null || echo veritas0)"
ip -4 route show default 2>/dev/null | awk -v iface="$IFACE" '
  /blackhole/ { next }
  /via/ {
    for (i = 1; i <= NF; i++) if ($i == "dev") { d=$(i+1); break }
    if (d == "" || d != iface) { print $3; exit }
  }
' 2>/dev/null | head -1
"#,
        )
        .output()
        .ok()?;
    let s = String::from_utf8_lossy(&out.stdout);
    let gw = s.trim();
    if gw.is_empty() {
        None
    } else {
        Some(gw.to_string())
    }
}

/// Load the last tunnel config for soft reconnect.
pub fn load_last_config() -> Result<SavedTunnelConfig, String> {
    let dir = state_dir().map_err(|e| e)?;
    let path = dir.join("last-config.json");
    let raw = fs::read_to_string(&path)
        .map_err(|e| format!("read last-config: {e}"))?;
    serde_json::from_str(&raw).map_err(|e| format!("parse last-config: {e}"))
}
