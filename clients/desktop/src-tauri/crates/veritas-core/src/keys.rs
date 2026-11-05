use base64::{engine::general_purpose::STANDARD, Engine};
use rand_core::OsRng;
use x25519_dalek::{PublicKey, StaticSecret};

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct KeyPair {
    pub private_key: String,
    pub public_key: String,
}

pub fn generate_keypair() -> KeyPair {
    let secret = StaticSecret::random_from_rng(OsRng);
    let public = PublicKey::from(&secret);
    KeyPair {
        private_key: STANDARD.encode(secret.to_bytes()),
        public_key: STANDARD.encode(public.to_bytes()),
    }
}

pub fn b64_key_to_hex(b64: &str) -> Result<String, String> {
    let bytes = STANDARD
        .decode(b64.trim())
        .map_err(|e| format!("invalid key: {e}"))?;
    if bytes.len() != 32 {
        return Err("WireGuard key must be 32 bytes".into());
    }
    Ok(hex::encode(bytes))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn generated_keys_are_32_byte_standard_base64() {
        let pair = generate_keypair();
        assert!(b64_key_to_hex(&pair.private_key).is_ok());
        assert!(b64_key_to_hex(&pair.public_key).is_ok());
        assert_ne!(pair.private_key, pair.public_key);
    }

    #[test]
    fn rejects_a_short_key() {
        let err = b64_key_to_hex(&STANDARD.encode([1u8; 16])).unwrap_err();
        assert!(err.contains("32 bytes"));
    }
}
