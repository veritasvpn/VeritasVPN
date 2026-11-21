//! Shared desktop logic. The Linux app and the future Windows service both
//! call these checks. The React UI still has its own copy of the reconnect
//! rules in `reconnectPolicy.ts`; the tests here mirror that file.

mod config;
mod keys;
mod reconnect;

pub use config::{ensure_host_cidr, normalize_path_prefix, WgTunnelConfig};
pub use keys::{b64_key_to_hex, generate_keypair, KeyPair};
pub use reconnect::{
    classify_peer_check, connect_control_disconnects, connect_watchdog_action, display_status,
    keeps_reconnecting_view, peer_gone_action, peer_gone_latch_held, recovery_retry_action,
    session_looks_connected, tunnel_address, underlay_notice_keeps_session, PeerCheckKind,
    WatchdogAction,
};
