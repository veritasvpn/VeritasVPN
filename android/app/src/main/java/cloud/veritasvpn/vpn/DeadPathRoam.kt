package cloud.veritasvpn.vpn

/**
 * Whether a network change must redial, including when the previous attempt
 * never received a reply.
 *
 * A handshake timestamp younger than the protected window is not proof the
 * path still works. Moving to another addressed network redials. The same
 * network with a handshake that actually received bytes stays up.
 */
object DeadPathRoam {
    /** Health poll plus the callback debounce. A switch has to fit in this. */
    const val REDIAL_WITHIN_MS = 3_000L
    const val HEALTH_POLL_MS = 2_000L

    data class Attempt(
        val recordedHandle: String?,
        val liveHandle: String?,
        val liveReady: Boolean,
        val handshakeFresh: Boolean,
        val rxSinceAttempt: Long,
    )

    /**
     * True when WireGuard has to be pointed at [Attempt.liveHandle].
     * A different ready network always redials, even if the last handshake
     * still looks fresh and even if that handshake never received data.
     * The first observation (nothing recorded yet) is not a roam.
     */
    fun redial(attempt: Attempt): Boolean {
        if (attempt.recordedHandle == null) return false
        if (!attempt.liveReady || attempt.liveHandle == null) return false
        return attempt.liveHandle != attempt.recordedHandle
    }

    /** Protected requires a fresh handshake that returned bytes. */
    fun workingSession(handshakeFresh: Boolean, rxSinceAttempt: Long): Boolean =
        handshakeFresh && rxSinceAttempt > 0L

    fun timeToRedialMs(): Long = HEALTH_POLL_MS + EndpointRefresh.DEBOUNCE_MS
}
