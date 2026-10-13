package cloud.veritasvpn.vpn

/**
 * Backoff for a failed background re-provision. One attempt runs at a time;
 * the delay grows from 5s to 60s while the user still wants the tunnel.
 */
object ReprovisionBackoff {
    const val INITIAL_MS = 5_000L
    const val MAX_MS = 60_000L

    fun next(currentMs: Long): Long = (currentMs * 2).coerceAtMost(MAX_MS)

    /**
     * A failed attempt must stay on Reconnecting / Not protected and try again
     * only while this session is still the one the user asked for.
     */
    fun shouldScheduleRetry(
        succeeded: Boolean,
        generationMatches: Boolean,
        sessionIntended: Boolean,
        disconnectInProgress: Boolean,
    ): Boolean = !succeeded && generationMatches && sessionIntended && !disconnectInProgress
}
