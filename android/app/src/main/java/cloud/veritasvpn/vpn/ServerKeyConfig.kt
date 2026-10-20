package cloud.veritasvpn.vpn

/**
 * Replaces the WireGuard peer public key when the API advertises a new one.
 * The endpoint, the client private key, and the selected transport stay put.
 */
object ServerKeyConfig {
    enum class Refresh { KEEP, REBUILD }

    fun peerPublicKey(config: String): String? {
        var inPeer = false
        for (line in config.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.startsWith("[")) {
                inPeer = trimmed.equals("[Peer]", ignoreCase = true)
                continue
            }
            if (!inPeer || !trimmed.startsWith("PublicKey", ignoreCase = true)) continue
            val eq = trimmed.indexOf('=')
            if (eq < 0) continue
            val value = trimmed.substring(eq + 1).trim()
            if (value.isNotEmpty()) return value
        }
        return null
    }

    fun changed(config: String?, advertised: String?): Boolean {
        val next = advertised?.trim().orEmpty()
        if (next.isEmpty() || config.isNullOrBlank()) return false
        val current = peerPublicKey(config) ?: return false
        return current != next
    }

    fun needsRebuild(udpConfig: String?, liveConfig: String?, advertised: String?): Boolean =
        changed(udpConfig, advertised) || changed(liveConfig, advertised)

    fun refresh(storedConfig: String?, advertised: String?): Refresh =
        if (changed(storedConfig, advertised)) Refresh.REBUILD else Refresh.KEEP

    /** [advertised] blank or equal leaves [config] unchanged. */
    fun withServerKey(config: String, advertised: String?): String {
        val next = advertised?.trim().orEmpty()
        if (!changed(config, next)) return config
        var inPeer = false
        var replaced = false
        val lines = config.split('\n').map { line ->
            val trimmed = line.trim()
            if (trimmed.startsWith("[")) {
                inPeer = trimmed.equals("[Peer]", ignoreCase = true)
                return@map line
            }
            if (inPeer && !replaced &&
                trimmed.startsWith("PublicKey", ignoreCase = true) &&
                trimmed.contains('=')
            ) {
                replaced = true
                "PublicKey = $next"
            } else {
                line
            }
        }
        return if (replaced) lines.joinToString("\n") else config
    }
}
