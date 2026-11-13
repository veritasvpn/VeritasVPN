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
    Diagnostics, Request, Response, TunnelStatus, CODE_BUSY, CODE_INVALID_CONFIG,
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
        OP_CONNECT => connect(request, &mut session),
        OP_DISCONNECT => {
            session.saved = None;
            forget_session();
            release_tunnel(&mut session);
            match release_lock() {
                Ok(()) => Response::ok(request.id),
                Err(_) => Response::fail(request.id, CODE_TUNNEL_FAILED),
            }
        }
        OP_SOFT_RECONNECT => soft_reconnect(&mut session, request.id, request.config.clone()),
        OP_CLEAR_LOCK => match release_lock() {
            Ok(()) => Response::ok(request.id),
            Err(_) => Response::fail(request.id, CODE_TUNNEL_FAILED),
        },
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

pub fn release_lock() -> Result<(), u32> {
    match crate::wfp::disarm() {
        Ok(()) => Ok(()),
        Err(code) => {
            log_code("lock", code);
            Err(code)
        }
    }
}

fn connect(request: &Request, session: &mut Session) -> Response {
    if session.adapter.is_some() {
        return Response::fail(request.id, CODE_BUSY);
    }
    let Some(config) = request.config.as_ref() else {
        return Response::fail(request.id, CODE_INVALID_CONFIG);
    };
    if config.validate().is_err() {
        log_line("connect", CODE_INVALID_CONFIG);
        return Response::fail(request.id, CODE_INVALID_CONFIG);
    }
    let using_stealth = !config.stealth_endpoint.trim().is_empty();
    if using_stealth && !crate::stealth::prefix_ok(config.stealth_path_prefix.trim()) && !config.stealth_path_prefix.trim().is_empty() {
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
            note_connected();
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

fn note_connected() {
    if let Ok(mut state) = MONITOR.lock() {
        state.last_fp = current_fingerprint();
        state.reconnecting = false;
        state.stalled = false;
        state.backoff.reset();
        state.next_kick = None;
        state.flat_since = None;
    }
}

fn phase_snapshot() -> Phase {
    let state = MONITOR.lock().unwrap_or_else(|e| e.into_inner());
    Phase {
        reconnecting: state.reconnecting,
        stalled: state.stalled,
        notice: if state.reconnecting || state.stalled {
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
    let fp = current_fingerprint();
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
        drop(state);
        show(crate::reconnect::on_resume(), false);
        kick(true);
        return;
    }
    if !up {
        state.reconnecting = true;
        let due = kick_due(&mut state);
        drop(state);
        show(crate::reconnect::on_link_down(), false);
        if due {
            kick(false);
        }
        return;
    }
    if changed {
        drop(state);
        show(crate::reconnect::on_underlay_change(), false);
        kick(true);
        return;
    }
    if snapshot.helper_down {
        state.reconnecting = true;
        let due = kick_due(&mut state);
        drop(state);
        show(crate::reconnect::on_link_up(), false);
        if due {
            kick(true);
        }
        return;
    }
    let lost = force_stall || stall_now(&mut state, snapshot.rx, snapshot.tx, snapshot.handshake_age);
    if lost {
        let due = kick_due(&mut state);
        state.reconnecting = true;
        state.stalled = true;
        drop(state);
        show(crate::reconnect::on_stall(due), true);
        if due {
            kick(true);
        }
        return;
    }
    let fresh = snapshot.handshake_age.is_some_and(|age| age <= crate::reconnect::HANDSHAKE_STALE);
    if fresh {
        state.reconnecting = false;
        state.stalled = false;
        state.backoff.reset();
        state.next_kick = None;
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
    rx: u64,
    tx: u64,
    handshake_age: Option<Duration>,
    helper_down: bool,
}

fn tunnel_snapshot() -> Snap {
    let session = SESSION.lock().unwrap_or_else(|e| e.into_inner());
    let Some(adapter) = session.adapter.as_ref() else {
        return Snap {
            adapter: None,
            up: None,
            rx: 0,
            tx: 0,
            handshake_age: None,
            helper_down: false,
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
        rx,
        tx,
        handshake_age: age,
        helper_down,
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

fn kick(refresh: bool) {
    let mut session = SESSION.lock().unwrap_or_else(|e| e.into_inner());
    let Some(saved) = session.saved.clone() else {
        return;
    };
    if kick_locked(&mut session, &saved, refresh).is_err() {
        log_line("reconnect", "kick_failed");
    }
}

fn kick_locked(session: &mut Session, saved: &SavedConnect, refresh: bool) -> Result<(), &'static str> {
    let Some(adapter) = session.adapter.as_ref() else {
        return Err(CODE_TUNNEL_FAILED);
    };
    if !adapter.is_up() {
        adapter.set_up().map_err(|_| CODE_TUNNEL_FAILED)?;
    }
    let using_stealth = !saved.config.stealth_endpoint.trim().is_empty();
    if using_stealth {
        let dead = session.helper.as_ref().map(|helper| !helper.alive()).unwrap_or(true);
        if dead {
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
    if refresh {
        if let Some(pin) = session.endpoint_pin.take() {
            net::unpin_endpoint(&pin);
        }
        match net::pin_endpoint(pin_ip, session.luid) {
            Ok(pin) => session.endpoint_pin = pin,
            Err(code) => log_code("endpoint_route", code),
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
    match kick_locked(session, &saved, true) {
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
    let _ = connect(&request, &mut session);
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

fn current_fingerprint() -> String {
    let sim = read_sim();
    if let Some(underlay) = sim.underlay {
        if !underlay.is_empty() {
            return format!("sim:{underlay}");
        }
    }
    match net::default_gateway() {
        Some(gateway) => format!("gw:{gateway}"),
        None => "gw:".to_string(),
    }
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
