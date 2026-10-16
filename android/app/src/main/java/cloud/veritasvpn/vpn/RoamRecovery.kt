package cloud.veritasvpn.vpn

/**
 * What to do when the system default network changes, and what not to do when
 * it does not.
 *
 * A capability or link-property update for the network already in use must not
 * restart WireGuard while the handshake is healthy. A real move (Wi-Fi lost,
 * cellular now the addressed default) reads the peer on that new network and
 * installs new protected sockets. One socket swap that does not handshake is
 * followed by a full rebuild on that same network before the round counts as
 * a failure.
 */
object RoamRecovery {
    enum class Install { SOCKET_SWAP, FULL_REBUILD }

    data class Plan(
        val touchTunnel: Boolean,
        val readPeerOnCurrentNetwork: Boolean,
        val install: Install?,
    )

    /** Same handle and a live handshake: leave the tunnel and the UI alone. */
    fun sameNetworkLeavesTunnelAlone(sameHandle: Boolean, handshakeHealthy: Boolean): Boolean =
        sameHandle && handshakeHealthy

    /**
     * The first install recreates sockets on the tun that is already up.
     * After that swap produces no handshake, the next install rebuilds the
     * interface without turning the tunnel down first.
     */
    fun installAfterFailedSwaps(failedSocketSwaps: Int): Install =
        if (failedSocketSwaps <= 0) Install.SOCKET_SWAP else Install.FULL_REBUILD

    /**
     * How long a roam may stay on Reconnecting before the give-up line.
     * A switch that handshakes in a few seconds never shows it.
     */
    const val NOTICE_AFTER_MS = 20_000L

    /**
     * The give-up line waits until this round's rebuild has finished and the
     * roam has been failing for [NOTICE_AFTER_MS]. [recoveryPending] means
     * that rebuild is still in progress. A later retry on the same network
     * does not clear the line.
     */
    fun failureEligible(
        socketSwapTried: Boolean,
        rebuildTried: Boolean,
        recoveryPending: Boolean,
        elapsedMs: Long,
    ): Boolean = socketSwapTried && rebuildTried && !recoveryPending && elapsedMs >= NOTICE_AFTER_MS

    /**
     * After a swap and a rebuild both miss, keep trying on this network.
     * The gap grows so a dead path does not spin.
     */
    object Retry {
        const val INITIAL_MS = 1_000L
        const val MAX_MS = 15_000L

        fun next(currentMs: Long): Long = (currentMs * 2).coerceAtMost(MAX_MS)
    }

    fun plan(
        previousHandle: Long?,
        currentHandle: Long?,
        currentHasAddress: Boolean,
        failedSocketSwaps: Int,
        handshakeHealthy: Boolean,
    ): Plan {
        val sameHandle = previousHandle != null && previousHandle == currentHandle
        if (sameNetworkLeavesTunnelAlone(sameHandle, handshakeHealthy)) {
            return Plan(touchTunnel = false, readPeerOnCurrentNetwork = false, install = null)
        }
        if (currentHandle == null || !currentHasAddress) {
            return Plan(touchTunnel = false, readPeerOnCurrentNetwork = false, install = null)
        }
        if (sameHandle && handshakeHealthy) {
            return Plan(touchTunnel = false, readPeerOnCurrentNetwork = false, install = null)
        }
        return Plan(
            touchTunnel = true,
            readPeerOnCurrentNetwork = true,
            install = installAfterFailedSwaps(failedSocketSwaps),
        )
    }
}
