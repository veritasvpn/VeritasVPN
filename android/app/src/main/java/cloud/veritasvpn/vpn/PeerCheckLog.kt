package cloud.veritasvpn.vpn

/**
 * Peer-check and re-provision log lines. Only an allowlisted outcome, a
 * status code, and the network handle are included. Hosts, keys, and tokens
 * cannot be written through these formatters.
 */
object PeerCheckLog {
    private val OUTCOMES = setOf(
        "PEER_ALIVE",
        "REFRESH_AND_RETRY",
        "PEER_GONE",
        "NETWORK_ERROR",
        "SIGNED_OUT",
    )
    private val VIAS = setOf("tunnel", "underlying")
    private val ATTEMPTS = setOf("started", "ok", "failed", "skipped")

    fun line(outcome: String, code: Int, via: String, handle: Long?): String {
        val safeOutcome = sanitizeOutcome(outcome).ifEmpty { "NETWORK_ERROR" }
        val safeVia = sanitizeVia(via).ifEmpty { "tunnel" }
        val safeCode = code.coerceIn(0, 599)
        val handleText = handle?.takeIf { it >= 0L }?.toString() ?: "none"
        return "peer check result=$safeOutcome code=$safeCode via=$safeVia handle=$handleText"
    }

    fun reprovision(outcome: String, code: Int): String {
        val safeOutcome = sanitizeAttempt(outcome).ifEmpty { "failed" }
        val safeCode = code.coerceIn(0, 599)
        return "re-provision attempt outcome=$safeOutcome code=$safeCode"
    }

    fun summary(outcome: String, code: Int, via: String): String {
        val safeOutcome = sanitizeOutcome(outcome)
        if (safeOutcome.isEmpty()) return "—"
        val safeVia = sanitizeVia(via)
        val codePart = if (code in 0..599) " code=$code" else ""
        val viaPart = if (safeVia.isEmpty()) "" else " via=$safeVia"
        return "$safeOutcome$codePart$viaPart"
    }

    fun reprovisionSummary(attempts: Int, outcome: String, code: Int): String {
        val count = attempts.coerceAtLeast(0)
        val safeOutcome = sanitizeAttempt(outcome)
        if (safeOutcome.isEmpty()) return "attempts=$count"
        val codePart = if (code in 0..599) " code=$code" else ""
        return "attempts=$count last=$safeOutcome$codePart"
    }

    fun sanitizeOutcome(raw: String?): String {
        val value = raw?.trim().orEmpty()
        return if (value in OUTCOMES) value else ""
    }

    fun sanitizeVia(raw: String?): String {
        val value = raw?.trim().orEmpty()
        return if (value in VIAS) value else ""
    }

    fun sanitizeAttempt(raw: String?): String {
        val value = raw?.trim().orEmpty()
        return if (value in ATTEMPTS) value else ""
    }

    /**
     * A create that was sent or that failed is one attempt. "started" is only
     * the log line before the response, and a skip did not create a peer.
     */
    fun countsAttempt(outcome: String?): Boolean = when (sanitizeAttempt(outcome)) {
        "ok", "failed" -> true
        else -> false
    }
}
