package cloud.veritasvpn.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DataPlaneHealthTest {
    private val wifi = 1648378171405L
    private val cellular = 99L

    @Test
    fun userspaceEndpointMismatchIsNotLoggedAsTheAddress() {
        val userspace = """
            private_key=abc
            public_key=def
            endpoint=203.0.113.10:443
            allowed_ip=0.0.0.0/0
        """.trimIndent()
        assertEquals("203.0.113.10:443", DataPlaneHealth.endpointOf(userspace))
        assertEquals(
            DataPlaneHealth.ConfigCheck.MATCH,
            DataPlaneHealth.configCheck(userspace, "203.0.113.10:443"),
        )
        assertEquals(
            DataPlaneHealth.ConfigCheck.MISMATCH,
            DataPlaneHealth.configCheck(userspace, "198.51.100.8:51820"),
        )
        assertEquals(
            DataPlaneHealth.ConfigCheck.UNREAD,
            DataPlaneHealth.configCheck(null, "203.0.113.10:443"),
        )
        assertEquals(
            "config-mismatch",
            DataPlaneHealth.failure(
                v4Present = true,
                protectV4 = true,
                bindAttempted = true,
                bindV4 = true,
                boundHandle = wifi,
                expectedHandle = wifi,
                tx = 148,
                rx = 0,
                config = DataPlaneHealth.ConfigCheck.MISMATCH,
            ),
        )
    }

    @Test
    fun protectBindAndByteCountsExplainAMissedHandshake() {
        assertEquals(
            "protect-failed",
            DataPlaneHealth.failure(
                v4Present = true,
                protectV4 = false,
                bindAttempted = false,
                bindV4 = false,
                boundHandle = null,
                expectedHandle = wifi,
                tx = 0,
                rx = 0,
                config = DataPlaneHealth.ConfigCheck.MATCH,
            ),
        )
        assertEquals(
            "stale-net",
            DataPlaneHealth.failure(
                v4Present = true,
                protectV4 = true,
                bindAttempted = true,
                bindV4 = true,
                boundHandle = cellular,
                expectedHandle = wifi,
                tx = 148,
                rx = 0,
                config = DataPlaneHealth.ConfigCheck.MATCH,
            ),
        )
        assertEquals(
            "no-send",
            DataPlaneHealth.failure(
                v4Present = true,
                protectV4 = true,
                bindAttempted = true,
                bindV4 = true,
                boundHandle = wifi,
                expectedHandle = wifi,
                tx = 0,
                rx = 0,
                config = DataPlaneHealth.ConfigCheck.MATCH,
            ),
        )
        assertEquals(
            "no-reply",
            DataPlaneHealth.failure(
                v4Present = true,
                protectV4 = true,
                bindAttempted = true,
                bindV4 = true,
                boundHandle = wifi,
                expectedHandle = wifi,
                tx = 148,
                rx = 0,
                config = DataPlaneHealth.ConfigCheck.MATCH,
            ),
        )
        assertEquals(
            "no-reply",
            DataPlaneHealth.failure(
                v4Present = true,
                protectV4 = false,
                bindAttempted = false,
                bindV4 = false,
                boundHandle = null,
                expectedHandle = wifi,
                tx = 148,
                rx = 0,
                config = DataPlaneHealth.ConfigCheck.MATCH,
                loopbackRelay = true,
            ),
        )
        assertFalse(TunnelRebuild.loopbackRelay("203.0.113.10:51820"))
        assertTrue(TunnelRebuild.loopbackRelay("127.0.0.1:41820"))
        assertTrue(TunnelRebuild.loopbackRelay("[::1]:41820"))
        val udp = """
            [Interface]
            PrivateKey = abc
            Address = 10.8.0.2/32

            [Peer]
            PublicKey = def
            Endpoint = 203.0.113.10:51820
            AllowedIPs = 0.0.0.0/0
        """.trimIndent()
        val saved = TunnelRebuild.endpointToSaveAfterUdpRestore(udp)
        assertEquals("203.0.113.10:51820", saved)
        assertFalse(TunnelRebuild.loopbackRelay(saved))
        assertNull(
            TunnelRebuild.endpointToSaveAfterUdpRestore(
                udp.replace("203.0.113.10:51820", "127.0.0.1:41820"),
            ),
        )
        assertFalse(TunnelRebuild.dataPlaneMayBind(wifi, cellular))
        assertTrue(TunnelRebuild.dataPlaneMayBind(wifi, wifi))
        assertEquals(2, TunnelRebuild.REBUILDS_BEFORE_FRESH_TUNNEL)
        assertFalse(TunnelRebuild.shouldReprovisionFreshTunnel(1))
        assertTrue(TunnelRebuild.shouldReprovisionFreshTunnel(2))
    }
}
