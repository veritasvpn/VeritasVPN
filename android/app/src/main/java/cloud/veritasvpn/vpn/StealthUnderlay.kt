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

    /**
     * protect() a socket that is already connected. Android refuses
     * [Network.bindSocket] on a connected TCP socket, so a later bind cannot
     * succeed and must not be treated as a failed pin. The dial path still
     * protects and binds before connect. Failure leaves the socket open.
     * The next write flushes the frame. This package stays inside the tun;
     * only this socket is kept on the underlay.
     */
    fun repin(socket: Socket, network: Network?, protect: (Socket) -> Boolean): Boolean {
        if (network == null || socket.isClosed) return false
        return protect(socket)
    }
}
