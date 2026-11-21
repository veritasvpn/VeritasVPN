use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr};

use serde::{Deserialize, Serialize};

use crate::b64_key_to_hex;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct WgTunnelConfig {
    pub private_key: String,
    pub address: String,
    pub dns: String,
    pub server_public_key: String,
    pub endpoint: String,
    pub allowed_ips: Vec<String>,
    pub peer_id: String,
    #[serde(default)]
    pub preshared_key: String,
    /// Remote TLS/WebSocket host:port for stealth mode (empty = direct UDP).
    #[serde(default)]
    pub stealth_endpoint: String,
    #[serde(default)]
    pub stealth_path_prefix: String,
    /// Alternate LAN/WAN WireGuard endpoints so path-adapt can fail over.
    #[serde(default)]
    pub endpoint_lan: String,
    #[serde(default)]
    pub endpoint_wan: String,
}

impl WgTunnelConfig {
    /// Treat every field received from the control plane as untrusted. These
    /// values are consumed by privileged networking code, so validation must
    /// happen before the config is persisted or privilege elevation begins.
    pub fn validate(&self) -> Result<(), String> {
        b64_key_to_hex(&self.private_key)?;
        b64_key_to_hex(&self.server_public_key)?;
        if !self.preshared_key.trim().is_empty() {
            b64_key_to_hex(&self.preshared_key)?;
        }
        validate_cidr(&self.address, "address")?;
        let dns = if self.dns.trim().is_empty() {
            "1.1.1.1"
        } else {
            self.dns.trim()
        };
        dns.parse::<IpAddr>()
            .map_err(|_| "invalid DNS address".to_string())?;
        validate_endpoint(&self.endpoint, "endpoint", false)?;
        validate_endpoint(&self.endpoint_lan, "LAN endpoint", true)?;
        validate_endpoint(&self.endpoint_wan, "WAN endpoint", true)?;
        validate_endpoint(&self.stealth_endpoint, "stealth endpoint", true)?;
        if !self.stealth_endpoint.trim().is_empty() {
            validate_path_prefix(&self.stealth_path_prefix)?;
        }
        if self.allowed_ips.len() > 64 {
            return Err("too many allowed IP ranges".into());
        }
        for allowed in &self.allowed_ips {
            validate_cidr(allowed, "allowed IP")?;
        }
        let peer_id = self.peer_id.trim();
        if peer_id.is_empty()
            || peer_id.len() > 128
            || !peer_id
                .bytes()
                .all(|b| b.is_ascii_alphanumeric() || b == b'-' || b == b'_')
        {
            return Err("invalid peer id".into());
        }
        Ok(())
    }

    pub fn normalize_addresses(&mut self) {
        self.address = ensure_host_cidr(&self.address);
    }
}

/// A reused device peer is returned as a bare host. A fresh allocation already
/// has the prefix. The tunnel address has to be CIDR either way.
pub fn ensure_host_cidr(value: &str) -> String {
    let value = value.trim();
    if value.is_empty() || value.contains('/') {
        return value.to_string();
    }
    if value.parse::<Ipv4Addr>().is_ok() {
        return format!("{value}/32");
    }
    if value.parse::<Ipv6Addr>().is_ok() {
        return format!("{value}/128");
    }
    value.to_string()
}

fn validate_cidr(value: &str, field: &str) -> Result<(), String> {
    let value = value.trim();
    let (ip, prefix) = value
        .split_once('/')
        .ok_or_else(|| format!("{field} must be CIDR notation"))?;
    let ip: IpAddr = ip.parse().map_err(|_| format!("invalid {field}"))?;
    let prefix: u8 = prefix
        .parse()
        .map_err(|_| format!("invalid {field} prefix"))?;
    let max = if ip.is_ipv4() { 32 } else { 128 };
    if prefix > max {
        return Err(format!("invalid {field} prefix"));
    }
    Ok(())
}

fn validate_endpoint(value: &str, field: &str, allow_empty: bool) -> Result<(), String> {
    let value = value.trim();
    if value.is_empty() && allow_empty {
        return Ok(());
    }
    if value.len() > 255 || value.chars().any(char::is_whitespace) {
        return Err(format!("invalid {field}"));
    }
    if let Ok(socket) = value.parse::<SocketAddr>() {
        return (socket.port() != 0)
            .then_some(())
            .ok_or_else(|| format!("invalid {field} port"));
    }
    let (host, port) = value
        .rsplit_once(':')
        .ok_or_else(|| format!("{field} must include a port"))?;
    let port: u16 = port.parse().map_err(|_| format!("invalid {field} port"))?;
    if port == 0
        || host.is_empty()
        || host.len() > 253
        || !host
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || b == b'.' || b == b'-')
        || host.starts_with(['.', '-'])
        || host.ends_with(['.', '-'])
    {
        return Err(format!("invalid {field}"));
    }
    Ok(())
}

fn validate_path_prefix(value: &str) -> Result<(), String> {
    normalize_path_prefix(value)
        .map(|_| ())
        .ok_or_else(|| "invalid stealth path prefix".to_string())
}

/// Canonical wstunnel upgrade-path prefix.
///
/// The server secret is a bare segment (`openssl rand -hex 16`) and wstunnel
/// builds `/<prefix>/events` itself, so a leading slash would never match the
/// server. Accept one optional leading `/` from older configs, then require a
/// single segment of `[A-Za-z0-9-_.~]`, at most 128 bytes, never `..`.
/// Returns the bare segment, the same form Android sends.
pub fn normalize_path_prefix(value: &str) -> Option<String> {
    let value = value.trim();
    let segment = value.strip_prefix('/').unwrap_or(value);
    if segment.is_empty()
        || segment.len() > 128
        || segment.contains("..")
        || !segment
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || matches!(b, b'-' | b'_' | b'.' | b'~'))
    {
        return None;
    }
    Some(segment.to_string())
}

#[cfg(test)]
mod tests {
    use base64::{engine::general_purpose::STANDARD, Engine};

    use super::*;

    fn valid_config() -> WgTunnelConfig {
        let key = STANDARD.encode([7u8; 32]);
        WgTunnelConfig {
            private_key: key.clone(),
            address: "10.0.0.2/32".into(),
            dns: "10.0.0.1".into(),
            server_public_key: key,
            endpoint: "vpn.example.com:51820".into(),
            allowed_ips: vec!["0.0.0.0/0".into()],
            peer_id: "peer-123".into(),
            preshared_key: String::new(),
            stealth_endpoint: String::new(),
            stealth_path_prefix: String::new(),
            endpoint_lan: "192.168.0.6:51820".into(),
            endpoint_wan: "203.0.113.10:51820".into(),
        }
    }

    #[test]
    fn accepts_typed_tunnel_config() {
        valid_config().validate().unwrap();
    }

    #[test]
    fn reused_bare_peer_address_is_a_host_route() {
        assert_eq!(ensure_host_cidr("10.7.0.2"), "10.7.0.2/32");
        assert_eq!(ensure_host_cidr(" 10.7.0.2 "), "10.7.0.2/32");
        assert_eq!(ensure_host_cidr("10.7.0.2/32"), "10.7.0.2/32");
        assert_eq!(ensure_host_cidr("fd00::2"), "fd00::2/128");
        assert_eq!(ensure_host_cidr(""), "");
        assert_eq!(ensure_host_cidr("not-an-address"), "not-an-address");
        let mut config = valid_config();
        config.address = "10.7.0.2".into();
        config.normalize_addresses();
        config.validate().expect("bare host address");
        assert_eq!(config.address, "10.7.0.2/32");
    }

    #[test]
    fn rejects_shell_syntax_in_privileged_fields() {
        for malicious in ["1.1.1.1'; id #", "$(id)", "host:51820\nwhoami"] {
            let mut config = valid_config();
            config.endpoint = malicious.into();
            assert!(
                config.validate().is_err(),
                "accepted endpoint: {malicious:?}"
            );
        }
        let mut config = valid_config();
        config.dns = "1.1.1.1'; id #".into();
        assert!(config.validate().is_err());
        let mut config = valid_config();
        config.stealth_endpoint = "vpn.example.com:443".into();
        config.stealth_path_prefix = "/safe/../../tmp/x".into();
        assert!(config.validate().is_err());
    }

    #[test]
    fn server_prefix_without_a_slash_is_accepted() {
        // The server secret is `openssl rand -hex 16`: no leading slash.
        let hex = "0123456789abcdef0123456789abcdef";
        let mut config = valid_config();
        config.stealth_endpoint = "vpn.example.com:443".into();
        config.stealth_path_prefix = hex.into();
        config.validate().expect("slash-less server prefix");
        config.stealth_path_prefix = format!("/{hex}");
        config.validate().expect("legacy leading slash");
    }

    #[test]
    fn path_prefix_normalizes_to_one_bare_segment() {
        assert_eq!(normalize_path_prefix("abc123").as_deref(), Some("abc123"));
        assert_eq!(normalize_path_prefix("/abc123").as_deref(), Some("abc123"));
        assert_eq!(normalize_path_prefix(" /abc123 ").as_deref(), Some("abc123"));
        assert_eq!(normalize_path_prefix("a-b_c.d~e").as_deref(), Some("a-b_c.d~e"));
        let longest = "a".repeat(128);
        assert_eq!(normalize_path_prefix(&longest).as_deref(), Some(longest.as_str()));
        assert_eq!(normalize_path_prefix(&format!("/{longest}")).as_deref(), Some(longest.as_str()));
        for bad in [
            "",
            " ",
            "/",
            "//abc",
            "abc/def",
            "/abc/",
            "..",
            "/..",
            "a..b",
            "abc def",
            "abc;id",
            "$(id)",
            "abc\nx",
            "abc%2f",
            "ab\u{e9}",
        ] {
            assert_eq!(normalize_path_prefix(bad), None, "accepted {bad:?}");
        }
        assert_eq!(normalize_path_prefix(&"a".repeat(129)), None);
    }
}
