package cloud.veritasvpn.vpn

/**
 * How Android chooses the WireGuard transport.
 *
 * Auto and UDP start on UDP and stay there. The app never switches itself to
 * Stealth after a failed handshake or a recovery round. Stealth runs only when
 * the user picked it, or when a session that is already on Stealth is resumed.
 * UDP and Stealth never fall back the other way.
 */
enum class StealthMode {
    AUTO,
    UDP,
    STEALTH;

    fun stored(): String = when (this) {
        AUTO -> "auto"
        UDP -> "udp"
        STEALTH -> "stealth"
    }

    companion object {
        fun fromStored(raw: String?): StealthMode = when (raw?.trim()?.lowercase()) {
            "udp" -> UDP
            "stealth" -> STEALTH
            else -> AUTO
        }
    }
}

/** Server-advertised Stealth listener. [available] is the API flag, not a guess. */
data class StealthOffer(
    val endpoint: String,
    val pathPrefix: String,
    val available: Boolean,
) {
    fun usable(): Boolean {
        if (!available) return false
        if (WstunnelProtocol.parseEndpoint(endpoint) == null) return false
        return runCatching { WstunnelProtocol.upgradePath(pathPrefix) }.isSuccess
    }
}

enum class InitialTransport {
    /** Bring WireGuard up on the UDP endpoint. Recovery stays on UDP. */
    UDP,

    /** Bring WireGuard up pointed at the local Stealth listener. */
    STEALTH,

    /** User asked for Stealth but the server did not offer a usable listener. */
    STEALTH_UNAVAILABLE,
}

object StealthPlanner {
    /**
     * Fresh connects and UDP-only always start on UDP. Auto resumes Stealth only
     * when this process is restoring a session that already fell back, so a
     * restart does not open a clearnet gap or repeat the UDP wait.
     */
    fun initial(
        mode: StealthMode,
        offer: StealthOffer,
        resumeStealth: Boolean = false,
    ): InitialTransport {
        if (resumeStealth && mode == StealthMode.AUTO && offer.usable()) {
            return InitialTransport.STEALTH
        }
        return when (mode) {
            StealthMode.UDP -> InitialTransport.UDP
            StealthMode.AUTO -> InitialTransport.UDP
            StealthMode.STEALTH ->
                if (offer.usable()) InitialTransport.STEALTH else InitialTransport.STEALTH_UNAVAILABLE
        }
    }

    /**
     * Failed UDP rounds never switch to Stealth, in Auto or any other mode.
     * [failedUdpRounds] is part of the decision so callers cannot treat "still
     * no handshake" as a reason to change transport.
     */
    fun switchesToStealthAfterFailedUdp(
        mode: StealthMode,
        failedUdpRounds: Int,
    ): Boolean {
        if (failedUdpRounds < 0) return false
        return when (mode) {
            StealthMode.AUTO, StealthMode.UDP, StealthMode.STEALTH -> false
        }
    }

    /**
     * Recovery and the initial UDP attempt never auto-switch to Stealth.
     * User-selected Stealth and a resumed Stealth session use [initial].
     */
    fun shouldFallback(
        mode: StealthMode,
        offer: StealthOffer,
        handshakeCompleted: Boolean,
    ): Boolean {
        if (!offer.usable() || handshakeCompleted) return false
        return switchesToStealthAfterFailedUdp(mode, failedUdpRounds = 1)
    }

    /** A handshake from an earlier tunnel must not count as this UDP attempt. */
    fun udpAttemptSucceeded(handshakeMs: Long, baselineMs: Long, startedMs: Long): Boolean =
        handshakeMs > baselineMs && handshakeMs >= startedMs
}
