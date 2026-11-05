//! When to show Reconnecting and when to kick the tunnel.
//! Nothing here touches Windows, the lock, or the transport mode.

use std::time::Duration;

/// Backoff cap from the Windows plan. Retries never grow past this.
pub const BACKOFF_CAP: Duration = Duration::from_secs(30);

/// Lost-handshake window. Matches the desktop stall rule: an idle rekey
/// around two minutes must not count as a failure.
pub const HANDSHAKE_STALE: Duration = Duration::from_secs(180);
pub const STALL_WINDOW: Duration = Duration::from_secs(15);
pub const NO_HANDSHAKE_AFTER: Duration = Duration::from_secs(20);

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Notice {
    Quiet,
    Reconnecting,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Action {
    pub notice: Notice,
    pub refresh_route: bool,
    pub kick: bool,
    /// Always false. Reconnect never drops the kill switch.
    pub release_lock: bool,
    /// Always false. Reconnect never changes Direct or Stealth, or the network.
    pub switch_transport: bool,
}

impl Action {
    fn quiet() -> Self {
        Self {
            notice: Notice::Quiet,
            refresh_route: false,
            kick: false,
            release_lock: false,
            switch_transport: false,
        }
    }

    fn reconnecting(kick: bool, refresh_route: bool) -> Self {
        Self {
            notice: Notice::Reconnecting,
            refresh_route,
            kick,
            release_lock: false,
            switch_transport: false,
        }
    }
}

pub fn on_link_down() -> Action {
    Action::reconnecting(false, false)
}

pub fn on_link_up() -> Action {
    Action::reconnecting(true, true)
}

pub fn on_underlay_change() -> Action {
    Action::reconnecting(true, true)
}

pub fn on_sleep() -> Action {
    Action::reconnecting(false, false)
}

pub fn on_resume() -> Action {
    Action::reconnecting(true, true)
}

pub fn on_stall(kick_due: bool) -> Action {
    Action::reconnecting(kick_due, kick_due)
}

pub fn on_boot(saved_intent: bool) -> Action {
    if saved_intent {
        Action::reconnecting(true, true)
    } else {
        Action::quiet()
    }
}

pub fn on_healthy() -> Action {
    Action::quiet()
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Backoff {
    next: Duration,
}

impl Backoff {
    pub const fn new() -> Self {
        Self {
            next: Duration::from_secs(1),
        }
    }

    pub fn next_delay(&mut self) -> Duration {
        let current = self.next;
        let doubled = current.saturating_mul(2);
        self.next = if doubled > BACKOFF_CAP { BACKOFF_CAP } else { doubled };
        current
    }

    pub fn reset(&mut self) {
        *self = Self::new();
    }
}

/// `elapsed` is how long receive counters have been flat.
pub fn handshake_lost(
    elapsed: Duration,
    rx_delta: u64,
    tx_delta: u64,
    handshake_age: Option<Duration>,
) -> bool {
    if elapsed < STALL_WINDOW || rx_delta > 0 {
        return false;
    }
    match handshake_age {
        None => elapsed >= NO_HANDSHAKE_AFTER,
        Some(age) => tx_delta > 0 && age > HANDSHAKE_STALE,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn keeps_lock(action: Action) {
        assert!(!action.release_lock);
        assert!(!action.switch_transport);
    }

    #[test]
    fn link_loss_waits_and_link_return_kicks() {
        let down = on_link_down();
        assert_eq!(down.notice, Notice::Reconnecting);
        assert!(!down.kick);
        keeps_lock(down);
        let up = on_link_up();
        assert!(up.kick && up.refresh_route);
        keeps_lock(up);
    }

    #[test]
    fn network_change_refreshes_the_same_endpoint() {
        let action = on_underlay_change();
        assert!(action.kick && action.refresh_route);
        assert_eq!(action.notice, Notice::Reconnecting);
        keeps_lock(action);
    }

    #[test]
    fn sleep_holds_and_resume_kicks() {
        let sleep = on_sleep();
        assert_eq!(sleep.notice, Notice::Reconnecting);
        assert!(!sleep.kick);
        keeps_lock(sleep);
        let resume = on_resume();
        assert!(resume.kick && resume.refresh_route);
        keeps_lock(resume);
    }

    #[test]
    fn lost_handshake_shows_reconnecting_and_backs_off() {
        assert!(!handshake_lost(
            Duration::from_secs(10),
            0,
            10,
            Some(Duration::from_secs(200))
        ));
        assert!(handshake_lost(
            Duration::from_secs(20),
            0,
            10,
            Some(Duration::from_secs(181))
        ));
        assert!(handshake_lost(Duration::from_secs(20), 0, 0, None));
        assert!(!handshake_lost(
            Duration::from_secs(20),
            1,
            10,
            Some(Duration::from_secs(181))
        ));
        let waiting = on_stall(false);
        assert_eq!(waiting.notice, Notice::Reconnecting);
        assert!(!waiting.kick);
        keeps_lock(waiting);
        let due = on_stall(true);
        assert!(due.kick);
        keeps_lock(due);
        let mut backoff = Backoff::new();
        let mut delays = Vec::new();
        for _ in 0..8 {
            delays.push(backoff.next_delay());
        }
        assert_eq!(delays[0], Duration::from_secs(1));
        assert!(delays.iter().all(|delay| *delay <= BACKOFF_CAP));
        assert_eq!(*delays.last().unwrap(), BACKOFF_CAP);
    }

    #[test]
    fn boot_resumes_only_a_saved_session() {
        assert_eq!(on_boot(false).notice, Notice::Quiet);
        let boot = on_boot(true);
        assert!(boot.kick && boot.refresh_route);
        keeps_lock(boot);
        keeps_lock(on_healthy());
    }
}
