//! Kill-switch rule list. This module does not call Windows.
//! The service installs the same list as persistent WFP filters.

use std::net::{Ipv4Addr, Ipv6Addr};

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Endpoint {
    pub addr: Ipv4Addr,
    pub port: u16,
}

#[derive(Debug, Clone)]
pub struct LockPlan {
    pub tunnel_luid: u64,
    pub wireguard: Vec<Endpoint>,
    /// Present only when Stealth is actually in use. Phase 3 does not turn Stealth on.
    pub stealth: Option<Endpoint>,
    pub local_network: bool,
    /// Runner management hosts. Empty in production. The harness fills these so
    /// the lock does not cut the machine off from its agent, DNS, or gateway.
    pub management_v4: Vec<Ipv4Addr>,
    pub management_v6: Vec<Ipv6Addr>,
    /// TCP 80 and 443 on every adapter except the blocks below. Harness only.
    pub permit_web: bool,
}

/// Highest priority first. Later rules cannot override an earlier match
/// once the filters are installed with clear-action-right.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Rule {
    /// IPv4 and IPv6 loopback stay open.
    PermitLoopback,
    /// Every protocol on the tunnel adapter.
    PermitTunnel { luid: u64 },
    /// Outer WireGuard UDP to one server endpoint, on non-tunnel adapters.
    PermitWireGuard { addr: Ipv4Addr, port: u16 },
    /// Stealth helper TCP. Not installed unless a stealth endpoint was given.
    PermitStealth { addr: Ipv4Addr, port: u16 },
    /// DHCPv4 so a new network can still hand out an address.
    PermitDhcp,
    /// Private ranges and multicast. Only when local network access is on.
    PermitLocalNetwork,
    /// One IPv4 host the machine must still be able to reach. Harness only.
    PermitManagement { addr: Ipv4Addr },
    /// One IPv6 host the machine must still be able to reach. Harness only.
    PermitManagementV6 { addr: Ipv6Addr },
    /// TCP 80 and 443, so a CI runner can reach its agent. Harness only.
    PermitWeb,
    /// TCP and UDP 53 and 853, except traffic already permitted on the tunnel.
    BlockDns,
    /// Everything else, IPv4 and IPv6, including new inbound on non-tunnel adapters.
    BlockOther,
}

pub fn rules(plan: &LockPlan) -> Vec<Rule> {
    let mut out = vec![
        Rule::PermitLoopback,
        Rule::PermitTunnel {
            luid: plan.tunnel_luid,
        },
    ];
    let mut seen = Vec::new();
    for endpoint in &plan.wireguard {
        if endpoint.port == 0 || seen.contains(endpoint) {
            continue;
        }
        seen.push(endpoint.clone());
        out.push(Rule::PermitWireGuard {
            addr: endpoint.addr,
            port: endpoint.port,
        });
    }
    if let Some(endpoint) = &plan.stealth {
        if endpoint.port != 0 {
            out.push(Rule::PermitStealth {
                addr: endpoint.addr,
                port: endpoint.port,
            });
        }
    }
    out.push(Rule::PermitDhcp);
    if plan.local_network {
        out.push(Rule::PermitLocalNetwork);
    }
    let mut seen_v4 = Vec::new();
    for addr in &plan.management_v4 {
        if !usable_v4(*addr) || seen_v4.contains(addr) {
            continue;
        }
        seen_v4.push(*addr);
        out.push(Rule::PermitManagement { addr: *addr });
    }
    let mut seen_v6 = Vec::new();
    for addr in &plan.management_v6 {
        if !usable_v6(*addr) || seen_v6.contains(addr) {
            continue;
        }
        seen_v6.push(*addr);
        out.push(Rule::PermitManagementV6 { addr: *addr });
    }
    if plan.permit_web {
        out.push(Rule::PermitWeb);
    }
    out.push(Rule::BlockDns);
    out.push(Rule::BlockOther);
    out
}

fn usable_v4(addr: Ipv4Addr) -> bool {
    !addr.is_unspecified() && !addr.is_loopback() && !addr.is_multicast() && !addr.is_broadcast()
}

fn usable_v6(addr: Ipv6Addr) -> bool {
    !addr.is_unspecified() && !addr.is_loopback() && !addr.is_multicast()
}

pub fn local_network_from_allowed(allowed: &[String]) -> bool {
    !allowed.iter().any(|item| item.trim() == "0.0.0.0/0")
}

/// Form WFP accepts for `FWP_V4_ADDR_AND_MASK`. The first octet is the high
/// byte, and a prefix mask has its bits set from the top. The low-byte form
/// is rejected as `FWP_E_INVALID_NET_MASK` (0x8032001F).
pub fn host_ipv4(addr: Ipv4Addr) -> u32 {
    u32::from(addr)
}

/// `prefix_mask` already has its prefix bits in the high end.
pub fn host_mask(prefix_mask: u32) -> u32 {
    prefix_mask
}

#[cfg(test)]
mod tests {
    use super::*;

    fn plan(local: bool, stealth: bool) -> LockPlan {
        LockPlan {
            tunnel_luid: 42,
            wireguard: vec![
                Endpoint {
                    addr: "203.0.113.10".parse().unwrap(),
                    port: 51820,
                },
                Endpoint {
                    addr: "203.0.113.10".parse().unwrap(),
                    port: 51820,
                },
                Endpoint {
                    addr: "192.0.2.9".parse().unwrap(),
                    port: 51820,
                },
            ],
            stealth: stealth.then_some(Endpoint {
                addr: "198.51.100.8".parse().unwrap(),
                port: 443,
            }),
            local_network: local,
            management_v4: Vec::new(),
            management_v6: Vec::new(),
            permit_web: false,
        }
    }

    #[test]
    fn order_is_permits_then_dns_then_everything_else() {
        let rules = rules(&plan(false, false));
        assert_eq!(rules.first(), Some(&Rule::PermitLoopback));
        assert_eq!(
            rules.get(1),
            Some(&Rule::PermitTunnel { luid: 42 })
        );
        assert!(rules.contains(&Rule::PermitDhcp));
        assert!(!rules.contains(&Rule::PermitLocalNetwork));
        assert!(!rules.iter().any(|rule| matches!(rule, Rule::PermitStealth { .. })));
        assert_eq!(rules[rules.len() - 2], Rule::BlockDns);
        assert_eq!(rules[rules.len() - 1], Rule::BlockOther);
        let wireguard = rules
            .iter()
            .filter(|rule| matches!(rule, Rule::PermitWireGuard { .. }))
            .count();
        assert_eq!(wireguard, 2, "duplicate endpoints are installed once");
    }

    #[test]
    fn local_network_and_stealth_are_optional_permits() {
        let rules = rules(&plan(true, true));
        assert!(rules.contains(&Rule::PermitLocalNetwork));
        assert!(rules.iter().any(|rule| matches!(
            rule,
            Rule::PermitStealth {
                port: 443,
                ..
            }
        )));
        let local = rules.iter().position(|rule| rule == &Rule::PermitLocalNetwork).unwrap();
        let dns = rules.iter().position(|rule| rule == &Rule::BlockDns).unwrap();
        assert!(local < dns);
    }

    #[test]
    fn wfp_values_keep_the_prefix_in_the_high_bits() {
        assert_eq!(host_ipv4("10.1.2.3".parse().unwrap()), 0x0a01_0203);
        let slash8 = u32::MAX.checked_shl(24).unwrap();
        assert_eq!(host_mask(slash8), 0xff00_0000);
        assert_eq!(host_mask(u32::MAX), u32::MAX);
    }

    #[test]
    fn management_permits_are_above_the_blocks_and_optional() {
        let mut locked = plan(false, false);
        locked.management_v4 = vec!["127.0.0.1".parse().unwrap(), "140.82.112.4".parse().unwrap()];
        locked.management_v6 = vec!["::1".parse().unwrap(), "2001:db8::8".parse().unwrap()];
        let rules = rules(&locked);
        assert!(!rules.iter().any(|rule| matches!(rule, Rule::PermitManagement { addr } if addr.is_loopback())));
        assert!(rules.iter().any(|rule| matches!(rule, Rule::PermitManagement { addr } if !addr.is_loopback())));
        assert!(rules.iter().any(|rule| matches!(rule, Rule::PermitManagementV6 { .. })));
        let permit = rules.iter().position(|rule| matches!(rule, Rule::PermitManagement { .. })).unwrap();
        let dns = rules.iter().position(|rule| rule == &Rule::BlockDns).unwrap();
        assert!(permit < dns);
        assert!(!super::rules(&plan(false, false)).iter().any(|rule| matches!(
            rule,
            Rule::PermitManagement { .. } | Rule::PermitManagementV6 { .. } | Rule::PermitWeb
        )));
        let mut web = plan(false, false);
        web.permit_web = true;
        let web_rules = super::rules(&web);
        let web_at = web_rules.iter().position(|rule| rule == &Rule::PermitWeb).unwrap();
        let dns_at = web_rules.iter().position(|rule| rule == &Rule::BlockDns).unwrap();
        assert!(web_at < dns_at);
    }

    #[test]
    fn full_tunnel_does_not_open_the_lan() {
        assert!(!local_network_from_allowed(&["0.0.0.0/0".into(), "::/0".into()]));
        assert!(local_network_from_allowed(&[
            "0.0.0.0/5".into(),
            "8.0.0.0/7".into()
        ]));
    }
}
