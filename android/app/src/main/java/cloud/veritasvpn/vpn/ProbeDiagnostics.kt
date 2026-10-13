package cloud.veritasvpn.vpn

/**
 * Log fields for one hard-rebind probe.
 *
 * The line is safe to log: a public-key prefix of at most 8 characters, an
 * endpoint kind (LAN or WAN), a network type, a network handle, and whether
 * the socket is bound to that handle. It never includes an address, a full
 * key, an endpoint, or a token.
 */
object ProbeDiagnostics {
    private val SAFE_KIND = setOf("LAN", "WAN")
    private val SAFE_NET = setOf("WIFI", "CELLULAR", "ETHERNET", "OTHER")

    /**
     * Eight characters of this phone's own public key, derived from PrivateKey.
     * The server PublicKey and the tunnel address are never logged here: the
     * address is an IP, and the server key is shared by every peer.
     */
    fun publicKeyPrefix(config: String): String =
        SavedPeerConfig.clientPublicKey(config).orEmpty().take(8)

    fun endpointKind(endpoint: String, lan: String?, wan: String?): String {
        val ep = endpoint.trim()
        val lanEp = lan?.trim().orEmpty()
        val wanEp = wan?.trim().orEmpty()
        if (lanEp.isNotEmpty() && ep == lanEp) return "LAN"
        if (wanEp.isNotEmpty() && ep == wanEp) return "WAN"
        if (EndpointSelector.isRfc1918(EndpointSelector.host(ep))) return "LAN"
        return "WAN"
    }

    fun networkType(wifi: Boolean, cellular: Boolean, ethernet: Boolean): String = when {
        wifi -> "WIFI"
        cellular -> "CELLULAR"
        ethernet -> "ETHERNET"
        else -> "OTHER"
    }

    fun format(
        keyPrefix: String,
        kind: String,
        networkType: String,
        handle: Long,
        socketBound: Boolean,
    ): String {
        val key = keyPrefix.take(8).filter { it.isLetterOrDigit() || it == '+' || it == '/' || it == '=' }
        val safeKind = if (kind in SAFE_KIND) kind else "WAN"
        val safeNet = if (networkType in SAFE_NET) networkType else "OTHER"
        return "path-adapt hard probe key=$key kind=$safeKind net=$safeNet handle=$handle bound=$socketBound"
    }
}
