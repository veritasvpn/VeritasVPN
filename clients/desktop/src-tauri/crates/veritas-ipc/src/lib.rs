//! Pipe messages for protocol version 1.
//!
//! A message is one JSON object, at most 64 KiB. The service returns a stable
//! `code` and never a raw operating-system string. The window maps that code
//! with [`user_message`] before anything is shown.

use serde::{Deserialize, Serialize};
use veritas_core::WgTunnelConfig;

pub const VERSION: u32 = 1;
pub const MAX_MESSAGE: usize = 64 * 1024;
pub const PIPE_NAME: &str = r"\\.\pipe\VeritasVPN";

pub const OP_HELLO: &str = "hello";
pub const OP_STATUS: &str = "status";
pub const OP_CONNECT: &str = "connect";
pub const OP_DISCONNECT: &str = "disconnect";
pub const OP_STATS: &str = "stats";
pub const OP_SOFT_RECONNECT: &str = "soft_reconnect";
pub const OP_SET_OPTIONS: &str = "set_options";
pub const OP_CLEAR_LOCK: &str = "clear_lock";
pub const OP_DIAGNOSTICS: &str = "diagnostics";

pub const CODE_UNAVAILABLE: &str = "unavailable";
pub const CODE_INVALID_CONFIG: &str = "invalid_config";
pub const CODE_TUNNEL_FAILED: &str = "tunnel_failed";
pub const CODE_DENIED: &str = "denied";
pub const CODE_NOT_IN_THIS_BUILD: &str = "not_in_this_build";
pub const CODE_LOCK_FAILED: &str = "lock_failed";
pub const CODE_BUSY: &str = "busy";

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Request {
    pub v: u32,
    pub id: u64,
    pub op: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub config: Option<WgTunnelConfig>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub local_network: Option<bool>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub stealth: Option<bool>,
    /// Test harness only. Forces the block-all plan without a LAN permit.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub full_lock: Option<bool>,
    /// Test harness only. Lets TCP 80 and 443 through so the CI runner can
    /// keep talking to GitHub while every other port stays blocked.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub permit_web: Option<bool>,
    /// Test harness only. IPv4 hosts the runner must still reach (agent, DNS, gateway).
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub management_v4: Vec<String>,
    /// Test harness only. IPv6 hosts the runner must still reach.
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub management_v6: Vec<String>,
}

impl Request {
    pub fn new(id: u64, op: &str) -> Self {
        Self {
            v: VERSION,
            id,
            op: op.to_string(),
            config: None,
            local_network: None,
            stealth: None,
            full_lock: None,
            permit_web: None,
            management_v4: Vec::new(),
            management_v6: Vec::new(),
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TunnelStatus {
    pub up: bool,
    pub rx: u64,
    pub tx: u64,
    /// Unix seconds. Zero when the peer has not answered.
    pub handshake: i64,
    /// The UI shows Reconnecting while this is set. The lock stays up.
    #[serde(default)]
    pub reconnecting: bool,
    /// Lost handshake. The UI shows Reconnecting for this too.
    #[serde(default)]
    pub stalled: bool,
    /// Fixed sentence. Empty when there is nothing to say.
    #[serde(default)]
    pub notice: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Diagnostics {
    pub service: bool,
    pub stealth: bool,
    pub kill_switch: bool,
    pub local_network: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Response {
    pub v: u32,
    pub id: u64,
    pub ok: bool,
    pub code: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub status: Option<TunnelStatus>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub diagnostics: Option<Diagnostics>,
}

impl Response {
    pub fn ok(id: u64) -> Self {
        Self {
            v: VERSION,
            id,
            ok: true,
            code: String::new(),
            status: None,
            diagnostics: None,
        }
    }

    pub fn fail(id: u64, code: &str) -> Self {
        Self {
            v: VERSION,
            id,
            ok: false,
            code: code.to_string(),
            status: None,
            diagnostics: None,
        }
    }
}

/// Sentences the window may show. None of these are raw errors or timeouts.
pub fn user_message(code: &str) -> &'static str {
    match code {
        CODE_UNAVAILABLE => "The VeritasVPN background service is not running.",
        CODE_BUSY => "A connection is already in progress.",
        CODE_LOCK_FAILED => "Kill switch could not be enabled. Connect was cancelled so traffic stays unprotected only while you fix it.",
        _ => "Couldn't start the tunnel. Try connecting again.",
    }
}

pub fn encode(value: &impl Serialize) -> Result<Vec<u8>, ()> {
    let bytes = serde_json::to_vec(value).map_err(|_| ())?;
    if bytes.len() > MAX_MESSAGE || bytes.is_empty() {
        return Err(());
    }
    Ok(bytes)
}

pub fn decode_request(bytes: &[u8]) -> Result<Request, ()> {
    if bytes.is_empty() || bytes.len() > MAX_MESSAGE {
        return Err(());
    }
    let request: Request = serde_json::from_slice(bytes).map_err(|_| ())?;
    if request.v != VERSION || request.op.len() > 32 || request.id == 0 {
        return Err(());
    }
    Ok(request)
}

pub fn decode_response(bytes: &[u8]) -> Result<Response, ()> {
    if bytes.is_empty() || bytes.len() > MAX_MESSAGE {
        return Err(());
    }
    let response: Response = serde_json::from_slice(bytes).map_err(|_| ())?;
    if response.v != VERSION {
        return Err(());
    }
    Ok(response)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn round_trip_rejects_oversize_and_hides_raw_text() {
        let mut request = Request::new(1, OP_HELLO);
        let bytes = encode(&request).unwrap();
        let back = decode_request(&bytes).unwrap();
        assert_eq!(back.op, OP_HELLO);
        request.v = 99;
        assert!(decode_request(&encode(&request).unwrap()).is_err());
        assert!(decode_request(&[b'{'; MAX_MESSAGE + 1]).is_err());
        for code in [
            CODE_UNAVAILABLE,
            CODE_INVALID_CONFIG,
            CODE_TUNNEL_FAILED,
            CODE_DENIED,
            CODE_NOT_IN_THIS_BUILD,
            CODE_LOCK_FAILED,
            CODE_BUSY,
            "timeout",
            "os error 5",
        ] {
            let text = user_message(code);
            let lower = text.to_ascii_lowercase();
            assert!(!lower.contains("os error"), "{text}");
            assert!(!lower.contains("exception"), "{text}");
            assert!(!lower.contains("timed out"), "{text}");
            assert_ne!(lower, "timeout");
        }
    }
}
