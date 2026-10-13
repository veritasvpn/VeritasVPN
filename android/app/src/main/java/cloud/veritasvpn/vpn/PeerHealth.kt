package cloud.veritasvpn.vpn

object PeerHealth {

    enum class Decision { OK, REDIAL, VERIFY_PEER }

    const val NO_HANDSHAKE_TIMEOUT_MS = 45_000L
    const val VERIFY_COOLDOWN_MS = 120_000L

    fun decide(
        handshakeMs: Long,
        linkAttachedAtMs: Long,
        nowMs: Long,
        networkUsable: Boolean,
        lastVerifyAtMs: Long,
        rxDeltaSinceAttach: Long = 0,
    ): Decision {
        if (!networkUsable) return Decision.OK
        val hasHandshake = handshakeMs > 0L && handshakeMs > linkAttachedAtMs
        if (hasHandshake) return Decision.OK
        if (rxDeltaSinceAttach > 0) return Decision.REDIAL
        val noHandshakeFor = nowMs - linkAttachedAtMs
        if (noHandshakeFor < NO_HANDSHAKE_TIMEOUT_MS) return Decision.REDIAL
        if (lastVerifyAtMs > 0L && nowMs - lastVerifyAtMs < VERIFY_COOLDOWN_MS) return Decision.REDIAL
        return Decision.VERIFY_PEER
    }
}
