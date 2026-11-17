//! WireGuardNT configuration blob. Pure data, no Windows calls, so the layout
//! and flags are unit tested on every platform. Layout matches wireguard.h
//! `ALIGNED(8)` and was checked on the Phase 0 spike.

use std::net::Ipv4Addr;

use base64::{engine::general_purpose::STANDARD, Engine};

pub(crate) const AF_INET: u16 = 2;

// WIREGUARD_INTERFACE_FLAGS
const IFACE_HAS_PRIVATE_KEY: u32 = 1 << 1;
const IFACE_HAS_LISTEN_PORT: u32 = 1 << 2;
const IFACE_REPLACE_PEERS: u32 = 1 << 3;

// WIREGUARD_PEER_FLAGS
pub(crate) const PEER_HAS_PUBLIC_KEY: u32 = 1 << 0;
pub(crate) const PEER_HAS_PRESHARED_KEY: u32 = 1 << 1;
pub(crate) const PEER_HAS_PERSISTENT_KEEPALIVE: u32 = 1 << 2;
pub(crate) const PEER_HAS_ENDPOINT: u32 = 1 << 3;
pub(crate) const PEER_REPLACE_ALLOWED_IPS: u32 = 1 << 5;

#[repr(C, align(4))]
#[derive(Clone, Copy)]
struct SockaddrInet {
    family: u16,
    port: u16,
    addr: [u8; 4],
    _rest: [u8; 20],
}

#[repr(C, align(8))]
#[derive(Clone, Copy)]
struct WgInterface {
    flags: u32,
    listen_port: u16,
    private_key: [u8; 32],
    public_key: [u8; 32],
    _pad_peers: u16,
    peers_count: u32,
}

#[repr(C, align(8))]
#[derive(Clone, Copy)]
struct WgPeer {
    flags: u32,
    reserved: u32,
    public_key: [u8; 32],
    preshared_key: [u8; 32],
    persistent_keepalive: u16,
    _pad_endpoint: u16,
    endpoint: SockaddrInet,
    tx_bytes: u64,
    rx_bytes: u64,
    last_handshake: u64,
    allowed_ips_count: u32,
    _pad_end: u32,
}

#[repr(C, align(8))]
#[derive(Clone, Copy)]
struct WgAllowedIp {
    address: [u8; 16],
    address_family: u16,
    cidr: u8,
    _pad_flags: u8,
    flags: u32,
}

const _: () = assert!(size_of::<WgInterface>() == 80);
const _: () = assert!(size_of::<WgPeer>() == 136);
const _: () = assert!(size_of::<WgAllowedIp>() == 24);
const _: () = assert!(size_of::<SockaddrInet>() == 28);

pub(crate) const INTERFACE_LEN: usize = size_of::<WgInterface>();
pub(crate) const PEER_LEN: usize = size_of::<WgPeer>();

/// The per-device preshared key from the control plane. Empty means the
/// server did not issue one. Anything else must be exactly 32 bytes.
pub fn decode_preshared_key(text: &str) -> Result<Option<[u8; 32]>, ()> {
    let text = text.trim();
    if text.is_empty() {
        return Ok(None);
    }
    let bytes = STANDARD.decode(text).map_err(|_| ())?;
    let key: [u8; 32] = bytes.try_into().map_err(|_| ())?;
    Ok(Some(key))
}

pub fn direct_config(
    private_key: [u8; 32],
    peer_key: [u8; 32],
    preshared_key: Option<[u8; 32]>,
    endpoint: Option<(Ipv4Addr, u16)>,
    listen_port: u16,
    keepalive: u16,
    allowed: &[(Ipv4Addr, u8)],
) -> Vec<u8> {
    let mut flags = IFACE_HAS_PRIVATE_KEY | IFACE_REPLACE_PEERS;
    if listen_port != 0 {
        flags |= IFACE_HAS_LISTEN_PORT;
    }
    let iface = WgInterface {
        flags,
        listen_port,
        private_key,
        public_key: [0; 32],
        _pad_peers: 0,
        peers_count: 1,
    };
    let mut peer_flags = PEER_HAS_PUBLIC_KEY | PEER_REPLACE_ALLOWED_IPS;
    let mut endpoint_addr = SockaddrInet {
        family: AF_INET,
        port: 0,
        addr: [0; 4],
        _rest: [0; 20],
    };
    if let Some((ip, port)) = endpoint {
        peer_flags |= PEER_HAS_ENDPOINT;
        endpoint_addr.port = port.to_be();
        endpoint_addr.addr = ip.octets();
    }
    if keepalive != 0 {
        peer_flags |= PEER_HAS_PERSISTENT_KEEPALIVE;
    }
    let psk = match preshared_key {
        Some(key) => {
            peer_flags |= PEER_HAS_PRESHARED_KEY;
            key
        }
        None => [0; 32],
    };
    let peer = WgPeer {
        flags: peer_flags,
        reserved: 0,
        public_key: peer_key,
        preshared_key: psk,
        persistent_keepalive: keepalive,
        _pad_endpoint: 0,
        endpoint: endpoint_addr,
        tx_bytes: 0,
        rx_bytes: 0,
        last_handshake: 0,
        allowed_ips_count: allowed.len() as u32,
        _pad_end: 0,
    };
    let mut bytes = Vec::with_capacity(INTERFACE_LEN + PEER_LEN + 24 * allowed.len());
    bytes.extend_from_slice(bytes_of(&iface));
    bytes.extend_from_slice(bytes_of(&peer));
    for (ip, cidr) in allowed {
        let row = WgAllowedIp {
            address: ip_bytes(*ip),
            address_family: AF_INET,
            cidr: *cidr,
            _pad_flags: 0,
            flags: 0,
        };
        bytes.extend_from_slice(bytes_of(&row));
    }
    bytes
}

/// True when `addr` is inside `network/prefix`.
pub fn in_subnet(addr: Ipv4Addr, network: Ipv4Addr, prefix: u8) -> bool {
    if prefix == 0 || prefix > 32 {
        return false;
    }
    let mask = u32::MAX << (32 - u32::from(prefix));
    (u32::from(addr) & mask) == (u32::from(network) & mask)
}

/// Decide whether the WireGuard server should be pinned on-link (no gateway).
/// `underlay` lists the IPv4 addresses and on-link prefix lengths of the
/// physical interface that carries the default route. A server on the same
/// LAN, like a home server next to this PC, is reached directly; sending it
/// via the router can drop the handshake.
pub fn endpoint_on_link(endpoint: Ipv4Addr, underlay: &[(Ipv4Addr, u8)]) -> bool {
    underlay
        .iter()
        // A /32 (or /31 point-to-point) carries no LAN, so it never makes a
        // neighbour on-link. Skip link-local too.
        .filter(|(addr, prefix)| *prefix > 0 && *prefix < 31 && !addr.is_link_local())
        .any(|(addr, prefix)| in_subnet(endpoint, *addr, *prefix))
}

fn ip_bytes(ip: Ipv4Addr) -> [u8; 16] {
    let o = ip.octets();
    let mut out = [0u8; 16];
    out[..4].copy_from_slice(&o);
    out
}

fn bytes_of<T>(value: &T) -> &[u8] {
    unsafe { std::slice::from_raw_parts((value as *const T).cast(), size_of::<T>()) }
}

#[cfg(test)]
mod tests {
    use super::*;

    const PEER_FLAGS: usize = INTERFACE_LEN;
    const PEER_PUBLIC_KEY: usize = INTERFACE_LEN + 8;
    const PEER_PSK: usize = INTERFACE_LEN + 8 + 32;

    fn peer_flags(blob: &[u8]) -> u32 {
        u32::from_ne_bytes(blob[PEER_FLAGS..PEER_FLAGS + 4].try_into().unwrap())
    }

    fn blob(psk: Option<[u8; 32]>) -> Vec<u8> {
        direct_config(
            [1; 32],
            [2; 32],
            psk,
            Some((Ipv4Addr::new(203, 0, 113, 10), 51820)),
            0,
            25,
            &[(Ipv4Addr::UNSPECIFIED, 0)],
        )
    }

    #[test]
    fn preshared_key_is_written_and_flagged() {
        let psk = [0xA5u8; 32];
        let text = STANDARD.encode(psk);
        let decoded = decode_preshared_key(&text).unwrap();
        assert_eq!(decoded, Some(psk));
        let bytes = blob(decoded);
        assert_eq!(bytes.len(), INTERFACE_LEN + PEER_LEN + 24);
        assert_eq!(&bytes[PEER_PUBLIC_KEY..PEER_PUBLIC_KEY + 32], &[2u8; 32]);
        assert_eq!(&bytes[PEER_PSK..PEER_PSK + 32], &psk);
        let flags = peer_flags(&bytes);
        assert_ne!(
            flags & PEER_HAS_PRESHARED_KEY,
            0,
            "preshared key flag must be set"
        );
        assert_ne!(flags & PEER_HAS_PUBLIC_KEY, 0);
        assert_ne!(flags & PEER_HAS_ENDPOINT, 0);
        assert_ne!(flags & PEER_HAS_PERSISTENT_KEEPALIVE, 0);
        assert_ne!(flags & PEER_REPLACE_ALLOWED_IPS, 0);
    }

    #[test]
    fn empty_preshared_key_leaves_the_flag_unset() {
        for text in ["", "   "] {
            let decoded = decode_preshared_key(text).unwrap();
            assert_eq!(decoded, None);
            let bytes = blob(decoded);
            assert_eq!(&bytes[PEER_PSK..PEER_PSK + 32], &[0u8; 32]);
            assert_eq!(peer_flags(&bytes) & PEER_HAS_PRESHARED_KEY, 0);
        }
    }

    #[test]
    fn bad_preshared_key_is_rejected() {
        assert!(decode_preshared_key(&STANDARD.encode([1u8; 16])).is_err());
        assert!(decode_preshared_key(&STANDARD.encode([1u8; 33])).is_err());
        assert!(decode_preshared_key("not base64 !!").is_err());
    }

    #[test]
    fn same_lan_server_is_on_link() {
        let lan = [(Ipv4Addr::new(10, 20, 30, 40), 24)];
        assert!(endpoint_on_link(Ipv4Addr::new(10, 20, 30, 7), &lan));
        assert!(!endpoint_on_link(Ipv4Addr::new(10, 20, 31, 7), &lan));
        assert!(!endpoint_on_link(Ipv4Addr::new(203, 0, 113, 10), &lan));
    }

    #[test]
    fn host_and_link_local_prefixes_never_make_on_link() {
        let only_host = [(Ipv4Addr::new(10, 0, 0, 5), 32)];
        assert!(!endpoint_on_link(Ipv4Addr::new(10, 0, 0, 5), &only_host));
        let link_local = [(Ipv4Addr::new(169, 254, 3, 4), 16)];
        assert!(!endpoint_on_link(
            Ipv4Addr::new(169, 254, 9, 9),
            &link_local
        ));
        assert!(!endpoint_on_link(Ipv4Addr::new(10, 0, 0, 5), &[]));
    }

    #[test]
    fn subnet_math() {
        assert!(in_subnet(
            Ipv4Addr::new(10, 1, 2, 3),
            Ipv4Addr::new(10, 1, 0, 0),
            16
        ));
        assert!(!in_subnet(
            Ipv4Addr::new(10, 2, 2, 3),
            Ipv4Addr::new(10, 1, 0, 0),
            16
        ));
        assert!(!in_subnet(
            Ipv4Addr::new(10, 1, 2, 3),
            Ipv4Addr::new(10, 1, 0, 0),
            0
        ));
    }
}
