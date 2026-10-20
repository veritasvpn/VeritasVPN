package cloud.veritasvpn.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * Internet means a validated network that is not the VPN itself.
 * A tunnel with no handshake must not hide the blocked-network card, and a
 * device with no validated network must not show it.
 */
object ValidatedNetworks {
    data class Fact(val vpn: Boolean, val validated: Boolean)

    fun hasInternet(facts: List<Fact>): Boolean {
        if (facts.isEmpty()) return false
        val underlay = facts.filter { !it.vpn }
        val pool = if (underlay.isEmpty()) facts else underlay
        return pool.any { it.validated }
    }

    fun deviceHasInternet(context: Context): Boolean {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val facts = manager.allNetworks.map { network ->
            val caps = manager.getNetworkCapabilities(network)
            Fact(
                vpn = caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true,
                validated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true,
            )
        }
        return hasInternet(facts)
    }
}
