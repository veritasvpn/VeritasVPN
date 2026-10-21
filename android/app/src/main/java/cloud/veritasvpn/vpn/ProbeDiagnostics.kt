package cloud.veritasvpn.vpn

/**
 * Log fields for one hard-rebind probe.
 *
 * The line is safe to log: an endpoint kind (LAN or WAN), a network type, a
 * network handle, and whether the socket is bound to that handle. It never
 * includes an address, a key, an endpoint, or a token.
 */
object ProbeDiagnostics {
    private val SAFE_KIND = setOf("LAN", "WAN")
    private val SAFE_NET = setOf("WIFI", "CELLULAR", "ETHERNET", "OTHER")

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
        kind: String,
        networkType: String,
        handle: Long,
        socketBound: Boolean,
    ): String {
        val safeKind = if (kind in SAFE_KIND) kind else "WAN"
        val safeNet = if (networkType in SAFE_NET) networkType else "OTHER"
        return "path-adapt hard probe kind=$safeKind net=$safeNet handle=$handle bound=$socketBound"
    }
}
