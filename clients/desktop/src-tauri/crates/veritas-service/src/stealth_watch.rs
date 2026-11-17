//! Pure Stealth helper policy: the URL, the restart backoff, and the log gate.
//! Nothing here touches Windows, so it is tested on every platform.

use std::time::{Duration, Instant};

/// Same numbers as Android `StealthBackoff`: 2s, doubling, capped at 30s,
/// plus up to 500ms of jitter so many clients do not retry in step.
pub const STEALTH_BACKOFF_INITIAL: Duration = Duration::from_secs(2);
pub const STEALTH_BACKOFF_CAP: Duration = Duration::from_secs(30);
pub const STEALTH_BACKOFF_JITTER_MS: u64 = 500;

/// The stealth server always speaks TLS (wstunnel `wss://0.0.0.0:443`), also
/// when the control plane hands a same-LAN client the LAN address. Android
/// and Linux always dial `wss://`; plain `ws://` to the TLS port makes
/// wstunnel fail every upgrade with "invalid HTTP version parsed".
/// A hostname gets SNI from the URL; an IP literal is sent without SNI, like
/// Android. Certificates are not pinned to a name (Android trusts all too);
/// the path secret and WireGuard keys authenticate the server.
pub fn tunnel_url(endpoint: &str) -> String {
    let endpoint = endpoint.trim();
    let bare = endpoint
        .strip_prefix("wss://")
        .or_else(|| endpoint.strip_prefix("ws://"))
        .or_else(|| endpoint.strip_prefix("https://"))
        .or_else(|| endpoint.strip_prefix("http://"))
        .unwrap_or(endpoint)
        .trim_end_matches('/');
    format!("wss://{bare}")
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct StealthBackoff {
    attempt: u32,
}

impl StealthBackoff {
    pub const fn new() -> Self {
        Self { attempt: 0 }
    }

    /// Base delay for the current attempt, without jitter.
    pub fn base(&self) -> Duration {
        let shift = self.attempt.min(8);
        let ms = STEALTH_BACKOFF_INITIAL.as_millis() as u64 * (1u64 << shift);
        Duration::from_millis(ms).min(STEALTH_BACKOFF_CAP)
    }

    /// Delay before the next restart. `jitter_ms` is clamped to the jitter
    /// window so callers can pass any random number.
    pub fn next_delay(&mut self, jitter_ms: u64) -> Duration {
        let delay = self.base() + Duration::from_millis(jitter_ms % (STEALTH_BACKOFF_JITTER_MS + 1));
        self.attempt = self.attempt.saturating_add(1);
        delay
    }

    pub fn attempts(&self) -> u32 {
        self.attempt
    }

    pub fn reset(&mut self) {
        *self = Self::new();
    }
}

impl Default for StealthBackoff {
    fn default() -> Self {
        Self::new()
    }
}

/// Restart gate for the helper. A dead or flooding helper is restarted only
/// when the backoff allows it, so a server that refuses every upgrade cannot
/// make the service spin.
#[derive(Debug, Clone, Copy)]
pub struct RestartGate {
    backoff: StealthBackoff,
    next_at: Option<Instant>,
}

impl RestartGate {
    pub const fn new() -> Self {
        Self {
            backoff: StealthBackoff::new(),
            next_at: None,
        }
    }

    /// True when a restart may run now. Schedules the one after it.
    pub fn try_restart(&mut self, now: Instant, jitter_ms: u64) -> bool {
        if self.next_at.is_some_and(|at| now < at) {
            return false;
        }
        self.next_at = Some(now + self.backoff.next_delay(jitter_ms));
        true
    }

    /// Delay the first restart after a flood was cut, so the kill itself
    /// counts as an attempt.
    pub fn note_failure(&mut self, now: Instant, jitter_ms: u64) {
        let at = now + self.backoff.next_delay(jitter_ms);
        self.next_at = Some(self.next_at.map_or(at, |old| old.max(at)));
    }

    pub fn attempts(&self) -> u32 {
        self.backoff.attempts()
    }

    pub fn reset(&mut self) {
        *self = Self::new();
    }
}

impl Default for RestartGate {
    fn default() -> Self {
        Self::new()
    }
}

/// stealth.log never grows past this. One older copy is kept as stealth.log.1.
pub const STEALTH_LOG_MAX: u64 = 512 * 1024;
/// wstunnel retries a refused upgrade in a tight loop. This many failed
/// upgrades inside [`FLOOD_WINDOW`] means it is spinning, so the service cuts
/// the helper and restarts it on the backoff.
pub const FLOOD_FAILURES: u32 = 20;
pub const FLOOD_WINDOW: Duration = Duration::from_secs(5);

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct LineOutcome {
    /// Text to append (already masked, colour codes removed), or None to drop.
    pub text: Option<String>,
    /// True on the line that crosses the flood threshold.
    pub flood: bool,
    /// True when this line reports a failed websocket upgrade.
    pub upgrade_failed: bool,
}

/// Filters wstunnel output before it reaches stealth.log.
#[derive(Debug, Clone)]
pub struct LogGate {
    secret: Option<String>,
    window_start: Option<Instant>,
    in_window: u32,
    flooded: bool,
    suppressed: u64,
}

impl LogGate {
    pub fn new(secret: Option<&str>) -> Self {
        Self {
            secret: secret.map(str::to_string).filter(|s| s.len() >= 4),
            window_start: None,
            in_window: 0,
            flooded: false,
            suppressed: 0,
        }
    }

    pub fn flooded(&self) -> bool {
        self.flooded
    }

    pub fn suppressed(&self) -> u64 {
        self.suppressed
    }

    pub fn line(&mut self, raw: &str, now: Instant) -> LineOutcome {
        let clean = strip_ansi(raw);
        let clean = match self.secret.as_deref() {
            Some(secret) => clean.replace(secret, "<prefix>"),
            None => clean,
        };
        let lower = clean.to_ascii_lowercase();
        let upgrade_failed = lower.contains("failed to do websocket handshake");
        let mut flood = false;
        if upgrade_failed {
            let fresh = self.window_start.is_none_or(|start| now.duration_since(start) > FLOOD_WINDOW);
            if fresh {
                self.window_start = Some(now);
                self.in_window = 0;
            }
            self.in_window += 1;
            if self.in_window >= FLOOD_FAILURES && !self.flooded {
                self.flooded = true;
                flood = true;
            }
        }
        // Once flooding, keep only the marker; the rest is the same loop.
        let text = if self.flooded && !flood {
            self.suppressed += 1;
            None
        } else if flood {
            Some(format!(
                "{clean}\nveritas: websocket upgrade failed {FLOOD_FAILURES} times in {}s; stopping the helper and retrying on backoff",
                FLOOD_WINDOW.as_secs()
            ))
        } else {
            Some(clean)
        };
        LineOutcome {
            text,
            flood,
            upgrade_failed,
        }
    }
}

/// True when appending `add` bytes to a file of `current` bytes should rotate first.
pub fn should_rotate(current: u64, add: u64) -> bool {
    current > 0 && current.saturating_add(add) > STEALTH_LOG_MAX
}

fn strip_ansi(text: &str) -> String {
    let mut out = String::with_capacity(text.len());
    let mut chars = text.chars().peekable();
    while let Some(c) = chars.next() {
        if c == '\u{1b}' {
            if chars.peek() == Some(&'[') {
                chars.next();
                for next in chars.by_ref() {
                    if next.is_ascii_alphabetic() {
                        break;
                    }
                }
            }
            continue;
        }
        if c == '\r' {
            continue;
        }
        out.push(c);
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn tunnel_url_is_always_tls() {
        assert_eq!(tunnel_url("192.168.0.6:443"), "wss://192.168.0.6:443");
        assert_eq!(tunnel_url("203.0.113.9:443"), "wss://203.0.113.9:443");
        assert_eq!(tunnel_url("stealth.example.net:443"), "wss://stealth.example.net:443");
        assert_eq!(tunnel_url(" ws://10.0.0.2:443/ "), "wss://10.0.0.2:443");
        assert_eq!(tunnel_url("wss://stealth.example.net:443"), "wss://stealth.example.net:443");
        for endpoint in ["192.168.0.6:443", "127.0.0.1:8443", "host:443"] {
            assert!(!tunnel_url(endpoint).starts_with("ws://"), "{endpoint}");
        }
    }

    #[test]
    fn backoff_doubles_from_two_seconds_and_caps_at_thirty() {
        let mut backoff = StealthBackoff::new();
        let delays: Vec<u64> = (0..8).map(|_| backoff.next_delay(0).as_secs()).collect();
        assert_eq!(delays, vec![2, 4, 8, 16, 30, 30, 30, 30]);
        for _ in 0..100 {
            assert!(backoff.next_delay(0) <= STEALTH_BACKOFF_CAP);
        }
        backoff.reset();
        assert_eq!(backoff.next_delay(0), STEALTH_BACKOFF_INITIAL);
    }

    #[test]
    fn backoff_jitter_stays_inside_half_a_second() {
        let mut backoff = StealthBackoff::new();
        assert_eq!(backoff.next_delay(250), Duration::from_millis(2_250));
        assert_eq!(backoff.next_delay(500), Duration::from_millis(4_500));
        let big = backoff.next_delay(u64::MAX);
        assert!(big >= Duration::from_secs(8) && big <= Duration::from_millis(8_500));
        let mut capped = StealthBackoff::new();
        for _ in 0..20 {
            capped.next_delay(0);
        }
        assert!(capped.next_delay(9_999) <= STEALTH_BACKOFF_CAP + Duration::from_millis(STEALTH_BACKOFF_JITTER_MS));
    }

    #[test]
    fn restart_gate_spaces_restarts_out() {
        let start = Instant::now();
        let mut gate = RestartGate::new();
        assert!(gate.try_restart(start, 0));
        assert!(!gate.try_restart(start + Duration::from_millis(1_999), 0));
        assert!(gate.try_restart(start + Duration::from_secs(2), 0));
        // Next one is 4s after the second restart.
        assert!(!gate.try_restart(start + Duration::from_secs(5), 0));
        assert!(gate.try_restart(start + Duration::from_secs(6), 0));
        // A tight loop of checks only lets one restart through per window.
        let mut allowed = 0;
        let mut t = start + Duration::from_secs(6);
        for _ in 0..10_000 {
            t += Duration::from_millis(1);
            if gate.try_restart(t, 0) {
                allowed += 1;
            }
        }
        assert_eq!(allowed, 1, "10s of polling allows one restart after 8s");
        gate.reset();
        assert!(gate.try_restart(t, 0));
    }

    #[test]
    fn restart_gate_counts_a_cut_flood_as_an_attempt() {
        let start = Instant::now();
        let mut gate = RestartGate::new();
        gate.note_failure(start, 0);
        assert!(!gate.try_restart(start + Duration::from_secs(1), 0));
        assert!(gate.try_restart(start + Duration::from_secs(2), 0));
        assert_eq!(gate.attempts(), 2);
    }

    #[test]
    fn log_gate_masks_secret_and_strips_colour() {
        let mut gate = LogGate::new(Some("0123456789abcdef"));
        let out = gate.line(
            "\u{1b}[32m INFO\u{1b}[0m GET /0123456789abcdef/events\r",
            Instant::now(),
        );
        assert_eq!(out.text.as_deref(), Some(" INFO GET /<prefix>/events"));
        assert!(!out.flood && !out.upgrade_failed);
    }

    #[test]
    fn log_gate_detects_the_upgrade_loop_and_stops_writing() {
        let start = Instant::now();
        let mut gate = LogGate::new(None);
        let fail = "ERROR wstunnel::tunnel::client::client: failed to do websocket handshake with the server wss://192.0.2.1:443";
        let mut written = 0;
        let mut floods = 0;
        for i in 0..10_000u32 {
            let at = start + Duration::from_micros(u64::from(i) * 100);
            for line in [fail, "", "Caused by:", "    invalid HTTP version parsed"] {
                let out = gate.line(line, at);
                if out.text.is_some() {
                    written += 1;
                }
                if out.flood {
                    floods += 1;
                }
            }
        }
        assert_eq!(floods, 1);
        assert!(gate.flooded());
        assert!(written < 100, "wrote {written} lines");
        assert!(gate.suppressed() > 39_000);
    }

    #[test]
    fn log_gate_ignores_slow_failures() {
        let start = Instant::now();
        let mut gate = LogGate::new(None);
        let fail = "ERROR failed to do websocket handshake with the server wss://192.0.2.1:443";
        for i in 0..200u64 {
            let out = gate.line(fail, start + Duration::from_secs(i));
            assert!(!out.flood);
            assert!(out.text.is_some());
        }
        assert!(!gate.flooded());
    }

    #[test]
    fn rotation_threshold() {
        assert!(!should_rotate(0, STEALTH_LOG_MAX * 2));
        assert!(!should_rotate(1_000, 100));
        assert!(should_rotate(STEALTH_LOG_MAX - 10, 11));
    }
}
