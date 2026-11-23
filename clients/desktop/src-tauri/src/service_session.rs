//! A session the service runs that this window did not start. Kept free of
//! Windows calls so the rules are unit tested on every platform.

use serde::Serialize;
use veritas_ipc::SessionInfo;

/// What the window gets back on start. `active` is false when there is
/// nothing to adopt.
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize)]
pub struct AttachedSession {
    pub active: bool,
    pub peer_id: String,
    pub stealth: bool,
    pub dns: String,
}

/// A session is adopted only when the adapter is up and the service still
/// holds the saved connect. A tunnel with no saved session is a leftover and
/// is handled by Restore internet, never shown as Connected.
pub fn adoptable(up: bool, session: Option<SessionInfo>) -> Option<SessionInfo> {
    let session = session?;
    if !up || session.peer_id.trim().is_empty() || session.dns.trim().is_empty() {
        return None;
    }
    Some(session)
}

pub fn attached(session: Option<SessionInfo>) -> AttachedSession {
    match session {
        Some(info) => AttachedSession {
            active: true,
            peer_id: info.peer_id,
            stealth: info.stealth,
            dns: info.dns,
        },
        None => AttachedSession::default(),
    }
}

/// Anything the service still runs must end before a fresh Connect.
pub fn needs_release(up: bool, has_session: bool) -> bool {
    up || has_session
}

#[cfg(test)]
mod tests {
    use super::*;

    fn info(peer: &str, dns: &str) -> SessionInfo {
        SessionInfo {
            peer_id: peer.into(),
            stealth: false,
            dns: dns.into(),
        }
    }

    #[test]
    fn adopts_only_a_live_saved_session() {
        assert_eq!(adoptable(true, Some(info("peer-1", "10.8.0.1"))), Some(info("peer-1", "10.8.0.1")));
        assert_eq!(adoptable(false, Some(info("peer-1", "10.8.0.1"))), None, "adapter down");
        assert_eq!(adoptable(true, None), None, "a leftover adapter is not a session");
        assert_eq!(adoptable(true, Some(info(" ", "10.8.0.1"))), None);
        assert_eq!(adoptable(true, Some(info("peer-1", ""))), None);
    }

    #[test]
    fn attached_shape() {
        let none = attached(None);
        assert!(!none.active);
        let mut stealth = info("peer-2", "10.8.0.1");
        stealth.stealth = true;
        let some = attached(Some(stealth));
        assert!(some.active && some.stealth);
        assert_eq!(some.peer_id, "peer-2");
        let text = serde_json::to_string(&some).unwrap();
        assert!(!text.contains("key"), "{text}");
    }

    #[test]
    fn release_before_connect() {
        assert!(needs_release(true, false));
        assert!(needs_release(false, true));
        assert!(!needs_release(false, false));
    }
}
