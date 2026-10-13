package cloud.veritasvpn.vpn

object StealthBackoff {

    const val INITIAL_MS = 2_000L
    const val MAX_MS = 30_000L
    const val JITTER_MS = 500L

    fun sequence(): Sequence<Long> = sequence {
        var current = INITIAL_MS
        while (true) {
            yield(current)
            current = (current * 2).coerceAtMost(MAX_MS)
        }
    }
}
