package cloud.veritasvpn.vpn

import android.net.Network
import java.net.Socket

/**
 * Pins the Stealth TCP socket to the underlay the data plane is using now.
 *
 * The network is chosen by the caller at each dial. Null means that network
 * is gone: the dial fails and the supervisor tries again. protect() runs
 * before [Network.bindSocket]. A failed bind is not treated as success.
 */
object StealthUnderlay {
    fun attach(socket: Socket, network: Network?, protect: (Socket) -> Boolean): Long? {
        if (network == null) return null
        StealthDial.protectThenBind(
            socket,
            protect,
            bind = { network.bindSocket(it) },
        )
        return network.networkHandle
    }
}
