//! Direct-mode session. The kill switch is armed after the tunnel is up and
//! released only by Disconnect, uninstall, or Repair. Soft reconnect keeps
//! the lock and the adapter. Stealth stays refused.

use std::fs::{self, OpenOptions};
use std::io::Write;
use std::net::{Ipv4Addr, SocketAddr, ToSocketAddrs};
use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Mutex, OnceLock};
use std::thread;
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use base64::{engine::general_purpose::STANDARD, Engine};
use serde::{Deserialize, Serialize};
use veritas_core::WgTunnelConfig;
use veritas_ipc::{
    Diagnostics, Request, Response, TunnelStatus, CODE_INVALID_CONFIG,
    CODE_NOT_IN_THIS_BUILD, CODE_TUNNEL_FAILED, OP_CLEAR_LOCK, OP_CONNECT, OP_DIAGNOSTICS,
    OP_DISCONNECT, OP_HELLO, OP_SET_OPTIONS, OP_SOFT_RECONNECT, OP_STATS, OP_STATUS,
};

use crate::net;
use crate::wg::{self, AdapterHandle, Api};

static API: OnceLock<Api> = OnceLock::new();
static SESSION: Mutex<Session> = Mutex::new(Session {
    adapter: None,
    luid: 0,
    routes: Vec::new(),
    endpoint_pin: None,
    local_network: false,
    stealth_requested: false,
    saved: None,
    helper: None,
    lock_plan: None,
    lock_helper: None,
});

struct Session {
    adapter: Option<AdapterHandle>,
    luid: u64,
    routes: Vec<(Ipv4Addr, u8)>,
    endpoint_pin: Option<net::PinnedEndpoint>,
    local_network: bool,
    stealth_requested: bool,
    saved: Option<SavedConnect>,
    helper: Option<crate::stealth::Helper>,
    /// What the kill switch was armed with, so a reconnect can re-scope the
    /// server permits in one transaction when the server address changes.
    lock_plan: Option<crate::wfp_plan::LockPlan>,
    lock_helper: Option<PathBuf>,
}

#[derive(Clone, Serialize, Deserialize)]
struct SavedConnect {
    config: WgTunnelConfig,
    full_lock: bool,
    permit_web: bool,
    management_v4: Vec<String>,
    management_v6: Vec<String>,
}

pub fn handle(request: &Request, caller_ok: bool) -> Response {
    if !caller_ok {
        return Response::fail(request.id, veritas_ipc::CODE_DENIED);
    }
    let mut session = SESSION.lock().unwrap_or_else(|e| e.into_inner());
    match request.op.as_str() {
        OP_HELLO => Response::ok(request.id),
        OP_STATUS | OP_STATS => status_response(request.id, &session),
        OP_CONNECT => connect(request, &mut session, true),
        OP_DISCONNECT => match teardown(&mut session, &crate::session_policy::FULL_TEARDOWN) {
            Ok(()) => Response::ok(request.id),
            Err(_) => Response::fail(request.id, CODE_TUNNEL_FAILED),
        },
        OP_SOFT_RECONNECT => soft_reconnect(&mut session, request.id, request.config.clone()),
        // Restore internet. It used to remove only the filters, so a tunnel the
        // window did not know about kept its adapter and every route.
        OP_CLEAR_LOCK => {
            log_line("restore", "teardown");
            match teardown(&mut session, &crate::session_policy::FULL_TEARDOWN) {
                Ok(()) => Response::ok(request.id),
                Err(_) => Response::fail(request.id, CODE_TUNNEL_FAILED),
            }
        }
        OP_SET_OPTIONS => {
            if let Some(value) = request.local_network {
                session.local_network = value;
            }
            if let Some(value) = request.stealth {
                session.stealth_requested = value;
            }
            log_line("set_options", "saved");
            Response::ok(request.id)
        }
        OP_DIAGNOSTICS => {
            let mut response = Response::ok(request.id);
            response.diagnostics = Some(Diagnostics {
                service: true,
                stealth: session
                    .saved
                    .as_ref()
                    .is_some_and(|saved| !saved.config.stealth_endpoint.trim().is_empty()),
                kill_switch: crate::wfp::is_armed(),
                local_network: session.local_network,
            });
            response.status = Some(read_status(&session));
            let _ = session.stealth_requested;
            response
        }
        _ => Response::fail(request.id, CODE_INVALID_CONFIG),
    }
}

/// Service stop or crash must leave the lock in place. Only Disconnect,
/// uninstall, and Repair call [`release_lock`].
pub fn shutdown_tunnel() {
    MONITOR_STOP.store(true, Ordering::SeqCst);
    if let Ok(mut session) = SESSION.lock() {
        release_tunnel(&mut session);
    }
}

/// Run teardown steps in order while the caller holds the session lock.
/// Returns the lock result; every other step is best effort.
fn teardown(session: &mut Session, steps: &[crate::session_policy::Teardown]) -> Result<(), u32> {
    use crate::session_policy::Teardown;
    let mut lock = Ok(());
    for step in steps {
        match step {
            Teardown::ForgetSaved => {
                session.saved = None;
                session.lock_plan = None;
                session.lock_helper = None;
                forget_session();
            }
            Teardown::ReleaseTunnel => release_tunnel(session),
            Teardown::ClearMonitor => clear_monitor(),
            Teardown::ReleaseLock => lock = release_lock(),
            Teardown::StaleAdapter => down_stale_adapter(),
        }
    }
    lock
}

/// An adapter named VeritasVPN that this session does not hold: left by a
/// previous service process. Turning it off removes its routes.
fn down_stale_adapter() {
    let Ok(api) = load_api() else {
        return;
    };
    if let Ok(old) = AdapterHandle::open(api, wg::ADAPTER_NAME) {
        let _ = old.set_down();
        log_line("adapter", "stale_down");
    }
}

/// Installer and console reset, run while the service is stopped: no saved
/// session, no lock, no adapter. A reinstall used to restart the service with
/// the old session on disk, and the boot resume brought the old tunnel back.
pub fn reset_offline() -> bool {
    forget_session();
    let lock_ok = release_lock().is_ok();
    down_stale_adapter();
    log_line("reset", if lock_ok { "ok" } else { "lock" });
    lock_ok
}

pub fn release_lock() -> Result<(), u32> {
    match crate::wfp::disarm() {
        Ok(()) => Ok(()),
        Err(code) => {
            log_code("lock", code);
            Err(code)
        }
    }
}

fn connect(request: &Request, session: &mut Session, confirm_stealth: bool) -> Response {
    let Some(config) = request.config.as_ref() else {
        return Response::fail(request.id, CODE_INVALID_CONFIG);
    };
    if config.validate().is_err() {
        log_line("connect", CODE_INVALID_CONFIG);
        return Response::fail(request.id, CODE_INVALID_CONFIG);
    }
    // A session the window does not know about (left by a reinstall or an
    // earlier window) used to answer Busy here while its adapter kept every
    // route. Replace it: the lock stays and is re-armed for the new tunnel.
    let start = crate::session_policy::connect_start(session.adapter.is_some(), session.saved.is_some());
    if start == crate::session_policy::ConnectStart::ReplaceStale {
        log_line("connect", "replace_stale");
        let _ = teardown(session, &crate::session_policy::REPLACE_TEARDOWN);
    }
    let response = connect_inner(request, config, session, confirm_stealth);
    if !response.ok && crate::session_policy::release_lock_after_failed_connect(start) {
        let _ = release_lock();
    }
    response
}

fn connect_inner(
    request: &Request,
    config: &WgTunnelConfig,
    session: &mut Session,
    confirm_stealth: bool,
) -> Response {
    let using_stealth = !config.stealth_endpoint.trim().is_empty();
    if using_stealth && !crate::stealth::prefix_ok(&config.stealth_path_prefix) {
        log_line("stealth", "prefix");
        return Response::fail(request.id, CODE_INVALID_CONFIG);
    }
    let helper_path = if using_stealth {
        match crate::stealth::locate() {
            Some(path) => Some(path),
            None => {
                log_line("stealth", "missing");
                return Response::fail(request.id, CODE_TUNNEL_FAILED);
            }
        }
    } else {
        None
    };
    let mut helper = if let Some(path) = helper_path.as_ref() {
        match crate::stealth::start(path, config.stealth_endpoint.trim(), config.stealth_path_prefix.trim()) {
            Ok(helper) => Some(helper),
            Err(code) => {
                log_code("stealth", code);
                return Response::fail(request.id, CODE_TUNNEL_FAILED);
            }
        }
    } else {
        None
    };
    match bring_up(config) {
        Ok((adapter, luid, routes, pin)) => {
            let mut local_network = crate::wfp_plan::local_network_from_allowed(&config.allowed_ips);
            if request.full_lock == Some(true) {
                local_network = false;
            }
            let plan = crate::wfp_plan::LockPlan {
                tunnel_luid: luid,
                wireguard: if using_stealth {
                    vec![crate::wfp_plan::Endpoint {
                        addr: Ipv4Addr::LOCALHOST,
                        port: 41820,
                    }]
                } else {
                    crate::wfp::endpoints_from(config)
                },
                stealth: stealth_permit(config),
                local_network,
                management_v4: parse_v4_list(&request.management_v4),
                management_v6: parse_v6_list(&request.management_v6),
                permit_web: request.permit_web == Some(true),
            };
            if let Err(code) = crate::wfp::arm(&plan, helper_path.as_deref()) {
                log_code("lock", code);
                for (network, len) in &routes {
                    net::delete_route(luid, *network, *len);
                }
                if let Some(pin) = pin.as_ref() {
                    net::unpin_endpoint(pin);
                }
                let _ = adapter.set_down();
                helper.take();
                return Response::fail(request.id, veritas_ipc::CODE_LOCK_FAILED);
            }
            if let Ok(dns) = config.dns.trim().parse::<Ipv4Addr>() {
                crate::wfp::nrpt_set(dns);
            }
            session.adapter = Some(adapter);
            session.luid = luid;
            session.routes = routes;
            session.endpoint_pin = pin;
            session.local_network = local_network;
            session.helper = helper.take();
            session.lock_plan = Some(plan.clone());
            session.lock_helper = helper_path.clone();
            if using_stealth {
                restart_gate(|gate| gate.reset());
            }
            if using_stealth && confirm_stealth {
                // Connected means the TLS websocket upgrade and the WireGuard
                // handshake through it both completed. Otherwise undo
                // everything; the window shows a fixed sentence and never
                // switches to Direct.
                if let Err(reason) = wait_stealth_handshake(session, config) {
                    log_line("stealth", reason);
                    release_tunnel(session);
                    let _ = release_lock();
                    return Response::fail(request.id, veritas_ipc::CODE_STEALTH_UNREACHABLE);
                }
                log_line("stealth", "handshake");
            }
            session.saved = Some(SavedConnect {
                config: config.clone(),
                full_lock: request.full_lock == Some(true),
                permit_web: request.permit_web == Some(true),
                management_v4: request.management_v4.clone(),
                management_v6: request.management_v6.clone(),
            });
            if let Some(saved) = session.saved.clone() {
                persist_session(&saved);
            }
            note_connected(session.luid);
            log_line("connect", "ok");
            status_response(request.id, session)
        }
        Err(code) => {
            helper.take();
            log_line("connect", code);
            Response::fail(request.id, code)
        }
    }
}

/// How long Connect waits for the first handshake through Stealth. The
/// window's pipe call allows 20s for the whole connect.
const STEALTH_CONFIRM: Duration = Duration::from_secs(13);

fn wait_stealth_handshake(session: &Session, config: &WgTunnelConfig) -> Result<(), &'static str> {
    let started = Instant::now();
    let nudge = nudge_target(config);
    let mut last_nudge: Option<Instant> = None;
    while started.elapsed() < STEALTH_CONFIRM {
        let Some(adapter) = session.adapter.as_ref() else {
            return Err("no_adapter");
        };
        if let Some((_, _, handshake)) = adapter.counters() {
            if wg::filetime_to_unix(handshake) > 0 {
                return Ok(());
            }
        }
        match session.helper.as_ref() {
            Some(helper) if helper.flooded() => return Err("upgrade_refused"),
            Some(helper) if !helper.alive() => return Err("helper_exited"),
            None => return Err("helper_missing"),
            _ => {}
        }
        // WireGuard only starts a handshake when it has something to send.
        if let Some(target) = nudge {
            if last_nudge.is_none_or(|at| at.elapsed() >= Duration::from_secs(1)) {
                last_nudge = Some(Instant::now());
                let _ = std::net::UdpSocket::bind("0.0.0.0:0").and_then(|socket| socket.send_to(&[0u8], target));
            }
        }
        thread::sleep(Duration::from_millis(250));
    }
    if session.helper.as_ref().is_some_and(|helper| helper.upgrade_failures() > 0) {
        return Err("upgrade_failed");
    }
    Err("no_handshake")
}

/// Discard port on the tunnel DNS address, only when that address is routed
/// into the tunnel. Nothing is ever sent outside it.
fn nudge_target(config: &WgTunnelConfig) -> Option<SocketAddr> {
    let dns: Ipv4Addr = config.dns.trim().parse().ok()?;
    let inside = ipv4_routes(&config.allowed_ips)
        .iter()
        .any(|(network, len)| network_base(dns, *len) == *network);
    inside.then(|| SocketAddr::from((dns, 9)))
}

static RESTART: Mutex<crate::stealth_watch::RestartGate> = Mutex::new(crate::stealth_watch::RestartGate::new());

fn restart_gate<T>(f: impl FnOnce(&mut crate::stealth_watch::RestartGate) -> T) -> T {
    let mut gate = RESTART.lock().unwrap_or_else(|e| e.into_inner());
    f(&mut gate)
}

fn jitter_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| u64::from(d.subsec_nanos()) / 1_000)
        .unwrap_or(0)
}

fn bring_up(
    config: &WgTunnelConfig,
) -> Result<(AdapterHandle, u64, Vec<(Ipv4Addr, u8)>, Option<net::PinnedEndpoint>), &'static str> {
    let api = load_api().map_err(|_| CODE_TUNNEL_FAILED)?;
    let private_key = decode_key(&config.private_key).map_err(|_| CODE_INVALID_CONFIG)?;
    let peer_key = decode_key(&config.server_public_key).map_err(|_| CODE_INVALID_CONFIG)?;
    // The control plane issues a per-device preshared key. Without it the
    // server never answers the handshake, so a bad one fails the connect.
    let preshared_key =
        wg::decode_preshared_key(&config.preshared_key).map_err(|_| CODE_INVALID_CONFIG)?;
    let using_stealth = !config.stealth_endpoint.trim().is_empty();
    let endpoint = if using_stealth {
        (Ipv4Addr::LOCALHOST, 41820)
    } else {
        resolve_endpoint(&config.endpoint).map_err(|_| CODE_INVALID_CONFIG)?
    };
    let pin_ip = if using_stealth {
        resolve_endpoint(config.stealth_endpoint.trim())
            .map(|(addr, _)| addr)
            .map_err(|_| CODE_INVALID_CONFIG)?
    } else {
        endpoint.0
    };
    let (address, prefix) = parse_cidr(&config.address).map_err(|_| CODE_INVALID_CONFIG)?;
    let dns = config
        .dns
        .trim()
        .parse::<Ipv4Addr>()
        .map_err(|_| CODE_INVALID_CONFIG)?;
    let allowed = ipv4_routes(&config.allowed_ips);
    if allowed.is_empty() {
        return Err(CODE_INVALID_CONFIG);
    }
    let _ = AdapterHandle::open(api, wg::ADAPTER_NAME).map(|old| {
        let _ = old.set_down();
    });
    let adapter = match AdapterHandle::create(api, wg::ADAPTER_NAME) {
        Ok(adapter) => adapter,
        Err(()) => {
            log_line("adapter", &format!("win32={}", wg::last_error()));
            return Err(CODE_TUNNEL_FAILED);
        }
    };
    let blob = wg::direct_config(
        private_key,
        peer_key,
        preshared_key,
        Some(endpoint),
        0,
        25,
        &allowed,
    );
    if adapter.set_config(&blob).is_err() || adapter.set_up().is_err() {
        log_line("adapter_up", &format!("win32={}", wg::last_error()));
        return Err(CODE_TUNNEL_FAILED);
    }
    let luid = adapter.luid();
    if let Err(code) = net::assign_v4(luid, address, prefix)
        .and_then(|_| net::set_mtu(luid, 1280))
        .and_then(|_| net::set_dns(luid, dns))
    {
        log_line("address", &format!("win32={code}"));
        let _ = adapter.set_down();
        return Err(CODE_TUNNEL_FAILED);
    }
    let pin = match net::pin_endpoint(pin_ip, luid) {
        Ok(pin) => pin,
        Err(code) => {
            log_line("endpoint_route", &format!("win32={code}"));
            let _ = adapter.set_down();
            return Err(CODE_TUNNEL_FAILED);
        }
    };
    for (network, len) in &allowed {
        if let Err(code) = net::add_route(luid, *network, *len) {
            log_line("route", &format!("win32={code}"));
            for (done_net, done_len) in &allowed {
                net::delete_route(luid, *done_net, *done_len);
            }
            if let Some(pin) = pin.as_ref() {
                net::unpin_endpoint(pin);
            }
            let _ = adapter.set_down();
            return Err(CODE_TUNNEL_FAILED);
        }
    }
    Ok((adapter, luid, allowed, pin))
}

fn release_tunnel(session: &mut Session) {
    session.helper.take();
    if let Some(pin) = session.endpoint_pin.take() {
        net::unpin_endpoint(&pin);
    }
    if let Some(adapter) = session.adapter.take() {
        for (network, len) in session.routes.drain(..) {
            net::delete_route(session.luid, network, len);
        }
        let _ = adapter.set_down();
        log_line("disconnect", "ok");
    }
}

fn status_response(id: u64, session: &Session) -> Response {
    let mut response = Response::ok(id);
    response.status = Some(read_status(session));
    response
}

fn read_status(session: &Session) -> TunnelStatus {
    let phase = phase_snapshot();
    let Some(adapter) = session.adapter.as_ref() else {
        return TunnelStatus {
            up: false,
            rx: 0,
            tx: 0,
            handshake: 0,
            reconnecting: phase.reconnecting,
            stalled: phase.stalled,
            notice: phase.notice,
            session: None,
            resuming: session_blob_exists(),
        };
    };
    let (rx, tx, handshake) = adapter.counters().unwrap_or((0, 0, 0));
    TunnelStatus {
        up: adapter.is_up(),
        rx,
        tx,
        handshake: wg::filetime_to_unix(handshake),
        reconnecting: phase.reconnecting,
        stalled: phase.stalled,
        notice: phase.notice,
        session: session.saved.as_ref().map(|saved| {
            crate::session_policy::session_info(
                &saved.config.peer_id,
                &saved.config.stealth_endpoint,
                &saved.config.dns,
            )
        }),
        resuming: false,
    }
}

fn load_api() -> Result<&'static Api, ()> {
    if let Some(api) = API.get() {
        return Ok(api);
    }
    let path = dll_path()?;
    let api = Api::load(&path)?;
    let _ = API.set(api);
    API.get().ok_or(())
}

pub fn dll_path() -> Result<PathBuf, ()> {
    if let Some(path) = std::env::var_os("VERITAS_WIREGUARD_DLL") {
        let path = PathBuf::from(path);
        if path.is_file() {
            return Ok(path);
        }
    }
    let exe = std::env::current_exe().map_err(|_| ())?;
    let beside = exe.parent().ok_or(())?.join("wireguard.dll");
    if beside.is_file() {
        return Ok(beside);
    }
    if let Some(dir) = program_data() {
        let stored = dir.join("wireguard.dll");
        if stored.is_file() {
            return Ok(stored);
        }
    }
    Err(())
}

pub fn ensure_api(path: &std::path::Path) -> Result<&'static Api, ()> {
    if let Some(api) = API.get() {
        return Ok(api);
    }
    let api = Api::load(path)?;
    let _ = API.set(api);
    API.get().ok_or(())
}

fn decode_key(text: &str) -> Result<[u8; 32], ()> {
    let bytes = STANDARD.decode(text.trim()).map_err(|_| ())?;
    bytes.try_into().map_err(|_| ())
}

pub(crate) fn resolve_endpoint(endpoint: &str) -> Result<(Ipv4Addr, u16), ()> {
    let socket = endpoint
        .trim()
        .to_socket_addrs()
        .map_err(|_| ())?
        .find_map(|addr| match addr {
            SocketAddr::V4(v4) => Some(v4),
            SocketAddr::V6(_) => None,
        })
        .ok_or(())?;
    if socket.port() == 0 {
        return Err(());
    }
    Ok((*socket.ip(), socket.port()))
}

fn parse_v4_list(values: &[String]) -> Vec<Ipv4Addr> {
    values.iter().filter_map(|value| value.trim().parse().ok()).take(32).collect()
}

fn parse_v6_list(values: &[String]) -> Vec<std::net::Ipv6Addr> {
    values.iter().filter_map(|value| value.trim().parse().ok()).take(16).collect()
}

fn parse_cidr(value: &str) -> Result<(Ipv4Addr, u8), ()> {
    let (ip, prefix) = value.trim().split_once('/').ok_or(())?;
    let ip: Ipv4Addr = ip.parse().map_err(|_| ())?;
    let prefix: u8 = prefix.parse().map_err(|_| ())?;
    if prefix > 32 {
        return Err(());
    }
    Ok((ip, prefix))
}

fn ipv4_routes(allowed: &[String]) -> Vec<(Ipv4Addr, u8)> {
    allowed
        .iter()
        .filter_map(|value| parse_cidr(value).ok())
        .map(|(ip, prefix)| (network_base(ip, prefix), prefix))
        .collect()
}

fn network_base(ip: Ipv4Addr, prefix: u8) -> Ipv4Addr {
    if prefix == 0 {
        return Ipv4Addr::UNSPECIFIED;
    }
    let mask = u32::MAX.checked_shl(32 - prefix as u32).unwrap_or(0);
    Ipv4Addr::from(u32::from(ip) & mask)
}

pub(crate) fn log_code(op: &str, code: u32) {
    log_line(op, &format!("win32={code}"));
}

pub(crate) fn log_line(op: &str, code: &str) {
    let Some(dir) = program_data() else {
        return;
    };
    let _ = fs::create_dir_all(&dir);
    let path = dir.join("service.log");
    if let Ok(meta) = fs::metadata(&path) {
        if meta.len() > 256 * 1024 {
            let _ = fs::remove_file(&path);
        }
    }
    if let Ok(mut file) = OpenOptions::new().create(true).append(true).open(path) {
        let _ = writeln!(file, "op={op} code={code}");
    }
}

pub fn program_data() -> Option<PathBuf> {
    Some(PathBuf::from(r"C:\ProgramData\VeritasVPN"))
}

const NOTICE: &str = "Reconnecting…";

struct Phase {
    reconnecting: bool,
    stalled: bool,
    notice: String,
}

struct MonitorState {
    last_fp: String,
    last_power: String,
    backoff: crate::reconnect::Backoff,
    next_kick: Option<Instant>,
    reconnecting: bool,
    stalled: bool,
    rx: u64,
    tx: u64,
    flat_since: Option<Instant>,
    /// Set by every kick. Reconnecting ends only when traffic flows again.
    recovery: Option<crate::underlay::Recovery>,
    recovery_stealth: bool,
}

static MONITOR: Mutex<MonitorState> = Mutex::new(MonitorState {
    last_fp: String::new(),
    last_power: String::new(),
    backoff: crate::reconnect::Backoff::new(),
    next_kick: None,
    reconnecting: false,
    stalled: false,
    rx: 0,
    tx: 0,
    flat_since: None,
    recovery: None,
    recovery_stealth: false,
});
static MONITOR_STOP: AtomicBool = AtomicBool::new(false);
static MONITOR_STARTED: AtomicBool = AtomicBool::new(false);
static POWER: Mutex<Option<&'static str>> = Mutex::new(None);

pub fn start_monitor() {
    if MONITOR_STARTED.swap(true, Ordering::SeqCst) {
        return;
    }
    MONITOR_STOP.store(false, Ordering::SeqCst);
    thread::spawn(|| {
        while !MONITOR_STOP.load(Ordering::SeqCst) {
            tick();
            thread::sleep(Duration::from_secs(2));
        }
    });
}

pub fn note_power(kind: &'static str) {
    if let Ok(mut slot) = POWER.lock() {
        *slot = Some(kind);
    }
}

fn note_connected(tunnel_luid: u64) {
    let fp = current_fingerprint(tunnel_luid);
    if let Ok(mut state) = MONITOR.lock() {
        state.last_fp = fp;
        state.reconnecting = false;
        state.stalled = false;
        state.backoff.reset();
        state.next_kick = None;
        state.flat_since = None;
        state.recovery = None;
    }
}

fn clear_monitor() {
    if let Ok(mut state) = MONITOR.lock() {
        state.last_fp.clear();
        state.reconnecting = false;
        state.stalled = false;
        state.backoff.reset();
        state.next_kick = None;
        state.flat_since = None;
        state.recovery = None;
    }
}

/// Record a kick. The first kick of an outage starts the recovery clock;
/// later ones move the traffic baseline.
fn note_kick(state: &mut MonitorState, snap: &Snap, now: Instant) {
    match state.recovery.as_mut() {
        Some(recovery) => recovery.kicked(now, snap.rx, snap.handshake_unix),
        None => state.recovery = Some(crate::underlay::Recovery::begin(now, snap.rx, snap.handshake_unix)),
    }
    state.recovery_stealth = snap.stealth;
    state.reconnecting = true;
    state.stalled = true;
}

fn phase_snapshot() -> Phase {
    let state = MONITOR.lock().unwrap_or_else(|e| e.into_inner());
    let stuck = state.recovery.is_some_and(|recovery| recovery.explain(Instant::now()));
    Phase {
        reconnecting: state.reconnecting,
        stalled: state.stalled,
        notice: if stuck {
            if state.recovery_stealth {
                crate::underlay::STEALTH_STUCK_NOTICE.to_string()
            } else {
                crate::underlay::DIRECT_STUCK_NOTICE.to_string()
            }
        } else if state.reconnecting || state.stalled {
            NOTICE.to_string()
        } else {
            String::new()
        },
    }
}

fn show(action: crate::reconnect::Action, stalled: bool) {
    let mut state = MONITOR.lock().unwrap_or_else(|e| e.into_inner());
    state.reconnecting = action.notice == crate::reconnect::Notice::Reconnecting;
    state.stalled = stalled || state.reconnecting && stalled;
    if action.notice == crate::reconnect::Notice::Quiet {
        state.stalled = false;
        state.backoff.reset();
        state.next_kick = None;
    }
}

fn kick_due(state: &mut MonitorState) -> bool {
    let now = Instant::now();
    if state.next_kick.is_some_and(|at| now < at) {
        return false;
    }
    let delay = state.backoff.next_delay();
    state.next_kick = Some(now + delay);
    true
}

fn tick() {
    if MONITOR_STOP.load(Ordering::SeqCst) {
        return;
    }
    let saved_on_disk = session_blob_exists();
    let snapshot = tunnel_snapshot();
    if snapshot.adapter.is_none() && saved_on_disk {
        show(crate::reconnect::on_boot(true), false);
        resume_saved();
        return;
    }
    let Some(up) = snapshot.up else {
        return;
    };
    let sim = read_sim();
    let fp = current_fingerprint(snapshot.luid);
    let now = Instant::now();
    let mut state = MONITOR.lock().unwrap_or_else(|e| e.into_inner());
    let first = state.last_fp.is_empty();
    let changed = !first && state.last_fp != fp;
    state.last_fp = fp;
    let power = take_power(&sim, &mut state);
    let force_stall = sim.stall;
    if let Some("sleep") = power {
        state.reconnecting = true;
        drop(state);
        show(crate::reconnect::on_sleep(), false);
        return;
    }
    if let Some("resume") = power {
        note_kick(&mut state, &snapshot, now);
        drop(state);
        show(crate::reconnect::on_resume(), true);
        kick(true, snapshot.stealth);
        return;
    }
    if !up {
        state.reconnecting = true;
        let due = kick_due(&mut state);
        drop(state);
        show(crate::reconnect::on_link_down(), false);
        if due {
            kick(false, false);
        }
        return;
    }
    if changed {
        // A different physical network: the server route moves to the new
        // default adapter and, in Stealth, wstunnel reconnects over it.
        // Its old TLS socket can stay bound to an address that is gone.
        note_kick(&mut state, &snapshot, now);
        drop(state);
        log_line("reconnect", "network_changed");
        show(crate::reconnect::on_underlay_change(), true);
        kick(true, snapshot.stealth);
        return;
    }
    if snapshot.helper_down {
        let due = kick_due(&mut state);
        if due {
            note_kick(&mut state, &snapshot, now);
        }
        state.reconnecting = true;
        state.stalled = true;
        drop(state);
        show(crate::reconnect::on_link_up(), true);
        if due {
            kick(true, false);
        }
        return;
    }
    if let Some(recovery) = state.recovery {
        if !force_stall && recovery.recovered(snapshot.rx, snapshot.handshake_unix) {
            state.recovery = None;
            state.reconnecting = false;
            state.stalled = false;
            state.backoff.reset();
            state.next_kick = None;
            state.flat_since = None;
            state.rx = snapshot.rx;
            state.tx = snapshot.tx;
            drop(state);
            restart_gate(|gate| gate.reset());
            log_line("reconnect", "recovered");
            return;
        }
        state.reconnecting = true;
        state.stalled = true;
        let retry = recovery.retry_due(now) && kick_due(&mut state);
        if retry {
            note_kick(&mut state, &snapshot, now);
        }
        drop(state);
        // Give WireGuard something to send so a working path answers.
        nudge(snapshot.nudge);
        if retry {
            log_line("reconnect", "retry");
            kick(true, snapshot.stealth);
        }
        return;
    }
    let lost = force_stall || stall_now(&mut state, snapshot.rx, snapshot.tx, snapshot.handshake_age);
    if lost {
        let due = kick_due(&mut state);
        if due {
            note_kick(&mut state, &snapshot, now);
        }
        state.reconnecting = true;
        state.stalled = true;
        drop(state);
        show(crate::reconnect::on_stall(due), true);
        if due {
            kick(true, snapshot.stealth);
        }
        return;
    }
    let fresh = snapshot.handshake_age.is_some_and(|age| age <= crate::reconnect::HANDSHAKE_STALE);
    if fresh {
        state.reconnecting = false;
        state.stalled = false;
        state.backoff.reset();
        state.next_kick = None;
        drop(state);
        restart_gate(|gate| gate.reset());
    }
}

fn nudge(target: Option<SocketAddr>) {
    if let Some(target) = target {
        let _ = std::net::UdpSocket::bind("0.0.0.0:0").and_then(|socket| socket.send_to(&[0u8], target));
    }
}

fn stall_now(state: &mut MonitorState, rx: u64, tx: u64, age: Option<Duration>) -> bool {
    if state.rx != rx {
        state.rx = rx;
        state.tx = tx;
        state.flat_since = Some(Instant::now());
        return false;
    }
    let tx_delta = tx.saturating_sub(state.tx);
    state.tx = tx;
    let started = *state.flat_since.get_or_insert_with(Instant::now);
    crate::reconnect::handshake_lost(started.elapsed(), 0, tx_delta, age)
}

struct Snap {
    adapter: Option<bool>,
    up: Option<bool>,
    luid: u64,
    rx: u64,
    tx: u64,
    handshake_unix: i64,
    handshake_age: Option<Duration>,
    helper_down: bool,
    stealth: bool,
    nudge: Option<SocketAddr>,
}

fn tunnel_snapshot() -> Snap {
    let session = SESSION.lock().unwrap_or_else(|e| e.into_inner());
    let Some(adapter) = session.adapter.as_ref() else {
        return Snap {
            adapter: None,
            up: None,
            luid: 0,
            rx: 0,
            tx: 0,
            handshake_unix: 0,
            handshake_age: None,
            helper_down: false,
            stealth: false,
            nudge: None,
        };
    };
    let up = adapter.is_up();
    let (rx, tx, handshake) = adapter.counters().unwrap_or((0, 0, 0));
    let unix = wg::filetime_to_unix(handshake);
    let age = if unix > 0 {
        let now = SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_secs() as i64).unwrap_or(0);
        Some(Duration::from_secs(now.saturating_sub(unix).max(0) as u64))
    } else {
        None
    };
    let stealth = session
        .saved
        .as_ref()
        .is_some_and(|saved| !saved.config.stealth_endpoint.trim().is_empty());
    let helper_down = stealth && session.helper.as_ref().map(|helper| !helper.alive()).unwrap_or(true);
    Snap {
        adapter: Some(true),
        up: Some(up),
        luid: session.luid,
        rx,
        tx,
        handshake_unix: unix,
        handshake_age: age,
        helper_down,
        stealth,
        nudge: session.saved.as_ref().and_then(|saved| nudge_target(&saved.config)),
    }
}

fn stealth_permit(config: &WgTunnelConfig) -> Option<crate::wfp_plan::Endpoint> {
    let remote = config.stealth_endpoint.trim();
    if remote.is_empty() {
        return None;
    }
    let (addr, port) = resolve_endpoint(remote).ok()?;
    Some(crate::wfp_plan::Endpoint { addr, port })
}

/// `restart_helper` replaces a running wstunnel too. A network change, a
/// resume, or a stalled Stealth tunnel leaves its TLS socket on the old
/// path, and a live process with a dead socket never recovers by itself.
fn kick(refresh: bool, restart_helper: bool) {
    let mut session = SESSION.lock().unwrap_or_else(|e| e.into_inner());
    let Some(saved) = session.saved.clone() else {
        return;
    };
    if kick_locked(&mut session, &saved, refresh, restart_helper).is_err() {
        log_line("reconnect", "kick_failed");
    }
}

fn kick_locked(
    session: &mut Session,
    saved: &SavedConnect,
    refresh: bool,
    restart_helper: bool,
) -> Result<(), &'static str> {
    let Some(adapter) = session.adapter.as_ref() else {
        return Err(CODE_TUNNEL_FAILED);
    };
    if !adapter.is_up() {
        adapter.set_up().map_err(|_| CODE_TUNNEL_FAILED)?;
    }
    let using_stealth = !saved.config.stealth_endpoint.trim().is_empty();
    let endpoint = if using_stealth {
        (Ipv4Addr::LOCALHOST, 41820)
    } else {
        resolve_endpoint(&saved.config.endpoint).map_err(|_| CODE_TUNNEL_FAILED)?
    };
    let pin_ip = if using_stealth {
        resolve_endpoint(saved.config.stealth_endpoint.trim())
            .map(|(addr, _)| addr)
            .unwrap_or(endpoint.0)
    } else {
        endpoint.0
    };
    // Route first: the server /32 moves to the current default adapter, so
    // a restarted wstunnel dials out over the new network. The Stealth
    // kill-switch permit is the server address and port for the pinned
    // wstunnel binary on any physical adapter, so it needs no change.
    if refresh {
        if let Some(pin) = session.endpoint_pin.take() {
            net::unpin_endpoint(&pin);
        }
        match net::pin_endpoint(pin_ip, session.luid) {
            Ok(pin) => session.endpoint_pin = pin,
            Err(code) => log_code("endpoint_route", code),
        }
        rescope_lock(&mut session.lock_plan, &mut session.lock_helper, saved, using_stealth);
    }
    if using_stealth {
        let flooded = session.helper.as_ref().is_some_and(|helper| helper.flooded());
        if flooded {
            // The reader already stopped it. Drop it once and count the cut
            // as an attempt so the next start waits for the backoff.
            session.helper = None;
            restart_gate(|gate| gate.note_failure(Instant::now(), jitter_ms()));
            log_line("stealth", "upgrade_loop_stopped");
        }
        let dead = session.helper.as_ref().map(|helper| !helper.alive()).unwrap_or(true);
        let restart = if restart_helper && !flooded {
            // Counted, so a network that keeps flapping still backs off
            // through the monitor's kick schedule.
            restart_gate(|gate| {
                let _ = gate.try_restart(Instant::now(), jitter_ms());
            });
            true
        } else {
            dead && restart_gate(|gate| gate.try_restart(Instant::now(), jitter_ms()))
        };
        if restart {
            log_line("stealth", if restart_helper { "restart_network" } else { "restart" });
            // Stop the old one first so the new one can bind the local port.
            session.helper = None;
            if let Some(path) = crate::stealth::locate() {
                match crate::stealth::start(
                    &path,
                    saved.config.stealth_endpoint.trim(),
                    saved.config.stealth_path_prefix.trim(),
                ) {
                    Ok(helper) => session.helper = Some(helper),
                    Err(code) => log_code("stealth", code),
                }
            }
        }
    }
    let private_key = decode_key(&saved.config.private_key).map_err(|_| CODE_INVALID_CONFIG)?;
    let peer_key = decode_key(&saved.config.server_public_key).map_err(|_| CODE_INVALID_CONFIG)?;
    let preshared_key =
        wg::decode_preshared_key(&saved.config.preshared_key).map_err(|_| CODE_INVALID_CONFIG)?;
    let allowed = ipv4_routes(&saved.config.allowed_ips);
    let blob = wg::direct_config(
        private_key,
        peer_key,
        preshared_key,
        Some(endpoint),
        0,
        25,
        &allowed,
    );
    adapter.set_config(&blob).map_err(|_| CODE_TUNNEL_FAILED)?;
    log_line("reconnect", "kick");
    Ok(())
}

/// A reconnect can carry a new server address (a replacement config, or a
/// hostname that now resolves elsewhere). Re-arm with the new server
/// permits. `wfp::arm` deletes and re-adds every filter inside one WFP
/// transaction, so the blocks never lapse; on failure the old lock stays.
fn rescope_lock(
    lock_plan: &mut Option<crate::wfp_plan::LockPlan>,
    lock_helper: &mut Option<PathBuf>,
    saved: &SavedConnect,
    using_stealth: bool,
) {
    let Some(plan) = lock_plan.as_ref() else {
        return;
    };
    let wireguard = if using_stealth {
        vec![crate::wfp_plan::Endpoint {
            addr: Ipv4Addr::LOCALHOST,
            port: 41820,
        }]
    } else {
        crate::wfp::endpoints_from(&saved.config)
    };
    let Some(next) = crate::wfp_plan::rescoped(plan, wireguard, stealth_permit(&saved.config)) else {
        return;
    };
    let helper = if using_stealth {
        lock_helper.clone().or_else(crate::stealth::locate)
    } else {
        None
    };
    match crate::wfp::arm(&next, helper.as_deref()) {
        Ok(()) => {
            *lock_plan = Some(next);
            *lock_helper = helper;
            log_line("lock", "rescoped");
        }
        Err(code) => log_code("lock_rescope", code),
    }
}

fn soft_reconnect(session: &mut Session, id: u64, replacement: Option<WgTunnelConfig>) -> Response {
    if let Some(config) = replacement {
        let Some(saved) = session.saved.as_mut() else {
            return Response::fail(id, CODE_TUNNEL_FAILED);
        };
        saved.config = config;
        let saved = saved.clone();
        persist_session(&saved);
    }
    let Some(saved) = session.saved.clone() else {
        return Response::fail(id, CODE_TUNNEL_FAILED);
    };
    show(crate::reconnect::on_link_up(), false);
    match kick_locked(session, &saved, true, true) {
        Ok(()) => status_response(id, session),
        Err(code) => {
            log_line("reconnect", code);
            Response::fail(id, code)
        }
    }
}

fn resume_saved() {
    let Some(saved) = load_session() else {
        return;
    };
    let mut request = Request::new(0, veritas_ipc::OP_CONNECT);
    request.config = Some(saved.config);
    request.full_lock = Some(saved.full_lock);
    request.permit_web = Some(saved.permit_web);
    request.management_v4 = saved.management_v4;
    request.management_v6 = saved.management_v6;
    let mut session = SESSION.lock().unwrap_or_else(|e| e.into_inner());
    // Disconnect deletes the blob while it holds this lock, then disarms.
    if session.adapter.is_some() || !session_blob_exists() {
        return;
    }
    // Boot resume keeps the old rule: the lock stays armed and the monitor
    // keeps retrying, so it does not wait for the first handshake here. It is
    // a fresh start, never a replace, so a failed resume keeps the lock and
    // the blob on disk for the next try.
    session.saved = None;
    if let Some(config) = request.config.clone() {
        let _ = connect_inner(&request, &config, &mut session, false);
    }
}

struct Sim {
    underlay: Option<String>,
    power: Option<String>,
    stall: bool,
}

fn read_sim() -> Sim {
    let mut sim = Sim {
        underlay: None,
        power: None,
        stall: false,
    };
    let Some(dir) = program_data() else {
        return sim;
    };
    let Ok(text) = fs::read_to_string(dir.join("sim.txt")) else {
        return sim;
    };
    for line in text.lines() {
        let Some((key, value)) = line.split_once('=') else {
            continue;
        };
        match key.trim() {
            "underlay" => sim.underlay = Some(value.trim().to_string()),
            "power" => sim.power = Some(value.trim().to_string()),
            "stall" => sim.stall = value.trim() == "1",
            _ => {}
        }
    }
    sim
}

fn current_fingerprint(tunnel_luid: u64) -> String {
    let sim = read_sim();
    if let Some(underlay) = sim.underlay {
        if !underlay.is_empty() {
            return format!("sim:{underlay}");
        }
    }
    net::underlay_fingerprint(tunnel_luid)
}

fn take_power(sim: &Sim, state: &mut MonitorState) -> Option<&'static str> {
    let mut pending = POWER.lock().unwrap_or_else(|e| e.into_inner());
    if let Some(kind) = pending.take() {
        state.last_power = kind.to_string();
        return Some(kind);
    }
    let Some(power) = sim.power.as_deref() else {
        return None;
    };
    if power == state.last_power {
        return None;
    }
    state.last_power = power.to_string();
    match power {
        "sleep" => Some("sleep"),
        "resume" => Some("resume"),
        _ => None,
    }
}

fn persist_session(saved: &SavedConnect) {
    let Ok(plain) = serde_json::to_vec(saved) else {
        return;
    };
    let Ok(sealed) = protect(&plain) else {
        log_line("session", "protect_failed");
        return;
    };
    let Some(dir) = program_data() else {
        return;
    };
    let _ = fs::create_dir_all(&dir);
    if fs::write(dir.join("session.bin"), sealed).is_err() {
        log_line("session", "write_failed");
    }
}

fn load_session() -> Option<SavedConnect> {
    let dir = program_data()?;
    let sealed = fs::read(dir.join("session.bin")).ok()?;
    let plain = unprotect(&sealed).ok()?;
    serde_json::from_slice(&plain).ok()
}

fn session_blob_exists() -> bool {
    program_data().is_some_and(|dir| dir.join("session.bin").is_file())
}

/// Console Repair runs in its own process; it can only delete the blob.
pub fn forget_saved_session() {
    forget_session();
}

fn forget_session() {
    if let Some(dir) = program_data() {
        let _ = fs::remove_file(dir.join("session.bin"));
    }
}

fn protect(plain: &[u8]) -> Result<Vec<u8>, u32> {
    use windows_sys::Win32::Foundation::LocalFree;
    use windows_sys::Win32::Security::Cryptography::{CryptProtectData, CRYPTPROTECT_LOCAL_MACHINE, CRYPT_INTEGER_BLOB};
    let mut input = CRYPT_INTEGER_BLOB {
        cbData: plain.len() as u32,
        pbData: plain.as_ptr() as *mut u8,
    };
    let mut output = CRYPT_INTEGER_BLOB {
        cbData: 0,
        pbData: std::ptr::null_mut(),
    };
    let ok = unsafe {
        CryptProtectData(
            &mut input,
            std::ptr::null(),
            std::ptr::null(),
            std::ptr::null(),
            std::ptr::null(),
            CRYPTPROTECT_LOCAL_MACHINE,
            &mut output,
        )
    };
    if ok == 0 {
        return Err(unsafe { windows_sys::Win32::Foundation::GetLastError() });
    }
    let bytes = unsafe { std::slice::from_raw_parts(output.pbData, output.cbData as usize) }.to_vec();
    unsafe { LocalFree(output.pbData as _) };
    Ok(bytes)
}

fn unprotect(sealed: &[u8]) -> Result<Vec<u8>, u32> {
    use windows_sys::Win32::Foundation::LocalFree;
    use windows_sys::Win32::Security::Cryptography::{CryptUnprotectData, CRYPT_INTEGER_BLOB};
    let mut input = CRYPT_INTEGER_BLOB {
        cbData: sealed.len() as u32,
        pbData: sealed.as_ptr() as *mut u8,
    };
    let mut output = CRYPT_INTEGER_BLOB {
        cbData: 0,
        pbData: std::ptr::null_mut(),
    };
    let ok = unsafe {
        CryptUnprotectData(
            &mut input,
            std::ptr::null_mut(),
            std::ptr::null(),
            std::ptr::null(),
            std::ptr::null(),
            0,
            &mut output,
        )
    };
    if ok == 0 {
        return Err(unsafe { windows_sys::Win32::Foundation::GetLastError() });
    }
    let bytes = unsafe { std::slice::from_raw_parts(output.pbData, output.cbData as usize) }.to_vec();
    unsafe { LocalFree(output.pbData as _) };
    Ok(bytes)
}
