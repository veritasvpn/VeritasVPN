package cloud.veritasvpn.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ServerKeyConfigTest {
    private val config = """
        [Interface]
        PrivateKey = clientPrivateKey
        Address = 10.0.0.2/32
        DNS = 10.0.0.1

        [Peer]
        PublicKey = oldServerKey
        Endpoint = 203.0.113.10:443
        AllowedIPs = 0.0.0.0/0
        PersistentKeepalive = 25
    """.trimIndent()

    @Test
    fun rebuildsWhenTheAdvertisedServerKeyDiffers() {
        assertEquals(ServerKeyConfig.Refresh.REBUILD, ServerKeyConfig.refresh(config, "newServerKey"))
        assertTrue(ServerKeyConfig.needsRebuild(config, config, "newServerKey"))
        val updated = ServerKeyConfig.withServerKey(config, "newServerKey")
        assertEquals("newServerKey", ServerKeyConfig.peerPublicKey(updated))
        assertTrue(updated.contains("PrivateKey = clientPrivateKey"))
        assertTrue(updated.contains("Endpoint = 203.0.113.10:443"))
        assertFalse(updated.contains("oldServerKey"))
    }

    @Test
    fun keepsTheTunnelWhenTheKeyMatchesOrIsMissing() {
        assertEquals(ServerKeyConfig.Refresh.KEEP, ServerKeyConfig.refresh(config, "oldServerKey"))
        assertEquals(ServerKeyConfig.Refresh.KEEP, ServerKeyConfig.refresh(config, "  "))
        assertEquals(ServerKeyConfig.Refresh.KEEP, ServerKeyConfig.refresh(null, "newServerKey"))
        assertEquals(config, ServerKeyConfig.withServerKey(config, "oldServerKey"))
        assertEquals(config, ServerKeyConfig.withServerKey(config, null))
        assertFalse(ServerKeyConfig.needsRebuild(config, config, "oldServerKey"))
    }

    @Test
    fun endpointReplacementDoesNotChangeTheServerKey() {
        val moved = EndpointSelector.replaceEndpoint(config, "198.51.100.8:51820")
        assertEquals("oldServerKey", ServerKeyConfig.peerPublicKey(moved))
        assertEquals("198.51.100.8:51820", EndpointSelector.endpointFromConfig(moved))
    }

    @Test
    fun peerCreateAndRefreshPathsUseTheAdvertisedKey() {
        val session = kotlinSource("WireGuardSession.kt")
        assertTrue(session.contains("PublicKey = \${peer.serverPublicKey}"))
        val service = kotlinSource("VeritasVpnService.kt")
        assertTrue(service.contains("BackendConfig.select"))
        assertTrue(service.contains("applyAdvertisedServerKey"))
        val backend = kotlinSource("BackendConfig.kt")
        assertTrue(backend.contains("ServerKeyConfig.withServerKey"))
        val api = kotlinSource("ApiClient.kt")
        assertTrue(api.contains("val server: PeerServerView"))
        assertTrue(api.contains("@SerializedName(\"public_key\") val publicKey"))
    }

    private fun kotlinSource(name: String): String {
        val roots = listOf(
            File("src/main/java"),
            File("app/src/main/java"),
            File("android/app/src/main/java"),
        )
        val file = roots.firstNotNullOfOrNull { root ->
            root.walk().firstOrNull { it.isFile && it.name == name }
        } ?: error("$name not found")
        return file.readText()
    }
}
