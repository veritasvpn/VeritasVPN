//! Platform boundary for the tunnel engine.
//!
//! Linux and macOS call the existing bring-up functions unchanged. Windows
//! sends Direct-mode commands to the background service. Stealth stays off.
//! The kill switch is enforced by the service, not by this process.

use tauri::AppHandle;

use crate::{WgTransferStats, WgTunnelConfig};

pub(crate) trait TunnelBackend {
    fn available(&self, app: &AppHandle) -> bool;
    fn bring_up(&self, app: &AppHandle, config: &WgTunnelConfig) -> Result<String, String>;
    fn bring_down(&self, app: &AppHandle) -> Result<String, String>;
    fn stats(&self) -> WgTransferStats;
}

pub(crate) fn backend() -> &'static dyn TunnelBackend {
    #[cfg(target_os = "linux")]
    {
        &LinuxTunnel
    }
    #[cfg(target_os = "windows")]
    {
        &WindowsTunnel
    }
    #[cfg(not(any(target_os = "linux", target_os = "windows")))]
    {
        &OtherTunnel
    }
}

#[cfg(target_os = "linux")]
struct LinuxTunnel;

#[cfg(target_os = "linux")]
impl TunnelBackend for LinuxTunnel {
    fn available(&self, app: &AppHandle) -> bool {
        crate::resolve_wireguard_go(app).is_ok()
    }

    fn bring_up(&self, app: &AppHandle, config: &WgTunnelConfig) -> Result<String, String> {
        crate::bring_up_wireguard(app, config)
    }

    fn bring_down(&self, app: &AppHandle) -> Result<String, String> {
        crate::bring_down_wireguard(app)
    }

    fn stats(&self) -> WgTransferStats {
        crate::wireguard_stats_linux()
    }
}

#[cfg(target_os = "windows")]
struct WindowsTunnel;

#[cfg(target_os = "windows")]
impl TunnelBackend for WindowsTunnel {
    fn available(&self, _app: &AppHandle) -> bool {
        crate::win_pipe::available()
    }

    fn bring_up(&self, _app: &AppHandle, config: &WgTunnelConfig) -> Result<String, String> {
        crate::win_pipe::connect(config)
    }

    fn bring_down(&self, _app: &AppHandle) -> Result<String, String> {
        crate::win_pipe::disconnect()
    }

    fn stats(&self) -> WgTransferStats {
        crate::win_pipe::stats()
    }
}

#[cfg(not(any(target_os = "linux", target_os = "windows")))]
struct OtherTunnel;

#[cfg(not(any(target_os = "linux", target_os = "windows")))]
impl TunnelBackend for OtherTunnel {
    fn available(&self, app: &AppHandle) -> bool {
        crate::resolve_wireguard_go(app).is_ok()
    }

    fn bring_up(&self, app: &AppHandle, config: &WgTunnelConfig) -> Result<String, String> {
        crate::bring_up_wireguard(app, config)
    }

    fn bring_down(&self, app: &AppHandle) -> Result<String, String> {
        crate::bring_down_wireguard(app)
    }

    fn stats(&self) -> WgTransferStats {
        empty_stats()
    }
}

#[cfg(not(target_os = "linux"))]
fn empty_stats() -> WgTransferStats {
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
