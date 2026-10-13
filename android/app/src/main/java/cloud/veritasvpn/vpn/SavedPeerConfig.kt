package cloud.veritasvpn.vpn

import com.wireguard.crypto.Key
import com.wireguard.crypto.KeyPair

/**
 * Which WireGuard config is allowed to replace the one already saved.
 *
 * A quick peer delete-then-create writes a new client key. A recovery path
 * that still holds the previous config must not put that old key back.
 *
 * The server [PublicKey] is the same for every peer on a node, so it cannot
 * tell two phone peers apart. Identity is the public key derived from this
 * phone's [PrivateKey], or the tunnel [Address] when that key cannot be read.
 */
object SavedPeerConfig {
    /** Public key of this phone's interface, derived from PrivateKey. */
    fun clientPublicKey(config: String): String? {
        val privateKey = configValue(config, "PrivateKey") ?: return null
        return runCatching {
            KeyPair(Key.fromBase64(privateKey)).publicKey.toBase64()
        }.getOrNull()
    }

    /**
     * Peer identity used to decide whether a recovery write may replace the
     * saved config. Never the server PublicKey.
     */
    fun peerIdentity(config: String): String? =
        clientPublicKey(config) ?: configValue(config, "Address")

    /**
     * [incoming] wins when it is the same peer (an endpoint update) or when
     * nothing is stored yet. A different stored client identity is newer and stays.
     */
    fun coalesce(stored: String?, incoming: String): String {
        if (stored.isNullOrBlank()) return incoming
        val storedId = peerIdentity(stored)
        val incomingId = peerIdentity(incoming)
        if (!storedId.isNullOrEmpty() && !incomingId.isNullOrEmpty() && storedId != incomingId) {
            return stored
        }
        return incoming
    }

    private fun configValue(config: String, name: String): String? {
        for (line in config.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("[")) continue
            val eq = trimmed.indexOf('=')
            if (eq <= 0) continue
            if (!trimmed.substring(0, eq).trim().equals(name, ignoreCase = true)) continue
            val value = trimmed.substring(eq + 1).trim()
            if (value.isNotEmpty()) return value
        }
        return null
    }
}
