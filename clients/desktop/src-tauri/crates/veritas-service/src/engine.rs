//! Direct-mode session. The kill switch is armed after the tunnel is up and
//! released only by Disconnect, uninstall, or Repair. Stealth and soft
//! reconnect stay refused.

use std::fs::{self, OpenOptions};
use std::io::Write;
use std::net::{Ipv4Addr, SocketAddr, ToSocketAddrs};
use std::path::PathBuf;
use std::sync::{Mutex, OnceLock};

use base64::{engine::general_purpose::STANDARD, Engine};
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
});

struct Session {
    adapter: Option<AdapterHandle>,
    luid: u64,
    routes: Vec<(Ipv4Addr, u8)>,
    endpoint_pin: Option<net::PinnedEndpoint>,
    local_network: bool,
    stealth_requested: bool,
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
            release_tunnel(&mut session);
            match release_lock() {
                Ok(()) => Response::ok(request.id),
                Err(_) => Response::fail(request.id, CODE_TUNNEL_FAILED),
            }
        }
        OP_SOFT_RECONNECT => Response::fail(request.id, CODE_NOT_IN_THIS_BUILD),
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
                stealth: false,
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
    if !config.stealth_endpoint.trim().is_empty() {
        log_line("connect", CODE_NOT_IN_THIS_BUILD);
        return Response::fail(request.id, CODE_NOT_IN_THIS_BUILD);
    }
    match bring_up(config) {
        Ok((adapter, luid, routes, pin)) => {
            let local_network = crate::wfp_plan::local_network_from_allowed(&config.allowed_ips);
            let plan = crate::wfp_plan::LockPlan {
                tunnel_luid: luid,
                wireguard: crate::wfp::endpoints_from(config),
                stealth: None,
                local_network,
            };
            if let Err(code) = crate::wfp::arm(&plan) {
                log_code("lock", code);
                for (network, len) in &routes {
                    net::delete_route(luid, *network, *len);
                }
                if let Some(pin) = pin.as_ref() {
                    net::unpin_endpoint(pin);
                }
                let _ = adapter.set_down();
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
            log_line("connect", "ok");
            status_response(request.id, session)
        }
        Err(code) => {
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
    let endpoint = resolve_endpoint(&config.endpoint).map_err(|_| CODE_INVALID_CONFIG)?;
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
    let endpoint_addr = endpoint.0;
    let pin = match net::pin_endpoint(endpoint_addr, luid) {
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
    let Some(adapter) = session.adapter.as_ref() else {
        return TunnelStatus {
            up: false,
            rx: 0,
            tx: 0,
            handshake: 0,
        };
    };
    let (rx, tx, handshake) = adapter.counters().unwrap_or((0, 0, 0));
    TunnelStatus {
        up: adapter.is_up(),
        rx,
        tx,
        handshake: wg::filetime_to_unix(handshake),
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

fn log_line(op: &str, code: &str) {
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
