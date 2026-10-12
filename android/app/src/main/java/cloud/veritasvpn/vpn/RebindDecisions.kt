package cloud.veritasvpn.vpn

/**
 * Pure functions for tunnel recovery decisions.
 *
 * Extracted from [VeritasVpnService] so they can be unit tested without
 * the Android framework or WireGuard backend.
 */
object RebindDecisions {

    /**
     * Soft rebind only counts as a success when a NEW WireGuard handshake
     * completed after the attach point AND after the baseline.
     *
     * A stale handshake (from before the network switch) or leftover rx
     * traffic from the old path must not be treated as success — that was
     * the original root cause of the tunnel dying after a network switch.
     */
    fun softRebindSucceeded(
        currentHandshakeMs: Long,
        attachedAtMs: Long,
        baselineHandshakeMs: Long,
    ): Boolean =
        currentHandshakeMs > attachedAtMs && currentHandshakeMs > baselineHandshakeMs

    /**
     * The hard rebind loop must not give up when the underlay is transiently
     * null (for example, HyperOS under lockdown takes time to mark a new
     * network as VALIDATED).
     *
     * Returns the underlay to probe with: validated first, then after
     * [fallbackAfterMs] milliseconds of elapsed time, the best unvalidated
     * snap is returned so the loop can start probing. If neither is
     * available, returns null and the loop should retry later.
     */
    fun resolveHardLoopUnderlay(
        elapsedMs: Long,
        fallbackAfterMs: Long,
        validatedUnderlay: String?,
        unvalidatedUnderlay: String?,
    ): String? {
        if (validatedUnderlay != null) return validatedUnderlay
        if (elapsedMs >= fallbackAfterMs && unvalidatedUnderlay != null) return unvalidatedUnderlay
        return null
    }
}
