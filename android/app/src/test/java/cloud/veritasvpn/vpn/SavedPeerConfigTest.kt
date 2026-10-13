package cloud.veritasvpn.vpn

import com.wireguard.crypto.KeyPair
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SavedPeerConfigTest {

    @Test
    fun sameServerKeyDifferentClientKeysDoNotOverwrite() {
        val server = KeyPair()
        val newer = KeyPair()
        val older = KeyPair()
        val stored = config(client = newer, address = "10.7.0.2/32", server = server, endpoint = "203.0.113.8:443")
        val incoming = config(client = older, address = "10.7.0.3/32", server = server, endpoint = "203.0.113.8:443")
        val kept = SavedPeerConfig.coalesce(stored, incoming)
        assertEquals(stored, kept)
        assertEquals(newer.publicKey.toBase64(), SavedPeerConfig.clientPublicKey(kept))
        assertEquals(newer.publicKey.toBase64(), SavedPeerConfig.peerIdentity(kept))
        assertFalse(SavedPeerConfig.peerIdentity(kept) == server.publicKey.toBase64())
        assertFalse(kept.contains(older.privateKey.toBase64()))
    }

    @Test
    fun quickPeerDeleteThenCreateKeepsTheNewClient() {
        val server = KeyPair()
        val oldClient = KeyPair()
        val newClient = KeyPair()
        val created = config(newClient, "10.7.0.4/32", server, "203.0.113.8:443")
        val recovery = config(oldClient, "10.7.0.2/32", server, "203.0.113.8:443")
        val kept = SavedPeerConfig.coalesce(created, recovery)
        assertEquals(created, kept)
        assertEquals(newClient.publicKey.toBase64(), SavedPeerConfig.clientPublicKey(kept))
    }

    @Test
    fun sameClientEndpointUpdateIsKept() {
        val server = KeyPair()
        val client = KeyPair()
        val stored = config(client, "10.7.0.2/32", server, "203.0.113.10:443")
        val incoming = config(client, "10.7.0.2/32", server, "203.0.113.10:51820")
        val kept = SavedPeerConfig.coalesce(stored, incoming)
        assertEquals(client.publicKey.toBase64(), SavedPeerConfig.peerIdentity(kept))
        assertTrue(kept.contains("51820"))
        assertFalse(kept.contains(":443"))
    }

    @Test
    fun differentAddressesAreDifferentPeersWhenThePrivateKeyIsMissing() {
        val stored = addressOnly("10.7.0.2/32", "203.0.113.8:443")
        val incoming = addressOnly("10.7.0.3/32", "203.0.113.8:51820")
        assertEquals(stored, SavedPeerConfig.coalesce(stored, incoming))
        assertEquals("10.7.0.2/32", SavedPeerConfig.peerIdentity(stored))
    }

    @Test
    fun sameAddressEndpointUpdateIsKeptWhenThePrivateKeyIsMissing() {
        val stored = addressOnly("10.7.0.2/32", "203.0.113.8:443")
        val incoming = addressOnly("10.7.0.2/32", "203.0.113.8:51820")
        val kept = SavedPeerConfig.coalesce(stored, incoming)
        assertTrue(kept.contains("51820"))
        assertEquals("10.7.0.2/32", SavedPeerConfig.peerIdentity(kept))
    }

    private fun config(client: KeyPair, address: String, server: KeyPair, endpoint: String): String =
        """
        [Interface]
        PrivateKey = ${client.privateKey.toBase64()}
        Address = $address
        [Peer]
        PublicKey = ${server.publicKey.toBase64()}
        Endpoint = $endpoint
        AllowedIPs = 0.0.0.0/0
        PersistentKeepalive = 25
        """.trimIndent()

    private fun addressOnly(address: String, endpoint: String): String =
        """
        [Interface]
        Address = $address
        [Peer]
        PublicKey = server
        Endpoint = $endpoint
        AllowedIPs = 0.0.0.0/0
        """.trimIndent()
}
