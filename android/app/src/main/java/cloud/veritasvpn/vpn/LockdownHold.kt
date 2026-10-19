package cloud.veritasvpn.vpn

/**
 * Tun held while this app is signed out and Always-on lockdown is on.
 *
 * Userspace WireGuard is off, so every other app's packets go into this tun
 * and are discarded. This package is disallowed, which is the only way the
 * sign-up WebView can reach the network: its sockets are not [android.net.VpnService.protect]'d.
 * Apps the user already set to bypass stay disallowed so their path does not change.
 */
object LockdownHold {
    const val IPV4_ADDRESS = "10.72.0.2"
    const val IPV4_PREFIX = 32
    const val IPV4_ROUTE = "0.0.0.0"
    const val DNS = "10.72.0.1"
    const val IPV6_ADDRESS = "fd00:7670::2"
    const val IPV6_PREFIX = 128
    const val IPV6_ROUTE = "::"
    const val MTU = 1280

    fun disallowedPackages(ownPackage: String, bypassApps: Set<String>): Set<String> {
        val own = ownPackage.trim()
        return buildSet {
            if (own.isNotEmpty()) add(own)
            bypassApps.map { it.trim() }.filter { it.isNotEmpty() }.forEach { add(it) }
        }
    }
}
