package cloud.veritasvpn.vpn

/**
 * WireGuard sends its first handshake inside wgTurnOn, before Java can
 * protect() the UDP socket. The next initiation is then held for the
 * retransmit interval (about 5s). Stripping persistent keepalive keeps that
 * first send from happening. After protect(), one tun packet starts the
 * handshake on the socket that already bypasses the VPN.
 */
object HandshakeKick {
    /** NAT on cellular dies without traffic. This stands in for WireGuard's 25s keepalive. */
    const val KEEPALIVE_EVERY_MS = 20_000L

    /**
     * Userspace is started without persistent keepalive, so it does not
     * retransmit on its own. These kicks stand in for that timer. The first
     * packet is sent by the caller; the delays here are the retries.
     */
    const val RETRANSMIT_EVERY_MS = 250L
    const val RETRANSMIT_WINDOW_MS = 2_000L

    fun retransmitDelaysMs(): List<Long> {
        val delays = ArrayList<Long>()
        var at = RETRANSMIT_EVERY_MS
        while (at <= RETRANSMIT_WINDOW_MS) {
            delays.add(at)
            at += RETRANSMIT_EVERY_MS
        }
        return delays
    }

    fun withoutKeepalive(configText: String): String {
        val kept = configText.lineSequence().filterNot { isKeepaliveLine(it) }.toList()
        if (kept.isEmpty()) return ""
        val text = kept.joinToString("\n")
        return if (text.endsWith("\n")) text else "$text\n"
    }

    fun isKeepaliveLine(line: String): Boolean {
        val key = line.substringBefore('=').trim()
        return key.equals("persistent_keepalive_interval", ignoreCase = true) ||
            key.equals("PersistentKeepalive", ignoreCase = true)
    }
}
