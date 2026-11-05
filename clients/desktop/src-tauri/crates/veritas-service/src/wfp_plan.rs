//! Kill-switch rule list. This module does not call Windows.
//! The service installs the same list as persistent WFP filters.

use std::net::Ipv4Addr;

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
    out.push(Rule::BlockDns);
    out.push(Rule::BlockOther);
    out
}

pub fn local_network_from_allowed(allowed: &[String]) -> bool {
    !allowed.iter().any(|item| item.trim() == "0.0.0.0/0")
}

/// WFP IPv4 conditions are host byte order.
pub fn host_ipv4(addr: Ipv4Addr) -> u32 {
    u32::from_ne_bytes(addr.octets())
}

/// `network_order_mask` has its prefix bits in the high end, matching `Ipv4Addr`.
pub fn host_mask(network_order_mask: u32) -> u32 {
    u32::from_ne_bytes(network_order_mask.to_be_bytes())
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
    fn wfp_values_are_host_byte_order() {
        assert_eq!(host_ipv4("10.1.2.3".parse().unwrap()), u32::from_ne_bytes([10, 1, 2, 3]));
        let slash8 = u32::MAX.checked_shl(24).unwrap();
        assert_eq!(host_mask(slash8), u32::from_ne_bytes([255, 0, 0, 0]));
        assert_eq!(host_mask(u32::MAX), u32::MAX);
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
