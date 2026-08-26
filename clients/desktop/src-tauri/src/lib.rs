use base64::{engine::general_purpose::STANDARD, Engine};
use rand_core::OsRng;
use serde::{Deserialize, Serialize};
use std::fs;
use std::path::{Path, PathBuf};
use std::process::Command;
use tauri::{AppHandle, Manager};
use x25519_dalek::{PublicKey, StaticSecret};

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

fn state_dir() -> Result<PathBuf, String> {
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
fn connect_wireguard(app: AppHandle, config: WgTunnelConfig) -> ConnectResult {
    match bring_up_wireguard(&app, &config) {
        Ok(msg) => ConnectResult {
            success: true,
            message: msg,
            mode: "wireguard".into(),
            peer_id: config.peer_id,
        },
        Err(e) => ConnectResult {
            success: false,
            message: e,
            mode: "wireguard".into(),
            peer_id: config.peer_id,
        },
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
fn wireguard_stats_linux() -> WgTransferStats {
    let iface = iface_path()
        .ok()
        .and_then(|p| fs::read_to_string(p).ok())
        .unwrap_or_else(|| "wg0".into())
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
    let output = Command::new("wg")
        .args(["show", &iface, "dump"])
        .output();
    let Ok(out) = output else {
        return WgTransferStats {
            rx_bytes: 0,
            tx_bytes: 0,
            last_handshake_sec: 0,
            interface_up: false,
        };
    };
    if !out.status.success() {
        return WgTransferStats {
            rx_bytes: 0,
            tx_bytes: 0,
            last_handshake_sec: 0,
            interface_up: false,
        };
    }
    let text = String::from_utf8_lossy(&out.stdout);
    // dump: iface line then peer lines with last_handshake rx_bytes tx_bytes
    let mut rx = 0u64;
    let mut tx = 0u64;
    let mut handshake = 0i64;
    let mut up = false;
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
        rx_bytes: rx,
        tx_bytes: tx,
        last_handshake_sec: handshake,
        interface_up: up && text.lines().count() > 1,
    }
}

#[tauri::command]
fn disconnect_wireguard(app: AppHandle) -> ConnectResult {
    match bring_down_wireguard(&app) {
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
  ifconfig "$OLD" down 2>/dev/null || true
  rm -f "$IFACE_FILE"
fi
# Drop stale split-default routes even if iface file was lost
route -n delete -net 0.0.0.0/1 2>/dev/null || true
route -n delete -net 128.0.0.0/1 2>/dev/null || true
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

    let uapi_path = dir.join("uapi.txt");
    let script_path = dir.join("bringup.sh");
    let iface_file = iface_path()?;
    let pid_file = pid_path()?;
    let stealth_pid = stealth_pid_path()?;

    fs::write(&uapi_path, &uapi).map_err(|e| format!("write uapi: {e}"))?;
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

    run_elevated(&script_path)?;
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

# Start userspace WireGuard
"$WG_GO" "$IFACE_NAME" >/tmp/veritas-wg-go.log 2>&1 &
echo $! > "$PID_FILE"
sleep 0.5

# Wait for socket
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
  [[ -f "$STEALTH_PID_FILE" ]] && kill "$(cat "$STEALTH_PID_FILE")" 2>/dev/null || true
  exit 1
fi
echo "$IFACE_NAME" > "$IFACE_FILE"

# Configure WireGuard via UAPI
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
# If IPv6 is enabled, keep a fail-closed default as an additional safeguard.
ip -6 route replace blackhole default metric 1 2>/tmp/veritas-wg-killswitch-v6-error.log || true

# Firewall enforcement is preferred; blackhole routes remain as fallback.
if ! install_killswitch; then
  cleanup_killswitch
  echo "Firewall kill-switch rules unavailable; fail-closed route protection remains active" >&2
fi

# DNS: backup resolv.conf and set new DNS
cp /etc/resolv.conf "$DNS_BACKUP" 2>/dev/null || true
if [[ -w /etc/resolv.conf ]]; then
  echo "nameserver $DNS" > /etc/resolv.conf
elif command -v resolvectl >/dev/null 2>&1; then
  resolvectl dns "$GW_IF" "$DNS" 2>/dev/null || true
fi

# Persist state for teardown
printf 'endpoint_ip=%s\ngateway=%s\niface=%s\ngw_if=%s\nstealth_remote=%s\n' \
  "$ROUTE_IP" "$GW" "$IFACE_NAME" "$GW_IF" "$STEALTH_REMOTE" > "$META_FILE"

# Verify internet connectivity through the tunnel
if ! curl -4 -fsS --connect-timeout 5 --max-time 12 https://api.ipify.org \
     >/tmp/veritas-wg-egress.log 2>/tmp/veritas-wg-egress-error.log; then
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
  rm -f "$DNS_BACKUP"
  ip link set "$IFACE_NAME" down 2>/dev/null || true
  kill -9 "$(cat "$PID_FILE")" 2>/dev/null || true
  [[ -f "$STEALTH_PID_FILE" ]] && kill -9 "$(cat "$STEALTH_PID_FILE")" 2>/dev/null || true
  rm -f "$PID_FILE" "$STEALTH_PID_FILE" "$IFACE_FILE" "$META_FILE" /var/run/wireguard/*.sock
  echo "VPN internet egress validation failed; normal internet was restored" >&2
  exit 1
fi

# Install passwordless sudo so future connects/disconnects skip pkexec prompts
SUDOERS_FILE="/etc/sudoers.d/veritasvpn-{user}"
if [ ! -f "$SUDOERS_FILE" ]; then
  echo "{user} ALL=(root) NOPASSWD: {home}/.veritasvpn/bringup.sh, {home}/.veritasvpn/teardown.sh" > "$SUDOERS_FILE" 2>/dev/null || true
  chmod 0440 "$SUDOERS_FILE" 2>/dev/null || true
fi

echo "ok iface=$IFACE_NAME endpoint_ip=$ROUTE_IP stealth=${{STEALTH_REMOTE:-off}} gw=$GW"
"#,
        wg_go = wg_go.display(),
        uapi = uapi_path.display(),
        iface_file = iface_file.display(),
        pid_file = pid_file.display(),
        stealth_pid = stealth_pid.display(),
        wstunnel = wstunnel.display(),
        stealth_remote = stealth_remote,
        stealth_prefix = stealth_prefix,
        address = address,
        dns = dns,
        endpoint = endpoint,
        user = std::env::var("USER").unwrap_or_default(),
        home = dirs_next::home_dir()
            .map(|p| p.to_string_lossy().to_string())
            .unwrap_or_default(),
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
ip route del blackhole default metric 1 2>/dev/null || true
ip -6 route del blackhole default metric 1 2>/dev/null || true

# Remove split-tunnel routes
if [[ -n "$IFACE" ]]; then
  ip route del 10.0.0.0/24 dev "$IFACE" 2>/dev/null || true
  ip route del 0.0.0.0/1 dev "$IFACE" 2>/dev/null || true
  ip route del 128.0.0.0/1 dev "$IFACE" 2>/dev/null || true
  ip link set "$IFACE" down 2>/dev/null || true
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
        if let Ok(ref out) = Command::new("sudo")
            .args(["-n", "bash", &path])
            .output()
        {
            if out.status.success() {
                return Ok(());
            }
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
            wireguard_available,
            generate_wg_keys,
            connect_wireguard,
            disconnect_wireguard,
            wireguard_stats
        ])
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}
