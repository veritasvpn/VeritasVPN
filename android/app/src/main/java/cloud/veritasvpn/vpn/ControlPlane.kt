package cloud.veritasvpn.vpn

import okhttp3.Dns
import okhttp3.OkHttpClient
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory

/**
 * Control calls the VPN service makes for itself: the peer check, the
 * re-provision POST, and the token refresh those need.
 *
 * Lockdown stays fail-closed for every other socket. These calls leave the
 * tunnel only while it is not healthy, and only toward [API_HOST], using a
 * client bound to the current system-default network. No other network is
 * chosen. While the tunnel is up and healthy, the normal client is used.
 */
object ControlPlane {
    const val API_HOST = "api.veritasvpn.cloud"
    const val SIGN_IN_AGAIN = "Sign in again"

    fun tunnelHealthy(
        reconnecting: Boolean,
        rebuilding: Boolean,
        adapting: Boolean,
        handshakeFresh: Boolean,
    ): Boolean {
        if (reconnecting || rebuilding || adapting) return false
        return handshakeFresh
    }

    /** True only for the VeritasVPN API. Every other host stays in the tunnel. */
    fun hostMayLeaveTunnel(host: String): Boolean =
        host.equals(API_HOST, ignoreCase = true)

    /**
     * [underlying] must already be bound to the current system default.
     * A null value means that network is gone. The normal client is returned
     * in that case; callers do not substitute a different network.
     */
    fun selectClient(
        tunnelHealthy: Boolean,
        underlying: OkHttpClient?,
        normal: OkHttpClient,
    ): OkHttpClient {
        if (!tunnelHealthy && underlying != null) return underlying
        return normal
    }

    /**
     * [delegate] is the current system-default network's socket factory.
     * [protect] then [bind] run before the socket connects. protect() can
     * stamp a stale VPN netId, so [bind] must pin that same system-default
     * network. No other network is selected here.
     */
    fun protectThenBind(
        delegate: SocketFactory,
        protect: (Socket) -> Unit,
        bind: (Socket) -> Unit,
    ): SocketFactory = ProtectedSocketFactory(delegate, protect, bind)

    /**
     * Dedicated client whose sockets come from the current system-default
     * [socketFactory]. DNS is refused for every host except [API_HOST].
     * Redirects are off so a 3xx cannot carry the call to another host.
     */
    fun clientForSystemDefault(
        socketFactory: SocketFactory,
        lookup: (String) -> List<InetAddress>,
    ): OkHttpClient {
        return OkHttpClient.Builder()
            .socketFactory(socketFactory)
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> {
                    if (!hostMayLeaveTunnel(hostname)) throw UnknownHostException(hostname)
                    val addresses = lookup(hostname)
                    if (addresses.isEmpty()) throw UnknownHostException(hostname)
                    return addresses
                }
            })
            .addInterceptor { chain ->
                val host = chain.request().url.host
                if (!hostMayLeaveTunnel(host)) throw UnknownHostException(host)
                chain.proceed(chain.request())
            }
            .build()
    }
}

/**
 * protect() then bind, then connect. OkHttp calls the no-arg factory and
 * connects afterwards. The host overloads do the same order so none of them
 * connect first.
 */
private class ProtectedSocketFactory(
    private val delegate: SocketFactory,
    private val protect: (Socket) -> Unit,
    private val bind: (Socket) -> Unit,
) : SocketFactory() {
    override fun createSocket(): Socket = open(local = null, remote = null)

    override fun createSocket(host: String, port: Int): Socket =
        open(local = null, remote = InetSocketAddress(host, port))

    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
        open(local = InetSocketAddress(localHost, localPort), remote = InetSocketAddress(host, port))

    override fun createSocket(host: InetAddress, port: Int): Socket =
        open(local = null, remote = InetSocketAddress(host, port))

    override fun createSocket(
        address: InetAddress,
        port: Int,
        localAddress: InetAddress,
        localPort: Int,
    ): Socket = open(
        local = InetSocketAddress(localAddress, localPort),
        remote = InetSocketAddress(address, port),
    )

    private fun open(local: InetSocketAddress?, remote: InetSocketAddress?): Socket {
        val socket = delegate.createSocket()
        try {
            protect(socket)
            bind(socket)
            if (local != null) socket.bind(local)
            if (remote != null) socket.connect(remote)
        } catch (e: Exception) {
            runCatching { socket.close() }
            throw e
        }
        return socket
    }
}
