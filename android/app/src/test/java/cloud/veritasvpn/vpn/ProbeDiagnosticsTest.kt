package cloud.veritasvpn.vpn

import com.wireguard.crypto.KeyPair
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProbeDiagnosticsTest {

    private val client = KeyPair()
    private val server = KeyPair()
    private val clientPublic = client.publicKey.toBase64()
    private val prefix = clientPublic.take(8)

    private val config = """
        [Interface]
        PrivateKey = ${client.privateKey.toBase64()}
        Address = 10.7.0.2/32
        [Peer]
        PublicKey = ${server.publicKey.toBase64()}
        Endpoint = 203.0.113.10:51820
        AllowedIPs = 0.0.0.0/0
    """.trimIndent()

    @Test
    fun publicKeyPrefixIsTheClientsOwnKey() {
        val logged = ProbeDiagnostics.publicKeyPrefix(config)
        assertEquals(8, logged.length)
        assertEquals(prefix, logged)
        assertEquals(prefix, clientPublic.take(8))
        assertFalse(logged == server.publicKey.toBase64().take(8) && clientPublic.take(8) != server.publicKey.toBase64().take(8))
        assertFalse(logged.contains(clientPublic.drop(8)))
    }

    @Test
    fun endpointKindIsLanOrWanOnly() {
        assertEquals(
            "WAN",
            ProbeDiagnostics.endpointKind("203.0.113.10:443", "192.168.1.1:51820", "203.0.113.10:443"),
        )
        assertEquals(
            "LAN",
            ProbeDiagnostics.endpointKind("192.168.1.1:51820", "192.168.1.1:51820", "203.0.113.10:443"),
        )
        assertEquals("LAN", ProbeDiagnostics.endpointKind("10.0.0.5:51820", null, null))
        assertEquals("WAN", ProbeDiagnostics.endpointKind("203.0.113.10:443", null, null))
    }

    @Test
    fun probeLineOmitsAddressesEndpointsFullKeysAndTokens() {
        val logged = ProbeDiagnostics.publicKeyPrefix(config)
        val line = ProbeDiagnostics.format(
            keyPrefix = logged,
            kind = ProbeDiagnostics.endpointKind("203.0.113.10:51820", "192.168.1.1:51820", "203.0.113.10:443"),
            networkType = ProbeDiagnostics.networkType(wifi = true, cellular = false, ethernet = false),
            handle = 77L,
            socketBound = true,
        )
        assertEquals(
            "path-adapt hard probe key=$prefix kind=WAN net=WIFI handle=77 bound=true",
            line,
        )
        assertFalse(line.contains("203.0.113.10"))
        assertFalse(line.contains("192.168.1.1"))
        assertFalse(line.contains("10.7.0.2"))
        assertFalse(line.contains("51820"))
        assertFalse(line.contains("Endpoint"))
        assertFalse(line.contains(clientPublic))
        assertFalse(line.contains(server.publicKey.toBase64()))
        assertFalse(line.contains(client.privateKey.toBase64()))
        assertFalse(line.contains("PrivateKey"))
        assertFalse(line.contains("Bearer"))
        assertFalse(line.contains("token"))
    }

    @Test
    fun unsafeKindOrNetworkTypeCannotSmuggleAnAddress() {
        val line = ProbeDiagnostics.format(
            keyPrefix = "203.0.113.10:51820-and-a-full-key-ABCDEFGHIJKLMNOP",
            kind = "203.0.113.10:51820",
            networkType = "10.1.2.3",
            handle = 1L,
            socketBound = false,
        )
        assertFalse(line.contains("203.0.113.10"))
        assertFalse(line.contains("10.1.2.3"))
        assertFalse(line.contains(":51820"))
        assertTrue(line.contains("kind=WAN"))
        assertTrue(line.contains("net=OTHER"))
        assertTrue(line.startsWith("path-adapt hard probe key="))
        val key = line.substringAfter("key=").substringBefore(" ")
        assertTrue(key.length <= 8)
    }
}
