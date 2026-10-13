package cloud.veritasvpn.vpn

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import javax.net.SocketFactory

class ControlPlaneTest {

    @Test
    fun peerCheckDuringBrokenTunnelUsesUnderlyingClient() {
        assertFalse(
            ControlPlane.tunnelHealthy(
                reconnecting = false,
                rebuilding = false,
                adapting = false,
                handshakeFresh = false,
            ),
        )
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        server.start()
        try {
            val underlyingFactory = CountingSocketFactory()
            val normalFactory = CountingSocketFactory()
            val underlying = ControlPlane.clientForSystemDefault(underlyingFactory) {
                listOf(InetAddress.getByName("127.0.0.1"))
            }
            val normal = OkHttpClient.Builder().socketFactory(normalFactory).build()
            val client = ControlPlane.selectClient(
                tunnelHealthy = false,
                underlying = underlying,
                normal = normal,
            )
            assertSame(underlying, client)
            val response = client.newCall(
                Request.Builder()
                    .url("http://${ControlPlane.API_HOST}:${server.port}/api/v1/wg/peers/peer-1")
                    .get()
                    .build(),
            ).execute()
            assertEquals(200, response.code)
            response.close()
            assertTrue(underlyingFactory.created.get() > 0)
            assertEquals(0, normalFactory.created.get())
            assertEquals("/api/v1/wg/peers/peer-1", server.takeRequest().path)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun healthyTunnelKeepsTheNormalClient() {
        assertTrue(
            ControlPlane.tunnelHealthy(
                reconnecting = false,
                rebuilding = false,
                adapting = false,
                handshakeFresh = true,
            ),
        )
        val underlyingFactory = CountingSocketFactory()
        val normalFactory = CountingSocketFactory()
        val underlying = ControlPlane.clientForSystemDefault(underlyingFactory) {
            listOf(InetAddress.getByName("127.0.0.1"))
        }
        val normal = OkHttpClient.Builder().socketFactory(normalFactory).build()
        val client = ControlPlane.selectClient(
            tunnelHealthy = true,
            underlying = underlying,
            normal = normal,
        )
        assertSame(normal, client)
        assertEquals(0, underlyingFactory.created.get())
    }

    @Test
    fun underlyingClientRefusesEveryHostExceptTheApi() {
        val factory = CountingSocketFactory()
        val client = ControlPlane.clientForSystemDefault(factory) {
            throw AssertionError("lookup must not run for a non-API host")
        }
        try {
            client.newCall(Request.Builder().url("http://example.com/").build()).execute()
            fail("expected the control client to refuse a non-API host")
        } catch (_: IOException) {
            // The tunnel stays the only path for every other host.
        }
        assertEquals(0, factory.created.get())
        assertTrue(ControlPlane.hostMayLeaveTunnel("api.veritasvpn.cloud"))
        assertTrue(ControlPlane.hostMayLeaveTunnel("API.VERITASVPN.CLOUD"))
        assertFalse(ControlPlane.hostMayLeaveTunnel("example.com"))
        assertFalse(ControlPlane.hostMayLeaveTunnel("203.0.113.10"))
    }
}

private class CountingSocketFactory : SocketFactory() {
    val created = AtomicInteger(0)
    private val delegate = getDefault()

    override fun createSocket(): Socket = count(delegate.createSocket())

    override fun createSocket(host: String, port: Int): Socket = count(delegate.createSocket(host, port))

    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
        count(delegate.createSocket(host, port, localHost, localPort))

    override fun createSocket(host: InetAddress, port: Int): Socket = count(delegate.createSocket(host, port))

    override fun createSocket(
        address: InetAddress,
        port: Int,
        localAddress: InetAddress,
        localPort: Int,
    ): Socket = count(delegate.createSocket(address, port, localAddress, localPort))

    private fun count(socket: Socket): Socket {
        created.incrementAndGet()
        return socket
    }
}
