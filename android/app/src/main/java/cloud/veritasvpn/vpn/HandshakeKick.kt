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

    /**
     * One IPv4 UDP datagram written straight onto the tun. Stealth excludes this
     * app from the VPN, so a socket send would not enter WireGuard. The
     * destination is 240.0.0.1:9, which only exists to make userspace start
     * a handshake.
     */
    fun tunTrigger(source: String): ByteArray? {
        val src = ipv4(source) ?: return null
        val packet = ByteArray(29)
        packet[0] = 0x45
        packet[3] = 29
        packet[8] = 64
        packet[9] = 17
        src.copyInto(packet, 12)
        packet[16] = 240.toByte()
        packet[19] = 1
        val sum = ipChecksum(packet)
        packet[10] = (sum ushr 8).toByte()
        packet[11] = sum.toByte()
        packet[21] = 9
        packet[23] = 9
        packet[25] = 9
        return packet
    }

    private fun ipv4(text: String): ByteArray? {
        val parts = text.trim().split('.')
        if (parts.size != 4) return null
        val raw = ByteArray(4)
        for (i in 0 until 4) {
            val n = parts[i].toIntOrNull() ?: return null
            if (n !in 0..255) return null
            raw[i] = n.toByte()
        }
        return raw
    }

    private fun ipChecksum(header: ByteArray): Int {
        var sum = 0
        var i = 0
        while (i < 20) {
            if (i == 10) {
                i += 2
                continue
            }
            sum += ((header[i].toInt() and 0xFF) shl 8) or (header[i + 1].toInt() and 0xFF)
            i += 2
        }
        while (sum ushr 16 != 0) sum = (sum and 0xFFFF) + (sum ushr 16)
        return sum.inv() and 0xFFFF
    }
}
