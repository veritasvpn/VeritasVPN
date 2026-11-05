//! Same decisions as `clients/desktop/src/reconnectPolicy.ts`.
//! Keep the two copies in step. The UI still uses the TypeScript file.

pub const HANDSHAKE_FRESH_SEC: i64 = 180;
pub const RECOVERY_MAX_ATTEMPTS: u32 = 3;

const GIVE_UP: &str = "Couldn't reconnect on this network. Check your connection and try again.";
const TUNNEL_RETRY: &str = "Couldn't start the tunnel. Try connecting again.";

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum PeerCheckKind {
    Alive,
    Gone,
    Network,
    Refresh,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum WatchdogAction {
    Wait,
    Fail,
    Ignore,
}

/// Mirrors Android's peer-check outcome. Only a real 404 or 410 is peer-gone.
pub fn classify_peer_check(status: Option<u16>, network_error: bool) -> PeerCheckKind {
    match status {
        Some(200) => PeerCheckKind::Alive,
        Some(401) => PeerCheckKind::Refresh,
        Some(404) | Some(410) => PeerCheckKind::Gone,
        _ if network_error || status.is_none() || status == Some(0) => PeerCheckKind::Network,
        _ => PeerCheckKind::Network,
    }
}

/// "Reconnecting…" keeps the session. "Tap to reconnect" means auto-reconnect gave up.
pub fn underlay_notice_keeps_session(notice: Option<&str>) -> bool {
    let text = notice.unwrap_or("").trim();
    let lower = text.to_ascii_lowercase();
    let rest = match lower.strip_prefix("reconnecting") {
        Some(rest) => rest,
        None => return false,
    };
    rest.is_empty() || !rest.chars().next().is_some_and(|c| c.is_ascii_alphanumeric() || c == '_')
}

/// The connect circle disconnects while protected and while reconnecting.
pub fn connect_control_disconnects(phase: Option<&str>) -> bool {
    matches!(phase, Some("protected") | Some("reconnecting"))
}

pub struct SessionInput {
    pub interface_up: bool,
    pub last_handshake_sec: i64,
    pub now_sec: i64,
    pub path_ok: bool,
}

pub fn session_looks_connected(input: SessionInput) -> bool {
    if !input.interface_up || !input.path_ok {
        return false;
    }
    if input.last_handshake_sec <= 0 {
        return false;
    }
    let age = input.now_sec.saturating_sub(input.last_handshake_sec);
    age >= 0 && age <= HANDSHAKE_FRESH_SEC
}

pub struct ReconnectingInput {
    pub healthy: bool,
    pub stalled: bool,
    pub underlay_reconnecting: bool,
    pub notice_keeps_session: bool,
}

pub fn keeps_reconnecting_view(input: ReconnectingInput) -> bool {
    if input.stalled {
        return true;
    }
    !input.healthy && (input.underlay_reconnecting || input.notice_keeps_session)
}

pub struct WatchdogInput<'a> {
    pub epoch: u64,
    pub active_epoch: u64,
    pub connecting: bool,
    pub disconnect_message: Option<&'a str>,
}

pub fn connect_watchdog_action(input: WatchdogInput<'_>) -> WatchdogAction {
    if input.epoch != input.active_epoch || !input.connecting {
        return WatchdogAction::Ignore;
    }
    if input
        .disconnect_message
        .unwrap_or("")
        .to_ascii_lowercase()
        .contains("still in progress")
    {
        return WatchdogAction::Wait;
    }
    WatchdogAction::Fail
}

/// `attempt` is the zero-based index of the try that just failed.
pub fn recovery_retry_action(attempt: i64) -> &'static str {
    if attempt < 0 {
        return "stop";
    }
    if attempt as u32 + 1 >= RECOVERY_MAX_ATTEMPTS {
        return "stop";
    }
    "retry"
}

pub fn peer_gone_latch_held(latched: bool, peer_alive: bool, user_connect: bool) -> bool {
    if !latched || user_connect || peer_alive {
        return false;
    }
    true
}

pub fn peer_gone_action(peer_gone: bool, in_flight: bool, latched: bool) -> &'static str {
    if !peer_gone {
        return "ignore";
    }
    if latched {
        return "latched";
    }
    if in_flight {
        return "in_flight";
    }
    "create"
}

pub fn tunnel_address(raw: Option<&str>) -> String {
    let value = raw.unwrap_or("").trim();
    if value.is_empty() || value.contains('/') {
        return value.to_string();
    }
    if is_ipv4_host(value) {
        return format!("{value}/32");
    }
    if is_ipv6_host(value) {
        return format!("{value}/128");
    }
    value.to_string()
}

fn is_ipv4_host(value: &str) -> bool {
    let parts: Vec<&str> = value.split('.').collect();
    if parts.len() != 4 {
        return false;
    }
    parts.iter().all(|part| {
        if part.is_empty() || part.len() > 3 || !part.bytes().all(|b| b.is_ascii_digit()) {
            return false;
        }
        part.parse::<u16>().ok().is_some_and(|n| n <= 255)
    })
}

fn is_ipv6_host(value: &str) -> bool {
    if !value.contains(':') || value.contains('/') {
        return false;
    }
    if !value
        .bytes()
        .all(|b| b.is_ascii_hexdigit() || b == b':')
    {
        return false;
    }
    if value.split("::").count() > 2 {
        return false;
    }
    let groups: Vec<&str> = value.split(':').filter(|part| !part.is_empty()).collect();
    !groups.is_empty()
        && groups.len() <= 8
        && groups.iter().all(|part| part.len() <= 4)
}

/// Raw timeout and exception text never reach the status line.
/// A sentence that already tells the user what to do is left as written.
pub fn display_status(raw: Option<&str>) -> String {
    let Some(raw) = raw else {
        return String::new();
    };
    let text = raw.trim();
    if text.is_empty() {
        return String::new();
    }
    if is_raw_transport_error(text) {
        return GIVE_UP.to_string();
    }
    if is_raw_address_error(text) {
        return TUNNEL_RETRY.to_string();
    }
    text.to_string()
}

fn is_raw_address_error(text: &str) -> bool {
    let lower = text.to_ascii_lowercase();
    if lower.contains("cidr notation") {
        return true;
    }
    if lower.contains("prefix is expected") {
        return true;
    }
    if lower == "invalid address" || lower == "invalid address prefix" {
        return true;
    }
    if lower.starts_with("invalid allowed ip") {
        return true;
    }
    if lower == "missing assigned address" {
        return true;
    }
    false
}

fn is_raw_transport_error(text: &str) -> bool {
    let lower = text.to_ascii_lowercase();
    if lower == "timeout" || lower == "timed out" {
        return true;
    }
    if lower.contains("aborterror") {
        return true;
    }
    if lower.contains("etimedout") {
        return true;
    }
    if lower.contains("error sending request") {
        return true;
    }
    if lower.contains("os error") {
        return true;
    }
    if lower.contains("exception") {
        return true;
    }
    if lower.contains("timed out") && !contains_user_guidance(text) {
        return true;
    }
    false
}

fn contains_user_guidance(text: &str) -> bool {
    let lower = text.to_ascii_lowercase();
    lower.contains("try again") || lower.contains("check your")
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn peer_check_matches_the_ui_cases() {
        assert_eq!(classify_peer_check(Some(200), false), PeerCheckKind::Alive);
        assert_eq!(classify_peer_check(Some(401), false), PeerCheckKind::Refresh);
        assert_eq!(classify_peer_check(Some(404), false), PeerCheckKind::Gone);
        assert_eq!(classify_peer_check(Some(410), true), PeerCheckKind::Gone);
        assert_eq!(classify_peer_check(Some(0), true), PeerCheckKind::Network);
        assert_eq!(classify_peer_check(None, true), PeerCheckKind::Network);
        assert_eq!(classify_peer_check(None, false), PeerCheckKind::Network);
        assert_eq!(classify_peer_check(Some(500), false), PeerCheckKind::Network);
        assert_eq!(classify_peer_check(Some(200), true), PeerCheckKind::Alive);
    }

    #[test]
    fn peer_gone_creates_once_then_latches() {
        assert_eq!(peer_gone_action(true, false, false), "create");
        assert_eq!(peer_gone_action(true, true, false), "in_flight");
        assert_eq!(peer_gone_action(false, false, false), "ignore");
        assert_eq!(peer_gone_action(true, false, true), "latched");
        assert_eq!(recovery_retry_action(0), "retry");
        assert_eq!(recovery_retry_action(1), "retry");
        assert_eq!(recovery_retry_action(2), "stop");
        assert!(!peer_gone_latch_held(true, true, false));
        assert!(peer_gone_latch_held(true, false, false));
    }

    #[test]
    fn status_text_hides_raw_errors() {
        let friendly = "Couldn't reconnect on this network. Check your connection and try again.";
        assert_eq!(display_status(Some("timeout")), friendly);
        assert_eq!(display_status(Some("timed out")), friendly);
        assert_eq!(display_status(Some("Request timed out")), friendly);
        assert_eq!(
            display_status(Some("AbortError: The operation was aborted")),
            friendly
        );
        assert_eq!(display_status(Some("os error 110")), friendly);
        assert_eq!(
            display_status(Some("Connection timed out. Check your network and try again.")),
            "Connection timed out. Check your network and try again."
        );
        assert_eq!(display_status(Some("")), "");
        assert_eq!(display_status(None), "");
        let retry = "Couldn't start the tunnel. Try connecting again.";
        assert_eq!(display_status(Some("address must be CIDR notation")), retry);
        assert!(!display_status(Some("address must be CIDR notation")).contains("CIDR"));
        assert_eq!(
            display_status(Some("An active subscription is required. Open Account to subscribe.")),
            "An active subscription is required. Open Account to subscribe."
        );
    }

    #[test]
    fn session_and_reconnecting_match_the_ui() {
        assert!(underlay_notice_keeps_session(Some("Reconnecting…")));
        assert!(underlay_notice_keeps_session(Some("reconnecting")));
        assert!(!underlay_notice_keeps_session(Some("Tap to reconnect")));
        assert!(!underlay_notice_keeps_session(None));
        let now = 1_700_000_000;
        assert!(session_looks_connected(SessionInput {
            interface_up: true,
            last_handshake_sec: now - 30,
            now_sec: now,
            path_ok: true,
        }));
        assert!(!session_looks_connected(SessionInput {
            interface_up: true,
            last_handshake_sec: now - 181,
            now_sec: now,
            path_ok: true,
        }));
        assert!(keeps_reconnecting_view(ReconnectingInput {
            healthy: true,
            stalled: true,
            underlay_reconnecting: false,
            notice_keeps_session: false,
        }));
        assert!(!keeps_reconnecting_view(ReconnectingInput {
            healthy: true,
            stalled: false,
            underlay_reconnecting: true,
            notice_keeps_session: true,
        }));
        assert_eq!(
            connect_watchdog_action(WatchdogInput {
                epoch: 1,
                active_epoch: 1,
                connecting: true,
                disconnect_message: Some("WireGuard connect still in progress"),
            }),
            WatchdogAction::Wait
        );
        assert_eq!(
            connect_watchdog_action(WatchdogInput {
                epoch: 1,
                active_epoch: 1,
                connecting: true,
                disconnect_message: Some("WireGuard disconnected"),
            }),
            WatchdogAction::Fail
        );
        assert!(connect_control_disconnects(Some("protected")));
        assert!(!connect_control_disconnects(Some("connecting")));
    }

    #[test]
    fn tunnel_address_adds_the_host_prefix() {
        assert_eq!(tunnel_address(Some("10.7.0.2")), "10.7.0.2/32");
        assert_eq!(tunnel_address(Some(" 10.7.0.2 ")), "10.7.0.2/32");
        assert_eq!(tunnel_address(Some("10.7.0.2/32")), "10.7.0.2/32");
        assert_eq!(tunnel_address(Some("fd00::2")), "fd00::2/128");
        assert_eq!(tunnel_address(None), "");
        assert_eq!(tunnel_address(Some("not-an-address")), "not-an-address");
    }
}
