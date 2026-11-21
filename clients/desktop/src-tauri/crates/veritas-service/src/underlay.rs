//! Pure underlay policy: what counts as "the network changed", which
//! physical default route carries the server, and when a reconnect has
//! really recovered. Nothing here touches Windows, so it is tested on every
//! platform.
//!
//! The old fingerprint was only the default gateway. Ethernet and Wi-Fi on
//! the same home router share that gateway (192.168.0.1), so turning
//! Ethernet off never looked like a change. Direct mode survived because
//! WireGuardNT picks a new source per packet; Stealth did not, because
//! wstunnel's TLS socket stayed bound to the Ethernet address that was gone.

use std::net::Ipv4Addr;
use std::time::{Duration, Instant};

/// One physical (non-tunnel) IPv4 default route.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct DefaultRoute {
    pub luid: u64,
    pub gateway: Ipv4Addr,
    /// Route metric plus interface metric, the number Windows compares.
    pub metric: u32,
    /// The interface reports media connected.
    pub connected: bool,
}

/// The route Windows would pick: lowest effective metric among connected
/// non-tunnel interfaces. Ties go to the lower LUID so the choice is stable.
pub fn best_default(routes: &[DefaultRoute], tunnel_luid: u64) -> Option<DefaultRoute> {
    routes
        .iter()
        .copied()
        .filter(|route| route.luid != 0 && route.luid != tunnel_luid && route.connected)
        .min_by_key(|route| (route.metric, route.luid))
}

/// Fingerprint of the physical network. It changes when an adapter that
/// carries a default route or a usable IPv4 address appears or disappears,
/// or when its gateway changes. Metrics are left out so a Windows metric
/// recalculation alone does not restart the tunnel.
pub fn fingerprint(routes: &[DefaultRoute], addresses: &[(u64, Ipv4Addr)], tunnel_luid: u64) -> String {
    let mut defaults: Vec<String> = routes
        .iter()
        .filter(|route| route.luid != 0 && route.luid != tunnel_luid && route.connected)
        .map(|route| format!("{:x}@{}", route.luid, route.gateway))
        .collect();
    defaults.sort();
    defaults.dedup();
    let mut addrs: Vec<String> = addresses
        .iter()
        .filter(|(luid, addr)| *luid != 0 && *luid != tunnel_luid && usable_address(*addr))
        .map(|(luid, addr)| format!("{luid:x}={addr}"))
        .collect();
    addrs.sort();
    addrs.dedup();
    format!("d:{};a:{}", defaults.join(","), addrs.join(","))
}

fn usable_address(addr: Ipv4Addr) -> bool {
    !(addr.is_unspecified() || addr.is_loopback() || addr.is_link_local() || addr.is_multicast() || addr.is_broadcast())
}

/// How long after a kick the monitor waits for traffic before it kicks
/// again. wstunnel needs a moment for TCP, TLS and the websocket upgrade.
pub const RECOVERY_RETRY: Duration = Duration::from_secs(10);
/// After this long without recovery the Reconnecting line explains what is
/// going on in plain words. The session and the kill switch stay.
pub const RECOVERY_EXPLAIN_AFTER: Duration = Duration::from_secs(45);

/// Shown with Reconnecting once Stealth has tried for a while. Never a code.
pub const STEALTH_STUCK_NOTICE: &str = "Reconnecting… Stealth can't reach the VeritasVPN server on this network yet. Your traffic stays blocked until it does. Check that this network is online, or press Disconnect to use the internet without the VPN.";
/// Same for Direct mode.
pub const DIRECT_STUCK_NOTICE: &str = "Reconnecting… The VeritasVPN server isn't answering on this network yet. Your traffic stays blocked until it does. Check that this network is online, or press Disconnect to use the internet without the VPN.";

/// A reconnect in progress. It ends only when the tunnel proves it carries
/// traffic again: new received bytes or a newer handshake than at the kick.
/// An old handshake from before the network change does not count.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Recovery {
    started: Instant,
    last_kick: Instant,
    rx: u64,
    handshake: i64,
}

impl Recovery {
    pub fn begin(now: Instant, rx: u64, handshake: i64) -> Self {
        Self {
            started: now,
            last_kick: now,
            rx,
            handshake,
        }
    }

    /// A later kick of the same outage keeps the start time and moves the
    /// baseline, so only traffic after this kick counts.
    pub fn kicked(&mut self, now: Instant, rx: u64, handshake: i64) {
        self.last_kick = now;
        self.rx = self.rx.max(rx);
        self.handshake = self.handshake.max(handshake);
    }

    pub fn recovered(&self, rx: u64, handshake: i64) -> bool {
        rx > self.rx || handshake > self.handshake
    }

    pub fn retry_due(&self, now: Instant) -> bool {
        now.saturating_duration_since(self.last_kick) >= RECOVERY_RETRY
    }

    pub fn explain(&self, now: Instant) -> bool {
        now.saturating_duration_since(self.started) >= RECOVERY_EXPLAIN_AFTER
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const ETH: u64 = 0x0006_0000_0100_0000;
    const WIFI: u64 = 0x0047_0000_0200_0000;
    const TUN: u64 = 0x0083_0000_0300_0000;
    const GW: Ipv4Addr = Ipv4Addr::new(192, 168, 0, 1);

    fn route(luid: u64, metric: u32) -> DefaultRoute {
        DefaultRoute {
            luid,
            gateway: GW,
            metric,
            connected: true,
        }
    }

    #[test]
    fn ethernet_off_on_the_same_router_is_a_change() {
        // Both adapters on one router: same gateway, as on the test PC.
        let both_routes = [route(ETH, 25), route(WIFI, 50)];
        let both_addrs = [(ETH, Ipv4Addr::new(192, 168, 0, 5)), (WIFI, Ipv4Addr::new(192, 168, 0, 8))];
        let wifi_routes = [route(WIFI, 50)];
        let wifi_addrs = [(WIFI, Ipv4Addr::new(192, 168, 0, 8))];
        let before = fingerprint(&both_routes, &both_addrs, TUN);
        let after = fingerprint(&wifi_routes, &wifi_addrs, TUN);
        assert_ne!(before, after);
        assert_eq!(best_default(&both_routes, TUN).unwrap().luid, ETH);
        assert_eq!(best_default(&wifi_routes, TUN).unwrap().luid, WIFI);
    }

    #[test]
    fn fingerprint_ignores_the_tunnel_order_and_metrics() {
        let a = fingerprint(
            &[route(ETH, 25), route(WIFI, 50), route(TUN, 0)],
            &[(ETH, Ipv4Addr::new(192, 168, 0, 5)), (TUN, Ipv4Addr::new(10, 0, 0, 7))],
            TUN,
        );
        let b = fingerprint(
            &[route(WIFI, 5), route(ETH, 90)],
            &[(ETH, Ipv4Addr::new(192, 168, 0, 5)), (TUN, Ipv4Addr::new(10, 0, 0, 9))],
            TUN,
        );
        assert_eq!(a, b);
        // Link-local and loopback addresses never count.
        let c = fingerprint(
            &[route(ETH, 25), route(WIFI, 50)],
            &[
                (ETH, Ipv4Addr::new(192, 168, 0, 5)),
                (WIFI, Ipv4Addr::new(169, 254, 3, 3)),
                (1, Ipv4Addr::LOCALHOST),
            ],
            TUN,
        );
        assert_eq!(a, c);
    }

    #[test]
    fn best_default_skips_the_tunnel_and_unplugged_adapters() {
        let mut eth = route(ETH, 25);
        eth.connected = false;
        let routes = [route(TUN, 0), eth, route(WIFI, 50)];
        assert_eq!(best_default(&routes, TUN).unwrap().luid, WIFI);
        assert!(best_default(&[route(TUN, 0)], TUN).is_none());
        let tie = [route(WIFI, 30), route(ETH, 30)];
        assert_eq!(best_default(&tie, TUN).unwrap().luid, ETH.min(WIFI));
    }

    #[test]
    fn an_old_handshake_does_not_end_the_reconnect() {
        let t0 = Instant::now();
        let mut recovery = Recovery::begin(t0, 1_000, 1_700_000_000);
        assert!(!recovery.recovered(1_000, 1_700_000_000));
        assert!(recovery.recovered(1_001, 1_700_000_000));
        assert!(recovery.recovered(1_000, 1_700_000_100));
        assert!(!recovery.retry_due(t0 + Duration::from_secs(3)));
        assert!(recovery.retry_due(t0 + RECOVERY_RETRY));
        recovery.kicked(t0 + RECOVERY_RETRY, 1_200, 1_700_000_000);
        assert!(!recovery.recovered(1_200, 1_700_000_000));
        assert!(!recovery.retry_due(t0 + RECOVERY_RETRY + Duration::from_secs(2)));
        assert!(!recovery.explain(t0 + Duration::from_secs(30)));
        assert!(recovery.explain(t0 + RECOVERY_EXPLAIN_AFTER));
    }

    #[test]
    fn stuck_notices_keep_the_session_and_carry_no_codes() {
        for notice in [STEALTH_STUCK_NOTICE, DIRECT_STUCK_NOTICE] {
            // The window keeps the session only for notices that start
            // with "Reconnecting".
            assert!(notice.starts_with("Reconnecting"));
            assert!(!notice.chars().any(|c| c.is_ascii_digit()), "{notice}");
            assert!(!notice.to_lowercase().contains("error"));
        }
    }
}
