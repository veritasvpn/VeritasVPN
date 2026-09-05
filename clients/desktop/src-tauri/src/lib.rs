use base64::{engine::general_purpose::STANDARD, Engine};
use rand_core::OsRng;
use serde::{Deserialize, Serialize};
use std::fs;
use std::path::{Path, PathBuf};
use std::process::Command;
use std::sync::{
    atomic::{AtomicBool, Ordering},
    Arc,
};
use std::thread;
use std::time::Duration;
use tauri::{AppHandle, Manager};
use x25519_dalek::{PublicKey, StaticSecret};

const KEYRING_SERVICE: &str = "cloud.veritasvpn.desktop";

mod network_switch;

fn keyring_entry(name: &str) -> Result<keyring::Entry, String> {
    if name != "access_token" && name != "refresh_token" {
        return Err("unsupported credential name".into());
    }
    keyring::Entry::new(KEYRING_SERVICE, name).map_err(|e| format!("open secure credential store: {e}"))
}

#[tauri::command]
fn secure_credential_set(name: String, value: String) -> Result<(), String> {
    if value.is_empty() {
        return Err("credential value cannot be empty".into());
    }
    keyring_entry(&name)?
        .set_password(&value)
        .map_err(|e| format!("store credential securely: {e}"))
}

#[tauri::command]
fn secure_credential_get(name: String) -> Result<Option<String>, String> {
    match keyring_entry(&name)?.get_password() {
        Ok(value) => Ok(Some(value)),
        Err(keyring::Error::NoEntry) => Ok(None),
        Err(e) => Err(format!("read secure credential: {e}")),
    }
}

#[tauri::command]
fn secure_credential_delete(name: String) -> Result<(), String> {
    match keyring_entry(&name)?.delete_credential() {
        Ok(()) | Err(keyring::Error::NoEntry) => Ok(()),
        Err(e) => Err(format!("delete secure credential: {e}")),
    }
}

#[derive(Debug, Serialize, Deserialize)]
pub struct WgTunnelConfig {
    pub private_key: String,
    pub address: String,
    pub dns: String,
    pub server_public_key: String,
    pub endpoint: String,
    pub allowed_ips: Vec<String>,
    pub peer_id: String,
    #[serde(default)]
    pub preshared_key: String,
    /// Remote TLS/WebSocket host:port for stealth mode (empty = direct UDP).
    #[serde(default)]
    pub stealth_endpoint: String,
    #[serde(default)]
    pub stealth_path_prefix: String,
}

#[derive(Debug, Serialize)]
pub struct ConnectResult {
    pub success: bool,
    pub message: String,
    pub mode: String,
    pub peer_id: String,
}

#[derive(Debug, Serialize)]
pub struct KeyPair {
    pub private_key: String,
    pub public_key: String,
}

pub(crate) fn state_dir() -> Result<PathBuf, String> {
    let home = dirs_next::home_dir().ok_or("Could not resolve home directory")?;
    #[cfg(target_os = "macos")]
    let dir = home
        .join("Library")
        .join("Application Support")
        .join("cloud.veritasvpn.desktop");
    #[cfg(not(target_os = "macos"))]
    let dir = home.join(".veritasvpn");
    fs::create_dir_all(&dir).map_err(|e| format!("create config dir: {e}"))?;
    Ok(dir)
}

fn conf_path() -> Result<PathBuf, String> {
    Ok(state_dir()?.join("veritas.conf"))
}

fn peer_id_path() -> Result<PathBuf, String> {
    Ok(state_dir()?.join("peer_id"))
}

fn iface_path() -> Result<PathBuf, String> {
    Ok(state_dir()?.join("iface"))
}

fn pid_path() -> Result<PathBuf, String> {
    Ok(state_dir()?.join("wireguard-go.pid"))
}

fn stealth_pid_path() -> Result<PathBuf, String> {
    Ok(state_dir()?.join("wstunnel.pid"))
}

fn resolve_wireguard_go(app: &AppHandle) -> Result<PathBuf, String> {
    let candidates = ["bin/wireguard-go", "resources/bin/wireguard-go"];
    for rel in candidates {
        if let Ok(p) = app
            .path()
            .resolve(rel, tauri::path::BaseDirectory::Resource)
        {
            if p.exists() {
                return Ok(p);
            }
        }
    }
    if let Ok(dir) = app.path().resource_dir() {
        for rel in [
            dir.join("bin/wireguard-go"),
            dir.join("resources/bin/wireguard-go"),
        ] {
            if rel.exists() {
                return Ok(rel);
            }
        }
    }
    let dev = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("resources/bin/wireguard-go");
    if dev.exists() {
        return Ok(dev);
    }
    Err("Bundled WireGuard engine missing from the app".into())
}

fn resolve_wstunnel(app: &AppHandle) -> Result<PathBuf, String> {
    let candidates = ["bin/wstunnel", "resources/bin/wstunnel"];
    for rel in candidates {
        if let Ok(p) = app
            .path()
            .resolve(rel, tauri::path::BaseDirectory::Resource)
        {
            if p.exists() {
                return Ok(p);
            }
        }
    }
    if let Ok(dir) = app.path().resource_dir() {
        for rel in [dir.join("bin/wstunnel"), dir.join("resources/bin/wstunnel")] {
            if rel.exists() {
                return Ok(rel);
            }
        }
    }
    let dev = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("resources/bin/wstunnel");
    if dev.exists() {
        return Ok(dev);
    }
    Err("Bundled stealth engine (wstunnel) missing from the app".into())
}

#[tauri::command]
fn wireguard_available(app: AppHandle) -> bool {
    #[cfg(target_os = "windows")]
    {
        let _ = app;
        return false;
    }
    #[cfg(not(target_os = "windows"))]
    resolve_wireguard_go(&app).is_ok()
}

#[tauri::command]
fn generate_wg_keys() -> Result<KeyPair, String> {
    let secret = StaticSecret::random_from_rng(OsRng);
    let public = PublicKey::from(&secret);
    Ok(KeyPair {
        private_key: STANDARD.encode(secret.to_bytes()),
        public_key: STANDARD.encode(public.to_bytes()),
    })
}

fn b64_key_to_hex(b64: &str) -> Result<String, String> {
    let bytes = STANDARD
        .decode(b64.trim())
        .map_err(|e| format!("invalid key: {e}"))?;
    if bytes.len() != 32 {
        return Err("WireGuard key must be 32 bytes".into());
    }
    Ok(hex::encode(bytes))
}

#[tauri::command]
async fn connect_wireguard(app: AppHandle, config: WgTunnelConfig) -> ConnectResult {
    // Persist config for soft reconnect after network switch (Phase 3).
    let _ = network_switch::save_last_config(&network_switch::SavedTunnelConfig {
        private_key: config.private_key.clone(),
        address: config.address.clone(),
        dns: config.dns.clone(),
        server_public_key: config.server_public_key.clone(),
        endpoint: config.endpoint.clone(),
        allowed_ips: config.allowed_ips.clone(),
        peer_id: config.peer_id.clone(),
        preshared_key: config.preshared_key.clone(),
        stealth_endpoint: config.stealth_endpoint.clone(),
        stealth_path_prefix: config.stealth_path_prefix.clone(),
    });

    // Run elevated bring-up off the async runtime so the UI stays responsive
    // (avoids GTK "veritasvpn is not responding" during pkexec + handshake).
    let peer_id = config.peer_id.clone();
    let app_for_thread = app.clone();
    let result = tauri::async_runtime::spawn_blocking(move || bring_up_wireguard(&app_for_thread, &config))
        .await
        .unwrap_or_else(|e| Err(format!("connect task failed: {e}")));

    match result {
        Ok(msg) => {
            // Store AppHandle so soft reconnect can re-bring the tunnel up.
            let _ = APP_HANDLE_FOR_RECOVER.set(app.clone());
            // Start background watcher (Phase 1) for underlay flaps.
            start_network_switch_watcher_if_needed();
            ConnectResult {
                success: true,
                message: msg,
                mode: "wireguard".into(),
                peer_id,
            }
        }
        Err(e) => ConnectResult {
            success: false,
            message: e,
            mode: "wireguard".into(),
            peer_id,
        },
    }
}

/// Start the network-switch recovery watcher if not already running.
static NETWORK_SWITCH_WATCHER: std::sync::OnceLock<Arc<AtomicBool>> = std::sync::OnceLock::new();
static NETWORK_SWITCH_JOIN: std::sync::OnceLock<thread::JoinHandle<()>> = std::sync::OnceLock::new();

fn start_network_switch_watcher_if_needed() {
    // Allow restart after disconnect/reconnect.
    if let Some(running) = NETWORK_SWITCH_WATCHER.get() {
        if running.load(Ordering::SeqCst) {
            return;
        }
        running.store(true, Ordering::SeqCst);
    } else {
        let running = Arc::new(AtomicBool::new(true));
        let _ = NETWORK_SWITCH_WATCHER.set(running.clone());
        let handle = thread::spawn(move || {
            while running.load(Ordering::SeqCst) {
                // Soft recovery is best-effort and must never block the UI.
                // Soft reconnect is also throttled inside recover_network_switch.
                match network_switch::recover_network_switch() {
                    network_switch::NetworkRecoverResult {
                        changed: true, ..
                    } => {
                        // Soft recovery ran; keep logging to stderr for diagnostics.
                    }
                    _ => {}
                }
                // Soft recovery is cheap when gateway is unchanged.
                // Soft reconnect is throttled inside recover_network_switch.
                thread::sleep(Duration::from_secs(2));
            }
        });
        let _ = NETWORK_SWITCH_JOIN.set(handle);
    }
}

fn stop_network_switch_watcher() {
    if let Some(running) = NETWORK_SWITCH_WATCHER.get() {
        running.store(false, Ordering::SeqCst);
    }
}

#[derive(Debug, Serialize)]
pub struct WgTransferStats {
    pub rx_bytes: u64,
    pub tx_bytes: u64,
    pub last_handshake_sec: i64,
    pub interface_up: bool,
}

#[tauri::command]
fn wireguard_stats() -> WgTransferStats {
    #[cfg(target_os = "linux")]
    {
        return wireguard_stats_linux();
    }
    #[cfg(not(target_os = "linux"))]
    {
        WgTransferStats {
            rx_bytes: 0,
            tx_bytes: 0,
            last_handshake_sec: 0,
            interface_up: false,
        }
    }
}

#[cfg(target_os = "linux")]
fn linux_iface_sysfs_stats(iface: &str) -> (bool, u64, u64) {
    let base = format!("/sys/class/net/{iface}");
    let oper = fs::read_to_string(format!("{base}/operstate"))
        .unwrap_or_default()
        .trim()
        .to_ascii_lowercase();
    let up = oper == "up" || Path::new(&base).exists();
    let rx = fs::read_to_string(format!("{base}/statistics/rx_bytes"))
        .ok()
        .and_then(|s| s.trim().parse().ok())
        .unwrap_or(0);
    let tx = fs::read_to_string(format!("{base}/statistics/tx_bytes"))
        .ok()
        .and_then(|s| s.trim().parse().ok())
        .unwrap_or(0);
    (up, rx, tx)
}

#[cfg(target_os = "linux")]
fn ensure_linux_stats_script() -> Result<PathBuf, String> {
    let dir = state_dir()?;
    let script_path = dir.join("stats.sh");
    let iface_file = iface_path()?;
    let script = format!(
        r#"#!/usr/bin/env bash
set -euo pipefail
IFACE_FILE='{iface_file}'
IFACE="$(cat "$IFACE_FILE" 2>/dev/null || true)"
IFACE="${{IFACE:-veritas0}}"
exec wg show "$IFACE" dump
"#,
        iface_file = iface_file.display()
    );
    fs::write(&script_path, &script).map_err(|e| format!("write stats script: {e}"))?;
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        let mut perms = fs::metadata(&script_path)
            .map_err(|e| format!("stats script meta: {e}"))?
            .permissions();
        perms.set_mode(0o755);
        let _ = fs::set_permissions(&script_path, perms);
    }
    Ok(script_path)
}

#[cfg(target_os = "linux")]
fn wireguard_stats_linux() -> WgTransferStats {
    let iface = iface_path()
        .ok()
        .and_then(|p| fs::read_to_string(p).ok())
        .unwrap_or_else(|| "veritas0".into())
        .trim()
        .to_string();
    if iface.is_empty() {
        return WgTransferStats {
            rx_bytes: 0,
            tx_bytes: 0,
            last_handshake_sec: 0,
            interface_up: false,
        };
    }

    let (sys_up, sys_rx, sys_tx) = linux_iface_sysfs_stats(&iface);

    // Prefer passwordless sudo via stats.sh (kernel WG UAPI is root-only).
    let privileged_dump = ensure_linux_stats_script().ok().and_then(|script| {
        Command::new("sudo")
            .args(["-n", "bash", &script.to_string_lossy()])
            .output()
            .ok()
            .filter(|o| o.status.success())
            .map(|o| String::from_utf8_lossy(&o.stdout).into_owned())
    });

    let dump_text = privileged_dump.or_else(|| {
        Command::new("wg")
            .args(["show", &iface, "dump"])
            .output()
            .ok()
            .filter(|o| o.status.success())
            .map(|o| String::from_utf8_lossy(&o.stdout).into_owned())
    });

    let Some(text) = dump_text else {
        // Unprivileged `wg` often fails on the root-only UAPI socket; still
        // report interface + byte counters from sysfs so LIVE STATS is useful.
        return WgTransferStats {
            rx_bytes: sys_rx,
            tx_bytes: sys_tx,
            last_handshake_sec: 0,
            interface_up: sys_up,
        };
    };

    // dump: iface line then peer lines with last_handshake rx_bytes tx_bytes
    let mut rx = 0u64;
    let mut tx = 0u64;
    let mut handshake = 0i64;
    let mut up = false;
    let line_count = text.lines().count();
    for (i, line) in text.lines().enumerate() {
        let cols: Vec<&str> = line.split('\t').collect();
        if i == 0 {
            up = !cols.is_empty();
            continue;
        }
        if cols.len() >= 7 {
            handshake = cols[4].parse().unwrap_or(0);
            rx = cols[5].parse().unwrap_or(0);
            tx = cols[6].parse().unwrap_or(0);
        }
    }
    WgTransferStats {
        rx_bytes: if rx > 0 { rx } else { sys_rx },
        tx_bytes: if tx > 0 { tx } else { sys_tx },
        last_handshake_sec: handshake,
        interface_up: (up && line_count > 1) || sys_up,
    }
}

#[tauri::command]
async fn disconnect_wireguard(app: AppHandle) -> ConnectResult {
    // Stop the recovery watcher before teardown so we don't soft-reconnect
    // after the user intentionally disconnected.
    stop_network_switch_watcher();
    // Teardown can prompt for elevation — keep it off the UI thread.
    let result = tauri::async_runtime::spawn_blocking(move || bring_down_wireguard(&app))
        .await
        .unwrap_or_else(|e| Err(format!("disconnect task failed: {e}")));
    match result {
        Ok(msg) => ConnectResult {
            success: true,
            message: msg,
            mode: "wireguard".into(),
            peer_id: String::new(),
        },
        Err(e) => ConnectResult {
            success: false,
            message: e,
            mode: "wireguard".into(),
            peer_id: String::new(),
        },
    }
}

/// Soft recovery for Linux network switch (Phase 1–3). Exposed for diagnostics.
/// Prefer the background watcher; this is only for diagnostics and should
/// never be called from the UI poll loop.
#[tauri::command]
fn network_switch_recover() -> serde_json::Value {
    #[cfg(target_os = "linux")]
    {
        return serde_json::to_value(network_switch::recover_network_switch()).unwrap_or_else(|_| {
            serde_json::json!({"changed": false, "message": "recover failed", "action": "error"})
        });
    }
    #[cfg(not(target_os = "linux"))]
    {
        serde_json::json!({
            "changed": false,
            "message": "network switch recovery is Linux-only",
            "action": "noop"
        })
    }
}

#[derive(Debug, Serialize)]
pub struct RouteRefreshResult {
    pub refreshed: bool,
    pub gateway: String,
    pub message: String,
}

/// Soft path adapt for Linux: when the underlay default gateway changes
/// (Wi-Fi↔Wi-Fi, Ethernet↔Wi-Fi, LAN→WAN), refresh the pinned endpoint host
/// route without tearing down the tunnel or recreating the peer.
#[tauri::command]
fn refresh_endpoint_route() -> RouteRefreshResult {
    #[cfg(target_os = "linux")]
    {
        return refresh_endpoint_route_linux();
    }
    #[cfg(not(target_os = "linux"))]
    {
        RouteRefreshResult {
            refreshed: false,
            gateway: String::new(),
            message: "endpoint route refresh is Linux-only in this build".into(),
        }
    }
}

#[cfg(target_os = "linux")]
pub(crate) fn refresh_endpoint_route_linux() -> RouteRefreshResult {
    let iface_file = match iface_path() {
        Ok(p) => p,
        Err(e) => {
            return RouteRefreshResult {
                refreshed: false,
                gateway: String::new(),
                message: e,
            };
        }
    };
    let meta_file = PathBuf::from(format!("{}.meta", iface_file.display()));
    if !meta_file.exists() {
        return RouteRefreshResult {
            refreshed: false,
            gateway: String::new(),
            message: "not connected".into(),
        };
    }
    let meta = fs::read_to_string(&meta_file).unwrap_or_default();
    let mut endpoint_ip = String::new();
    let mut old_gw = String::new();
    let mut iface = String::new();
    for line in meta.lines() {
        if let Some((k, v)) = line.split_once('=') {
            match k.trim() {
                "endpoint_ip" => endpoint_ip = v.trim().to_string(),
                "gateway" => old_gw = v.trim().to_string(),
                "iface" => iface = v.trim().to_string(),
                _ => {}
            }
        }
    }
    if endpoint_ip.is_empty() || endpoint_ip == "127.0.0.1" {
        return RouteRefreshResult {
            refreshed: false,
            gateway: old_gw,
            message: "no endpoint host route to refresh".into(),
        };
    }

    // Prefer a unicast underlay default (skip blackhole kill-switch + tunnel).
    let detect = Command::new("bash")
        .arg("-c")
        .arg(format!(
            r#"
set -e
IFACE='{iface}'
# Unicast default via underlay (exclude blackhole + tunnel iface).
GW="$(ip -4 route show default | awk -v iface="$IFACE" '
  /blackhole/ {{ next }}
  /via/ {{
    for (i = 1; i <= NF; i++) if ($i == "dev") {{ d=$(i+1); break }}
    if (d == "" || d != iface) {{ print $3; exit }}
  }}
')"
GW_IF="$(ip -4 route show default | awk -v iface="$IFACE" '
  /blackhole/ {{ next }}
  /via/ {{
    for (i = 1; i <= NF; i++) if ($i == "dev") {{ d=$(i+1); break }}
    if (d == "" || d != iface) {{ print d; exit }}
  }}
')"
# Fallback: route to a public IP excluding the tunnel interface.
if [[ -z "$GW" ]]; then
  LINE="$(ip -4 route get 1.1.1.1 2>/dev/null | head -1 || true)"
  if [[ "$LINE" != *"dev $IFACE"* ]]; then
    GW="$(awk '{{for(i=1;i<=NF;i++) if($i=="via"){{print $(i+1); exit}}}}' <<<"$LINE")"
    GW_IF="$(awk '{{for(i=1;i<=NF;i++) if($i=="dev"){{print $(i+1); exit}}}}' <<<"$LINE")"
  fi
fi
printf '%s %s\n' "$GW" "$GW_IF"
"#,
            iface = iface.replace('\'', "'\\''")
        ))
        .output();
    let Ok(out) = detect else {
        return RouteRefreshResult {
            refreshed: false,
            gateway: old_gw,
            message: "could not detect underlay gateway".into(),
        };
    };
    let detected = String::from_utf8_lossy(&out.stdout);
    let mut parts = detected.split_whitespace();
    let new_gw = parts.next().unwrap_or("").to_string();
    let new_gw_if = parts.next().unwrap_or("").to_string();
    if new_gw.is_empty() {
        return RouteRefreshResult {
            refreshed: false,
            gateway: old_gw,
            message: "underlay gateway not ready yet".into(),
        };
    }
    if new_gw == old_gw {
        return RouteRefreshResult {
            refreshed: false,
            gateway: old_gw,
            message: "gateway unchanged".into(),
        };
    }

    let dir = match state_dir() {
        Ok(d) => d,
        Err(e) => {
            return RouteRefreshResult {
                refreshed: false,
                gateway: old_gw,
                message: e,
            };
        }
    };
    let script_path = dir.join("refresh-route.sh");
    let script = format!(
        r#"#!/usr/bin/env bash
set -euo pipefail
ENDPOINT_IP='{endpoint}'
NEW_GW='{gw}'
NEW_GW_IF='{gw_if}'
META_FILE='{meta}'
IFACE='{iface}'
# Replace the pinned endpoint host route for the new underlay.
ip route del "$ENDPOINT_IP" 2>/dev/null || true
if [[ -n "$NEW_GW_IF" ]]; then
  ip route replace "$ENDPOINT_IP" via "$NEW_GW" dev "$NEW_GW_IF"
else
  ip route replace "$ENDPOINT_IP" via "$NEW_GW"
fi
# Keep meta in sync so the next change detects correctly.
if [[ -f "$META_FILE" ]]; then
  tmp="$(mktemp)"
  awk -v gw="$NEW_GW" -v gif="$NEW_GW_IF" '
    BEGIN {{ updated_gw=0; updated_gif=0 }}
    /^gateway=/ {{ print "gateway=" gw; updated_gw=1; next }}
    /^gw_if=/ {{ print "gw_if=" gif; updated_gif=1; next }}
    {{ print }}
    END {{
      if (!updated_gw) print "gateway=" gw
      if (!updated_gif && gif != "") print "gw_if=" gif
    }}
  ' "$META_FILE" > "$tmp"
  mv "$tmp" "$META_FILE"
fi
echo "ok refreshed endpoint=$ENDPOINT_IP via=$NEW_GW dev=$NEW_GW_IF iface=$IFACE"
"#,
        endpoint = endpoint_ip.replace('\'', "'\\''"),
        gw = new_gw.replace('\'', "'\\''"),
        gw_if = new_gw_if.replace('\'', "'\\''"),
        meta = meta_file.display().to_string().replace('\'', "'\\''"),
        iface = iface.replace('\'', "'\\''"),
    );
    if let Err(e) = fs::write(&script_path, script) {
        return RouteRefreshResult {
            refreshed: false,
            gateway: old_gw,
            message: format!("write refresh script: {e}"),
        };
    }
    let _ = Command::new("chmod").args(["0700", &script_path.to_string_lossy()]).status();
    // Soft recovery / path adapt must never use interactive pkexec.
    // Soft path adapt only uses noninteractive elevation.
    match run_elevated_noninteractive(&script_path) {
        Ok(()) => RouteRefreshResult {
            refreshed: true,
            gateway: new_gw.clone(),
            message: format!("endpoint route moved {old_gw} → {new_gw}"),
        },
        Err(e) => RouteRefreshResult {
            refreshed: false,
            gateway: old_gw,
            message: e,
        },
    }
}

// ---------------------------------------------------------------------------
// Network-switch recovery helpers (Phase 1–3)
// ---------------------------------------------------------------------------

pub(crate) fn last_config_path() -> Result<PathBuf, String> {
    Ok(state_dir()?.join("last-config.json"))
}

pub(crate) fn write_last_config_json(dir: &Path, config: &network_switch::SavedTunnelConfig) -> Result<(), String> {
    let path = dir.join("last-config.json");
    let raw = serde_json::to_string(config)
        .map_err(|e| format!("serialize last-config: {e}"))?;
    fs::write(&path, raw).map_err(|e| format!("write last-config: {e}"))
}

pub(crate) fn reapply_dns_from_saved() -> Result<RouteRefreshResult, String> {
    // Re-apply DNS after underlay switch. Prefer the DNS value from last-config.
    // Soft recovery must never use interactive elevation — noninteractive only.
    let dns = load_dns_from_last_config().unwrap_or_default();
    if dns.is_empty() {
        return Ok(RouteRefreshResult {
            refreshed: false,
            gateway: String::new(),
            message: "no DNS in last-config".into(),
        });
    }

    let dir = state_dir().map_err(|e| e)?;
    let meta_file = dir.join("iface.meta");
    let mut gw_if = String::new();
    if meta_file.exists() {
        for line in fs::read_to_string(&meta_file).unwrap_or_default().lines() {
            if let Some((k, v)) = line.split_once('=') {
                if k.trim() == "gw_if" {
                    gw_if = v.trim().to_string();
                    break;
                }
            }
        }
    }
    // Prefer resolvectl on the underlay interface when available.
    if !gw_if.is_empty() {
        if let Ok(out) = Command::new("resolvectl")
            .args(["dns", &gw_if, &dns])
            .output()
        {
            if out.status.success() {
                return Ok(RouteRefreshResult {
                    refreshed: true,
                    gateway: gw_if.clone(),
                    message: format!("DNS re-applied via resolvectl on {gw_if}: {dns}"),
                });
            }
        }
    }
    // Fallback: write the DNS server into resolv.conf if writable.
    // Soft recovery must never hang on elevated — use noninteractive only.
    let script = format!(
        r#"#!/bin/bash
set -uo pipefail
if command -v resolvectl >/dev/null 2>&1; then
  GW_IF="{gw_if}"
  DNS="{dns}"
  if [[ -n "$GW_IF" ]]; then
    resolvectl dns "$GW_IF" "$DNS" 2>/dev/null || true
    resolvectl flush-caches 2>/dev/null || true
  fi
fi
"#,
        gw_if = gw_if.replace('\'', "'\\''"),
        dns = dns.replace('\'', "'\\''"),
    );
    let script_path = dir.join("reapply-dns.sh");
    if fs::write(&script_path, script).is_ok() {
        let _ = Command::new("chmod")
            .args(["0700", &script_path.to_string_lossy()])
            .status();
        // Soft recovery: noninteractive elevated only — never pkexec.
        if let Ok(()) = run_elevated_noninteractive(&script_path) {
            return Ok(RouteRefreshResult {
                refreshed: true,
                gateway: gw_if,
                message: format!("DNS re-applied: {dns}"),
            });
        }
    }
    Ok(RouteRefreshResult {
        refreshed: false,
        gateway: gw_if,
        message: "DNS re-apply failed".into(),
    })
}

fn load_dns_from_last_config() -> Option<String> {
    let path = match last_config_path() {
        Ok(p) => p,
        Err(_) => return None,
    };
    let raw = fs::read_to_string(&path).ok()?;
    let cfg: serde_json::Value = serde_json::from_str(&raw).ok()?;
    cfg.get("dns")
        .and_then(|v| v.as_str())
        .map(|s| s.to_string())
}

/// True if the tunnel is healthy enough for soft recovery to skip.
pub(crate) fn tunnel_is_healthy() -> Result<bool, String> {
    // Prefer wireguard_stats: if the interface is up and we have a recent
    // handshake, the tunnel is healthy. Soft recovery should not soft-reconnect.
    let stats = wireguard_stats_linux();
    if !stats.interface_up {
        return Ok(false);
    }
    // Soft recovery: require a recent handshake when available so we do not
    // soft-reconnect solely on a flaky public-egress probe.
    let now = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs() as i64)
        .unwrap_or(0);
    let handshake_ok = stats.last_handshake_sec > 0
        && now.saturating_sub(stats.last_handshake_sec) <= 180;
    if handshake_ok {
        return Ok(true);
    }
    // Soft recovery also needs public egress to work. If the interface is up
    // but kill switch is still blackholing clearnet (no handshake), soft
    // reconnect / kill-switch cleanup is required.
    if !public_egress_ok() {
        return Ok(false);
    }
    Ok(true)
}

fn public_egress_ok() -> bool {
    // Best-effort: if curl fails for a short window, recovery is needed.
    // Timeout is short so soft recovery never hangs the UI.
    let out = Command::new("curl")
        .args(["-fsS", "--max-time", "2", "https://api.ipify.org"])
        .output()
        .ok();
    match out {
        Some(o) if o.status.success() => true,
        _ => false,
    }
}

pub(crate) fn cleanup_kill_switch_noninteractive() -> Result<bool, String> {
    // Soft recovery must never hang the UI waiting on a password dialog.
    // Prefer passwordless sudo; if that fails, spawn detached pkexec so the
    // auth prompt appears once without freezing the app — restores clearnet.
    let script = r#"#!/bin/bash
set -uo pipefail
if command -v nft >/dev/null 2>&1; then
  nft delete table inet veritasvpn_killswitch 2>/dev/null || true
fi
if command -v iptables >/dev/null 2>&1; then
  while iptables -C OUTPUT -j VERITASVPN_KILLSWITCH 2>/dev/null; do
    iptables -D OUTPUT -j VERITASVPN_KILLSWITCH 2>/dev/null || break
  done
  iptables -F VERITASVPN_KILLSWITCH 2>/dev/null || true
  iptables -X VERITASVPN_KILLSWITCH 2>/dev/null || true
fi
if command -v ip6tables >/dev/null 2>&1; then
  while ip6tables -C OUTPUT -j VERITASVPN_KILLSWITCH_V6 2>/dev/null; do
    ip6tables -D OUTPUT -j VERITASVPN_KILLSWITCH_V6 2>/dev/null || break
  done
  ip6tables -F VERITASVPN_KILLSWITCH_V6 2>/dev/null || true
  ip6tables -X VERITASVPN_KILLSWITCH_V6 2>/dev/null || true
fi
ip route del blackhole default metric 1 2>/dev/null || true
ip -6 route del blackhole default metric 1 2>/dev/null || true
"#;
    let dir = state_dir()?;
    let script_path = dir.join("cleanup-killswitch-recover.sh");
    fs::write(&script_path, script).map_err(|e| format!("write cleanup: {e}"))?;
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        let mut perms = fs::metadata(&script_path)
            .map_err(|e| format!("stat cleanup: {e}"))?
            .permissions();
        perms.set_mode(0o700);
        fs::set_permissions(&script_path, perms).ok();
    }
    match run_elevated_noninteractive(&script_path) {
        Ok(()) => Ok(true),
        Err(_) => {
            // Best-effort non-elevated (usually fails for nft/iptables).
            let _ = Command::new("timeout")
                .args(["2", "bash", &script_path.to_string_lossy()])
                .output();
            // Detached pkexec: one auth prompt, UI stays responsive, clearnet
            // can come back after the user approves. Never wait on pkexec here.
            #[cfg(target_os = "linux")]
            {
                let _ = Command::new("pkexec")
                    .arg("bash")
                    .arg(&script_path)
                    .spawn();
            }
            Ok(true)
        }
    }
}

/// Soft reconnect using last-config.json (Phase 3).
/// Soft reconnect MUST never use interactive elevation (pkexec).
/// Soft reconnect is non-blocking and must never freeze the UI.
pub(crate) fn bring_up_from_saved_config_soft() -> Result<String, String> {
    let saved = network_switch::load_last_config().map_err(|e| e)?;
    let config = WgTunnelConfig {
        private_key: saved.private_key,
        address: saved.address,
        dns: saved.dns,
        server_public_key: saved.server_public_key,
        endpoint: saved.endpoint,
        allowed_ips: saved.allowed_ips,
        peer_id: saved.peer_id,
        preshared_key: saved.preshared_key,
        stealth_endpoint: saved.stealth_endpoint,
        stealth_path_prefix: saved.stealth_path_prefix,
    };
    // Soft reconnect: force noninteractive elevated only — never pkexec.
    // Soft reconnect is detached so soft recovery never freezes the watcher.
    // Soft reconnect is best-effort and may fail if passwordless sudo is
    // not available for soft recovery (user may need to reconnect).
    soft_reconnect_via_existing_bringup(&config)
}

/// Soft reconnect that reuses the normal bringup path but never falls back
/// to interactive pkexec. Soft reconnect is timed and non-blocking.
/// Soft reconnect is best-effort and never freezes the UI.
fn soft_reconnect_via_existing_bringup(config: &WgTunnelConfig) -> Result<String, String> {
    let app = get_app_handle_for_recover().ok_or_else(|| {
        String::from("no AppHandle for soft reconnect")
    })?;
    // Soft reconnect runs with noninteractive elevated only.
    // Soft reconnect is best-effort and may fail if passwordless sudo is
    // not available for soft recovery.
    // Soft recovery uses a dedicated soft mode so run_elevated never falls back
    // to interactive pkexec. Soft recovery never freezes the UI.
    let _guard = SoftElevatedGuard::enter();
    bring_up_wireguard(&app, config)
}

/// RAII guard so soft-elevated mode is always cleared.
struct SoftElevatedGuard;
impl SoftElevatedGuard {
    fn enter() -> Self {
        set_soft_elevated(true);
        SoftElevatedGuard
    }
}
impl Drop for SoftElevatedGuard {
    fn drop(&mut self) {
        set_soft_elevated(false);
    }
}

/// Soft-elevated mode: only for soft recovery. Soft recovery never freezes UI.
static SOFT_ELEVATED: std::sync::atomic::AtomicBool = std::sync::atomic::AtomicBool::new(false);

fn set_soft_elevated(on: bool) {
    SOFT_ELEVATED.store(on, std::sync::atomic::Ordering::SeqCst);
}

fn is_soft_elevated() -> bool {
    SOFT_ELEVATED.load(std::sync::atomic::Ordering::SeqCst)
}

fn get_app_handle_for_recover() -> Option<AppHandle> {
    APP_HANDLE_FOR_RECOVER.get().cloned()
}

// Stored when the first successful connect happens.
static APP_HANDLE_FOR_RECOVER: std::sync::OnceLock<AppHandle> = std::sync::OnceLock::new();

// ---------------------------------------------------------------------------

#[cfg(target_os = "macos")]
fn bring_up_wireguard(app: &AppHandle, config: &WgTunnelConfig) -> Result<String, String> {
    if !config.stealth_endpoint.trim().is_empty() {
        return Err("Stealth mode is available on Linux desktop in this build".into());
    }
    bring_up_wireguard_impl(app, config, build_bringup_script_macos)
}

#[cfg(target_os = "linux")]
fn bring_up_wireguard(app: &AppHandle, config: &WgTunnelConfig) -> Result<String, String> {
    bring_up_wireguard_linux_full(app, config)
}

fn bring_up_wireguard_impl(
    app: &AppHandle,
    config: &WgTunnelConfig,
    build_script: fn(
        wg_go: &Path,
        uapi_path: &Path,
        iface_file: &Path,
        pid_file: &Path,
        address: &str,
        dns: &str,
        endpoint: &str,
    ) -> String,
) -> Result<String, String> {
    let wg_go = resolve_wireguard_go(app)?;
    let dir = state_dir()?;
    let address = config
        .address
        .trim()
        .split('/')
        .next()
        .unwrap_or("")
        .to_string();
    if address.is_empty() {
        return Err("missing assigned address".into());
    }

    let priv_hex = b64_key_to_hex(&config.private_key)?;
    let pub_hex = b64_key_to_hex(&config.server_public_key)?;
    let endpoint = config.endpoint.trim().to_string();

    let allowed = if config.allowed_ips.is_empty() {
        vec!["0.0.0.0".into()]
    } else {
        config.allowed_ips.clone()
    };

    let mut uapi = format!(
        "set=1\nprivate_key={priv_hex}\nreplace_peers=true\npublic_key={pub_hex}\nendpoint={endpoint}\npersistent_keepalive_interval=25\n"
    );
    if !config.preshared_key.trim().is_empty() {
        let psk_hex = b64_key_to_hex(&config.preshared_key)?;
        uapi.push_str(&format!("preshared_key={psk_hex}\n"));
    }
    for ip in &allowed {
        uapi.push_str(&format!("allowed_ip={}\n", ip.trim()));
    }
    uapi.push('\n');

    let uapi_path = dir.join("uapi.txt");
    let script_path = dir.join("bringup.sh");
    let iface_file = iface_path()?;
    let pid_file = pid_path()?;

    fs::write(&uapi_path, &uapi).map_err(|e| format!("write uapi: {e}"))?;
    fs::write(
        conf_path()?,
        format!(
            "# VeritasVPN managed tunnel\n# endpoint {}\n# address {}\n",
            endpoint, config.address
        ),
    )
    .ok();
    fs::write(peer_id_path()?, config.peer_id.as_bytes()).ok();

    let script = build_script(
        &wg_go,
        &uapi_path,
        &iface_file,
        &pid_file,
        &address,
        if config.dns.trim().is_empty() {
            "1.1.1.1"
        } else {
            config.dns.trim()
        },
        &endpoint,
    );

    fs::write(&script_path, script).map_err(|e| format!("write script: {e}"))?;
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        let mut perms = fs::metadata(&script_path)
            .map_err(|e| format!("stat script: {e}"))?
            .permissions();
        perms.set_mode(0o700);
        fs::set_permissions(&script_path, perms).ok();
    }

    run_elevated(&script_path)?;
    Ok(format!("WireGuard connected via {endpoint}"))
}

#[cfg(target_os = "macos")]
fn build_bringup_script_macos(
    wg_go: &Path,
    uapi_path: &Path,
    iface_file: &Path,
    pid_file: &Path,
    address: &str,
    dns: &str,
    endpoint: &str,
) -> String {
    format!(
        r#"#!/bin/bash
set -uo pipefail
WG_GO='{wg_go}'
UAPI='{uapi}'
IFACE_FILE='{iface_file}'
PID_FILE='{pid_file}'
META_FILE='{iface_file}.meta'
DNS_BACKUP="${{META_FILE}}.dns"
DNS_PID_FILE="${{META_FILE}}.dns-proxy.pid"
ADDR='{address}'
DNS='{dns}'
ENDPOINT='{endpoint}'
trap 'rm -f "$UAPI"' EXIT

# --- tear down any previous Veritas tunnel (best-effort) ---
if [[ -f "$PID_FILE" ]]; then
  kill "$(cat "$PID_FILE")" 2>/dev/null || true
  rm -f "$PID_FILE"
fi
if [[ -f "$IFACE_FILE" ]]; then
  OLD="$(cat "$IFACE_FILE")"
  route -n delete -net 0.0.0.0/1 -interface "$OLD" 2>/dev/null || true
  route -n delete -net 128.0.0.0/1 -interface "$OLD" 2>/dev/null || true
  route -n delete -inet6 ::/1 ::1 2>/dev/null || true
  route -n delete -inet6 8000::/1 ::1 2>/dev/null || true
  ifconfig "$OLD" down 2>/dev/null || true
  rm -f "$IFACE_FILE"
fi
# Drop stale split-default routes even if iface file was lost
route -n delete -net 0.0.0.0/1 2>/dev/null || true
route -n delete -net 128.0.0.0/1 2>/dev/null || true
route -n delete -inet6 ::/1 ::1 2>/dev/null || true
route -n delete -inet6 8000::/1 ::1 2>/dev/null || true
pkill -f '/wireguard-go utun' 2>/dev/null || true
rm -f /var/run/wireguard/*.sock 2>/dev/null || true
if [[ -f "$DNS_PID_FILE" ]]; then
  kill "$(cat "$DNS_PID_FILE")" 2>/dev/null || true
  rm -f "$DNS_PID_FILE"
fi
ifconfig lo0 -alias 127.0.0.2 2>/dev/null || true

# Capture the REAL default gateway BEFORE we install tunnel routes.
# Without a host route to the WG endpoint via this gateway, 0.0.0.0/1
# blackholes WireGuard UDP itself and kills all internet.
GW="$(route -n get default 2>/dev/null | awk '/gateway: / {{print $2; exit}}')"
GW_IF="$(route -n get default 2>/dev/null | awk '/interface: / {{print $2; exit}}')"
ENDPOINT_HOST="${{ENDPOINT%%:*}}"
ENDPOINT_IP=""
if [[ -n "$ENDPOINT_HOST" ]]; then
  if [[ "$ENDPOINT_HOST" =~ ^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
    ENDPOINT_IP="$ENDPOINT_HOST"
  else
    ENDPOINT_IP="$(dscacheutil -q host -a name "$ENDPOINT_HOST" 2>/dev/null | awk '/ip_address: /{{print $2; exit}}')"
    if [[ -z "$ENDPOINT_IP" ]]; then
      ENDPOINT_IP="$(python3 -c "import socket; print(socket.gethostbyname('$ENDPOINT_HOST'))" 2>/dev/null || true)"
    fi
  fi
fi

"$WG_GO" utun >/tmp/veritas-wg-go.log 2>&1 &
echo $! > "$PID_FILE"
sleep 0.5

IFACE=""
for _ in $(seq 1 40); do
  for sock in /var/run/wireguard/*.sock; do
    [[ -e "$sock" ]] || continue
    IFACE="$(basename "$sock" .sock)"
    break 2
  done
  sleep 0.1
done
if [[ -z "$IFACE" ]]; then
  echo "failed to start WireGuard engine" >&2
  cat /tmp/veritas-wg-go.log >&2 || true
  exit 1
fi
echo "$IFACE" > "$IFACE_FILE"

python3 - "$IFACE" "$UAPI" <<'PY'
import socket, sys, pathlib
iface, uapi = sys.argv[1], sys.argv[2]
sock_path = f"/var/run/wireguard/{{iface}}.sock"
data = pathlib.Path(uapi).read_bytes()
s = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
s.connect(sock_path)
s.sendall(data)
s.shutdown(socket.SHUT_WR)
resp = s.recv(4096).decode("utf-8", "replace")
s.close()
if "errno=0" not in resp:
    sys.stderr.write(resp + "\n")
    sys.exit(1)
PY

ifconfig "$IFACE" inet "$ADDR" "$ADDR" netmask 255.255.255.255 up
# Product default MTU 1280 (reliability on mobile/hostile paths); see docs/MTU_STRATEGY.md
ifconfig "$IFACE" mtu 1280

# Prove that WireGuard is exchanging encrypted traffic before changing the
# machine-wide default route or DNS. A failed handshake must never take the
# user's normal internet connection down.
route -n delete -net 10.0.0.0/24 -interface "$IFACE" 2>/dev/null || true
route -n add -net 10.0.0.0/24 -interface "$IFACE"
if ! ping -c 3 -W 1000 10.0.0.1 >/tmp/veritas-wg-handshake.log 2>&1; then
	python3 - "$IFACE" >/tmp/veritas-wg-status.log 2>/dev/null <<'PY' || true
import socket, sys
s = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
s.connect(f"/var/run/wireguard/{{sys.argv[1]}}.sock")
s.sendall(b"get=1\n\n")
s.shutdown(socket.SHUT_WR)
data = s.recv(65536).decode("utf-8", "replace")
s.close()
safe = ("endpoint=", "last_handshake_time_sec=", "tx_bytes=", "rx_bytes=", "errno=")
print(" ".join(line for line in data.splitlines() if line.startswith(safe)))
PY
	WG_STATUS="$(cat /tmp/veritas-wg-status.log 2>/dev/null || true)"
  route -n delete -net 10.0.0.0/24 -interface "$IFACE" 2>/dev/null || true
  ifconfig "$IFACE" down 2>/dev/null || true
  kill "$(cat "$PID_FILE")" 2>/dev/null || true
  rm -f "$PID_FILE" "$IFACE_FILE" "$META_FILE" /var/run/wireguard/*.sock
	echo "VPN server did not respond at $ENDPOINT over UDP; normal internet was left unchanged. Check UDP 51820 forwarding/filtering. $WG_STATUS" >&2
  exit 1
fi

# Keep the VPN server reachable outside the tunnel.
if [[ -n "$ENDPOINT_IP" && -n "$GW" ]]; then
  route -n delete -host "$ENDPOINT_IP" 2>/dev/null || true
  route -n add -host "$ENDPOINT_IP" "$GW"
fi

# Split default (like wg-quick) so we don't replace the system default route entry.
route -n delete -net 0.0.0.0/1 -interface "$IFACE" 2>/dev/null || true
route -n delete -net 128.0.0.0/1 -interface "$IFACE" 2>/dev/null || true
route -n add -net 0.0.0.0/1 -interface "$IFACE"
route -n add -net 128.0.0.0/1 -interface "$IFACE"

# The current service is IPv4-only. Install two more-specific blackhole routes
# so IPv6 cannot bypass the VPN. Track these exact routes and remove only them
# during rollback/disconnect, leaving the user's normal IPv6 default intact.
if ! route -n add -inet6 -blackhole ::/1 ::1 2>/tmp/veritas-wg-killswitch-v6-error.log || \
   ! route -n add -inet6 -blackhole 8000::/1 ::1 2>>/tmp/veritas-wg-killswitch-v6-error.log; then
  route -n delete -inet6 ::/1 ::1 2>/dev/null || true
  route -n delete -inet6 8000::/1 ::1 2>/dev/null || true
  route -n delete -net 0.0.0.0/1 -interface "$IFACE" 2>/dev/null || true
  route -n delete -net 128.0.0.0/1 -interface "$IFACE" 2>/dev/null || true
  route -n delete -net 10.0.0.0/24 -interface "$IFACE" 2>/dev/null || true
  [[ -n "$ENDPOINT_IP" ]] && route -n delete -host "$ENDPOINT_IP" 2>/dev/null || true
  ifconfig "$IFACE" down 2>/dev/null || true
  kill "$(cat "$PID_FILE")" 2>/dev/null || true
  rm -f "$PID_FILE" "$IFACE_FILE" "$META_FILE" /var/run/wireguard/*.sock
  echo "Could not install the IPv6 VPN kill switch; normal internet was restored" >&2
  exit 1
fi

# Active network service for DNS (not "first listed").
SERVICE="$(networksetup -listnetworkserviceorder 2>/dev/null | awk -v iface="$GW_IF" '
  /^\([0-9]+\) / {{
    name=$0
    sub(/^\([0-9]+\) /, "", name)
  }}
  /Device: / {{
    dev=$0
    sub(/^.*Device: /, "", dev)
    sub(/\).*/, "", dev)
    if (iface != "" && dev == iface) {{ print name; exit }}
  }}
')"
if [[ -z "$SERVICE" ]]; then
  SERVICE="$(networksetup -listallnetworkservices 2>/dev/null | awk 'NR==2{{print; exit}}')"
fi
if [[ -z "$SERVICE" ]]; then SERVICE="Wi-Fi"; fi
networksetup -getdnsservers "$SERVICE" > "$DNS_BACKUP" 2>/dev/null || true
# Never preserve a resolver owned by a previous interrupted Veritas session.
if grep -Eq '^(10\.0\.0\.1|127\.0\.0\.[12])$' "$DNS_BACKUP" 2>/dev/null; then
  printf '%s\n' 'There are not any DNS Servers set on this service.' > "$DNS_BACKUP"
fi
# macOS scopes DNS configured on a physical service to that interface. A DNS
# server reached through a userspace utun would therefore be bypassed. Bind a
# loopback forwarder and let its upstream socket follow the VPN routing table.
ifconfig lo0 alias 127.0.0.2 255.255.255.255
python3 - "$DNS" >/tmp/veritas-dns-proxy.log 2>&1 <<'PY' &
import signal, socket, socketserver, struct, sys, threading

upstream = (sys.argv[1], 53)

def exchange(payload):
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.settimeout(5)
    try:
        sock.sendto(payload, upstream)
        return sock.recvfrom(65535)[0]
    finally:
        sock.close()

class UDP(socketserver.ThreadingUDPServer):
    allow_reuse_address = True
    daemon_threads = True

class UDPHandler(socketserver.BaseRequestHandler):
    def handle(self):
        data, client = self.request
        try:
            client.sendto(exchange(data), self.client_address)
        except OSError:
            pass

class TCP(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True

class TCPHandler(socketserver.BaseRequestHandler):
    def handle(self):
        header = self.request.recv(2)
        if len(header) != 2:
            return
        remaining = struct.unpack("!H", header)[0]
        chunks = []
        while remaining:
            chunk = self.request.recv(remaining)
            if not chunk:
                return
            chunks.append(chunk)
            remaining -= len(chunk)
        try:
            answer = exchange(b"".join(chunks))
            self.request.sendall(struct.pack("!H", len(answer)) + answer)
        except OSError:
            pass

udp = UDP(("127.0.0.2", 53), UDPHandler)
tcp = TCP(("127.0.0.2", 53), TCPHandler)
threading.Thread(target=udp.serve_forever, daemon=True).start()
threading.Thread(target=tcp.serve_forever, daemon=True).start()
signal.pause()
PY
echo $! > "$DNS_PID_FILE"
for _ in $(seq 1 30); do
  dig +time=1 +tries=1 @127.0.0.2 api.ipify.org A +short 2>/dev/null | \
    grep -Eq '^[0-9]+(\.[0-9]+){{3}}$' && break
  sleep 0.1
done
if ! kill -0 "$(cat "$DNS_PID_FILE")" 2>/dev/null || \
   ! networksetup -setdnsservers "$SERVICE" 127.0.0.2; then
  networksetup -setdnsservers "$SERVICE" Empty 2>/dev/null || true
  route -n delete -net 0.0.0.0/1 -interface "$IFACE" 2>/dev/null || true
  route -n delete -net 128.0.0.0/1 -interface "$IFACE" 2>/dev/null || true
  route -n delete -inet6 ::/1 ::1 2>/dev/null || true
  route -n delete -inet6 8000::/1 ::1 2>/dev/null || true
  route -n delete -net 10.0.0.0/24 -interface "$IFACE" 2>/dev/null || true
  [[ -n "$ENDPOINT_IP" ]] && route -n delete -host "$ENDPOINT_IP" 2>/dev/null || true
  ifconfig "$IFACE" down 2>/dev/null || true
  kill "$(cat "$DNS_PID_FILE")" 2>/dev/null || true
  kill "$(cat "$PID_FILE")" 2>/dev/null || true
  ifconfig lo0 -alias 127.0.0.2 2>/dev/null || true
  rm -f "$DNS_PID_FILE" "$PID_FILE" "$IFACE_FILE" "$META_FILE" "$DNS_BACKUP" /var/run/wireguard/*.sock
  dscacheutil -flushcache 2>/dev/null || true
  killall -HUP mDNSResponder 2>/dev/null || true
  echo "Could not configure VPN DNS on $SERVICE" >&2
  exit 1
fi
dscacheutil -flushcache 2>/dev/null || true
killall -HUP mDNSResponder 2>/dev/null || true

# Persist enough state for a reliable teardown even if the app crashes.
printf 'endpoint_ip=%s\ngateway=%s\nservice=%s\niface=%s\n' \
  "$ENDPOINT_IP" "$GW" "$SERVICE" "$IFACE" > "$META_FILE"

# Do not report Connected unless DNS and HTTPS both work. Keeping the checks
# separate makes failures actionable and prevents a false Connected UI.
DNS_OK=0
for _ in $(seq 1 20); do
  dscacheutil -q host -a name api.ipify.org \
    >/tmp/veritas-wg-dns.log 2>/tmp/veritas-wg-dns-error.log && \
    grep -q 'ip_address:' /tmp/veritas-wg-dns.log && DNS_OK=1 && break
  sleep 0.25
done
HTTPS_OK=0
if [[ "$DNS_OK" -eq 1 ]] && \
   /usr/bin/curl -4 -fsS --connect-timeout 5 --max-time 12 https://api.ipify.org \
     >/tmp/veritas-wg-egress.log 2>/tmp/veritas-wg-egress-error.log; then
  HTTPS_OK=1
fi
if [[ "$DNS_OK" -ne 1 || "$HTTPS_OK" -ne 1 ]]; then
  route -n delete -net 0.0.0.0/1 -interface "$IFACE" 2>/dev/null || true
  route -n delete -net 128.0.0.0/1 -interface "$IFACE" 2>/dev/null || true
  route -n delete -inet6 ::/1 ::1 2>/dev/null || true
  route -n delete -inet6 8000::/1 ::1 2>/dev/null || true
  route -n delete -net 10.0.0.0/24 -interface "$IFACE" 2>/dev/null || true
  [[ -n "$ENDPOINT_IP" ]] && route -n delete -host "$ENDPOINT_IP" 2>/dev/null || true
  route -n delete -host 1.1.1.1 2>/dev/null || true
  route -n delete -host 8.8.8.8 2>/dev/null || true
  DNS_VALUES=()
  while IFS= read -r VALUE; do
    [[ "$VALUE" =~ ^[0-9a-fA-F:.]+$ && "$VALUE" != "10.0.0.1" && "$VALUE" != "127.0.0.1" && "$VALUE" != "127.0.0.2" ]] && DNS_VALUES+=("$VALUE")
  done < "$DNS_BACKUP"
  if [[ ${{#DNS_VALUES[@]}} -gt 0 ]]; then
    networksetup -setdnsservers "$SERVICE" "${{DNS_VALUES[@]}}" 2>/dev/null || true
  else
    networksetup -setdnsservers "$SERVICE" Empty 2>/dev/null || true
  fi
  ifconfig "$IFACE" down 2>/dev/null || true
  kill "$(cat "$DNS_PID_FILE")" 2>/dev/null || true
  kill "$(cat "$PID_FILE")" 2>/dev/null || true
  ifconfig lo0 -alias 127.0.0.2 2>/dev/null || true
  rm -f "$DNS_PID_FILE" "$PID_FILE" "$IFACE_FILE" "$META_FILE" "$DNS_BACKUP" /var/run/wireguard/*.sock
  dscacheutil -flushcache 2>/dev/null || true
  killall -HUP mDNSResponder 2>/dev/null || true
  if [[ "$DNS_OK" -ne 1 ]]; then
    echo "VPN DNS validation failed; normal internet was restored" >&2
  else
    echo "VPN internet egress validation failed; normal internet was restored" >&2
  fi
  exit 1
fi

echo "ok iface=$IFACE endpoint_ip=$ENDPOINT_IP gw=$GW"
"#,
        wg_go = wg_go.display(),
        uapi = uapi_path.display(),
        iface_file = iface_file.display(),
        pid_file = pid_file.display(),
        address = address,
        dns = dns,
        endpoint = endpoint,
    )
}

#[cfg(target_os = "linux")]
fn bring_up_wireguard_linux_full(app: &AppHandle, config: &WgTunnelConfig) -> Result<String, String> {
    let wg_go = resolve_wireguard_go(app)?;
    let stealth_remote = config.stealth_endpoint.trim().to_string();
    let stealth_prefix = config.stealth_path_prefix.trim().to_string();
    let wstunnel = if stealth_remote.is_empty() {
        PathBuf::new()
    } else {
        if stealth_prefix.is_empty() {
            return Err("Stealth mode enabled but server did not provide a path prefix".into());
        }
        resolve_wstunnel(app)?
    };

    let dir = state_dir()?;
    let address = config
        .address
        .trim()
        .split('/')
        .next()
        .unwrap_or("")
        .to_string();
    if address.is_empty() {
        return Err("missing assigned address".into());
    }

    let priv_hex = b64_key_to_hex(&config.private_key)?;
    let pub_hex = b64_key_to_hex(&config.server_public_key)?;
    let endpoint = if stealth_remote.is_empty() {
        config.endpoint.trim().to_string()
    } else {
        "127.0.0.1:41820".into()
    };

    let allowed = if config.allowed_ips.is_empty() {
        vec!["0.0.0.0".into()]
    } else {
        config.allowed_ips.clone()
    };

    let mut uapi = format!(
        "set=1\nprivate_key={priv_hex}\nreplace_peers=true\npublic_key={pub_hex}\nendpoint={endpoint}\npersistent_keepalive_interval=25\n"
    );
    if !config.preshared_key.trim().is_empty() {
        let psk_hex = b64_key_to_hex(&config.preshared_key)?;
        uapi.push_str(&format!("preshared_key={psk_hex}\n"));
    }
    for ip in &allowed {
        uapi.push_str(&format!("allowed_ip={}\n", ip.trim()));
    }
    uapi.push('\n');

    // Kernel `wg setconf` wants base64 keys (same form the API returns).
    let allowed_joined = allowed
        .iter()
        .map(|s| s.trim())
        .filter(|s| !s.is_empty())
        .collect::<Vec<_>>()
        .join(", ");
    let mut wg_conf = format!(
        "[Interface]\nPrivateKey = {}\n\n[Peer]\nPublicKey = {}\nEndpoint = {}\nPersistentKeepalive = 25\nAllowedIPs = {allowed_joined}\n",
        config.private_key.trim(),
        config.server_public_key.trim(),
        endpoint
    );
    if !config.preshared_key.trim().is_empty() {
        wg_conf.push_str(&format!(
            "PresharedKey = {}\n",
            config.preshared_key.trim()
        ));
    }

    let uapi_path = dir.join("uapi.txt");
    let wg_conf_path = dir.join("wg.conf");
    let script_path = dir.join("bringup.sh");
    let iface_file = iface_path()?;
    let pid_file = pid_path()?;
    let stealth_pid = stealth_pid_path()?;

    fs::write(&uapi_path, &uapi).map_err(|e| format!("write uapi: {e}"))?;
    fs::write(&wg_conf_path, &wg_conf).map_err(|e| format!("write wg.conf: {e}"))?;
    fs::write(
        conf_path()?,
        format!(
            "# VeritasVPN managed tunnel\n# endpoint {}\n# stealth {}\n# address {}\n",
            endpoint, stealth_remote, config.address
        ),
    )
    .ok();
    fs::write(peer_id_path()?, config.peer_id.as_bytes()).ok();

    let script = build_bringup_script_linux(
        &wg_go,
        &uapi_path,
        &wg_conf_path,
        &iface_file,
        &pid_file,
        &address,
        if config.dns.trim().is_empty() {
            "1.1.1.1"
        } else {
            config.dns.trim()
        },
        &endpoint,
        &stealth_remote,
        &wstunnel,
        &stealth_prefix,
        &stealth_pid,
    );

    fs::write(&script_path, script).map_err(|e| format!("write script: {e}"))?;
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        let mut perms = fs::metadata(&script_path)
            .map_err(|e| format!("stat script: {e}"))?
            .permissions();
        perms.set_mode(0o700);
        fs::set_permissions(&script_path, perms).ok();
    }

    // Soft recovery never freezes the UI — never fall back to interactive pkexec.
    if is_soft_elevated() {
        run_elevated_noninteractive(&script_path)?;
    } else {
        run_elevated(&script_path)?;
    }
    if stealth_remote.is_empty() {
        Ok(format!("WireGuard connected via {endpoint}"))
    } else {
        Ok(format!("WireGuard connected via stealth {stealth_remote}"))
    }
}

#[cfg(target_os = "linux")]
fn build_bringup_script_linux(
    wg_go: &Path,
    uapi_path: &Path,
    wg_conf_path: &Path,
    iface_file: &Path,
    pid_file: &Path,
    address: &str,
    dns: &str,
    endpoint: &str,
    stealth_remote: &str,
    wstunnel: &Path,
    stealth_prefix: &str,
    stealth_pid: &Path,
) -> String {
    format!(
        r#"#!/bin/bash
set -uo pipefail
WG_GO='{wg_go}'
UAPI='{uapi}'
WG_CONF='{wg_conf}'
IFACE_FILE='{iface_file}'
PID_FILE='{pid_file}'
STEALTH_PID_FILE='{stealth_pid}'
WSTUNNEL='{wstunnel}'
STEALTH_REMOTE='{stealth_remote}'
STEALTH_PREFIX='{stealth_prefix}'
META_FILE='{iface_file}.meta'
DNS_BACKUP="${{META_FILE}}.dns"
ADDR='{address}'
DNS='{dns}'
ENDPOINT='{endpoint}'
IFACE_NAME="veritas0"
ENDPOINT_PORT="${{ENDPOINT##*:}}"
KILLSWITCH_TABLE="veritasvpn_killswitch"
KILLSWITCH_CHAIN="VERITASVPN_KILLSWITCH"
IFACE=""
ENGINE=""

cleanup_killswitch() {{
  if command -v nft >/dev/null 2>&1; then
    nft delete table inet "$KILLSWITCH_TABLE" 2>/dev/null || true
  fi
  if command -v iptables >/dev/null 2>&1; then
    while iptables -C OUTPUT -j "$KILLSWITCH_CHAIN" 2>/dev/null; do
      iptables -D OUTPUT -j "$KILLSWITCH_CHAIN" 2>/dev/null || break
    done
    iptables -F "$KILLSWITCH_CHAIN" 2>/dev/null || true
    iptables -X "$KILLSWITCH_CHAIN" 2>/dev/null || true
  fi
  if command -v ip6tables >/dev/null 2>&1; then
    while ip6tables -C OUTPUT -j "${{KILLSWITCH_CHAIN}}_V6" 2>/dev/null; do
      ip6tables -D OUTPUT -j "${{KILLSWITCH_CHAIN}}_V6" 2>/dev/null || break
    done
    ip6tables -F "${{KILLSWITCH_CHAIN}}_V6" 2>/dev/null || true
    ip6tables -X "${{KILLSWITCH_CHAIN}}_V6" 2>/dev/null || true
  fi
}}

install_killswitch() {{
  cleanup_killswitch
  if command -v nft >/dev/null 2>&1; then
    nft add table inet "$KILLSWITCH_TABLE" || return 1
    nft "add chain inet $KILLSWITCH_TABLE output {{ type filter hook output priority -5; policy accept; }}" || return 1
    nft add rule inet "$KILLSWITCH_TABLE" output oifname "lo" accept || return 1
    nft add rule inet "$KILLSWITCH_TABLE" output oifname "$IFACE_NAME" accept || return 1
    if [[ -n "$ROUTE_IP" && -n "$ROUTE_PORT" ]]; then
      if [[ -n "$STEALTH_REMOTE" ]]; then
        nft add rule inet "$KILLSWITCH_TABLE" output oifname != "$IFACE_NAME" oifname != "lo" ip daddr "$ROUTE_IP" tcp dport "$ROUTE_PORT" accept || return 1
      else
        nft add rule inet "$KILLSWITCH_TABLE" output oifname != "$IFACE_NAME" oifname != "lo" ip daddr "$ROUTE_IP" udp dport "$ROUTE_PORT" accept || return 1
      fi
    fi
    nft add rule inet "$KILLSWITCH_TABLE" output oifname != "$IFACE_NAME" oifname != "lo" drop || return 1
    nft add rule inet "$KILLSWITCH_TABLE" output meta nfproto ipv6 oifname "lo" accept || return 1
    nft add rule inet "$KILLSWITCH_TABLE" output meta nfproto ipv6 oifname "$IFACE_NAME" accept || return 1
    nft add rule inet "$KILLSWITCH_TABLE" output meta nfproto ipv6 drop || return 1
    return 0
  fi
  if command -v iptables >/dev/null 2>&1; then
    iptables -N "$KILLSWITCH_CHAIN" 2>/dev/null || true
    iptables -F "$KILLSWITCH_CHAIN" || return 1
    iptables -C OUTPUT -j "$KILLSWITCH_CHAIN" 2>/dev/null || iptables -I OUTPUT 1 -j "$KILLSWITCH_CHAIN" || return 1
    iptables -A "$KILLSWITCH_CHAIN" -o lo -j ACCEPT || return 1
    iptables -A "$KILLSWITCH_CHAIN" -o "$IFACE_NAME" -j ACCEPT || return 1
    if [[ -n "$ROUTE_IP" && -n "$ROUTE_PORT" ]]; then
      if [[ -n "$STEALTH_REMOTE" ]]; then
        iptables -A "$KILLSWITCH_CHAIN" -d "$ROUTE_IP" -p tcp --dport "$ROUTE_PORT" -j ACCEPT || return 1
      else
        iptables -A "$KILLSWITCH_CHAIN" -d "$ROUTE_IP" -p udp --dport "$ROUTE_PORT" -j ACCEPT || return 1
      fi
    fi
    iptables -A "$KILLSWITCH_CHAIN" -j DROP || return 1
    if command -v ip6tables >/dev/null 2>&1; then
      ip6tables -N "${{KILLSWITCH_CHAIN}}_V6" 2>/dev/null || true
      ip6tables -F "${{KILLSWITCH_CHAIN}}_V6" || return 1
      ip6tables -C OUTPUT -j "${{KILLSWITCH_CHAIN}}_V6" 2>/dev/null || ip6tables -I OUTPUT 1 -j "${{KILLSWITCH_CHAIN}}_V6" || return 1
      ip6tables -A "${{KILLSWITCH_CHAIN}}_V6" -o lo -j ACCEPT || return 1
      ip6tables -A "${{KILLSWITCH_CHAIN}}_V6" -o "$IFACE_NAME" -j ACCEPT || return 1
      ip6tables -A "${{KILLSWITCH_CHAIN}}_V6" -j DROP || return 1
    fi
    return 0
  fi
  return 1
}}

trap 'rm -f "$UAPI"' EXIT

# --- tear down any previous Veritas tunnel (best-effort) ---
cleanup_killswitch
ip -6 route del blackhole default metric 1 2>/dev/null || true
if [[ -f "$PID_FILE" ]]; then
  kill "$(cat "$PID_FILE")" 2>/dev/null || true
  rm -f "$PID_FILE"
fi
if [[ -f "$STEALTH_PID_FILE" ]]; then
  kill "$(cat "$STEALTH_PID_FILE")" 2>/dev/null || true
  rm -f "$STEALTH_PID_FILE"
fi
pkill -f '/wstunnel client' 2>/dev/null || true
if [[ -f "$IFACE_FILE" ]]; then
  OLD="$(cat "$IFACE_FILE")"
  ip route del 0.0.0.0/1 dev "$OLD" 2>/dev/null || true
  ip route del 128.0.0.0/1 dev "$OLD" 2>/dev/null || true
  ip link set "$OLD" down 2>/dev/null || true
  rm -f "$IFACE_FILE"
fi
ip route del 0.0.0.0/1 2>/dev/null || true
ip route del 128.0.0.0/1 2>/dev/null || true
# Remove only the dedicated Veritas kill-switch route left by an interrupted session.
ip route del blackhole default metric 1 2>/dev/null || true
pkill -f 'wireguard-go veritas0' 2>/dev/null || true
rm -f /var/run/wireguard/*.sock 2>/dev/null || true

# Find the default gateway before adding tunnel routes
GW="$(ip route show default | awk '/default via/ {{print $3; exit}}')"
GW_IF="$(ip route show default | awk '/dev/ {{print $5; exit}}')"

# Host/port used for the outside-tunnel exception (UDP direct or TCP stealth).
if [[ -n "$STEALTH_REMOTE" ]]; then
  ROUTE_HOST="${{STEALTH_REMOTE%%:*}}"
  ROUTE_PORT="${{STEALTH_REMOTE##*:}}"
else
  ROUTE_HOST="${{ENDPOINT%%:*}}"
  ROUTE_PORT="${{ENDPOINT##*:}}"
fi
ROUTE_IP=""
if [[ -n "$ROUTE_HOST" ]]; then
  if [[ "$ROUTE_HOST" =~ ^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
    ROUTE_IP="$ROUTE_HOST"
  else
    ROUTE_IP="$(dig +short "$ROUTE_HOST" 2>/dev/null | head -1 || true)"
    if [[ -z "$ROUTE_IP" ]]; then
      ROUTE_IP="$(getent hosts "$ROUTE_HOST" 2>/dev/null | awk '{{print $1; exit}}' || true)"
    fi
  fi
fi
ENDPOINT_IP="$ROUTE_IP"

# Optional stealth sidecar: local UDP → TLS/WebSocket → server WG
if [[ -n "$STEALTH_REMOTE" ]]; then
  if [[ ! -x "$WSTUNNEL" ]]; then
    echo "stealth engine missing: $WSTUNNEL" >&2
    exit 1
  fi
  "$WSTUNNEL" client \
    --http-upgrade-path-prefix "$STEALTH_PREFIX" \
    -L "udp://127.0.0.1:41820:127.0.0.1:51820?timeout_sec=0" \
    "wss://${{STEALTH_REMOTE}}" \
    >/tmp/veritas-wstunnel.log 2>&1 &
  echo $! > "$STEALTH_PID_FILE"
  sleep 0.4
  if ! kill -0 "$(cat "$STEALTH_PID_FILE")" 2>/dev/null; then
    echo "failed to start stealth transport" >&2
    cat /tmp/veritas-wstunnel.log >&2 || true
    exit 1
  fi
fi

# Start WireGuard: prefer in-kernel (stable under pkexec). wireguard-go's
# self-daemonize can drop the UAPI socket before we configure it, which
# produced FileNotFoundError + "Cannot find device veritas0" for users.
ip link delete "$IFACE_NAME" 2>/dev/null || true
if command -v wg >/dev/null 2>&1 && ip link add "$IFACE_NAME" type wireguard 2>/tmp/veritas-wg-kernel.log; then
  ENGINE=kernel
  IFACE="$IFACE_NAME"
  if ! wg setconf "$IFACE_NAME" "$WG_CONF" 2>>/tmp/veritas-wg-kernel.log; then
    ip link delete "$IFACE_NAME" 2>/dev/null || true
    echo "failed to configure kernel WireGuard" >&2
    cat /tmp/veritas-wg-kernel.log >&2 || true
    [[ -f "$STEALTH_PID_FILE" ]] && kill "$(cat "$STEALTH_PID_FILE")" 2>/dev/null || true
    exit 1
  fi
else
  ENGINE=userspace
  : > /tmp/veritas-wg-go.log
  # -f keeps the process in the foreground so nohup owns a stable PID/socket.
  nohup "$WG_GO" -f "$IFACE_NAME" >>/tmp/veritas-wg-go.log 2>&1 &
  echo $! > "$PID_FILE"
  sleep 0.3
  for _ in $(seq 1 50); do
    if [[ -S "/var/run/wireguard/${{IFACE_NAME}}.sock" ]]; then
      IFACE="$IFACE_NAME"
      break
    fi
    if [[ -f "$PID_FILE" ]] && ! kill -0 "$(cat "$PID_FILE")" 2>/dev/null; then
      break
    fi
    sleep 0.1
  done
  if [[ -z "$IFACE" ]]; then
    echo "failed to start WireGuard engine" >&2
    cat /tmp/veritas-wg-go.log >&2 || true
    cat /tmp/veritas-wg-kernel.log >&2 || true
    [[ -f "$STEALTH_PID_FILE" ]] && kill "$(cat "$STEALTH_PID_FILE")" 2>/dev/null || true
    exit 1
  fi

  # Desktop UI polls `wg show` unprivileged. wireguard-go's UAPI socket is
  # root-only by default, which made LIVE STATS show 0 B / Handshake never.
  DESKTOP_USER="${{SUDO_USER:-}}"
  if [[ -z "$DESKTOP_USER" && -n "${{PKEXEC_UID:-}}" ]]; then
    DESKTOP_USER="$(getent passwd "$PKEXEC_UID" | cut -d: -f1 || true)"
  fi
  if [[ -z "$DESKTOP_USER" ]]; then
    DESKTOP_USER="$(stat -c '%U' "$(dirname "$IFACE_FILE")" 2>/dev/null || true)"
  fi
  if [[ -n "$DESKTOP_USER" && -S "/var/run/wireguard/${{IFACE}}.sock" ]]; then
    chown "$DESKTOP_USER:" "/var/run/wireguard/${{IFACE}}.sock" 2>/dev/null || true
    chmod 600 "/var/run/wireguard/${{IFACE}}.sock" 2>/dev/null || true
  fi

  if ! python3 - "$IFACE" "$UAPI" <<'PY'
import socket, sys, pathlib
iface, uapi = sys.argv[1], sys.argv[2]
sock_path = f"/var/run/wireguard/{{iface}}.sock"
data = pathlib.Path(uapi).read_bytes()
s = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
s.connect(sock_path)
s.sendall(data)
s.shutdown(socket.SHUT_WR)
resp = s.recv(4096).decode("utf-8", "replace")
s.close()
if "errno=0" not in resp:
    sys.stderr.write(resp + "\n")
    sys.exit(1)
PY
  then
    echo "failed to configure userspace WireGuard" >&2
    kill "$(cat "$PID_FILE")" 2>/dev/null || true
    [[ -f "$STEALTH_PID_FILE" ]] && kill "$(cat "$STEALTH_PID_FILE")" 2>/dev/null || true
    rm -f "$PID_FILE" "$IFACE_FILE" /var/run/wireguard/*.sock
    ip link delete "$IFACE_NAME" 2>/dev/null || true
    exit 1
  fi
fi
echo "$IFACE_NAME" > "$IFACE_FILE"

ip addr add "$ADDR" dev "$IFACE_NAME"
ip link set "$IFACE_NAME" up
# Product default MTU 1280 (reliability on mobile/hostile paths); see docs/MTU_STRATEGY.md
ip link set "$IFACE_NAME" mtu 1280

# Prove WireGuard handshake before changing system routes
ip route add 10.0.0.0/24 dev "$IFACE_NAME" 2>/dev/null
if ! ping -c 3 -W 1 10.0.0.1 >/tmp/veritas-wg-handshake.log 2>&1; then
  ip route del 10.0.0.0/24 dev "$IFACE_NAME" 2>/dev/null || true
  ip link set "$IFACE_NAME" down 2>/dev/null || true
  kill "$(cat "$PID_FILE")" 2>/dev/null || true
  [[ -f "$STEALTH_PID_FILE" ]] && kill "$(cat "$STEALTH_PID_FILE")" 2>/dev/null || true
  ip link delete "$IFACE_NAME" 2>/dev/null || true
  rm -f "$PID_FILE" "$STEALTH_PID_FILE" "$IFACE_FILE" "$META_FILE" /var/run/wireguard/*.sock
  echo "WireGuard handshake with the VPN server failed; normal internet was left unchanged" >&2
  exit 1
fi

# Host route so VPN transport stays reachable outside the tunnel
if [[ -n "$ROUTE_IP" && -n "$GW" && "$ROUTE_IP" != "127.0.0.1" ]]; then
  ip route del "$ROUTE_IP" 2>/dev/null || true
  ip route add "$ROUTE_IP" via "$GW" 2>/dev/null || true
fi

# Split default via the tunnel
ip route del 0.0.0.0/1 dev "$IFACE_NAME" 2>/dev/null || true
ip route del 128.0.0.0/1 dev "$IFACE_NAME" 2>/dev/null || true
ip route add 0.0.0.0/1 dev "$IFACE_NAME"
ip route add 128.0.0.0/1 dev "$IFACE_NAME"

# Linux kill switch: keep a dedicated fail-closed default route beneath the
# tunnel's /1 routes. If WireGuard disappears or the split routes are removed,
# traffic hits this blackhole instead of falling back to the normal gateway.
if ! ip route replace blackhole default metric 1 2>/tmp/veritas-wg-killswitch-error.log; then
  ip route del 0.0.0.0/1 dev "$IFACE_NAME" 2>/dev/null || true
  ip route del 128.0.0.0/1 dev "$IFACE_NAME" 2>/dev/null || true
  ip route del 10.0.0.0/24 dev "$IFACE_NAME" 2>/dev/null || true
  [[ -n "$ROUTE_IP" && "$ROUTE_IP" != "127.0.0.1" ]] && ip route del "$ROUTE_IP" 2>/dev/null || true
  ip link set "$IFACE_NAME" down 2>/dev/null || true
  kill -9 "$(cat "$PID_FILE")" 2>/dev/null || true
  [[ -f "$STEALTH_PID_FILE" ]] && kill -9 "$(cat "$STEALTH_PID_FILE")" 2>/dev/null || true
  rm -f "$PID_FILE" "$STEALTH_PID_FILE" "$IFACE_FILE" "$META_FILE" /var/run/wireguard/*.sock
  echo "Could not install the VPN kill switch; normal internet was left unchanged" >&2
  cat /tmp/veritas-wg-killswitch-error.log >&2 || true
  exit 1
fi
# If IPv6 is enabled, require the same fail-closed default route safeguard.
if ! ip -6 route replace blackhole default metric 1 2>/tmp/veritas-wg-killswitch-v6-error.log; then
  cleanup_killswitch
  ip route del 0.0.0.0/1 dev "$IFACE_NAME" 2>/dev/null || true
  ip route del 128.0.0.0/1 dev "$IFACE_NAME" 2>/dev/null || true
  ip route del blackhole default metric 1 2>/dev/null || true
  ip route del 10.0.0.0/24 dev "$IFACE_NAME" 2>/dev/null || true
  [[ -n "$ROUTE_IP" && "$ROUTE_IP" != "127.0.0.1" ]] && ip route del "$ROUTE_IP" 2>/dev/null || true
  ip link set "$IFACE_NAME" down 2>/dev/null || true
  kill -9 "$(cat "$PID_FILE")" 2>/dev/null || true
  [[ -f "$STEALTH_PID_FILE" ]] && kill -9 "$(cat "$STEALTH_PID_FILE")" 2>/dev/null || true
  rm -f "$PID_FILE" "$STEALTH_PID_FILE" "$IFACE_FILE" "$META_FILE" /var/run/wireguard/*.sock
  echo "Could not install the IPv6 VPN kill switch; normal internet was left unchanged" >&2
  cat /tmp/veritas-wg-killswitch-v6-error.log >&2 || true
  exit 1
fi

# Firewall kill switch is mandatory (no off option; parity with Android lockdown).
# Blackhole routes alone are not enough — abort if nftables/iptables rules cannot be installed.
if ! install_killswitch; then
  cleanup_killswitch
  ip route del 0.0.0.0/1 dev "$IFACE_NAME" 2>/dev/null || true
  ip route del 128.0.0.0/1 dev "$IFACE_NAME" 2>/dev/null || true
  ip route del blackhole default metric 1 2>/dev/null || true
  ip -6 route del blackhole default metric 1 2>/dev/null || true
  ip route del 10.0.0.0/24 dev "$IFACE_NAME" 2>/dev/null || true
  [[ -n "$ROUTE_IP" && "$ROUTE_IP" != "127.0.0.1" ]] && ip route del "$ROUTE_IP" 2>/dev/null || true
  ip link set "$IFACE_NAME" down 2>/dev/null || true
  kill -9 "$(cat "$PID_FILE")" 2>/dev/null || true
  [[ -f "$STEALTH_PID_FILE" ]] && kill -9 "$(cat "$STEALTH_PID_FILE")" 2>/dev/null || true
  ip link delete "$IFACE_NAME" 2>/dev/null || true
  rm -f "$PID_FILE" "$STEALTH_PID_FILE" "$IFACE_FILE" "$META_FILE" /var/run/wireguard/*.sock
  echo "Could not install the firewall kill switch; install nftables or iptables and try again. Normal internet was left unchanged" >&2
  exit 1
fi

# DNS: backup resolv.conf and set new DNS
cp /etc/resolv.conf "$DNS_BACKUP" 2>/dev/null || true
if [[ -w /etc/resolv.conf ]]; then
  echo "nameserver $DNS" > /etc/resolv.conf
elif command -v resolvectl >/dev/null 2>&1; then
  resolvectl dns "$GW_IF" "$DNS" 2>/dev/null || true
  resolvectl flush-caches 2>/dev/null || true
fi

# Persist state for teardown
printf 'endpoint_ip=%s\ngateway=%s\niface=%s\ngw_if=%s\nstealth_remote=%s\nengine=%s\n' \
  "$ROUTE_IP" "$GW" "$IFACE_NAME" "$GW_IF" "$STEALTH_REMOTE" "$ENGINE" > "$META_FILE"

restore_after_validation_fail() {{
  local msg="$1"
  cleanup_killswitch
  ip route del 0.0.0.0/1 dev "$IFACE_NAME" 2>/dev/null || true
  ip route del 128.0.0.0/1 dev "$IFACE_NAME" 2>/dev/null || true
  ip route del blackhole default metric 1 2>/dev/null || true
  ip -6 route del blackhole default metric 1 2>/dev/null || true
  ip route del 10.0.0.0/24 dev "$IFACE_NAME" 2>/dev/null || true
  [[ -n "$ROUTE_IP" && "$ROUTE_IP" != "127.0.0.1" ]] && ip route del "$ROUTE_IP" 2>/dev/null || true
  if [[ -f "$DNS_BACKUP" ]]; then
    cat "$DNS_BACKUP" > /etc/resolv.conf 2>/dev/null || true
  fi
  if [[ -n "$GW_IF" ]] && command -v resolvectl >/dev/null 2>&1; then
    resolvectl revert "$GW_IF" 2>/dev/null || true
  fi
  rm -f "$DNS_BACKUP"
  ip link set "$IFACE_NAME" down 2>/dev/null || true
  kill -9 "$(cat "$PID_FILE")" 2>/dev/null || true
  [[ -f "$STEALTH_PID_FILE" ]] && kill -9 "$(cat "$STEALTH_PID_FILE")" 2>/dev/null || true
  ip link delete "$IFACE_NAME" 2>/dev/null || true
  rm -f "$PID_FILE" "$STEALTH_PID_FILE" "$IFACE_FILE" "$META_FILE" /var/run/wireguard/*.sock
  echo "$msg" >&2
  exit 1
}}

# Do not report Connected unless tunnel DNS filtering and HTTPS egress both work.
DNS_OK=0
for _ in $(seq 1 20); do
  # Built-in agent test domain must NXDOMAIN through the protected gateway.
  if command -v dig >/dev/null 2>&1; then
    STATUS="$(dig +time=1 +tries=1 @"$DNS" dns-protection-test.veritasvpn.invalid A 2>/dev/null | awk '/status:/{{print $6}}' | tr -d ',')"
    if [[ "$STATUS" == "NXDOMAIN" ]]; then
      DNS_OK=1
      break
    fi
  elif getent hosts api.ipify.org >/tmp/veritas-wg-dns.log 2>/tmp/veritas-wg-dns-error.log; then
    DNS_OK=1
    break
  fi
  sleep 0.25
done
if [[ "$DNS_OK" -ne 1 ]]; then
  restore_after_validation_fail "VPN DNS validation failed; normal internet was restored"
fi

if ! curl -4 -fsS --connect-timeout 5 --max-time 12 https://api.ipify.org \
     >/tmp/veritas-wg-egress.log 2>/tmp/veritas-wg-egress-error.log; then
  restore_after_validation_fail "VPN internet egress validation failed; normal internet was restored"
fi

# Install passwordless sudo was removed: never NOPASSWD user-writable
# ~/.veritasvpn/*.sh (local replace → root). Future connects use pkexec.
# A root-owned binary helper may restore passwordless connect later.

echo "ok iface=$IFACE_NAME endpoint_ip=$ROUTE_IP stealth=${{STEALTH_REMOTE:-off}} gw=$GW engine=$ENGINE"
"#,
        wg_go = wg_go.display(),
        uapi = uapi_path.display(),
        wg_conf = wg_conf_path.display(),
        iface_file = iface_file.display(),
        pid_file = pid_file.display(),
        stealth_pid = stealth_pid.display(),
        wstunnel = wstunnel.display(),
        stealth_remote = stealth_remote,
        stealth_prefix = stealth_prefix,
        address = address,
        dns = dns,
        endpoint = endpoint,
    )
}

#[cfg(target_os = "macos")]
fn bring_down_wireguard(_app: &AppHandle) -> Result<String, String> {
    bring_down_wireguard_macos(_app)
}

#[cfg(target_os = "linux")]
fn bring_down_wireguard(app: &AppHandle) -> Result<String, String> {
    bring_down_wireguard_linux(app)
}

#[cfg(target_os = "macos")]
fn bring_down_wireguard_macos(_app: &AppHandle) -> Result<String, String> {
    let script_path = state_dir()?.join("teardown.sh");
    let iface_file = iface_path()?;
    let pid_file = pid_path()?;
    let meta_file = state_dir()?.join("iface.meta");
    // Never use `set -e` here — partial cleanup must still complete.
    let script = format!(
        r#"#!/bin/bash
set -uo pipefail
IFACE_FILE='{iface_file}'
PID_FILE='{pid_file}'
META_FILE='{meta_file}'
DNS_BACKUP="${{META_FILE}}.dns"
DNS_PID_FILE="${{META_FILE}}.dns-proxy.pid"

ENDPOINT_IP=""
GW=""
SERVICE=""
IFACE=""

if [[ -f "$META_FILE" ]]; then
  # shellcheck disable=SC1090
  source "$META_FILE" 2>/dev/null || true
  ENDPOINT_IP="${{endpoint_ip:-}}"
  GW="${{gateway:-}}"
  SERVICE="${{service:-}}"
  IFACE="${{iface:-}}"
fi
if [[ -z "$IFACE" && -f "$IFACE_FILE" ]]; then
  IFACE="$(cat "$IFACE_FILE")"
fi

# Restore physical-service DNS before stopping the local forwarder or tunnel.
# This keeps disconnect fail-open and avoids an offline transition window.
if [[ -z "$SERVICE" ]]; then
  SERVICE="$(networksetup -listallnetworkservices 2>/dev/null | awk 'NR==2{{print; exit}}')"
fi
if [[ -n "$SERVICE" ]]; then
  DNS_VALUES=()
  if [[ -f "$DNS_BACKUP" ]]; then
    while IFS= read -r VALUE; do
      [[ "$VALUE" =~ ^[0-9a-fA-F:.]+$ && "$VALUE" != "10.0.0.1" && "$VALUE" != "127.0.0.1" && "$VALUE" != "127.0.0.2" ]] && DNS_VALUES+=("$VALUE")
    done < "$DNS_BACKUP"
  fi
  if [[ ${{#DNS_VALUES[@]}} -gt 0 ]]; then
    networksetup -setdnsservers "$SERVICE" "${{DNS_VALUES[@]}}" 2>/dev/null || true
  else
    networksetup -setdnsservers "$SERVICE" Empty 2>/dev/null || true
  fi
fi
dscacheutil -flushcache 2>/dev/null || true
killall -HUP mDNSResponder 2>/dev/null || true

# Remove full-tunnel split routes (by iface and globally).
if [[ -n "$IFACE" ]]; then
  route -n delete -net 10.0.0.0/24 -interface "$IFACE" 2>/dev/null || true
  route -n delete -net 0.0.0.0/1 -interface "$IFACE" 2>/dev/null || true
  route -n delete -net 128.0.0.0/1 -interface "$IFACE" 2>/dev/null || true
  ifconfig "$IFACE" down 2>/dev/null || true
fi
route -n delete -net 0.0.0.0/1 2>/dev/null || true
route -n delete -net 128.0.0.0/1 2>/dev/null || true
route -n delete -inet6 ::/1 ::1 2>/dev/null || true
route -n delete -inet6 8000::/1 ::1 2>/dev/null || true

# Remove pinned endpoint host route.
if [[ -n "$ENDPOINT_IP" ]]; then
  route -n delete -host "$ENDPOINT_IP" 2>/dev/null || true
fi

# Remove the temporary physical DNS routes used by the unsigned test build.
route -n delete -host 1.1.1.1 2>/dev/null || true
route -n delete -host 8.8.8.8 2>/dev/null || true

# Stop only the processes recorded for this connection. Give each a bounded
# graceful shutdown, then force it only if it is still alive.
stop_pid_file() {{
  local file="$1" pid=""
  [[ -f "$file" ]] || return 0
  pid="$(cat "$file" 2>/dev/null || true)"
  if [[ "$pid" =~ ^[0-9]+$ ]] && kill -0 "$pid" 2>/dev/null; then
    kill "$pid" 2>/dev/null || true
    for _ in $(seq 1 20); do
      kill -0 "$pid" 2>/dev/null || break
      sleep 0.1
    done
    kill -0 "$pid" 2>/dev/null && kill -9 "$pid" 2>/dev/null || true
  fi
  rm -f "$file"
}}
stop_pid_file "$DNS_PID_FILE"
stop_pid_file "$PID_FILE"
ifconfig lo0 -alias 127.0.0.2 2>/dev/null || true
rm -f /var/run/wireguard/*.sock 2>/dev/null || true
rm -f "$IFACE_FILE" "$META_FILE"
rm -f "$DNS_BACKUP"

echo ok
"#,
        iface_file = iface_file.display(),
        pid_file = pid_file.display(),
        meta_file = meta_file.display(),
    );
    fs::write(&script_path, script).map_err(|e| format!("write teardown: {e}"))?;
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        let mut perms = fs::metadata(&script_path)
            .map_err(|e| format!("stat teardown: {e}"))?
            .permissions();
        perms.set_mode(0o700);
        fs::set_permissions(&script_path, perms).ok();
    }
    // Prefer elevated teardown; if the user cancels the password prompt, still
    // try a best-effort non-elevated cleanup so we don't leave them offline.
    if let Err(elev_err) = run_elevated(&script_path) {
        let _ = Command::new("bash").arg(&script_path).output();
        let _ = fs::remove_file(conf_path()?);
        let _ = fs::remove_file(peer_id_path()?);
        let _ = elev_err;
        return Err(
            "disconnect needs admin rights — run manually: sudo bash ~/.veritasvpn/teardown.sh"
                .into(),
        );
    }
    let _ = fs::remove_file(conf_path()?);
    let _ = fs::remove_file(peer_id_path()?);
    Ok("WireGuard disconnected".into())
}

#[cfg(target_os = "linux")]
fn bring_down_wireguard_linux(app: &AppHandle) -> Result<String, String> {
    let wg_go = resolve_wireguard_go(app)?;
    let _ = wg_go;
    let script_path = state_dir()?.join("teardown.sh");
    let iface_file = iface_path()?;
    let pid_file = pid_path()?;
    let meta_file = state_dir()?.join("iface.meta");
    let script = format!(
        r#"#!/bin/bash
set -uo pipefail
IFACE_FILE='{iface_file}'
PID_FILE='{pid_file}'
STEALTH_PID_FILE='{stealth_pid}'
META_FILE='{meta_file}'
DNS_BACKUP="${{META_FILE}}.dns"

ENDPOINT_IP=""
GW=""
IFACE=""
GW_IF=""

if [[ -f "$META_FILE" ]]; then
  source "$META_FILE" 2>/dev/null || true
  ENDPOINT_IP="${{endpoint_ip:-}}"
  GW="${{gateway:-}}"
  IFACE="${{iface:-}}"
  GW_IF="${{gw_if:-}}"
fi
if [[ -z "$IFACE" && -f "$IFACE_FILE" ]]; then
  IFACE="$(cat "$IFACE_FILE")"
fi

# Remove the dedicated kill switch before intentionally restoring normal internet.
ip route del blackhole default metric 1 2>/dev/null || true

if command -v nft >/dev/null 2>&1; then
  nft delete table inet veritasvpn_killswitch 2>/dev/null || true
fi
if command -v iptables >/dev/null 2>&1; then
  while iptables -C OUTPUT -j VERITASVPN_KILLSWITCH 2>/dev/null; do
    iptables -D OUTPUT -j VERITASVPN_KILLSWITCH 2>/dev/null || break
  done
  iptables -F VERITASVPN_KILLSWITCH 2>/dev/null || true
  iptables -X VERITASVPN_KILLSWITCH 2>/dev/null || true
fi
if command -v ip6tables >/dev/null 2>&1; then
  while ip6tables -C OUTPUT -j VERITASVPN_KILLSWITCH_V6 2>/dev/null; do
    ip6tables -D OUTPUT -j VERITASVPN_KILLSWITCH_V6 2>/dev/null || break
  done
  ip6tables -F VERITASVPN_KILLSWITCH_V6 2>/dev/null || true
  ip6tables -X VERITASVPN_KILLSWITCH_V6 2>/dev/null || true
fi
ip route del blackhole default metric 1 2>/dev/null || true
ip -6 route del blackhole default metric 1 2>/dev/null || true

# Remove split-tunnel routes
if [[ -n "$IFACE" ]]; then
  ip route del 10.0.0.0/24 dev "$IFACE" 2>/dev/null || true
  ip route del 0.0.0.0/1 dev "$IFACE" 2>/dev/null || true
  ip route del 128.0.0.0/1 dev "$IFACE" 2>/dev/null || true
  ip link set "$IFACE" down 2>/dev/null || true
  ip link delete "$IFACE" 2>/dev/null || true
fi
ip route del 0.0.0.0/1 2>/dev/null || true
ip route del 128.0.0.0/1 2>/dev/null || true

# Remove endpoint host route
if [[ -n "$ENDPOINT_IP" && "$ENDPOINT_IP" != "127.0.0.1" ]]; then
  ip route del "$ENDPOINT_IP" 2>/dev/null || true
fi

# Stop userspace WireGuard
if [[ -f "$PID_FILE" ]]; then
  kill -9 "$(cat "$PID_FILE")" 2>/dev/null || true
  rm -f "$PID_FILE"
fi
pkill -f 'wireguard-go veritas0' 2>/dev/null || true
rm -f /var/run/wireguard/*.sock 2>/dev/null || true

# Stop stealth sidecar
if [[ -f "$STEALTH_PID_FILE" ]]; then
  kill -9 "$(cat "$STEALTH_PID_FILE")" 2>/dev/null || true
  rm -f "$STEALTH_PID_FILE"
fi
pkill -f '/wstunnel client' 2>/dev/null || true

rm -f "$IFACE_FILE" "$META_FILE"

# Restore DNS
if [[ -f "$DNS_BACKUP" ]]; then
  cat "$DNS_BACKUP" > /etc/resolv.conf 2>/dev/null || true
fi
if [[ -n "$GW_IF" ]] && command -v resolvectl >/dev/null 2>&1; then
  resolvectl revert "$GW_IF" 2>/dev/null || true
fi
# Full DNS reset so browsing works immediately after disconnect
if command -v resolvectl >/dev/null 2>&1; then
  resolvectl flush-caches 2>/dev/null || true
fi
systemctl reload-or-restart systemd-resolved 2>/dev/null || true
rm -f "$DNS_BACKUP"

echo ok
"#,
        iface_file = iface_file.display(),
        pid_file = pid_file.display(),
        stealth_pid = stealth_pid_path()
            .map(|p| p.display().to_string())
            .unwrap_or_else(|_| "~/.veritasvpn/wstunnel.pid".into()),
        meta_file = meta_file.display(),
    );
    fs::write(&script_path, script).map_err(|e| format!("write teardown: {e}"))?;
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        let mut perms = fs::metadata(&script_path)
            .map_err(|e| format!("stat teardown: {e}"))?
            .permissions();
        perms.set_mode(0o700);
        fs::set_permissions(&script_path, perms).ok();
    }
    if let Err(elev_err) = run_elevated(&script_path) {
        let _ = Command::new("bash").arg(&script_path).output();
        let _ = fs::remove_file(conf_path()?);
        let _ = fs::remove_file(peer_id_path()?);
        let _ = elev_err;
        return Err(
            "disconnect needs admin rights — run manually: sudo bash ~/.veritasvpn/teardown.sh"
                .into(),
        );
    }
    let _ = fs::remove_file(conf_path()?);
    let _ = fs::remove_file(peer_id_path()?);
    Ok("WireGuard disconnected".into())
}

/// Elevated execution without interactive password prompts.
/// Never falls back to pkexec (which shows a password dialog and freezes the UI).
fn run_elevated_noninteractive(script: &Path) -> Result<(), String> {
    #[cfg(target_os = "macos")]
    {
        // macOS uses osascript with administrator privileges only when needed —
        // never use soft recovery with this path.
        let path = script
            .to_string_lossy()
            .replace('\\', "\\\\")
            .replace('"', "\\\"");
        let apple = format!(r#"do shell script "bash \"{path}\"" with administrator privileges"#);
        let output = Command::new("osascript")
            .args(["-e", &apple])
            .output()
            .map_err(|e| format!("osascript: {e}"))?;
        if !output.status.success() {
            let err = String::from_utf8_lossy(&output.stderr);
            let out = String::from_utf8_lossy(&output.stdout);
            return Err(format!("privilege bring-up failed: {err} {out}"));
        }
        return Ok(());
    }

    #[cfg(target_os = "linux")]
    {
        let path = script.to_string_lossy().replace('"', "\\\"");
        // Soft recovery must never hang the UI — only noninteractive sudo.
        // Soft recovery never falls back to pkexec (which freezes the UI).
        let timeout = if is_soft_elevated() { "30" } else { "20" };
        match Command::new("sudo")
            .args(["-n", "timeout", timeout, "bash", &path])
            .output()
        {
            Ok(out) if out.status.success() => Ok(()),
            Ok(out) => Err(format!(
                "noninteractive sudo failed (status={}): {}",
                out.status,
                String::from_utf8_lossy(&out.stderr)
            )),
            Err(e) => Err(format!("sudo noninteractive: {e}")),
        }
    }

    #[cfg(not(any(target_os = "macos", target_os = "linux")))]
    {
        let output = Command::new("bash")
            .arg(script)
            .output()
            .map_err(|e| format!("bash: {e}"))?;
        if !output.status.success() {
            return Err("elevated script failed".into());
        }
        Ok(())
    }
}

fn run_elevated(script: &Path) -> Result<(), String> {
    #[cfg(target_os = "macos")]
    {
        let path = script
            .to_string_lossy()
            .replace('\\', "\\\\")
            .replace('"', "\\\"");
        let apple = format!(r#"do shell script "bash \"{path}\"" with administrator privileges"#);
        let output = Command::new("osascript")
            .args(["-e", &apple])
            .output()
            .map_err(|e| format!("osascript: {e}"))?;
        if !output.status.success() {
            let err = String::from_utf8_lossy(&output.stderr);
            let out = String::from_utf8_lossy(&output.stdout);
            return Err(format!("privilege bring-up failed: {err} {out}"));
        }
        return Ok(());
    }

    #[cfg(target_os = "linux")]
    {
        let path = script.to_string_lossy().replace('"', "\\\"");
        // Soft recovery paths must never use interactive pkexec.
        // Prefer noninteractive sudo only.
        if let Ok(ref out) = Command::new("sudo")
            .args(["-n", "bash", &path])
            .output()
        {
            if out.status.success() {
                return Ok(());
            }
        }
        // Interactive fallback only for intentional connect/disconnect — never for soft recovery.
        // Soft recovery uses run_elevated_noninteractive only.
        // Soft elevated mode never falls back to interactive elevation.
        if is_soft_elevated() {
            return Err("soft recovery cannot use interactive elevation".into());
        }
        let output = Command::new("pkexec")
            .arg("bash")
            .arg(&path)
            .output()
            .map_err(|e| format!("pkexec: {e}"))?;
        if !output.status.success() {
            let err = String::from_utf8_lossy(&output.stderr);
            return Err(format!("privilege bring-up failed: {err}"));
        }
        return Ok(());
    }

    #[cfg(not(any(target_os = "macos", target_os = "linux")))]
    {
        let output = Command::new("bash")
            .arg(script)
            .output()
            .map_err(|e| format!("bash: {e}"))?;
        if !output.status.success() {
            return Err(format!(
                "bring-up failed: {}",
                String::from_utf8_lossy(&output.stderr)
            ));
        }
        Ok(())
    }
}

#[cfg(target_os = "macos")]
fn get_active_network_service() -> Result<String, String> {
    let output = Command::new("sh")
        .arg("-c")
        .arg("networksetup -listnetworkserviceorder | grep -B1 \"$(route -n get default 2>/dev/null | grep interface | awk '{print $2}')\" | head -1 | sed 's/^([0-9]*) //'")
        .output()
        .map_err(|e| format!("Failed to detect network service: {}", e))?;

    let service = String::from_utf8_lossy(&output.stdout).trim().to_string();
    if service.is_empty() {
        for candidate in &["Wi-Fi", "Ethernet", "USB 10/100/1000 LAN"] {
            let check = Command::new("networksetup")
                .args(["-getinfo", candidate])
                .output();
            if check.is_ok() {
                return Ok(candidate.to_string());
            }
        }
        return Err("Could not detect active network service".into());
    }
    Ok(service)
}

#[cfg(target_os = "macos")]
fn set_proxy_macos(host: &str, port: u16) -> Result<String, String> {
    let service = get_active_network_service()?;
    let port_str = port.to_string();
    Command::new("networksetup")
        .args(["-setsocksfirewallproxy", &service, host, &port_str])
        .output()
        .map_err(|e| format!("Failed to set SOCKS proxy: {}", e))?;
    Command::new("networksetup")
        .args(["-setsocksfirewallproxystate", &service, "on"])
        .output()
        .map_err(|e| format!("Failed to enable SOCKS proxy: {}", e))?;
    Ok(format!(
        "SOCKS5 proxy set on {} -> {}:{}",
        service, host, port
    ))
}

#[cfg(target_os = "macos")]
fn remove_proxy_macos() -> Result<String, String> {
    let service = get_active_network_service()?;
    Command::new("networksetup")
        .args(["-setsocksfirewallproxystate", &service, "off"])
        .output()
        .map_err(|e| format!("Failed to disable SOCKS proxy: {}", e))?;
    Ok(format!("SOCKS5 proxy disabled on {}", service))
}

#[cfg(target_os = "windows")]
fn set_proxy_windows(host: &str, port: u16) -> Result<String, String> {
    use winreg::enums::*;
    use winreg::RegKey;
    let hkcu = RegKey::predef(HKEY_CURRENT_USER);
    let proxy_path = "Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings";
    let settings = hkcu
        .open_subkey_with_flags(proxy_path, KEY_SET_VALUE)
        .map_err(|e| format!("Failed to open registry: {}", e))?;
    let proxy_addr = format!("{}:{}", host, port);
    settings
        .set_value("ProxyServer", &proxy_addr)
        .map_err(|e| format!("Failed to set ProxyServer: {}", e))?;
    settings
        .set_value("ProxyEnable", &1u32)
        .map_err(|e| format!("Failed to enable proxy: {}", e))?;
    Ok(format!("SOCKS5 proxy set -> {}:{}", host, port))
}

#[cfg(target_os = "windows")]
fn remove_proxy_windows() -> Result<String, String> {
    use winreg::enums::*;
    use winreg::RegKey;
    let hkcu = RegKey::predef(HKEY_CURRENT_USER);
    let proxy_path = "Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings";
    let settings = hkcu
        .open_subkey_with_flags(proxy_path, KEY_SET_VALUE)
        .map_err(|e| format!("Failed to open registry: {}", e))?;
    settings
        .set_value("ProxyEnable", &0u32)
        .map_err(|e| format!("Failed to disable proxy: {}", e))?;
    Ok("System proxy disabled".into())
}

#[cfg(target_os = "linux")]
fn set_proxy_linux(host: &str, port: u16) -> Result<String, String> {
    let port_str = port.to_string();
    let _ = Command::new("gsettings")
        .args(["set", "org.gnome.system.proxy", "mode", "'manual'"])
        .output();
    let _ = Command::new("gsettings")
        .args([
            "set",
            "org.gnome.system.proxy.socks",
            "host",
            &format!("'{}'", host),
        ])
        .output();
    let _ = Command::new("gsettings")
        .args(["set", "org.gnome.system.proxy.socks", "port", &port_str])
        .output();
    Ok(format!("SOCKS5 proxy set (GNOME) -> {}:{}", host, port))
}

#[cfg(target_os = "linux")]
fn remove_proxy_linux() -> Result<String, String> {
    let _ = Command::new("gsettings")
        .args(["set", "org.gnome.system.proxy", "mode", "'none'"])
        .output();
    Ok("System proxy disabled (GNOME)".into())
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .plugin(tauri_plugin_http::init())
        .plugin(tauri_plugin_opener::init())
        .invoke_handler(tauri::generate_handler![
            secure_credential_set,
            secure_credential_get,
            secure_credential_delete,
            wireguard_available,
            generate_wg_keys,
            connect_wireguard,
            disconnect_wireguard,
            wireguard_stats,
            refresh_endpoint_route,
            network_switch_recover
        ])
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}
