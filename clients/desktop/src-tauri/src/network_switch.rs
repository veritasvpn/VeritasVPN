//! Linux network-switch recovery (Phase 1–3).
//!
//! Soft recovery is background-only and non-interactive.
//! Soft recovery never freezes the UI and never prompts for a password.

use std::fs;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Condvar, Mutex};
use std::time::{Duration, Instant};

use serde::{Deserialize, Serialize};

use super::{
    bring_up_from_saved_config_soft, privileged_state_dir, refresh_endpoint_route_linux,
    soft_release_tunnel, state_dir, tunnel_is_healthy, write_last_config_json,
};

/// Last successful tunnel config so soft reconnect can re-bring the peer up.
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

/// Result of one recovery pass.
#[derive(Debug, Clone, Serialize)]
pub struct NetworkRecoverResult {
    pub changed: bool,
    pub message: String,
    pub action: String,
}

/// Persist the last tunnel config for soft reconnect.
pub fn save_last_config(config: &SavedTunnelConfig) -> Result<(), String> {
    let dir = state_dir().map_err(|e| e)?;
    write_last_config_json(&dir, config)
}

/// Soft recovery for a network switch. Call repeatedly while connected.
pub fn recover_network_switch() -> NetworkRecoverResult {
    match recover_network_switch_linux() {
        Ok(r) => r,
        Err(e) => NetworkRecoverResult {
            changed: false,
            message: e,
            action: "error".into(),
        },
    }
}

// Rebind throttle — keep the tunnel up; never tear it down on a network switch.
static LAST_REBIND: Mutex<Option<Instant>> = Mutex::new(None);
const REBIND_MIN_INTERVAL: Duration = Duration::from_secs(3);

static REBIND_IN_FLIGHT: Mutex<Option<(Instant, u64)>> = Mutex::new(None);
static REBIND_OWNER_SEQ: Mutex<u64> = Mutex::new(0);
/// Owner token for the recovery_open that the UI must close. A second close
/// sees None and cannot drop a path-adapt lock taken after the first release.
static RECOVERY_OWNER: Mutex<Option<u64>> = Mutex::new(None);
const REBIND_LOCK_STALE: Duration = Duration::from_secs(45);

fn rebind_lock_held(slot: &Option<(Instant, u64)>) -> bool {
    match slot {
        Some((started, _)) => started.elapsed() < REBIND_LOCK_STALE,
        None => false,
    }
}

fn next_lock_token() -> u64 {
    let mut seq = REBIND_OWNER_SEQ.lock().unwrap_or_else(|e| e.into_inner());
    *seq = seq.wrapping_add(1);
    if *seq == 0 {
        *seq = 1;
    }
    *seq
}

/// One lock for path-adapt and the outside-tunnel recovery read.
/// The token is the only handle that can release this hold.
pub(crate) fn recovery_lock_acquire() -> Option<u64> {
    let mut slot = REBIND_IN_FLIGHT.lock().unwrap_or_else(|e| e.into_inner());
    if rebind_lock_held(&slot) {
        return None;
    }
    let token = next_lock_token();
    *slot = Some((Instant::now(), token));
    Some(token)
}

pub(crate) fn recovery_lock_release(token: u64) {
    let mut slot = REBIND_IN_FLIGHT.lock().unwrap_or_else(|e| e.into_inner());
    if let Some((_, held)) = *slot {
        if held == token {
            *slot = None;
        }
    }
}

pub(crate) fn recovery_lock_acquire_owned() -> Option<u64> {
    let token = recovery_lock_acquire()?;
    *RECOVERY_OWNER.lock().unwrap_or_else(|e| e.into_inner()) = Some(token);
    Some(token)
}

/// Release the recovery_open hold once. A repeated close is a no-op.
pub(crate) fn recovery_lock_release_owned() {
    let token = RECOVERY_OWNER.lock().unwrap_or_else(|e| e.into_inner()).take();
    if let Some(token) = token {
        recovery_lock_release(token);
    }
}

pub(crate) fn recovery_lock_abandon(token: u64) {
    let mut owner = RECOVERY_OWNER.lock().unwrap_or_else(|e| e.into_inner());
    if *owner == Some(token) {
        *owner = None;
    }
    drop(owner);
    recovery_lock_release(token);
}

/// How long a network change may fail to restore traffic before the kill
/// switch is dropped (no purge, no password) while reconnect intent stays.
/// This also covers a second link that was already up: a path-adapt that
/// did not actually move the endpoint route must not hold the internet.
pub(crate) const UNDERLAY_RELEASE_AFTER: Duration = Duration::from_secs(18);
/// Give up only after the underlay has been back this long and rebuilds failed.
pub(crate) const UNDERLAY_GIVE_UP_AFTER: Duration = Duration::from_secs(120);

pub(crate) const RECONNECTING_NOTICE: &str = "Reconnecting…";
pub(crate) const UNDERLAY_GAVE_UP_NOTICE: &str = "Tap to reconnect";
pub(crate) const UNDERLAY_BLOCKED_NOTICE: &str =
    "The network changed and the VPN couldn't follow it. Tap Restore internet, then connect again.";

const RECONNECT_WANTED_FILE: &str = "reconnect-wanted";

/// What one watcher tick should do. Pure so tests can cover it without root.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum UnderlayFollow {
    Idle,
    Rebind,
    Wait,
    ReleaseKeepIntent,
    Rebuild,
    GiveUp,
}

#[derive(Debug, Clone, Copy)]
pub(crate) struct FollowInput {
    pub user_disconnected: bool,
    pub tunnel_up: bool,
    pub underlay_present: bool,
    pub underlay_changed: bool,
    /// How long this outage has lasted. Zero unless the underlay changed,
    /// disappeared, or the endpoint host route is not on the underlay.
    /// A stable tunnel that is briefly unhealthy stays at zero.
    pub outage_for: Duration,
    /// Route is on the current underlay and a short egress check succeeded.
    /// Callers pass true when they are not in an outage and skipped the probe.
    pub traffic_ok: bool,
    pub reconnect_wanted: bool,
    pub released: bool,
    pub underlay_back_for: Option<Duration>,
    pub failed_attempts: u32,
    pub since_last_attempt: Option<Duration>,
    pub gave_up: bool,
}

pub(crate) fn reconnect_backoff(failed_attempts: u32) -> Duration {
    match failed_attempts {
        0 => Duration::ZERO,
        1 => Duration::from_secs(2),
        2 => Duration::from_secs(5),
        3 => Duration::from_secs(15),
        _ => Duration::from_secs(30),
    }
}

/// Connected sessions follow the network. A user disconnect is Idle even when
/// a route is available. Release happens when an outage has not restored
/// traffic, including when another link is already up.
pub(crate) fn decide_underlay_follow(input: FollowInput) -> UnderlayFollow {
    if input.user_disconnected || input.gave_up {
        return UnderlayFollow::Idle;
    }
    if input.tunnel_up {
        // No underlay means traffic cannot be flowing, even if a caller
        // left traffic_ok set from an earlier observation.
        let traffic_ok = input.traffic_ok && input.underlay_present;
        if !traffic_ok && input.outage_for >= UNDERLAY_RELEASE_AFTER && !input.released {
            return UnderlayFollow::ReleaseKeepIntent;
        }
        if !input.underlay_present {
            return UnderlayFollow::Wait;
        }
        if input.underlay_changed || (!input.traffic_ok && input.outage_for > Duration::ZERO) {
            return UnderlayFollow::Rebind;
        }
        return UnderlayFollow::Idle;
    }
    if !input.reconnect_wanted {
        return UnderlayFollow::Idle;
    }
    if !input.underlay_present {
        return UnderlayFollow::Wait;
    }
    let back_for = input.underlay_back_for.unwrap_or_default();
    if back_for >= UNDERLAY_GIVE_UP_AFTER && input.failed_attempts >= 4 {
        return UnderlayFollow::GiveUp;
    }
    let wait = reconnect_backoff(input.failed_attempts);
    let elapsed = input.since_last_attempt.unwrap_or(Duration::MAX);
    if input.failed_attempts == 0 || elapsed >= wait {
        return UnderlayFollow::Rebuild;
    }
    UnderlayFollow::Wait
}

struct FollowMemory {
    diverged_since: Option<Instant>,
    released: bool,
    underlay_back_since: Option<Instant>,
    failed_attempts: u32,
    last_attempt: Option<Instant>,
    gave_up: bool,
    /// False only when a soft release left the kill switch in place.
    release_cleared: bool,
}

static FOLLOW: Mutex<FollowMemory> = Mutex::new(FollowMemory {
    diverged_since: None,
    released: false,
    underlay_back_since: None,
    failed_attempts: 0,
    last_attempt: None,
    gave_up: false,
    release_cleared: true,
});
static UNDERLAY_NOTICE: Mutex<String> = Mutex::new(String::new());
static UNDERLAY_RECONNECTING: AtomicBool = AtomicBool::new(false);
static UNDERLAY_GAVE_UP: AtomicBool = AtomicBool::new(false);
static USER_HOLD: AtomicBool = AtomicBool::new(false);

fn follow_lock() -> std::sync::MutexGuard<'static, FollowMemory> {
    FOLLOW.lock().unwrap_or_else(|e| e.into_inner())
}

fn clear_clocks(mem: &mut FollowMemory) {
    mem.diverged_since = None;
    mem.released = false;
    mem.underlay_back_since = None;
    mem.failed_attempts = 0;
    mem.last_attempt = None;
    mem.release_cleared = true;
}

pub(crate) fn clear_underlay_watch() {
    clear_clocks(&mut follow_lock());
    set_underlay_reconnecting(false);
}

/// Remember that this connected session should come back after a network change.
pub(crate) fn mark_reconnect_wanted() {
    USER_HOLD.store(false, Ordering::SeqCst);
    {
        let mut mem = follow_lock();
        mem.gave_up = false;
        clear_clocks(&mut mem);
    }
    UNDERLAY_GAVE_UP.store(false, Ordering::SeqCst);
    set_underlay_reconnecting(false);
    if let Ok(dir) = state_dir() {
        let _ = fs::write(dir.join(RECONNECT_WANTED_FILE), b"1\n");
    }
}

/// User Disconnect. The watcher must not bring the tunnel back.
pub(crate) fn clear_reconnect_wanted() {
    USER_HOLD.store(true, Ordering::SeqCst);
    UNDERLAY_GAVE_UP.store(false, Ordering::SeqCst);
    set_underlay_reconnecting(false);
    {
        let mut mem = follow_lock();
        mem.gave_up = false;
        clear_clocks(&mut mem);
    }
    if let Ok(dir) = state_dir() {
        let _ = fs::remove_file(dir.join(RECONNECT_WANTED_FILE));
    }
}

fn reconnect_wanted() -> bool {
    if USER_HOLD.load(Ordering::SeqCst) {
        return false;
    }
    state_dir()
        .map(|dir| dir.join(RECONNECT_WANTED_FILE).is_file())
        .unwrap_or(false)
}

fn user_disconnected_now() -> bool {
    USER_HOLD.load(Ordering::SeqCst)
}

pub(crate) fn clear_underlay_notice() {
    *UNDERLAY_NOTICE.lock().unwrap_or_else(|e| e.into_inner()) = String::new();
}

pub(crate) fn take_underlay_notice() -> String {
    std::mem::take(&mut *UNDERLAY_NOTICE.lock().unwrap_or_else(|e| e.into_inner()))
}

fn set_underlay_notice(msg: &str) {
    *UNDERLAY_NOTICE.lock().unwrap_or_else(|e| e.into_inner()) = msg.to_string();
}

/// `recorded` and `detected` are `gateway|iface|src`.
/// A missing detection counts as a change once a tunnel underlay was recorded,
/// so an interface that disappeared is retried instead of ignored.
pub(crate) fn underlay_fingerprint_diverged(recorded: &str, detected: Option<&str>) -> bool {
    let recorded = recorded.trim();
    match detected.map(str::trim).filter(|s| !s.is_empty()) {
        None => !recorded.is_empty(),
        Some(now) => recorded.is_empty() || now != recorded,
    }
}

pub(crate) fn underlay_release_due(diverged_for: Duration) -> bool {
    diverged_for >= UNDERLAY_RELEASE_AFTER
}

fn recorded_underlay_fingerprint() -> String {
    let Ok(dir) = privileged_state_dir() else {
        return String::new();
    };
    let meta = fs::read_to_string(dir.join("iface.meta")).unwrap_or_default();
    let mut gw = String::new();
    let mut gif = String::new();
    let mut src = String::new();
    for line in meta.lines() {
        let Some((k, v)) = line.split_once('=') else {
            continue;
        };
        match k.trim() {
            "gateway" => gw = v.trim().to_string(),
            "gw_if" => gif = v.trim().to_string(),
            "underlay_src" => src = v.trim().to_string(),
            _ => {}
        }
    }
    if gw.is_empty() && gif.is_empty() {
        String::new()
    } else {
        format!("{gw}|{gif}|{src}")
    }
}

fn noop(action: &str, message: &str) -> NetworkRecoverResult {
    NetworkRecoverResult {
        changed: false,
        message: message.into(),
        action: action.into(),
    }
}

fn tunnel_iface_up() -> bool {
    std::path::Path::new("/sys/class/net/veritas0").exists()
}

fn set_underlay_reconnecting(on: bool) {
    UNDERLAY_RECONNECTING.store(on, Ordering::SeqCst);
}

pub(crate) fn underlay_reconnecting() -> bool {
    UNDERLAY_RECONNECTING.load(Ordering::SeqCst)
}

pub(crate) fn underlay_gave_up() -> bool {
    UNDERLAY_GAVE_UP.load(Ordering::SeqCst)
}

fn show_reconnecting() {
    if user_disconnected_now() {
        return;
    }
    set_underlay_reconnecting(true);
    set_underlay_notice(RECONNECTING_NOTICE);
}

fn follow_snapshot(
    tunnel_up: bool,
    present: bool,
    changed: bool,
    route_ok: bool,
    traffic_ok: bool,
) -> FollowInput {
    let mut mem = follow_lock();
    if !present {
        mem.underlay_back_since = None;
        mem.failed_attempts = 0;
        mem.last_attempt = None;
    } else if mem.underlay_back_since.is_none() {
        mem.underlay_back_since = Some(Instant::now());
    }
    // A matching fingerprint is not traffic. Keep the outage clock until the
    // endpoint route is on the underlay and egress works. Do not start it for
    // a stable tunnel that is only briefly unhealthy.
    let network_outage = !present || changed || !route_ok;
    if tunnel_up && traffic_ok && route_ok && present {
        mem.diverged_since = None;
        mem.released = false;
    } else if tunnel_up && !traffic_ok && (network_outage || mem.diverged_since.is_some()) {
        mem.diverged_since.get_or_insert_with(Instant::now);
    }
    let outage_for = mem
        .diverged_since
        .map(|started| started.elapsed())
        .unwrap_or(Duration::ZERO);
    if tunnel_up && present && !changed && route_ok && traffic_ok {
        mem.failed_attempts = 0;
        mem.last_attempt = None;
        mem.underlay_back_since = None;
    }
    FollowInput {
        user_disconnected: user_disconnected_now(),
        tunnel_up,
        underlay_present: present,
        underlay_changed: changed,
        outage_for,
        traffic_ok,
        reconnect_wanted: reconnect_wanted(),
        released: mem.released,
        underlay_back_for: mem.underlay_back_since.map(|started| started.elapsed()),
        failed_attempts: mem.failed_attempts,
        since_last_attempt: mem.last_attempt.map(|started| started.elapsed()),
        gave_up: mem.gave_up,
    }
}

/// In-place re-path through the passwordless helper. DNS stays inside that helper.
fn rebind_underlay(force: bool) -> Result<NetworkRecoverResult, String> {
    let owner = {
        let mut in_flight = REBIND_IN_FLIGHT.lock().map_err(|e| e.to_string())?;
        if rebind_lock_held(&in_flight) {
            return Ok(noop("in_progress", "path rebind already in progress"));
        }
        let mut last = LAST_REBIND.lock().map_err(|e| e.to_string())?;
        let now = Instant::now();
        if !force {
            if let Some(t) = *last {
                if now.duration_since(t) < REBIND_MIN_INTERVAL {
                    return Ok(noop("throttle", "path rebind throttled"));
                }
            }
        }
        *last = Some(now);
        let token = next_lock_token();
        *in_flight = Some((now, token));
        token
    };
    let endpoint = refresh_endpoint_route_linux();
    recovery_lock_release(owner);
    // "ok path-adapt" only means the helper exited. The outage clock stays
    // until a later tick sees the host route on the new interface and egress.
    // Clearing it here is what left the UI on Reconnecting with no further tries.
    if user_disconnected_now() {
        set_underlay_reconnecting(false);
        clear_underlay_notice();
    }
    Ok(NetworkRecoverResult {
        changed: endpoint.refreshed,
        message: if endpoint.refreshed {
            "path updated".into()
        } else {
            RECONNECTING_NOTICE.into()
        },
        action: if endpoint.refreshed {
            "soft_path".into()
        } else {
            "soft_path_pending".into()
        },
    })
}

fn release_keep_intent() -> NetworkRecoverResult {
    if user_disconnected_now() {
        set_underlay_reconnecting(false);
        return noop("idle", "user disconnected");
    }
    let cleared = soft_release_tunnel();
    {
        let mut mem = follow_lock();
        mem.release_cleared = cleared;
        if cleared {
            mem.released = true;
        }
    }
    show_reconnecting();
    NetworkRecoverResult {
        changed: true,
        message: RECONNECTING_NOTICE.into(),
        action: "released".into(),
    }
}

fn rebuild_from_saved() -> NetworkRecoverResult {
    if user_disconnected_now() {
        return noop("idle", "user disconnected");
    }
    show_reconnecting();
    let Some(_guard) = FlightGuard::enter(FlightKind::Rebind) else {
        return noop("in_progress", "reconnect already in progress");
    };
    if user_disconnected_now() {
        return noop("idle", "user disconnected");
    }
    match bring_up_from_saved_config_soft() {
        Ok(_) => {
            if user_disconnected_now() {
                return noop("idle", "user disconnected");
            }
            let mut mem = follow_lock();
            clear_clocks(&mut mem);
            set_underlay_reconnecting(false);
            clear_underlay_notice();
            NetworkRecoverResult {
                changed: true,
                message: "reconnected".into(),
                action: "rebuilt".into(),
            }
        }
        Err(_) => {
            let mut mem = follow_lock();
            mem.failed_attempts = mem.failed_attempts.saturating_add(1);
            mem.last_attempt = Some(Instant::now());
            show_reconnecting();
            NetworkRecoverResult {
                changed: false,
                message: RECONNECTING_NOTICE.into(),
                action: "rebuild_failed".into(),
            }
        }
    }
}

fn give_up_reconnect() -> NetworkRecoverResult {
    let blocked = !follow_lock().release_cleared;
    follow_lock().gave_up = true;
    set_underlay_reconnecting(false);
    UNDERLAY_GAVE_UP.store(true, Ordering::SeqCst);
    let notice = if blocked {
        UNDERLAY_BLOCKED_NOTICE
    } else {
        UNDERLAY_GAVE_UP_NOTICE
    };
    set_underlay_notice(notice);
    NetworkRecoverResult {
        changed: true,
        message: notice.into(),
        action: "gave_up".into(),
    }
}

fn recover_network_switch_linux() -> Result<NetworkRecoverResult, String> {
    if user_disconnected_now() {
        set_underlay_reconnecting(false);
        return Ok(noop("idle", "user disconnected"));
    }

    let tunnel_up = tunnel_iface_up();
    let detected = detect_underlay_fingerprint();
    let present = detected.is_some();
    let recorded = if tunnel_up {
        recorded_underlay_fingerprint()
    } else {
        String::new()
    };
    let changed = tunnel_up && underlay_fingerprint_diverged(&recorded, detected.as_deref());
    let underlay_if = detected
        .as_deref()
        .and_then(fingerprint_iface)
        .unwrap_or("");
    let route_ok = !tunnel_up || !present || endpoint_route_on_underlay(underlay_if);
    // Probe egress only during an outage, and skip the probe when the host
    // route is already on the wrong interface so the next try stays fast.
    let outage_active = follow_lock().diverged_since.is_some();
    let probing = tunnel_up && (changed || !present || !route_ok || outage_active);
    let traffic_ok = if !probing {
        true
    } else if !present || !route_ok {
        false
    } else {
        tunnel_is_healthy().unwrap_or(false)
    };
    let input = follow_snapshot(tunnel_up, present, changed, route_ok, traffic_ok);

    match decide_underlay_follow(input) {
        UnderlayFollow::Idle => {
            if user_disconnected_now() {
                set_underlay_reconnecting(false);
                return Ok(noop("idle", "user disconnected"));
            }
            if !input.gave_up {
                set_underlay_reconnecting(false);
                clear_underlay_notice();
            }
            if !input.gave_up
                && tunnel_up
                && present
                && !changed
                && route_ok
                && !tunnel_is_healthy().unwrap_or(false)
            {
                return rebind_underlay(false);
            }
            Ok(noop("noop", "underlay unchanged"))
        }
        UnderlayFollow::Rebind => {
            show_reconnecting();
            let result = rebind_underlay(true)?;
            if user_disconnected_now() {
                set_underlay_reconnecting(false);
                clear_underlay_notice();
                return Ok(noop("idle", "user disconnected"));
            }
            show_reconnecting();
            Ok(result)
        }
        UnderlayFollow::Wait => {
            show_reconnecting();
            if tunnel_up {
                let result = rebind_underlay(true)?;
                if user_disconnected_now() {
                    set_underlay_reconnecting(false);
                    clear_underlay_notice();
                    return Ok(noop("idle", "user disconnected"));
                }
                show_reconnecting();
                return Ok(result);
            }
            Ok(noop("wait", RECONNECTING_NOTICE))
        }
        UnderlayFollow::ReleaseKeepIntent => Ok(release_keep_intent()),
        UnderlayFollow::Rebuild => Ok(rebuild_from_saved()),
        UnderlayFollow::GiveUp => Ok(give_up_reconnect()),
    }
}

/// One privileged helper at a time. Teardown and recover-close coalesce so a
/// burst of callers shares the in-flight sudo instead of starting another.
const FLIGHT_COALESCE: Duration = Duration::from_secs(3);

#[derive(Clone, Copy, PartialEq, Eq)]
pub(crate) enum FlightKind {
    Close,
    Teardown,
    Rebind,
}

struct FlightGate {
    busy: Option<FlightKind>,
    finished: Option<(FlightKind, Instant)>,
}

static FLIGHT: Mutex<FlightGate> = Mutex::new(FlightGate {
    busy: None,
    finished: None,
});
static FLIGHT_CV: Condvar = Condvar::new();

enum FlightRole {
    Run,
    Skip,
}

fn flight_enter(kind: FlightKind) -> FlightRole {
    let mut gate = FLIGHT.lock().unwrap_or_else(|e| e.into_inner());
    loop {
        if gate.busy.is_some() {
            gate = FLIGHT_CV.wait(gate).unwrap_or_else(|e| e.into_inner());
            continue;
        }
        if kind != FlightKind::Rebind {
            if let Some((done, at)) = gate.finished {
                if at.elapsed() < FLIGHT_COALESCE && (done == FlightKind::Teardown || done == kind)
                {
                    return FlightRole::Skip;
                }
            }
        }
        gate.busy = Some(kind);
        return FlightRole::Run;
    }
}

fn flight_exit(kind: FlightKind) {
    let mut gate = FLIGHT.lock().unwrap_or_else(|e| e.into_inner());
    if gate.busy == Some(kind) {
        gate.busy = None;
        gate.finished = Some((kind, Instant::now()));
    }
    FLIGHT_CV.notify_all();
}

pub(crate) struct FlightGuard {
    kind: FlightKind,
}

impl FlightGuard {
    pub(crate) fn enter(kind: FlightKind) -> Option<Self> {
        match flight_enter(kind) {
            FlightRole::Run => Some(Self { kind }),
            FlightRole::Skip => None,
        }
    }
}

impl Drop for FlightGuard {
    fn drop(&mut self) {
        flight_exit(self.kind);
    }
}

fn fingerprint_iface(fp: &str) -> Option<&str> {
    let iface = fp.split('|').nth(1)?.trim();
    if iface.is_empty() {
        None
    } else {
        Some(iface)
    }
}

/// `ip route get` output uses this device. The first `dev` token is the one
/// the kernel will actually use.
pub(crate) fn route_dev_is(text: &str, iface: &str) -> bool {
    let line = text.lines().next().unwrap_or("");
    let mut parts = line.split_whitespace();
    while let Some(word) = parts.next() {
        if word == "dev" {
            return parts.next() == Some(iface);
        }
    }
    false
}

fn recorded_endpoint_ip() -> String {
    let Ok(dir) = privileged_state_dir() else {
        return String::new();
    };
    let meta = fs::read_to_string(dir.join("iface.meta")).unwrap_or_default();
    for line in meta.lines() {
        let Some((k, v)) = line.split_once('=') else {
            continue;
        };
        if k.trim() == "endpoint_ip" {
            return v.trim().to_string();
        }
    }
    String::new()
}

/// True when the WireGuard endpoint host route exits via this underlay.
/// Stealth (no public endpoint) has nothing to pin. A missing `ip` does not
/// invent an outage; a route that lands on veritas0 is not ok.
fn endpoint_route_on_underlay(iface: &str) -> bool {
    let ip = recorded_endpoint_ip();
    if ip.is_empty() || ip == "127.0.0.1" {
        return true;
    }
    if iface.is_empty() || iface == "veritas0" {
        return false;
    }
    let Ok(out) = std::process::Command::new("ip")
        .args(["-4", "route", "get", &ip])
        .output()
    else {
        return true;
    };
    route_dev_is(&String::from_utf8_lossy(&out.stdout), iface)
}

/// Detect underlay path as `gateway|iface|src` (best-effort, non-elevated).
fn detect_underlay_fingerprint() -> Option<String> {
    let out = std::process::Command::new("bash")
        .arg("-c")
        .arg(
            r#"
IFACE="veritas0"
gw=""; gif=""; src=""
# The OS default route is the underlay. Skip a down NIC so a stale Wi-Fi
# default does not hide an Ethernet default that is already up.
while IFS= read -r line; do
  case "$line" in *blackhole*) continue ;; esac
  case "$line" in *" via "*) ;; *) continue ;; esac
  gw="$(awk '{ for (i = 1; i <= NF; i++) if ($i == "via") { print $(i + 1); exit } }' <<<"$line")"
  gif="$(awk '{ for (i = 1; i <= NF; i++) if ($i == "dev") { print $(i + 1); exit } }' <<<"$line")"
  case "$gif" in
    ""|lo|"$IFACE") gw=""; gif=""; continue ;;
  esac
  case "$gif" in
    *[!A-Za-z0-9_.:-]*) gw=""; gif=""; continue ;;
  esac
  state="$(cat "/sys/class/net/$gif/operstate" 2>/dev/null || true)"
  if [ "$state" = "down" ]; then
    gw=""; gif=""
    continue
  fi
  break
done <<EOF
$(ip -4 route show default 2>/dev/null)
EOF
if [ -z "$gw" ] && command -v nmcli >/dev/null 2>&1; then
  if command -v timeout >/dev/null 2>&1; then
    nm_list="$(timeout 2 nmcli -t -f DEVICE,TYPE,STATE device 2>/dev/null || true)"
  else
    nm_list="$(nmcli -t -f DEVICE,TYPE,STATE device 2>/dev/null || true)"
  fi
  while IFS=: read -r dev type state; do
    [ "$state" = "connected" ] || continue
    case "$type" in wifi|ethernet|802-11-wireless|802-3-ethernet) ;; *) continue ;; esac
    [ -n "$dev" ] && [ "$dev" != "$IFACE" ] && [ "$dev" != "lo" ] || continue
    oper="$(cat "/sys/class/net/$dev/operstate" 2>/dev/null || true)"
    [ "$oper" = "down" ] && continue
    if command -v timeout >/dev/null 2>&1; then
      gw="$(timeout 2 nmcli -g IP4.GATEWAY device show "$dev" 2>/dev/null | head -1 || true)"
      src="$(timeout 2 nmcli -g IP4.ADDRESS device show "$dev" 2>/dev/null | head -1 || true)"
    else
      gw="$(nmcli -g IP4.GATEWAY device show "$dev" 2>/dev/null | head -1 || true)"
      src="$(nmcli -g IP4.ADDRESS device show "$dev" 2>/dev/null | head -1 || true)"
    fi
    src="${src%%/*}"
    gif="$dev"
    [ -n "$gw" ] && break
  done <<<"$nm_list"
fi
if [ -z "$src" ] && [ -n "$gif" ]; then
  src="$(ip -4 -o addr show dev "$gif" 2>/dev/null | awk '{print $4; exit}')"
  src="${src%%/*}"
fi
[ -n "$gw" ] || exit 0
printf '%s|%s|%s\n' "$gw" "$gif" "$src"
"#,
        )
        .output()
        .ok()?;
    let s = String::from_utf8_lossy(&out.stdout);
    let fp = s.trim();
    if fp.is_empty() {
        None
    } else {
        Some(fp.to_string())
    }
}

/// Load the last tunnel config for soft reconnect.
pub fn load_last_config() -> Result<SavedTunnelConfig, String> {
    let dir = state_dir().map_err(|e| e)?;
    let path = dir.join("last-config.json");
    let raw = fs::read_to_string(&path).map_err(|e| format!("read last-config: {e}"))?;
    serde_json::from_str(&raw).map_err(|e| format!("parse last-config: {e}"))
}

#[cfg(test)]
mod tests {
    use std::time::Duration;

    use super::{
        decide_underlay_follow, recovery_lock_abandon, recovery_lock_acquire,
        recovery_lock_acquire_owned, recovery_lock_release, recovery_lock_release_owned,
        reconnect_backoff, route_dev_is, underlay_fingerprint_diverged, underlay_release_due,
        FlightGuard, FlightKind, FollowInput, UnderlayFollow, UNDERLAY_GIVE_UP_AFTER,
        UNDERLAY_RELEASE_AFTER,
    };

    fn follow(mut set: impl FnMut(&mut FollowInput)) -> UnderlayFollow {
        let mut input = FollowInput {
            user_disconnected: false,
            tunnel_up: true,
            underlay_present: true,
            underlay_changed: false,
            outage_for: Duration::ZERO,
            traffic_ok: true,
            reconnect_wanted: true,
            released: false,
            underlay_back_for: None,
            failed_attempts: 0,
            since_last_attempt: None,
            gave_up: false,
        };
        set(&mut input);
        decide_underlay_follow(input)
    }

    #[test]
    fn underlay_switch_retries_then_releases() {
        let wifi = "192.0.2.1|wlx90de802153e1|192.0.2.20";
        let wired = "198.51.100.1|eno1|198.51.100.10";
        assert!(!underlay_fingerprint_diverged(wired, Some(wired)));
        assert!(underlay_fingerprint_diverged(wifi, Some(wired)));
        assert!(
            underlay_fingerprint_diverged(wifi, None),
            "a disappeared underlay must be retried"
        );
        assert!(!underlay_fingerprint_diverged("", None));
        assert!(!underlay_release_due(Duration::from_secs(17)));
        assert!(underlay_release_due(Duration::from_secs(18)));
        assert!(underlay_release_due(UNDERLAY_RELEASE_AFTER));
    }

    #[test]
    fn underlay_loss_then_return_reconnects() {
        assert_eq!(
            follow(|input| {
                input.underlay_present = false;
                input.underlay_changed = true;
                input.outage_for = Duration::ZERO;
                input.traffic_ok = false;
            }),
            UnderlayFollow::Wait
        );
        assert_eq!(
            follow(|input| {
                input.underlay_present = false;
                input.underlay_changed = true;
                input.outage_for = Duration::from_secs(18);
                input.traffic_ok = false;
            }),
            UnderlayFollow::ReleaseKeepIntent
        );
        assert_eq!(
            follow(|input| {
                input.tunnel_up = false;
                input.underlay_present = false;
                input.released = true;
            }),
            UnderlayFollow::Wait,
            "a released tunnel keeps reconnect intent while the underlay is gone"
        );
        assert_eq!(
            follow(|input| {
                input.tunnel_up = false;
                input.underlay_present = true;
                input.released = true;
                input.underlay_back_for = Some(Duration::from_secs(1));
                input.failed_attempts = 0;
            }),
            UnderlayFollow::Rebuild
        );
        assert_eq!(reconnect_backoff(1), Duration::from_secs(2));
        assert_eq!(
            follow(|input| {
                input.tunnel_up = false;
                input.underlay_present = true;
                input.underlay_back_for = Some(Duration::from_secs(10));
                input.failed_attempts = 1;
                input.since_last_attempt = Some(Duration::from_secs(1));
            }),
            UnderlayFollow::Wait
        );
        assert_eq!(
            follow(|input| {
                input.tunnel_up = false;
                input.underlay_present = true;
                input.underlay_back_for = Some(UNDERLAY_GIVE_UP_AFTER);
                input.failed_attempts = 4;
                input.since_last_attempt = Some(Duration::from_secs(30));
            }),
            UnderlayFollow::GiveUp
        );
    }

    #[test]
    fn wired_to_wifi_rebinds_then_releases_when_traffic_stays_down() {
        assert_eq!(
            follow(|input| {
                input.underlay_present = true;
                input.underlay_changed = true;
                input.outage_for = Duration::ZERO;
                input.traffic_ok = false;
            }),
            UnderlayFollow::Rebind
        );
        assert_eq!(
            follow(|input| {
                input.underlay_present = true;
                input.underlay_changed = true;
                input.outage_for = Duration::from_secs(18);
                input.traffic_ok = false;
            }),
            UnderlayFollow::ReleaseKeepIntent,
            "a live second link does not keep a dead path behind the kill switch"
        );
        assert_eq!(
            follow(|input| {
                input.underlay_present = true;
                input.underlay_changed = true;
                input.outage_for = Duration::from_secs(60);
                input.traffic_ok = true;
            }),
            UnderlayFollow::Rebind,
            "restored traffic still records the new underlay"
        );
    }

    #[test]
    fn stable_unhealthy_tunnel_does_not_drop_the_kill_switch() {
        assert_eq!(
            follow(|input| {
                input.underlay_changed = false;
                input.underlay_present = true;
                input.traffic_ok = false;
                input.outage_for = Duration::ZERO;
            }),
            UnderlayFollow::Idle
        );
    }

    #[test]
    fn endpoint_route_must_use_the_new_underlay() {
        assert!(route_dev_is(
            "203.0.113.10 via 198.51.100.1 dev eno1 src 198.51.100.20",
            "eno1"
        ));
        assert!(!route_dev_is(
            "203.0.113.10 dev veritas0 src 10.0.0.2",
            "eno1"
        ));
        assert!(!route_dev_is(
            "203.0.113.10 via 192.0.2.1 dev wlx90de802153e1 src 192.0.2.50",
            "eno1"
        ));
    }

    #[test]
    fn disconnect_during_reconnect_does_not_rebuild() {
        assert_eq!(
            follow(|input| {
                input.user_disconnected = true;
                input.tunnel_up = true;
                input.underlay_present = true;
                input.underlay_changed = true;
                input.outage_for = Duration::from_secs(30);
                input.traffic_ok = false;
                input.reconnect_wanted = true;
            }),
            UnderlayFollow::Idle
        );
    }

    #[test]
    fn user_disconnect_does_not_auto_reconnect() {
        assert_eq!(
            follow(|input| {
                input.user_disconnected = true;
                input.tunnel_up = false;
                input.underlay_present = true;
                input.reconnect_wanted = true;
                input.underlay_back_for = Some(Duration::from_secs(5));
            }),
            UnderlayFollow::Idle
        );
        assert_eq!(
            follow(|input| {
                input.tunnel_up = false;
                input.underlay_present = true;
                input.reconnect_wanted = false;
                input.underlay_back_for = Some(Duration::ZERO);
            }),
            UnderlayFollow::Idle
        );
    }

    #[test]
    fn resolvectl_is_not_spawned_unprivileged() {
        let src = include_str!("lib.rs");
        assert!(
            !src.contains("Command::new(\"resolvectl\")"),
            "dns, domain, and default-route must run inside the root helper"
        );
    }

    #[test]
    fn teardown_flight_is_single() {
        use std::sync::atomic::{AtomicUsize, Ordering};
        use std::sync::Arc;
        use std::thread;

        let ran = Arc::new(AtomicUsize::new(0));
        let mut handles = Vec::new();
        for _ in 0..8 {
            let ran = ran.clone();
            handles.push(thread::spawn(move || {
                if FlightGuard::enter(FlightKind::Teardown).is_some() {
                    ran.fetch_add(1, Ordering::SeqCst);
                    thread::sleep(Duration::from_millis(40));
                }
            }));
        }
        for handle in handles {
            handle.join().expect("flight thread");
        }
        assert_eq!(ran.load(Ordering::SeqCst), 1);
    }

    #[test]
    fn recovery_lock_release_matches_its_owner() {
        let first = recovery_lock_acquire().expect("acquire");
        assert!(recovery_lock_acquire().is_none());
        recovery_lock_release(first.wrapping_add(9));
        assert!(
            recovery_lock_acquire().is_none(),
            "a mismatched token released the lock"
        );
        recovery_lock_release(first);

        let _owned = recovery_lock_acquire_owned().expect("recovery owner");
        recovery_lock_release_owned();
        let path = recovery_lock_acquire().expect("path-adapt after recovery close");
        recovery_lock_release_owned();
        assert!(
            recovery_lock_acquire().is_none(),
            "a second recovery close dropped the path-adapt lock"
        );
        recovery_lock_release(path);

        let abandoned = recovery_lock_acquire_owned().expect("abandon");
        recovery_lock_abandon(abandoned);
        let after = recovery_lock_acquire().expect("free after abandon");
        recovery_lock_release(after);
    }
}
