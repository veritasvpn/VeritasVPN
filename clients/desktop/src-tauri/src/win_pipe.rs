//! Client for the VeritasVPN service pipe. Failures become stable sentences.
//! Windows error text never leaves this module.

use veritas_ipc::{
    encode, user_message, Request, Response, TunnelStatus, CODE_UNAVAILABLE, OP_CLEAR_LOCK,
    OP_CONNECT, OP_DIAGNOSTICS, OP_DISCONNECT, OP_HELLO, OP_STATS, PIPE_NAME,
};
use windows_sys::Win32::Foundation::{GetLastError, ERROR_ACCESS_DENIED, ERROR_PIPE_BUSY};
use windows_sys::Win32::System::Pipes::CallNamedPipeW;

use crate::{WgTransferStats, WgTunnelConfig};

pub fn available() -> bool {
    match call(&Request::new(1, OP_HELLO), 800) {
        Ok(response) => response.ok,
        Err(_) => false,
    }
}

pub fn connect(config: &WgTunnelConfig) -> Result<String, String> {
    let mut request = Request::new(2, OP_CONNECT);
    request.config = Some(config.clone());
    match call(&request, 20_000) {
        Ok(response) if response.ok => Ok("Connected".into()),
        Ok(response) => Err(user_message(&response.code).into()),
        Err(code) => Err(user_message(code).into()),
    }
}

pub fn kick_reconnect() -> bool {
    matches!(
        call(&Request::new(7, veritas_ipc::OP_SOFT_RECONNECT), 20_000),
        Ok(response) if response.ok
    )
}

pub fn soft_reconnect(config: &crate::WgTunnelConfig) -> bool {
    let mut request = Request::new(8, veritas_ipc::OP_SOFT_RECONNECT);
    request.config = Some(config.clone());
    matches!(call(&request, 20_000), Ok(response) if response.ok)
}

pub fn disconnect() -> Result<String, String> {
    match call(&Request::new(3, OP_DISCONNECT), 10_000) {
        Ok(response) if response.ok => Ok("Disconnected".into()),
        Ok(_) | Err(_) => Err("Couldn't finish disconnecting. Try again.".into()),
    }
}

/// Report a leftover lock. Does not release it. A dead service is not a leftover:
/// recovery restarts it, and the filters stay until Disconnect, uninstall, or Repair.
pub fn lock_status() -> StaleLock {
    match call(&Request::new(5, OP_DIAGNOSTICS), 2_000) {
        Ok(response) if response.ok => StaleLock {
            blocked: response
                .diagnostics
                .as_ref()
                .is_some_and(|item| item.kill_switch),
            cleared: false,
        },
        _ => StaleLock {
            blocked: false,
            cleared: false,
        },
    }
}

pub fn repair_lock() -> StaleLock {
    match call(&Request::new(6, OP_CLEAR_LOCK), 10_000) {
        Ok(response) if response.ok => StaleLock {
            blocked: false,
            cleared: true,
        },
        _ => StaleLock {
            blocked: true,
            cleared: false,
        },
    }
}

pub struct StaleLock {
    pub blocked: bool,
    pub cleared: bool,
}

pub fn stats() -> WgTransferStats {
    match call(&Request::new(4, OP_STATS), 2_000) {
        Ok(response) => from_status(response.status.as_ref()),
        Err(_) => from_status(None),
    }
}

fn from_status(status: Option<&TunnelStatus>) -> WgTransferStats {
    let Some(status) = status else {
        return empty();
    };
    WgTransferStats {
        rx_bytes: status.rx,
        tx_bytes: status.tx,
        last_handshake_sec: status.handshake,
        interface_up: status.up,
        underlay_notice: status.notice.clone(),
        underlay_reconnecting: status.reconnecting,
        underlay_gave_up: false,
        underlay_path_ok: status.up || status.reconnecting,
        stalled: status.stalled,
    }
}

fn empty() -> WgTransferStats {
    WgTransferStats {
        rx_bytes: 0,
        tx_bytes: 0,
        last_handshake_sec: 0,
        interface_up: false,
        underlay_notice: String::new(),
        underlay_reconnecting: false,
        underlay_gave_up: false,
        underlay_path_ok: false,
        stalled: false,
    }
}

fn call(request: &Request, timeout_ms: u32) -> Result<Response, &'static str> {
    let bytes = encode(request).map_err(|_| CODE_UNAVAILABLE)?;
    let name = wide(PIPE_NAME);
    let mut out = vec![0u8; veritas_ipc::MAX_MESSAGE];
    let mut read = 0u32;
    let ok = unsafe {
        CallNamedPipeW(
            name.as_ptr(),
            bytes.as_ptr().cast(),
            bytes.len() as u32,
            out.as_mut_ptr().cast(),
            out.len() as u32,
            &mut read,
            timeout_ms,
        )
    };
    if ok == 0 {
        let err = unsafe { GetLastError() };
        if err == ERROR_PIPE_BUSY {
            return Err(veritas_ipc::CODE_BUSY);
        }
        if err == ERROR_ACCESS_DENIED {
            return Err(veritas_ipc::CODE_DENIED);
        }
        return Err(CODE_UNAVAILABLE);
    }
    veritas_ipc::decode_response(&out[..read as usize]).map_err(|_| CODE_UNAVAILABLE)
}

fn wide(value: &str) -> Vec<u16> {
    use std::os::windows::ffi::OsStrExt;
    std::ffi::OsStr::new(value).encode_wide().chain(std::iter::once(0)).collect()
}
