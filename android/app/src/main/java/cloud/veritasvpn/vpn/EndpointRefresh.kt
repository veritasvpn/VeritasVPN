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
    /**
     * Coalesce the burst of callbacks from one switch. A real network change
     * must not sit behind a one-second timer before the socket swap starts.
     */
    const val DEBOUNCE_MS = 200L

    /** How often to read backend handshake stats while a roam is in progress. */
    const val HANDSHAKE_POLL_MS = 200L

    /** Upper bound for one attempt. Connected is published as soon as stats move, not when this ends. */
    const val HANDSHAKE_WAIT_MS = 1_200L

    /**
     * The peer GET runs beside the socket swap. It must not use the old 4s
     * budget: a slow read was holding the first handshake.
     */
    const val PEER_READ_TIMEOUT_SECONDS = 2L
    const val PEER_READ_BUDGET_MS = 2_000L

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

    /**
     * Endpoint to install for the network the phone is on now.
     *
     * A private LAN endpoint is kept only when an underlay address shares its
     * /24. Leaving that LAN installs the public endpoint, including when the
     * server still advertises the LAN address because the read left over the
     * old network or through the tunnel. The previous private endpoint is
     * never a fallback, and no port is invented.
     */
    data class UnderlayChoice(
        val udpEndpoint: String,
        val stealthEndpoint: String?,
        val kind: String,
    )

    fun chooseForUnderlay(
        advertised: String?,
        lan: String?,
        wan: String?,
        underlayIpv4s: List<String>,
        stealth: Boolean = false,
        stealthEndpoint: String? = null,
    ): UnderlayChoice? {
        val advertisedEp = blankToNull(advertised)
        val storedLan = blankToNull(lan)
        // Known LAN first, then a private server_endpoint from this network's
        // peer read. The first one this underlay can reach wins. A public
        // address is not a fallback while that LAN endpoint is reachable.
        val privateCandidates = LinkedHashSet<String>()
        storedLan?.takeIf { isPrivateEndpoint(it) }?.let { privateCandidates.add(it) }
        advertisedEp?.takeIf { isPrivateEndpoint(it) }?.let { privateCandidates.add(it) }
        val matchingLan = privateCandidates.firstOrNull { ep ->
            val host = EndpointSelector.host(ep)
            underlayIpv4s.any { EndpointSelector.sameIpv4Slash24(it, host) }
        }
        val wanEp = blankToNull(wan)?.takeUnless { isPrivateEndpoint(it) }
            ?: advertisedEp?.takeUnless { isPrivateEndpoint(it) }
        val nodeLan = storedLan?.takeIf { isPrivateEndpoint(it) }
            ?: advertisedEp?.takeIf { isPrivateEndpoint(it) }
        val onLan = matchingLan != null
        val udp = matchingLan ?: wanEp
        if (udp == null) return null
        if (!stealth) {
            val kind = if (isPrivateEndpoint(udp)) "LAN" else "public"
            return UnderlayChoice(udp, null, kind)
        }
        val chosenStealth = chooseStealth(blankToNull(stealthEndpoint), nodeLan, wanEp, onLan) ?: return null
        return UnderlayChoice(udp, chosenStealth, "stealth")
    }

    /**
     * A blank advertisement must not hide the endpoint saved at connect time.
     */
    fun storedOrFresh(fresh: String?, stored: String?): String? =
        blankToNull(fresh) ?: blankToNull(stored)

    /**
     * The installed endpoint may stay only when it is the one [chooseForUnderlay]
     * would install. A public endpoint is not acceptable on the node LAN.
     */
    fun endpointFitsUnderlay(
        installed: String?,
        advertised: String?,
        lan: String?,
        wan: String?,
        underlayIpv4s: List<String>,
        stealth: Boolean = false,
        stealthEndpoint: String? = null,
    ): Boolean {
        if (!keepsPrivateEndpoint(installed, underlayIpv4s)) return false
        val choice = chooseForUnderlay(
            advertised = advertised,
            lan = lan,
            wan = wan,
            underlayIpv4s = underlayIpv4s,
            stealth = stealth,
            stealthEndpoint = stealthEndpoint,
        ) ?: return true
        val wanted = if (stealth) choice.stealthEndpoint else choice.udpEndpoint
        if (wanted.isNullOrBlank() || !isPrivateEndpoint(wanted)) return true
        return !endpointChanged(installed, wanted)
    }

    /**
     * True when [endpoint] may stay installed on this underlay. A private
     * address stays only on its own LAN. An empty endpoint is not private.
     */
    fun keepsPrivateEndpoint(endpoint: String?, underlayIpv4s: List<String>): Boolean {
        val ep = blankToNull(endpoint) ?: return true
        if (!isPrivateEndpoint(ep)) return true
        val host = EndpointSelector.host(ep)
        return underlayIpv4s.any { EndpointSelector.sameIpv4Slash24(it, host) }
    }

    private fun chooseStealth(
        stealth: String?,
        lan: String?,
        wan: String?,
        onLan: Boolean,
    ): String? {
        if (stealth == null) return null
        if (onLan && lan != null && isPrivateEndpoint(lan)) {
            return withHost(stealth, EndpointSelector.host(lan)) ?: stealth.takeIf { isPrivateEndpoint(it) }
        }
        if (!isPrivateEndpoint(stealth)) return stealth
        val wanHost = wan?.let { EndpointSelector.host(it) }?.takeUnless { EndpointSelector.isRfc1918(it) }
            ?: return null
        if (lan != null && EndpointSelector.host(stealth) == EndpointSelector.host(lan)) {
            return withHost(stealth, wanHost)
        }
        return null
    }

    private fun isPrivateEndpoint(endpoint: String): Boolean =
        EndpointSelector.isRfc1918(EndpointSelector.host(endpoint))

    private fun withHost(endpoint: String, host: String): String? {
        val port = portOf(endpoint) ?: return null
        if (host.isBlank() || host.contains(':')) return null
        return "$host:$port"
    }

    private fun portOf(endpoint: String): String? {
        val colon = endpoint.lastIndexOf(':')
        if (colon <= 0 || colon == endpoint.lastIndex) return null
        val port = endpoint.substring(colon + 1)
        val n = port.toIntOrNull() ?: return null
        if (n !in 1..65535) return null
        return port
    }

    /**
     * Connected when the backend has a handshake newer than the network change.
     * An older handshake from the previous network does not count, and the
     * decision does not wait for a timer to expire.
     */
    fun handshakeRestoresConnected(handshakeMs: Long, changedAtMs: Long): Boolean =
        changedAtMs > 0L && handshakeMs > changedAtMs

    fun statusAfterHandshake(reconnecting: Boolean, handshakeMs: Long, changedAtMs: Long): String =
        if (reconnecting && !handshakeRestoresConnected(handshakeMs, changedAtMs)) {
            "Reconnecting…"
        } else {
            "Connected"
        }

    /** The fresh endpoint is already the one in the tunnel. No second socket swap. */
    fun endpointChanged(current: String?, fresh: String?): Boolean {
        val next = fresh?.trim().orEmpty()
        if (next.isEmpty()) return false
        return next != current?.trim().orEmpty()
    }

    /**
     * A roam keeps the VpnService interface. Replacing it with a new establish()
     * drops the kill switch and lets traffic out in the clear.
     */
    fun establishesNewTunOnRoam(): Boolean = false

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

    /**
     * Shown only after a socket swap and a full rebuild have both been tried,
     * and only when that recovery is no longer pending. A peer-read miss
     * alone stays on Reconnecting.
     */
    fun noticeAfter(
        failures: Int,
        rebuildTried: Boolean = false,
        recoveryPending: Boolean = false,
    ): String? {
        if (!RoamRecovery.failureEligible(
                socketSwapTried = failures > 0,
                rebuildTried = rebuildTried,
                recoveryPending = recoveryPending,
            )
        ) {
            return null
        }
        return if (failures >= RETRIES_BEFORE_MESSAGE) GIVE_UP_MESSAGE else null
    }

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
        if (text.contains("timed out", ignoreCase = true)) return true
        if (text.contains("Exception")) return true
        if (text.startsWith("java.")) return true
        return false
    }

    private fun blankToNull(value: String?): String? =
        value?.trim()?.takeIf { it.isNotEmpty() }
}
