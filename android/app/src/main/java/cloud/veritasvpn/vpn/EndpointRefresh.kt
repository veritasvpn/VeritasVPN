package cloud.veritasvpn.vpn

/**
 * Picks the WireGuard endpoint for the phone's current network.
 *
 * A fresh connect asks the API, which returns the endpoint for the source
 * address it sees. Reconnect must do the same. The endpoint chosen on a
 * previous network is never a fallback: a home router without NAT hairpin
 * cannot deliver that public address back onto the LAN.
 */
object EndpointRefresh {
    const val DEBOUNCE_MS = 1_000L

    /** How long to wait for a handshake after the fresh endpoint is installed. */
    const val HANDSHAKE_WAIT_MS = 2_500L

    const val RETRY_DELAY_MS = 1_000L
    const val RETRIES_BEFORE_MESSAGE = 3

    const val GIVE_UP_MESSAGE =
        "Couldn't reconnect on this network. Check your connection and try again."

    enum class Transport { UDP, STEALTH }

    data class Offer(
        val serverEndpoint: String,
        val lan: String?,
        val wan: String?,
        val stealthEndpoint: String?,
        val stealthAvailable: Boolean?,
        val stealthPrefix: String?,
    )

    data class Flight(val handle: Long?, val generation: Long)

    /**
     * The endpoint the server just returned for this network. Blank is a
     * failure. [ignoredPrevious] is never substituted, including when the
     * fresh value is missing.
     */
    fun offer(
        serverEndpoint: String?,
        lan: String?,
        wan: String?,
        stealthEndpoint: String?,
        stealthAvailable: Boolean?,
        stealthPrefix: String?,
        @Suppress("UNUSED_PARAMETER") ignoredPrevious: String?,
    ): Offer? {
        val server = freshEndpoint(serverEndpoint) ?: return null
        return Offer(
            serverEndpoint = server,
            lan = blankToNull(lan),
            wan = blankToNull(wan),
            stealthEndpoint = blankToNull(stealthEndpoint),
            stealthAvailable = stealthAvailable,
            stealthPrefix = blankToNull(stealthPrefix),
        )
    }

    fun freshEndpoint(serverEndpoint: String?): String? = blankToNull(serverEndpoint)

    /** A new default network cancels every in-flight attempt for the old one. */
    fun supersede(current: Flight?, nextHandle: Long?): Flight =
        Flight(nextHandle, (current?.generation ?: 0L) + 1L)

    /** A retry on the same network keeps the in-flight attempt. */
    fun sameNetwork(current: Flight?, nextHandle: Long?): Flight {
        if (current != null && current.handle == nextHandle) return current
        return supersede(current, nextHandle)
    }

    fun stillCurrent(attempt: Flight, latest: Flight?): Boolean =
        latest != null &&
            latest.generation == attempt.generation &&
            latest.handle == attempt.handle

    fun abandon(disconnectRequested: Boolean, attempt: Flight, latest: Flight?): Boolean =
        disconnectRequested || !stillCurrent(attempt, latest)

    fun noticeAfter(failures: Int): String? =
        if (failures >= RETRIES_BEFORE_MESSAGE) GIVE_UP_MESSAGE else null

    /**
     * Raw timeout and exception text never reach the UI. A sentence that
     * already tells the user what to do is left as written.
     */
    fun displayStatus(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        return if (isRawTimeout(raw)) GIVE_UP_MESSAGE else raw.trim()
    }

    fun statusWhileReconnecting(serviceError: String?): String =
        displayStatus(serviceError) ?: "Reconnecting…"

    /**
     * Direct stays Direct and Stealth stays Stealth. The current network is
     * not an input: moving between cell and Wi-Fi never changes the mode.
     */
    fun selectedTransport(
        modeStored: String?,
        resumeStealth: Boolean,
        transportRunning: Boolean,
    ): Transport {
        if (transportRunning || resumeStealth) return Transport.STEALTH
        if (StealthMode.fromStored(modeStored) == StealthMode.STEALTH) return Transport.STEALTH
        return Transport.UDP
    }

    private fun isRawTimeout(raw: String): Boolean {
        val text = raw.trim()
        if (text.equals("timeout", ignoreCase = true)) return true
        if (text.equals("timed out", ignoreCase = true)) return true
        if (text.equals("Connection timed out", ignoreCase = true)) return true
        if (text.contains("Exception")) return true
        if (text.startsWith("java.")) return true
        return false
    }

    private fun blankToNull(value: String?): String? =
        value?.trim()?.takeIf { it.isNotEmpty() }
}
