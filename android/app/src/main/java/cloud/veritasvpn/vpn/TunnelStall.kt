package cloud.veritasvpn.vpn

object TunnelStall {

    const val DETECT_WINDOW_MS = 15_000L
    const val RX_THRESHOLD = 0L
    const val HANDSHAKE_STALE_MS = 180_000L
    const val MIN_REBIND_INTERVAL_MS = 30_000L
    const val MAX_REBIND_INTERVAL_MS = 120_000L

    fun isStalled(
        elapsedMs: Long,
        rxDelta: Long,
        txDelta: Long,
        handshakeAgeMs: Long,
    ): Boolean =
        elapsedMs >= DETECT_WINDOW_MS &&
            rxDelta <= RX_THRESHOLD &&
            txDelta > 0 &&
            handshakeAgeMs > HANDSHAKE_STALE_MS

    fun nextBackoff(currentBackoffMs: Long): Long =
        (currentBackoffMs * 2).coerceAtMost(MAX_REBIND_INTERVAL_MS)

    fun shouldResetBackoff(rxDelta: Long, handshakeAgeMs: Long): Boolean =
        rxDelta > RX_THRESHOLD || handshakeAgeMs <= HANDSHAKE_STALE_MS
}
