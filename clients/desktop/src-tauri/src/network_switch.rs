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
    /// Unconfirmed iface|gateway. A metric bump must not become a change
    /// until the new path has been the same for UNDERLAY_CHANGE_DEBOUNCE.
    pending_key: String,
    pending_since: Option<Instant>,
}

static FOLLOW: Mutex<FollowMemory> = Mutex::new(FollowMemory {
    diverged_since: None,
    released: false,
    underlay_back_since: None,
    failed_attempts: 0,
    last_attempt: None,
    gave_up: false,
    release_cleared: true,
    pending_key: String::new(),
    pending_since: None,
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
    mem.pending_key.clear();
    mem.pending_since = None;
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

/// How long a new iface|gateway must stay before it is an underlay change.
/// A NetworkManager metric penalty (+20000) on the same path never qualifies.
pub(crate) const UNDERLAY_CHANGE_DEBOUNCE: Duration = Duration::from_secs(2);

/// `iface|gateway` for either the live `ip route get` key or the older
/// `gateway|iface|src` record. Metric and source address are dropped.
pub(crate) fn underlay_path_key(raw: &str) -> String {
    let parts: Vec<&str> = raw.trim().split('|').map(str::trim).collect();
    let (iface, gw) = if parts.len() >= 3 {
        (parts[1], parts[0])
    } else if parts.len() == 2 {
        (parts[0], parts[1])
    } else if parts.len() == 1 && !parts[0].is_empty() {
        (parts[0], "")
    } else {
        return String::new();
    };
    if iface.is_empty() || iface == "veritas0" || iface == "lo" {
        return String::new();
    }
    format!("{iface}|{gw}")
}

fn split_path_key(raw: &str) -> Option<(String, String)> {
    let key = underlay_path_key(raw);
    let (iface, gw) = key.split_once('|')?;
    if iface.is_empty() {
        None
    } else {
        Some((iface.to_string(), gw.to_string()))
    }
}

/// Same NIC and same next hop. An on-link route (no via) on that NIC is the
/// same path. A different metric is not a difference.
pub(crate) fn underlay_same(recorded: &str, detected: &str) -> bool {
    let Some((recorded_iface, recorded_gw)) = split_path_key(recorded) else {
        return false;
    };
    let Some((detected_iface, detected_gw)) = split_path_key(detected) else {
        return false;
    };
    if recorded_iface != detected_iface {
        return false;
    }
    detected_gw.is_empty() || recorded_gw.is_empty() || detected_gw == recorded_gw
}

/// `recorded` and `detected` identify the underlay by interface and gateway.
/// A missing detection counts as a change once a tunnel underlay was recorded,
/// so an interface that disappeared is retried instead of ignored.
pub(crate) fn underlay_fingerprint_diverged(recorded: &str, detected: Option<&str>) -> bool {
    let recorded_key = underlay_path_key(recorded);
    match detected.map(underlay_path_key).filter(|s| !s.is_empty()) {
        None => !recorded_key.is_empty(),
        Some(now) => recorded_key.is_empty() || !underlay_same(&recorded_key, &now),
    }
}

/// `(present, changed)` after the new path has stayed put for `debounce`.
/// The first sample of a different iface or gateway is not a change yet, so a
/// one-tick metric reorder cannot run path-adapt or move the UI.
pub(crate) fn debounced_path_change(
    recorded: &str,
    detected: Option<&str>,
    pending_key: &mut String,
    pending_since: &mut Option<Instant>,
    now: Instant,
    debounce: Duration,
) -> (bool, bool) {
    let recorded_key = underlay_path_key(recorded);
    let detected_key = detected.map(underlay_path_key).filter(|s| !s.is_empty());
    if recorded_key.is_empty() {
        pending_key.clear();
        *pending_since = None;
        return (detected_key.is_some(), false);
    }
    if let Some(now_key) = detected_key.as_ref() {
        if underlay_same(&recorded_key, now_key) {
            pending_key.clear();
            *pending_since = None;
            return (true, false);
        }
    }
    let key = detected_key.clone().unwrap_or_default();
    if pending_key != &key {
        *pending_key = key;
        *pending_since = Some(now);
        return (true, false);
    }
    let started = pending_since.unwrap_or(now);
    if now.saturating_duration_since(started) < debounce {
        return (true, false);
    }
    (detected_key.is_some(), true)
}

/// First line of `ip route get`. Metric and src are ignored. The tunnel
/// device is not an underlay.
pub(crate) fn path_key_from_route_get(text: &str) -> Option<String> {
    let line = text.lines().next().unwrap_or("").trim();
    if line.is_empty() {
        return None;
    }
    let mut dev = "";
    let mut gw = "";
    let mut parts = line.split_whitespace();
    while let Some(word) = parts.next() {
        match word {
            "dev" => dev = parts.next().unwrap_or(""),
            "via" => gw = parts.next().unwrap_or(""),
            _ => {}
        }
    }
    if dev.is_empty() || dev == "veritas0" || dev == "lo" {
        return None;
    }
    if !dev
        .bytes()
        .all(|b| b.is_ascii_alphanumeric() || matches!(b, b'_' | b'.' | b':' | b'-'))
    {
        return None;
    }
    if !gw.is_empty()
        && !gw
            .bytes()
            .all(|b| b.is_ascii_digit() || b == b'.')
    {
        gw = "";
    }
    Some(format!("{dev}|{gw}"))
}

/// After a helper returns, Reconnecting stays only while the path is still down.
pub(crate) fn reconnecting_after_rebind(healthy: bool, user_disconnected: bool) -> bool {
    !user_disconnected && !healthy
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
    for line in meta.lines() {
        let Some((k, v)) = line.split_once('=') else {
            continue;
        };
        match k.trim() {
            "gateway" => gw = v.trim().to_string(),
            "gw_if" => gif = v.trim().to_string(),
            _ => {}
        }
    }
    if gw.is_empty() && gif.is_empty() {
        String::new()
    } else {
        format!("{gif}|{gw}")
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

fn current_session_healthy() -> bool {
    if !tunnel_iface_up() || !super::published_handshake_fresh() {
        return false;
    }
    let Some(detected) = detect_underlay_fingerprint() else {
        return false;
    };
    let recorded = recorded_underlay_fingerprint();
    if !recorded.is_empty() && !underlay_same(&recorded, &detected) {
        return false;
    }
    let Some(iface) = fingerprint_iface(&detected) else {
        return false;
    };
    endpoint_route_on_underlay(&iface)
}

/// The UI reads this every poll. A healthy tunnel must not stay on Reconnecting
/// just because a helper ran earlier.
pub(crate) fn note_healthy_session() {
    if user_disconnected_now() || !current_session_healthy() {
        return;
    }
    set_underlay_reconnecting(false);
    let mut notice = UNDERLAY_NOTICE.lock().unwrap_or_else(|e| e.into_inner());
    if notice.trim() == RECONNECTING_NOTICE {
        notice.clear();
    }
}

pub(crate) fn underlay_path_matches() -> bool {
    let Some(detected) = detect_underlay_fingerprint() else {
        return false;
    };
    let recorded = recorded_underlay_fingerprint();
    if recorded.is_empty() {
        return true;
    }
    if !underlay_same(&recorded, &detected) {
        return false;
    }
    fingerprint_iface(&detected)
        .map(|iface| endpoint_route_on_underlay(&iface))
        .unwrap_or(false)
}

fn finish_rebind(result: NetworkRecoverResult) -> Result<NetworkRecoverResult, String> {
    if user_disconnected_now() {
        set_underlay_reconnecting(false);
        clear_underlay_notice();
        return Ok(noop("idle", "user disconnected"));
    }
    let healthy = current_session_healthy();
    if reconnecting_after_rebind(healthy, false) {
        show_reconnecting();
    } else {
        set_underlay_reconnecting(false);
        clear_underlay_notice();
        let mut mem = follow_lock();
        mem.diverged_since = None;
        mem.pending_key.clear();
        mem.pending_since = None;
    }
    Ok(result)
}

fn recover_network_switch_linux() -> Result<NetworkRecoverResult, String> {
    if user_disconnected_now() {
        set_underlay_reconnecting(false);
        return Ok(noop("idle", "user disconnected"));
    }

    let tunnel_up = tunnel_iface_up();
    let detected = detect_underlay_fingerprint();
    let recorded = if tunnel_up {
        recorded_underlay_fingerprint()
    } else {
        String::new()
    };
    let (present, changed) = {
        let mut mem = follow_lock();
        let now = Instant::now();
        let mut pending_key = std::mem::take(&mut mem.pending_key);
        let mut pending_since = mem.pending_since.take();
        let result = debounced_path_change(
            &recorded,
            detected.as_deref(),
            &mut pending_key,
            &mut pending_since,
            now,
            UNDERLAY_CHANGE_DEBOUNCE,
        );
        mem.pending_key = pending_key;
        mem.pending_since = pending_since;
        result
    };
    // Until a new iface|gateway has persisted, keep the recorded path. A
    // metric-only sample then cannot start path-adapt or the outage clock.
    let unconfirmed = tunnel_up
        && !changed
        && underlay_fingerprint_diverged(&recorded, detected.as_deref());
    let underlay_if = if unconfirmed {
        fingerprint_iface(&recorded).unwrap_or_default()
    } else {
        detected
            .as_deref()
            .and_then(fingerprint_iface)
            .unwrap_or_default()
    };
    let route_ok = if unconfirmed {
        true
    } else if !tunnel_up || !present || underlay_if.is_empty() {
        !tunnel_up || !present
    } else {
        endpoint_route_on_underlay(&underlay_if)
    };
    // Probe egress only during an outage, and skip the probe when the host
    // route is already on the wrong interface so the next try stays fast.
    let outage_active = follow_lock().diverged_since.is_some();
    let probing = tunnel_up && (changed || !present || !route_ok || outage_active);
    // A fresh handshake on the recorded iface|gateway is health. A failed
    // public-IP probe must not run path-adapt while that path is unchanged.
    let handshake_ok = super::published_handshake_fresh();
    let traffic_ok = if !probing {
        true
    } else if !present || !route_ok {
        false
    } else if !changed && handshake_ok {
        true
    } else {
        handshake_ok || tunnel_is_healthy().unwrap_or(false)
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
            // Same iface and gateway: do not path-adapt and do not change the UI,
            // even when a connectivity probe just failed.
            Ok(noop("noop", "underlay unchanged"))
        }
        UnderlayFollow::Rebind => {
            show_reconnecting();
            let result = rebind_underlay(true)?;
            finish_rebind(result)
        }
        UnderlayFollow::Wait => {
            show_reconnecting();
            if tunnel_up {
                let result = rebind_underlay(true)?;
                return finish_rebind(result);
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

fn fingerprint_iface(fp: &str) -> Option<String> {
    split_path_key(fp).map(|(iface, _)| iface)
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

fn iface_is_down(iface: &str) -> bool {
    let path = format!("/sys/class/net/{iface}/operstate");
    fs::read_to_string(path)
        .map(|state| state.trim().eq_ignore_ascii_case("down"))
        .unwrap_or(false)
}

fn command_stdout(args: &[&str]) -> Option<String> {
    let (bin, rest) = args.split_first()?;
    let out = std::process::Command::new(bin).args(rest).output().ok()?;
    let text = String::from_utf8_lossy(&out.stdout).trim().to_string();
    if text.is_empty() {
        None
    } else {
        Some(text)
    }
}

/// First usable default route, as `iface|gateway`. Metric is not read.
fn default_route_key(text: &str) -> Option<String> {
    for line in text.lines() {
        if line.contains("blackhole") || !line.contains(" via ") {
            continue;
        }
        let key = path_key_from_route_get(line)?;
        let iface = key.split('|').next().unwrap_or("");
        if iface.is_empty() || iface_is_down(iface) {
            continue;
        }
        return Some(key);
    }
    None
}

/// Underlay identity is `iface|gateway` with the metric stripped.
///
/// The default route is what the OS just selected. A NetworkManager
/// connectivity penalty changes only the metric, so it is not a new underlay.
/// `ip route get ENDPOINT` is the pin check: a host route left on the previous
/// gateway does not hide a new default route, and a pin that has fallen into
/// the tunnel is not healthy.
fn detect_underlay_fingerprint() -> Option<String> {
    if let Some(text) = command_stdout(&["ip", "-4", "route", "show", "default"]) {
        if let Some(key) = default_route_key(&text) {
            return Some(key);
        }
    }
    let endpoint = recorded_endpoint_ip();
    if endpoint.is_empty() || endpoint == "127.0.0.1" {
        return None;
    }
    let text = command_stdout(&["ip", "-4", "route", "get", &endpoint])?;
    let key = path_key_from_route_get(&text)?;
    let iface = key.split('|').next().unwrap_or("");
    if iface.is_empty() || iface_is_down(iface) {
        None
    } else {
        Some(key)
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
        debounced_path_change, decide_underlay_follow, default_route_key, path_key_from_route_get,
        reconnecting_after_rebind, recovery_lock_abandon, recovery_lock_acquire,
        recovery_lock_acquire_owned, recovery_lock_release, recovery_lock_release_owned,
        reconnect_backoff, route_dev_is, underlay_fingerprint_diverged, underlay_path_key,
        underlay_release_due, underlay_same, FlightGuard, FlightKind, FollowInput,
        UnderlayFollow, UNDERLAY_CHANGE_DEBOUNCE, UNDERLAY_GIVE_UP_AFTER, UNDERLAY_RELEASE_AFTER,
    };
    use std::time::Instant;

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
    fn default_route_metric_is_not_a_new_underlay() {
        let low = "default via 192.0.2.1 dev wlx90de802153e1 proto dhcp src 192.0.2.50 metric 600\n";
        let high = "default via 192.0.2.1 dev wlx90de802153e1 proto dhcp src 192.0.2.50 metric 20600\n";
        assert_eq!(default_route_key(low), default_route_key(high));
        assert_eq!(
            default_route_key(low).as_deref(),
            Some("wlx90de802153e1|192.0.2.1")
        );
        let with_blackhole = "blackhole default metric 1\ndefault via 192.0.2.1 dev wlx90de802153e1 metric 20600\n";
        assert_eq!(default_route_key(with_blackhole), default_route_key(low));
    }

    #[test]
    fn metric_only_change_is_the_same_underlay() {
        let low = path_key_from_route_get(
            "203.0.113.10 via 192.0.2.1 dev wlx90de802153e1 src 192.0.2.50 uid 1000\n    cache",
        )
        .expect("low metric route");
        let high = path_key_from_route_get(
            "203.0.113.10 via 192.0.2.1 dev wlx90de802153e1 src 192.0.2.50 metric 20600",
        )
        .expect("penalized metric route");
        assert_eq!(low, "wlx90de802153e1|192.0.2.1");
        assert_eq!(low, high);
        assert!(underlay_same(&low, &high));
        assert!(!underlay_fingerprint_diverged(&low, Some(&high)));
        // Older records were gateway|iface|src. A different source is still the same path.
        assert!(!underlay_fingerprint_diverged(
            "192.0.2.1|wlx90de802153e1|192.0.2.20",
            Some("wlx90de802153e1|192.0.2.1")
        ));
        assert!(!underlay_fingerprint_diverged(
            "192.0.2.1|wlx90de802153e1|192.0.2.20",
            Some("192.0.2.1|wlx90de802153e1|192.0.2.99")
        ));
        assert!(path_key_from_route_get("203.0.113.10 dev veritas0 src 10.0.0.2").is_none());
        assert_eq!(
            underlay_path_key("eno1|198.51.100.1"),
            "eno1|198.51.100.1"
        );
        assert!(underlay_fingerprint_diverged(
            "wlx90de802153e1|192.0.2.1",
            Some("eno1|198.51.100.1")
        ));
    }

    #[test]
    fn underlay_change_must_persist() {
        let t0 = Instant::now();
        let mut key = String::new();
        let mut since = None;
        let (present, changed) = debounced_path_change(
            "wlx|192.0.2.1",
            Some("wlx|192.0.2.1"),
            &mut key,
            &mut since,
            t0,
            UNDERLAY_CHANGE_DEBOUNCE,
        );
        assert!(present && !changed, "same iface and gateway is idle");
        let (present, changed) = debounced_path_change(
            "wlx|192.0.2.1",
            Some("eno1|198.51.100.1"),
            &mut key,
            &mut since,
            t0,
            UNDERLAY_CHANGE_DEBOUNCE,
        );
        assert!(present && !changed, "the first sample is not a change");
        let (present, changed) = debounced_path_change(
            "wlx|192.0.2.1",
            Some("eno1|198.51.100.1"),
            &mut key,
            &mut since,
            t0 + UNDERLAY_CHANGE_DEBOUNCE,
            UNDERLAY_CHANGE_DEBOUNCE,
        );
        assert!(present && changed, "a path that stays is a change");
        let (present, changed) = debounced_path_change(
            "wlx|192.0.2.1",
            None,
            &mut key,
            &mut since,
            t0,
            UNDERLAY_CHANGE_DEBOUNCE,
        );
        assert!(present && !changed, "a one-sample loss does not drop the session");
        let (present, changed) = debounced_path_change(
            "wlx|192.0.2.1",
            None,
            &mut key,
            &mut since,
            t0 + UNDERLAY_CHANGE_DEBOUNCE,
            UNDERLAY_CHANGE_DEBOUNCE,
        );
        assert!(!present && changed, "a loss that persists is a change");
    }

    #[test]
    fn healthy_tunnel_leaves_reconnecting() {
        assert!(!reconnecting_after_rebind(true, false));
        assert!(!reconnecting_after_rebind(true, true));
        assert!(!reconnecting_after_rebind(false, true));
        assert!(reconnecting_after_rebind(false, false));
        let src = include_str!("network_switch.rs");
        let needle = format!("{}{}", "rebind_underlay", "(false)");
        assert!(
            !src.contains(&needle),
            "a matching iface and gateway must not run path-adapt"
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
