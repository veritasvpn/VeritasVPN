//! Stand-in for the Linux network-switch watcher on every other OS.
//! The Linux file is compiled unchanged. These functions only exist so the
//! shared command handlers still type-check. They do not touch adapters.

use serde::{Deserialize, Serialize};

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

pub fn save_last_config(_config: &SavedTunnelConfig) -> Result<(), String> {
    Ok(())
}

pub fn recover_network_switch() {}

pub(crate) fn clear_underlay_watch() {}

pub(crate) fn mark_reconnect_wanted() {}

pub(crate) fn clear_reconnect_wanted() {}

pub(crate) fn clear_underlay_notice() {}

#[derive(Clone, Copy, PartialEq, Eq)]
pub(crate) enum FlightKind {
    Close,
    Teardown,
    Rebind,
}

pub(crate) struct FlightGuard;

impl FlightGuard {
    pub(crate) fn enter(_kind: FlightKind) -> Option<Self> {
        Some(Self)
    }
}

pub(crate) fn recovery_lock_acquire_owned() -> Option<u64> {
    None
}

pub(crate) fn recovery_lock_abandon(_token: u64) {}

pub(crate) fn recovery_lock_release_owned() {}
