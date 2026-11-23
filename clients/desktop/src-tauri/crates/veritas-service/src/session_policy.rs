//! Rules for a session the window did not start, kept free of Windows calls
//! so they run in the Linux unit tests too.
//!
//! The service keeps a session across its own restart (crash recovery and the
//! boot resume). A reinstall used to restart it with the old session still on
//! disk, so the new service brought the old tunnel back while the new window
//! showed Not connected. These rules decide what Connect, Restore internet,
//! Disconnect, and the installer do with such a session.

use veritas_ipc::SessionInfo;

/// One step of tearing a session down. The service runs them in this order
/// while it holds the session lock, so the monitor can never resume the
/// session between steps.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Teardown {
    /// Delete the sealed session on disk so nothing resumes it.
    ForgetSaved,
    /// Drop the helper, the server pin, the routes, and the adapter.
    ReleaseTunnel,
    /// Reset the reconnect monitor so it shows no Reconnecting state.
    ClearMonitor,
    /// Remove the kill-switch filters and the DNS policy.
    ReleaseLock,
    /// Turn off any adapter named VeritasVPN this session does not hold.
    StaleAdapter,
}

/// Disconnect and Restore internet both end with no tunnel, no lock, no
/// saved session, and no VeritasVPN adapter. Restore used to only remove the
/// filters, so a live tunnel kept every route.
pub const FULL_TEARDOWN: [Teardown; 5] = [
    Teardown::ForgetSaved,
    Teardown::ReleaseTunnel,
    Teardown::ClearMonitor,
    Teardown::ReleaseLock,
    Teardown::StaleAdapter,
];

/// What Connect does before it brings a tunnel up.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ConnectStart {
    /// Nothing is running.
    Fresh,
    /// A session the window does not know about is still up. It is torn down
    /// first (the lock stays and is re-armed for the new tunnel in one
    /// transaction) so the old adapter cannot capture the new handshake.
    ReplaceStale,
}

pub fn connect_start(has_adapter: bool, has_saved: bool) -> ConnectStart {
    if has_adapter || has_saved {
        ConnectStart::ReplaceStale
    } else {
        ConnectStart::Fresh
    }
}

/// Steps run before a replacing Connect. The lock is not released: the new
/// tunnel re-arms it, and if the new tunnel fails the lock is released then.
pub const REPLACE_TEARDOWN: [Teardown; 4] = [
    Teardown::ForgetSaved,
    Teardown::ReleaseTunnel,
    Teardown::ClearMonitor,
    Teardown::StaleAdapter,
];

/// A Connect that failed after it replaced a session must not leave the old
/// lock blocking a machine that now has no tunnel. A fresh Connect that fails
/// armed nothing, so there is nothing to release.
pub fn release_lock_after_failed_connect(start: ConnectStart) -> bool {
    start == ConnectStart::ReplaceStale
}

/// The part of a running session a newly opened window may adopt.
pub fn session_info(peer_id: &str, stealth_endpoint: &str, dns: &str) -> SessionInfo {
    SessionInfo {
        peer_id: peer_id.trim().to_string(),
        stealth: !stealth_endpoint.trim().is_empty(),
        dns: dns.trim().to_string(),
    }
}

/// The installer hooks, as shipped. Checked in tests so a reinstall always
/// clears the old session before the new service starts.
#[cfg(test)]
const INSTALLER_HOOKS: &str = include_str!("../../../windows/hooks.nsh");

#[cfg(test)]
mod tests {
    use super::*;

    fn macro_body<'a>(hooks: &'a str, name: &str) -> &'a str {
        let start = hooks
            .find(&format!("!macro {name}"))
            .unwrap_or_else(|| panic!("{name} missing"));
        let rest = &hooks[start..];
        let end = rest.find("!macroend").expect("macroend");
        &rest[..end]
    }

    fn position(body: &str, needle: &str) -> usize {
        body.find(needle).unwrap_or_else(|| panic!("{needle} missing in\n{body}"))
    }

    #[test]
    fn disconnect_and_restore_remove_everything() {
        for step in [
            Teardown::ForgetSaved,
            Teardown::ReleaseTunnel,
            Teardown::ClearMonitor,
            Teardown::ReleaseLock,
            Teardown::StaleAdapter,
        ] {
            assert!(FULL_TEARDOWN.contains(&step), "{step:?}");
        }
        // The saved session goes first so the monitor cannot resume it.
        assert_eq!(FULL_TEARDOWN[0], Teardown::ForgetSaved);
        let tunnel = FULL_TEARDOWN.iter().position(|s| *s == Teardown::ReleaseTunnel).unwrap();
        let lock = FULL_TEARDOWN.iter().position(|s| *s == Teardown::ReleaseLock).unwrap();
        // Routes go before the filters, so nothing leaks through a dead tunnel
        // route the moment the lock lifts.
        assert!(tunnel < lock);
    }

    #[test]
    fn connect_replaces_a_stale_session_and_keeps_the_lock() {
        assert_eq!(connect_start(false, false), ConnectStart::Fresh);
        assert_eq!(connect_start(true, false), ConnectStart::ReplaceStale);
        assert_eq!(connect_start(false, true), ConnectStart::ReplaceStale);
        assert_eq!(connect_start(true, true), ConnectStart::ReplaceStale);
        assert!(!REPLACE_TEARDOWN.contains(&Teardown::ReleaseLock));
        assert!(REPLACE_TEARDOWN.contains(&Teardown::ReleaseTunnel));
        assert!(REPLACE_TEARDOWN.contains(&Teardown::StaleAdapter));
        assert_eq!(REPLACE_TEARDOWN[0], Teardown::ForgetSaved);
        assert!(release_lock_after_failed_connect(ConnectStart::ReplaceStale));
        assert!(!release_lock_after_failed_connect(ConnectStart::Fresh));
    }

    #[test]
    fn session_info_carries_no_keys() {
        let info = session_info(" peer-9 ", "vpn.example:443", " 10.8.0.1 ");
        assert_eq!(info.peer_id, "peer-9");
        assert!(info.stealth);
        assert_eq!(info.dns, "10.8.0.1");
        assert!(!session_info("p", "  ", "d").stealth);
        let text = serde_json::to_string(&info).unwrap();
        for word in ["private", "preshared", "endpoint"] {
            assert!(!text.contains(word), "{text}");
        }
    }

    #[test]
    fn reinstall_clears_the_old_session_before_the_new_service_starts() {
        let post = macro_body(INSTALLER_HOOKS, "NSIS_HOOK_POSTINSTALL");
        let reset = position(post, "veritas-service.exe\" reset");
        let install = position(post, "veritas-service.exe\" install");
        let start = position(post, "veritas-service.exe\" start");
        assert!(reset < install && install < start, "reset must run before install and start");

        // The old service is stopped first, then asked to clear its session.
        // An older service that has no reset command just prints its usage.
        let pre = macro_body(INSTALLER_HOOKS, "NSIS_HOOK_PREINSTALL");
        let stop = position(pre, "veritas-service.exe\" stop");
        let pre_reset = position(pre, "veritas-service.exe\" reset");
        assert!(stop < pre_reset);
    }
}
